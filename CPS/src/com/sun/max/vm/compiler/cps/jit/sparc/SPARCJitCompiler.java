/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.compiler.cps.jit.sparc;

import static com.sun.max.vm.compiler.CallEntryPoint.*;

import com.sun.max.annotate.*;
import com.sun.max.asm.*;
import com.sun.max.asm.sparc.*;
import com.sun.max.asm.sparc.complete.*;
import com.sun.max.memory.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.compiler.builtin.*;
import com.sun.max.vm.compiler.cps.b.c.d.e.sparc.target.*;
import com.sun.max.vm.compiler.cps.eir.sparc.*;
import com.sun.max.vm.compiler.cps.jit.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.runtime.VMRegister.*;
import com.sun.max.vm.runtime.sparc.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.StackFrameWalker.*;
import com.sun.max.vm.stack.sparc.*;
import com.sun.max.vm.template.*;
import com.sun.max.vm.thread.*;

/**
 * Template-based implementation of JIT compiler for SPARC.
 *
 * @author Bernd Mathiske
 * @author Laurent Daynes
 * @author Paul Caprioli
 */
public class SPARCJitCompiler extends JitCompiler {
    private static GPR jitFramePointerRegister;
    private static GPR jitLiteralBaseRegister;

    private final SPARCTemplateBasedTargetGenerator targetGenerator;

    /**
     * Unwinds a thread's stack to an exception handler in JITed code.
     *
     * To enable us to find the SPARC register spill area, the last Opt method we walked through recorded the
     * SPARC stack pointer in the StackUnwindingContext object.
     *
     * Note that a JIT-frame may not have a register window on its own.
     * It is shared with all the JIT frames between an opt->jit and a jit->opt adapter (or jit->runtime call).  The
     * register window keeps moving with the stack pointer.
     *
     * <p>
     * The critical state of the registers before the RET instruction is:
     * <ul>
     * <li>%i7 must hold exception handler address, taking into account the SPARC link register convention.</li>
     * <li>%i6 must hold the stack pointer of the handler</li>
     * </ul>
     *
     * @param context the stack unwinding context
     * @param catchAddress the address of the exception handler
     * @param catcherStackPointer the stack pointer for the exception handler
     * @param catcherJitFramePointer the frame pointer for the exception handler
     */
    @NEVER_INLINE
    private static void unwind(StackUnwindingContext context, Address catchAddress, Pointer catcherTopOfStackPointer, Pointer catcherJitFramePointer) {
        // Put the exception where the exception handler expects to find it
        VmThreadLocal.EXCEPTION_OBJECT.setVariableReference(Reference.fromJava(context.throwable));

        final Pointer literalBase = catcherJitFramePointer.readWord(-JitStackFrameLayout.STACK_SLOT_SIZE).asPointer();

        // Compute the catcher stack pointer: this one will be the top frame, so we need to augment it with space for saving a register window plus
        // mandatory output register.  We also need to bias it.
        final Pointer catcherStackPointer = StackBias.JIT_SPARC_V9.bias(catcherTopOfStackPointer.minus(SPARCStackFrameLayout.MIN_STACK_FRAME_SIZE));

        final Word catcherFramePointer = SPARCStackFrameLayout.getRegisterInSavedWindow(context.stackPointer(), GPR.I6);
        final Word catcherCallAddress = SPARCStackFrameLayout.getRegisterInSavedWindow(context.stackPointer(), GPR.I7);

        // Set the top of stack to null (will be replaced later with the throwable)
        catcherTopOfStackPointer.writeReference(0, null);

        // Patch the JIT frame pointer in the saved register window area of the JIT frame to which we'll unwind:
        SPARCStackFrameLayout.setRegisterInSavedWindow(catcherStackPointer, jitFramePointerRegister, catcherJitFramePointer);
        // Patch the literal base register in the saved register window area of the JIT frame to which we'll unwind:
        SPARCStackFrameLayout.setRegisterInSavedWindow(catcherStackPointer, jitLiteralBaseRegister, literalBase);
        // Patch the frame pointer in the saved register window area of the JIT frame to which we'll unwind:
        SPARCStackFrameLayout.setRegisterInSavedWindow(catcherStackPointer, GPR.I6, catcherFramePointer);
        // Patch the link register value in the saved register window area of the JIT frame to which we'll unwind:
        SPARCStackFrameLayout.setRegisterInSavedWindow(catcherStackPointer, GPR.I7, catcherCallAddress);

        if (context.throwable instanceof StackOverflowError) {
            // This complete call-chain must be inlined down to the native call
            // so that no further stack banging instructions
            // are executed before execution jumps to the catch handler.
            VirtualMemory.protectPages(VmThread.current().stackYellowZone(), VmThread.STACK_YELLOW_ZONE_PAGES);
        }

        SpecialBuiltin.flushRegisterWindows();

        VMRegister.setCallAddressRegister(catchAddress.minus(InstructionSet.SPARC.offsetToReturnPC));  // sets %i7
        VMRegister.setAbiFramePointer(catcherStackPointer);  // sets %i6
    }

