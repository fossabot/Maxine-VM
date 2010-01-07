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
package com.sun.max.vm.cps.jit.amd64;

import static com.sun.max.vm.compiler.CallEntryPoint.*;

import com.sun.max.annotate.*;
import com.sun.max.memory.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.cps.jit.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.StackFrameWalker.*;
import com.sun.max.vm.stack.amd64.*;
import com.sun.max.vm.template.*;
import com.sun.max.vm.thread.*;

/**
 * Template-based implementation of JIT compiler for AMD64.
 *
 * @author Laurent Daynes
 * @author Ben L. Titzer
 * @see AMD64JitStackFrameLayout
 */
public class AMD64JitCompiler extends JitCompiler {

    private final AMD64TemplateBasedTargetGenerator targetGenerator;

    @HOSTED_ONLY
    public AMD64JitCompiler(VMConfiguration vmConfiguration) {
        super(vmConfiguration);
        targetGenerator = new AMD64TemplateBasedTargetGenerator(this);
    }

    @HOSTED_ONLY
    public AMD64JitCompiler(VMConfiguration vmConfiguration, TemplateTable templateTable) {
        this(vmConfiguration);
        targetGenerator.initializeTemplateTable(templateTable);
    }

    @Override
    protected TemplateBasedTargetGenerator targetGenerator() {
        return targetGenerator;
    }

    public TemplateTable peekTemplateTable() {
        return targetGenerator.templateTable();
    }

    private static final byte ENTER = (byte) 0xC8;
    private static final byte LEAVE = (byte) 0xC9;
    private static final byte POP_RBP = (byte) 0x5D;

    private static final byte RET = (byte) 0xC3;
    private static final byte RET2 = (byte) 0xC2;

    /**
     * Offset to the last instruction of the prologue from the JIT entry point. The prologue comprises two instructions,
     * the first one of which is enter (fixed size, 4 bytes long).
     */
    public static final int OFFSET_TO_LAST_PROLOGUE_INSTRUCTION = 4;

    @Override
    public void initialize(MaxineVM.Phase phase) {
        super.initialize(phase);

        if (MaxineVM.isHosted()) {
            unwindMethod = ClassActor.fromJava(AMD64JitCompiler.class).findLocalClassMethodActor(SymbolTable.makeSymbol("unwind"), null);
        }
    }

    private static ClassMethodActor unwindMethod;

    private static int unwindFrameSize = -1;

    @NEVER_INLINE
    private static int getUnwindFrameSize() {
        if (unwindFrameSize == -1) {
            unwindFrameSize = CompilationScheme.Static.getCurrentTargetMethod(unwindMethod).frameSize();
        }
        return unwindFrameSize;
    }

    /**
     * This method must be compiled such that it uses neither the stack or frame pointer implicitly. Doing so might
     * conflict with any code restoring these registers before returning to the dispatcher of the exception. The
     * critical state of the registers before the RET instruction is:
     * <ul>
     * <li>RSP must be one word less than the stack pointer of the handler frame that is the target of the unwinding</li>
     * <li>The value at [RSP] must be address of the handler code</li>
     * </ul>
     * <p>
     *
     * @param catchAddress the address of the handler code (actually the dispatcher code)
     * @param stackPointer the stack pointer denoting the frame of the handler to which the stack is unwound upon
     *            returning from this method
     * @param framePointer
     */
    @NEVER_INLINE
    public static void unwind(Throwable throwable, Address catchAddress, Pointer stackPointer, Pointer framePointer) {
        int unwindFrameSize = getUnwindFrameSize();

        // Put the exception where the exception handler expects to find it
        VmThreadLocal.EXCEPTION_OBJECT.setVariableReference(Reference.fromJava(throwable));

        if (throwable instanceof StackOverflowError) {
            // This complete call-chain must be inlined down to the native call
            // so that no further stack banging instructions
            // are executed before execution jumps to the catch handler.
            VirtualMemory.protectPages(VmThread.current().stackYellowZone(), VmThread.STACK_YELLOW_ZONE_PAGES);
        }
        // Push 'catchAddress' to the handler's stack frame and update RSP to point to the pushed value.
        // When the RET instruction is executed, the pushed 'catchAddress' will be popped from the stack
        // and the stack will be in the correct state for the handler.
        Pointer returnAddressPointer = stackPointer.minus(Word.size());
        returnAddressPointer.setWord(catchAddress);

        VMRegister.setCpuStackPointer(returnAddressPointer.minus(unwindFrameSize));
        VMRegister.setCpuFramePointer(framePointer);
    }