    @HOSTED_ONLY
    public SPARCJitCompiler(VMConfiguration vmConfiguration) {
        super(vmConfiguration);
        targetGenerator = new SPARCTemplateBasedTargetGenerator(this);
    }

    @HOSTED_ONLY
    public SPARCJitCompiler(VMConfiguration vmConfiguration, TemplateTable templateTable) {
        this(vmConfiguration);
        targetGenerator.initializeTemplateTable(templateTable);
    }

    @Override
    public void initialize(MaxineVM.Phase phase) {
        super.initialize(phase);
        if (phase == MaxineVM.Phase.STARTING) {
            final TargetABI jitABI = vmConfiguration().targetABIsScheme().jitABI();
            jitFramePointerRegister = (GPR) jitABI.framePointer();
            jitLiteralBaseRegister = (GPR) jitABI.literalBaseRegister();
        }
    }

    @Override
    protected TemplateBasedTargetGenerator targetGenerator() {
        return targetGenerator;
    }

    private boolean walkAdapterFrame(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, TargetMethod targetMethod, Purpose purpose, Object context, boolean isTopFrame) {
        final Pointer instructionPointer = current.ip();
        final Pointer optimizedEntryPoint = OPTIMIZED_ENTRY_POINT.in(targetMethod);
        // The frame pointer read off the JIT frame called by this adapter is unbiased.
        final Pointer framePointer;
        final Pointer stackPointer;
        final int adapterTopFrameSize =  SPARCAdapterFrameGenerator.optToJitAdapterFrameSize(stackFrameWalker, optimizedEntryPoint);

        final int adapterFrameSize =  isTopFrame ? adapterTopFrameSize : adapterTopFrameSize - SPARCStackFrameLayout.MIN_STACK_FRAME_SIZE;

        final boolean inCallerRegisterWindow = SPARCCPSCompiler.inCallerRegisterWindow(instructionPointer, optimizedEntryPoint, adapterTopFrameSize, true);

        if (inCallerRegisterWindow) {
            // The save instruction hasn't been executed. The frame pointer is the same as the caller's stack pointer.
            // We need to compute the stack pointer for this frame
            framePointer = current.sp();
            stackPointer = framePointer.minus(adapterFrameSize);
        } else {
            framePointer = isTopFrame ? current.fp() : StackBias.SPARC_V9.bias(current.fp());
            stackPointer = current.sp();
        }

        switch (purpose) {
            case EXCEPTION_HANDLING: {
                // cannot have exception handler in adapter frame
                break;
            }
            case REFERENCE_MAP_PREPARING: {
                break;
            }
            case RAW_INSPECTING: {
                final RawStackFrameVisitor stackFrameVisitor = (RawStackFrameVisitor) context;
                final int flags = RawStackFrameVisitor.Util.makeFlags(isTopFrame, true);
                if (!stackFrameVisitor.visitFrame(targetMethod, instructionPointer, framePointer, stackPointer, flags)) {
                    return false;
                }
                break;
            }
            case INSPECTING: {
                final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                final StackFrame stackFrame = new AdapterStackFrame(stackFrameWalker.calleeStackFrame(), new AdapterStackFrameLayout(adapterFrameSize, false), targetMethod, instructionPointer, framePointer, stackPointer);
                if (!stackFrameVisitor.visitFrame(stackFrame)) {
                    return false;
                }
                break;
            }
        }

        final Pointer callerInstructionPointer;
        final Pointer callerStackPointer;
        final Pointer callerFramePointer;

        if (isTopFrame) {
            // We're in the top frame. The bottom of the frame comprises the register window's saving areas. We can
            // retrieve values from there.
            if (inCallerRegisterWindow) {
                callerInstructionPointer = stackFrameWalker.readRegister(Role.FRAMELESS_CALL_INSTRUCTION_ADDRESS, targetMethod.abi()).asPointer();
                callerStackPointer = current.sp();
                callerFramePointer = current.fp();
            } else {
                callerInstructionPointer = SPARCStackFrameLayout.getCallerPC(current, stackFrameWalker);
                callerStackPointer = framePointer;
                callerFramePointer = SPARCStackFrameLayout.getCallerFramePointer(current, stackFrameWalker);
            }
        } else {
            final int ripSaveAreaOffset =  SPARCJitStackFrameLayout.OFFSET_TO_FLOATING_POINT_TEMP_AREA - Word.size();
            final Pointer ripSaveArea = framePointer.plus(ripSaveAreaOffset);
            // Get caller's instruction pointer from the rip save area in the adapter frame.
            callerInstructionPointer = stackFrameWalker.readWord(ripSaveArea, 0).asPointer();
            // We can obtain the caller's frame pointer from its register window saving area, which is at the
            // adapter frame's frame pointer.
            final Pointer unbiasedFramePointer = current.fp();
            callerStackPointer = framePointer;
            callerFramePointer = SPARCStackFrameLayout.getCallerFramePointer(stackFrameWalker, unbiasedFramePointer);
        }

        stackFrameWalker.advance(callerInstructionPointer, callerStackPointer, callerFramePointer);

        return true;
    }