    private boolean walkAdapterFrame(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, TargetMethod targetMethod, Purpose purpose, Object context, boolean isTopFrame) {
        Pointer instructionPointer = current.ip();
        Pointer stackPointer = current.sp();
        Pointer entryPoint = OPTIMIZED_ENTRY_POINT.in(targetMethod);
        Pointer ripPointer = adapterReturnInstructionPointer(targetMethod, instructionPointer, stackPointer, entryPoint);
        Pointer callerInstructionPointer = stackFrameWalker.readWord(ripPointer, 0).asPointer();

        switch (purpose) {
            case EXCEPTION_HANDLING: {
                // cannot have exception handler in adapter frame
                break;
            }
            case REFERENCE_MAP_PREPARING: {
                break;
            }
            case RAW_INSPECTING: {
                RawStackFrameVisitor stackFrameVisitor = (RawStackFrameVisitor) context;
                int flags = RawStackFrameVisitor.Util.makeFlags(isTopFrame, true);
                if (!stackFrameVisitor.visitFrame(targetMethod, instructionPointer, stackPointer, stackPointer, flags)) {
                    return false;
                }
                break;
            }
            case INSPECTING: {
                StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                StackFrame stackFrame = new AMD64OptimizedToJitAdapterFrame(stackFrameWalker.calleeStackFrame(), targetMethod, instructionPointer, stackPointer, stackPointer);
                if (!stackFrameVisitor.visitFrame(stackFrame)) {
                    return false;
                }
                break;
            }
        }
        Pointer callerStackPointer = ripPointer.plus(Word.size()); // skip RIP word
        stackFrameWalker.advance(callerInstructionPointer, callerStackPointer, callerStackPointer);
        return true;
    }

    private Pointer adapterReturnInstructionPointer(TargetMethod targetMethod, Pointer instructionPointer, Pointer stackPointer, Pointer entryPoint) {
        ClassMethodActor classMethodActor = targetMethod.classMethodActor();
        // Currently, the opto-jit adapter frame always increases the stack by at least one slot, to make it looks like
        // a call from a

        boolean hasNoFrame = instructionPointer.equals(entryPoint) || classMethodActor.isStatic() && (classMethodActor.descriptor().numberOfParameters() == 0);

        Pointer ripPointer; // stack pointer at call entry point (where the RIP is).
        if (hasNoFrame) {
            ripPointer = stackPointer;
        } else {
            // The adapter frame was constructed. Add space taken by the parameters for the placeholder of RBP.
            ripPointer = stackPointer.plus(AMD64OptStackWalking.adapterFrameSize(classMethodActor));
        }
        return ripPointer;
    }

    private AMD64JitTargetMethod.FramePointerState stackFrameState(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, Pointer lastPrologueInstr) {
        Pointer instructionPointer = current.ip();
        byte byteAtInstructionPointer = stackFrameWalker.readByte(instructionPointer, 0);
        if (instructionPointer.lessThan(lastPrologueInstr) || byteAtInstructionPointer == ENTER || byteAtInstructionPointer == RET || byteAtInstructionPointer == RET2) {
            return AMD64JitTargetMethod.FramePointerState.CALLER_FRAME_IN_RBP;
        }
        if (instructionPointer.equals(lastPrologueInstr) || byteAtInstructionPointer == LEAVE) {
            return AMD64JitTargetMethod.FramePointerState.CALLER_FRAME_AT_RBP;
        }
        if (byteAtInstructionPointer == POP_RBP) {
            return AMD64JitTargetMethod.FramePointerState.RETURNING_FROM_RUNTIME;
        }
        return AMD64JitTargetMethod.FramePointerState.IN_RBP;
    }

    /**
     * @see AMD64JitStackFrame
     */
    public boolean walkFrame(StackFrameWalker.Cursor current, StackFrameWalker.Cursor callee, Purpose purpose, Object context) {
        StackFrameWalker stackFrameWalker = current.stackFrameWalker();
        TargetMethod targetMethod = current.targetMethod();
        boolean isTopFrame = current.isTopFrame();
        // FIXME: need to encapsulate adapter frame related code in an
        // adapter frame scheme so that this code does not comprise any adapter related code.
        Pointer instructionPointer = current.ip();
        Pointer jitEntryPoint = JIT_ENTRY_POINT.in(targetMethod);
        Pointer optimizedEntryPoint = OPTIMIZED_ENTRY_POINT.in(targetMethod);
        boolean hasAdapterFrame = !jitEntryPoint.equals(optimizedEntryPoint);

        // points to the first instruction following the prologue of the JIT-ed method, whether there is an adapter
        // embedded in the code or not.
        Pointer startOfPrologue;
        if (hasAdapterFrame) {
            JitTargetMethod jitTargetMethod = (JitTargetMethod) targetMethod;
            Pointer endOfAdapter = optimizedEntryPoint.plus(jitTargetMethod.optimizedCallerAdapterFrameCodeSize());
            if (instructionPointer.greaterEqual(optimizedEntryPoint) && instructionPointer.lessThan(endOfAdapter)) {
                return walkAdapterFrame(current, stackFrameWalker, targetMethod, purpose, context, isTopFrame);
            }
            startOfPrologue = endOfAdapter;
        } else {
            startOfPrologue = jitEntryPoint;
        }
        Pointer lastPrologueInstruction = startOfPrologue.plus(OFFSET_TO_LAST_PROLOGUE_INSTRUCTION);
        AMD64JitTargetMethod.FramePointerState framePointerState = stackFrameState(current, stackFrameWalker, lastPrologueInstruction);

        switch (purpose) {
            case REFERENCE_MAP_PREPARING: {
                if (!walkFrameForReferenceMapPreparing(current, stackFrameWalker, (AMD64JitTargetMethod) targetMethod, context, framePointerState)) {
                    return false;
                }
                break;
            }
            case EXCEPTION_HANDLING: {
                walkFrameForExceptionHandling(current, stackFrameWalker, isTopFrame, targetMethod, context, framePointerState);
                break;
            }
            case RAW_INSPECTING:
            case INSPECTING: {
                if (!walkFrameForInspecting(current, stackFrameWalker, isTopFrame, targetMethod, context, framePointerState)) {
                    return false;
                }
                break;
            }
        }

        Pointer returnInstructionPointer = framePointerState.returnIP(current);
        Pointer callerInstructionPointer = stackFrameWalker.readWord(returnInstructionPointer, 0).asPointer();
        Pointer callerStackPointer = returnInstructionPointer.plus(Word.size()); // Skip the rip
        stackFrameWalker.advance(callerInstructionPointer, callerStackPointer, framePointerState.callerFP(current));
        return true;
    }

    private boolean walkFrameForInspecting(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, boolean isTopFrame, TargetMethod targetMethod, Object context, AMD64JitTargetMethod.FramePointerState framePointerState) {
        Pointer localVariablesBase = framePointerState.localVariablesBase(current);
        if (context instanceof StackFrameVisitor) {
            StackFrame stackFrame = new AMD64JitStackFrame(stackFrameWalker.calleeStackFrame(), targetMethod, current.ip(), current.sp(), localVariablesBase, localVariablesBase);
            StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
            return stackFrameVisitor.visitFrame(stackFrame);
        }
        RawStackFrameVisitor stackFrameVisitor = (RawStackFrameVisitor) context;
        int flags = RawStackFrameVisitor.Util.makeFlags(isTopFrame, false);
        return stackFrameVisitor.visitFrame(targetMethod, current.ip(), current.sp(), localVariablesBase, flags);
    }

    private boolean walkFrameForReferenceMapPreparing(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, AMD64JitTargetMethod targetMethod, Object context, AMD64JitTargetMethod.FramePointerState framePointerState) {
        Pointer trapState = stackFrameWalker.trapState();
        if (!trapState.isZero()) {
            FatalError.check(!targetMethod.classMethodActor().isTrapStub(), "Cannot have a trap in the trapStub");
            TrapStateAccess trapStateAccess = TrapStateAccess.instance();
            if (trapStateAccess.getTrapNumber(trapState) == Trap.Number.STACK_FAULT) {
                // There's no need to deal with any references in a frame that triggered a stack overflow.
                // The explicit stack banging code that causes a stack overflow trap is always in the
                // prologue which is guaranteed not to be in the scope of a local exception handler.
                // Thus, no GC roots need to be scanned in this frame.
                return true;
            }
        }
        Pointer localVariablesBase = framePointerState.localVariablesBase(current);
        return targetMethod.prepareFrameReferenceMap((StackReferenceMapPreparer) context, current.ip(), localVariablesBase, current.sp(), 0);
    }

    private void walkFrameForExceptionHandling(StackFrameWalker.Cursor current, StackFrameWalker stackFrameWalker, boolean isTopFrame, TargetMethod targetMethod, Object context, AMD64JitTargetMethod.FramePointerState framePointerState) {
        Address throwAddress = current.ip();
        StackUnwindingContext stackUnwindingContext = UnsafeCast.asStackUnwindingContext(context);
        Address catchAddress = targetMethod.throwAddressToCatchAddress(isTopFrame, throwAddress, stackUnwindingContext.throwable.getClass());

        if (!catchAddress.isZero()) {
            if (StackFrameWalker.TRACE_STACK_WALK.getValue()) {
                Log.print("StackFrameWalk: Handler position for exception at position ");
                Log.print(throwAddress.minus(targetMethod.codeStart()).toInt());
                Log.print(" is ");
                Log.println(catchAddress.minus(targetMethod.codeStart()).toInt());
            }
            Throwable throwable = stackUnwindingContext.throwable;
            Pointer localVariablesBase = framePointerState.localVariablesBase(current);
            // The Java operand stack of the method that handles the exception is always cleared.
            // A null object is then pushed to ensure the depth of the stack is as expected upon
            // entry to an exception handler. However, the handler must have a prologue that loads
            // the exception from VmThreadLocal.EXCEPTION_OBJECT which is indeed guaranteed by
            // ExceptionDispatcher.
            // Compute the offset to the first stack slot of the Java Stack: frame size - (space for locals + saved RBP
            // + space of the first slot itself).
            Pointer catcherStackPointer = localVariablesBase.minus(framePointerState.sizeOfNonParameterLocals(targetMethod) + JitStackFrameLayout.JIT_SLOT_SIZE);
            // Push the null object on top of the stack first
            catcherStackPointer.writeReference(0, null);

            // found an exception handler, and thus we are done with the stack walker
            stackFrameWalker.reset();

            // Completes the exception handling protocol (with respect to the garbage collector) initiated in
            // Throwing.raise()
            Safepoint.enable();

            unwind(throwable, catchAddress, catcherStackPointer, localVariablesBase);
            // We should never reach here
        }
    }

}