    public boolean walkFrame(StackFrameWalker.Cursor current, StackFrameWalker.Cursor callee, Purpose purpose, Object context) {
        StackFrameWalker stackFrameWalker = current.stackFrameWalker();
        TargetMethod targetMethod = current.targetMethod();
        boolean isTopFrame = current.isTopFrame();
        final SPARCJitTargetMethod jitTargetMethod = (SPARCJitTargetMethod) targetMethod;
        final Pointer instructionPointer = current.ip();
        final Pointer jitEntryPoint = JIT_ENTRY_POINT.in(targetMethod);
        final Pointer optimizedEntryPoint = OPTIMIZED_ENTRY_POINT.in(targetMethod);
        final boolean hasAdapterFrame = !jitEntryPoint.equals(optimizedEntryPoint);

        if (hasAdapterFrame) {
            final Pointer endOfAdapter = optimizedEntryPoint.plus(jitTargetMethod.optimizedCallerAdapterFrameCodeSize());

            if (instructionPointer.greaterEqual(optimizedEntryPoint) && instructionPointer.lessThan(endOfAdapter)) {
                return walkAdapterFrame(current, stackFrameWalker, targetMethod, purpose, context, isTopFrame);
            }
        }

        // In JITed code.
        if (isTopFrame) {
            // Make sure the appropriate ABI is used to obtain the top frame's stack and frame pointers.
            stackFrameWalker.useABI(targetMethod.abi());
        }

        final FrameState frameState = stackFrameState(current, stackFrameWalker, jitTargetMethod);
        final Pointer localVariablesBase = frameState.localVariablesBase(current);

        switch (purpose) {
            case REFERENCE_MAP_PREPARING: {
                if (!walkFrameForReferenceMapPreparing(current, stackFrameWalker, jitTargetMethod, context, frameState)) {
                    return false;
                }
                break;
            }
            case EXCEPTION_HANDLING: {
                final StackUnwindingContext stackUnwindingContext = UnsafeCast.asStackUnwindingContext(context);
                final Address catchAddress = targetMethod.throwAddressToCatchAddress(isTopFrame, instructionPointer, stackUnwindingContext.throwable.getClass());
                if (!catchAddress.isZero()) {
                    if (StackFrameWalker.TRACE_STACK_WALK.getValue()) {
                        Log.print("StackFrameWalk: Handler position for exception at position ");
                        Log.print(instructionPointer.minus(targetMethod.codeStart()).toInt());
                        Log.print(" is ");
                        Log.println(catchAddress.minus(targetMethod.codeStart()).toInt());
                    }

                    // found an exception handler, and thus we are done with the stack walker
                    stackFrameWalker.reset();

                    // The Java operand stack of the method that handles the exception is always cleared.
                    // A null object is then pushed to ensure the depth of the stack is as expected upon
                    // entry to an exception handler. However, the handler must have a prologue that loads
                    // the exception from VmThreadLocal.EXCEPTION_OBJECT, which is indeed guaranteed by
                    // ExceptionDispatcher.

                    // Compute the offset to the first stack slot of the Java Stack as follows:
                    // frame pointer - (space for non-local parameters + saved literal base (1 slot) + space of the first slot itself).
                    final int offsetToFirstOperandStackSlot = jitTargetMethod.stackFrameLayout().sizeOfNonParameterLocals() + 2 * JitStackFrameLayout.JIT_SLOT_SIZE;
                    final Pointer catcherTopOfStackPointer = localVariablesBase.minus(offsetToFirstOperandStackSlot);

                    // Completes the exception handling protocol (with respect to the garbage collector) initiated in Throwing.raise()
                    Safepoint.enable();

                    unwind(stackUnwindingContext, catchAddress, catcherTopOfStackPointer, localVariablesBase);
                    // We should never reach here
                }
                break;
            }
            case RAW_INSPECTING: {
                final RawStackFrameVisitor stackFrameVisitor = (RawStackFrameVisitor) context;
                final int flags = RawStackFrameVisitor.Util.makeFlags(isTopFrame, false);
                if (!stackFrameVisitor.visitFrame(targetMethod, current.ip(), current.sp(), localVariablesBase, flags)) {
                    return false;
                }
                break;
            }
            case INSPECTING: {
                final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                final StackFrame stackFrame = new SPARCJitStackFrame(stackFrameWalker.calleeStackFrame(), targetMethod,
                                current.ip(), current.sp(), localVariablesBase, localVariablesBase);
                if (!stackFrameVisitor.visitFrame(stackFrame)) {
                    return false;
                }
                break;
            }
        }

        final Pointer callerInstructionPointer;
        if (frameState.isReturnInstructionPointerOnStack()) {
            final Pointer returnInstructionPointer = frameState.returnInstructionPointer(current);
            callerInstructionPointer = stackFrameWalker.readWord(returnInstructionPointer, 0).asPointer();
        } else {
            final Pointer trapStateInPreviousFrame = stackFrameWalker.trapState();
            if (trapStateInPreviousFrame != Pointer.zero()) {
                callerInstructionPointer = SPARCTrapStateAccess.getCallAddressRegister(trapStateInPreviousFrame);
            } else if (isTopFrame && frameState == FrameState.BUILDING_CALLEE_FRAME && (purpose == Purpose.EXCEPTION_HANDLING || purpose == Purpose.REFERENCE_MAP_PREPARING)) {
                // When purpose is other than inspecting, this situation can only occur when we trapped in a prologue (e.g., when banging the stack).
                // We can fish for the caller's instruction pointer in the trap state.
                final Pointer trapStateInPreviousUnwalkedFrame = current.sp().plus(SPARCEirPrologue.trapStateOffsetFromTrappedSP());
                callerInstructionPointer = SPARCTrapStateAccess.getCallAddressRegister(trapStateInPreviousUnwalkedFrame);
            } else {
                callerInstructionPointer = stackFrameWalker.readRegister(Role.FRAMELESS_CALL_INSTRUCTION_ADDRESS, jitTargetMethod.abi()).asPointer();
            }
        }
        final Pointer callerStackPointer = frameState.callerStackPointer(current);
        final Pointer callerFramePointer = frameState.callerFramePointer(current);
        stackFrameWalker.advance(callerInstructionPointer, callerStackPointer, callerFramePointer);

        return true;
    }

    private boolean walkFrameForReferenceMapPreparing(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, SPARCJitTargetMethod targetMethod, Object context, FrameState frameState) {
        final Pointer trapState = stackFrameWalker.trapState();
        if (!trapState.isZero()) {
            FatalError.check(!targetMethod.classMethodActor().isTrapStub(), "Cannot have a trap in the trapStub");
            final TrapStateAccess trapStateAccess = TrapStateAccess.instance();
            if (trapStateAccess.getTrapNumber(trapState) == Trap.Number.STACK_FAULT) {
                // There's no need to deal with any references in a frame that triggered a stack overflow.
                // The explicit stack banging code that causes a stack overflow trap is always in the
                // prologue which is guaranteed not to be in the scope of a local exception handler.
                // Thus, no GC roots need to be scanned in this frame.
                return true;
            }
        }
        final Pointer localVariablesBase = frameState.localVariablesBase(current);
        final Pointer operandStackPointer = StackBias.SPARC_V9.unbias(current.sp());
        return targetMethod.prepareFrameReferenceMap((StackReferenceMapPreparer) context, current.ip(), localVariablesBase,
                                                     operandStackPointer, SPARCStackFrameLayout.LOCAL_REGISTERS_SAVE_AREA_SIZE);
    }

    private FrameState stackFrameState(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, SPARCJitTargetMethod targetMethod) {
        final Pointer instructionPointer = current.ip();
        final Pointer optimizedEntryPoint = OPTIMIZED_ENTRY_POINT.in(targetMethod);
        if (instructionPointer.lessThan(optimizedEntryPoint)) {
            return FrameState.IN_CALLER_FRAME;
        }
        // The stack frame of JITed code is setup by a small sequence of instructions called the frame builder below.
        // The frame builder starts at the end of the optimized-to-jit adapter. Its size depends on the frame size.
        // Hence this code to figure out the boundaries of the frame builder.
        final Pointer startOfFrameBuilder = optimizedEntryPoint.plus(targetMethod.optimizedCallerAdapterFrameCodeSize());
        final int builderSize = BytecodeToSPARCTargetTranslator.frameBuilderSize(targetMethod);
        final Pointer endOfFrameBuilder = startOfFrameBuilder.plus(builderSize);

        if (instructionPointer.greaterEqual(endOfFrameBuilder)) {
            final int currentInstruction = stackFrameWalker.readInt(instructionPointer, 0);
            final int prevInstruction = stackFrameWalker.readInt(instructionPointer, -InstructionSet.SPARC.instructionWidth);
            if (currentInstruction == BytecodeToSPARCTargetTranslator.RET_TEMPLATE || prevInstruction == BytecodeToSPARCTargetTranslator.RET_TEMPLATE) {
                return FrameState.EXITING_CALLEE;
            }
            return FrameState.NORMAL;
        }
        // We're in the frame builder
        // If the target method's frame size is large, the ABI frame pointer is changed by the second instruction only.
        // So we're still in IN_CALLER_FRAME state if we haven't passed the first two instructions of the frame builder.
        if (!SPARCAssembler.isSimm13(targetMethod.frameSize())) {
            if (instructionPointer.equals(startOfFrameBuilder) ||
                            instructionPointer.equals(startOfFrameBuilder.plus(InstructionSet.SPARC.instructionWidth))) {
                return FrameState.IN_CALLER_FRAME;
            }
        }
        // We're building the frame. The frame pointer is already set up (can be obtained off the ABI frame pointer register).
        return FrameState.BUILDING_CALLEE_FRAME;
    }

    private enum FrameState {
        /**
         * Normal state.  Frame pointer is in the frame pointer register defined by the ABI.
         * Caller's address and frame pointer are on the stack, just above the template slots.
         * Stack pointer is just the ABI stack pointer.
         */
        NORMAL {
            @Override
            boolean isReturnInstructionPointerOnStack() {
                return true;
            }
            @Override
            Pointer localVariablesBase(StackFrameWalker.Cursor current) {
                return current.fp();
            }

            @Override
            Pointer returnInstructionPointer(StackFrameWalker.Cursor current) {
                // The RIP is the top slot in the caller save
                // area, so we have to remove a stack slot to the computed size.
                final int dispToRip = offsetToTopOfFrame(targetMethodOf(current)) - JitStackFrameLayout.STACK_SLOT_SIZE;
                return current.fp().plus(dispToRip);
            }

            @Override
            Pointer callerFramePointer(StackFrameWalker.Cursor current) {
                return current.stackFrameWalker().readWord(returnInstructionPointer(current), -Word.size()).asPointer();
            }
        },

        /**
         * State when entering the method.  The callee's frame isn't allocated yet.
         * The Frame Pointer register is still set to the caller's.
         * The callee's frame pointer can be computed from the caller's stack pointer and the called method's frame size.
         */
        IN_CALLER_FRAME {
            @Override
            Pointer localVariablesBase(StackFrameWalker.Cursor current) {
                //  We just need to subtract the offset to the top of the frame from the frame pointer.
                final int offsetToCalleeFramePointer = offsetToTopOfFrame(targetMethodOf(current));
                return current.sp().minus(offsetToCalleeFramePointer);
            }
            @Override
            Pointer callerStackPointer(StackFrameWalker.Cursor current) {
                return current.sp();
            }
        },

        /**
         * State when building the stack frame on entering the method.
         * The callee's frame is allocated, but the frame pointer isn't setup.
         * Furthermore, the call save area may not be initialized.
         * The callee's frame pointer can be computed from the callee's stack pointer.
         */
        BUILDING_CALLEE_FRAME {
            @Override
            Pointer localVariablesBase(StackFrameWalker.Cursor current) {
                final int offsetToCalleeFramePointer = targetMethodOf(current).stackFrameLayout().sizeOfNonParameterLocals()  +
                    SPARCStackFrameLayout.OFFSET_FROM_SP_TO_FIRST_SLOT;
                return current.sp().plus(offsetToCalleeFramePointer);
            }
       },

       EXITING_CALLEE {
            @Override
            Pointer localVariablesBase(StackFrameWalker.Cursor current) {
                // The following assume that on exiting, the operand stack is empty.
                // This is not crucial as this is only used for inspection.
                // A better approach would be to read the callee frame pointer directly off the O5 register.
                final int offsetToCalleeFramePointer =   targetMethodOf(current).stackFrameLayout().sizeOfNonParameterLocals();
                return current.sp().plus(offsetToCalleeFramePointer);
            }
        };

        abstract Pointer localVariablesBase(StackFrameWalker.Cursor current);

        Pointer returnInstructionPointer(StackFrameWalker.Cursor current) {
            ProgramError.unexpected("Must call returnInstructionPointer only when in normal frame state");
            return null;
        }

        Pointer callerFramePointer(StackFrameWalker.Cursor current) {
            return current.fp();
        }

        boolean isReturnInstructionPointerOnStack() {
            return false;
        }

        Pointer callerStackPointer(StackFrameWalker.Cursor current) {
            return current.sp().plus(targetMethodOf(current).stackFrameLayout().frameSize());
        }

        SPARCJitTargetMethod targetMethodOf(StackFrameWalker.Cursor current) {
            return (SPARCJitTargetMethod) current.targetMethod();
        }

        /**
         * Return the offset to the top of the frame from the frame pointer.
         * @param targetMethod
         * @return an offset in byte
         */
        int offsetToTopOfFrame(SPARCJitTargetMethod targetMethod) {
            // The frame pointer points at the base of the template slots.
            // The caller save area is just above the template slots. There might be padding between the template slots and the call area size to
            // align the frame to the required boundary.
            final int unalignedSize = targetMethod.stackFrameLayout().sizeOfTemplateSlots() + SPARCJitStackFrameLayout.CALL_SAVE_AREA_SIZE;
            return targetMethod.abi().alignFrameSize(unalignedSize);
        }
    }

}
