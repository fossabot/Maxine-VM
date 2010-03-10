/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.c1x.opt;

import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.ci.*;
import com.sun.c1x.graph.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.util.*;

/**
 * This class implements a data-flow analysis to remove redundant null checks
 * and deoptimization info for instructions that cannot ever produce {@code NullPointerException}.
 *
 * This implementation uses an optimistic dataflow analysis by attempting to visit all predecessors
 * of a block before visiting the block itself. For this purpose it uses the block numbers computed by
 * the {@link com.sun.c1x.graph.BlockMap} during graph construction, which may not actually be
 * a valid reverse post-order number (due to inlining and any previous optimizations).
 *
 * When loops are encountered, or if the blocks are not visited in the optimal order, this implementation
 * will fall back to performing an iterative data flow analysis where it maintains a set
 * of incoming non-null instructions and a set of locally produced outgoing non-null instructions
 * and iterates the dataflow equations to a fixed point. Basically, for block b,
 * out(b) = in(b) U local_out(b) and in(b) = intersect(out(pred)). After a fixed point is
 * reached, the resulting incoming sets are used to visit instructions with uneliminated null checks
 * a second time.
 *
 * Note that the iterative phase is actually optional, because the first pass is conservative.
 * Iteration can be disabled by setting {@link com.sun.c1x.C1XOptions#OptIterativeNCE} to
 * {@code false}. Iteration is rarely necessary for acyclic graphs.
 *
 * @author Ben L. Titzer
 */
public class NullCheckEliminator extends DefaultValueVisitor {

    static class IfEdge {
        final BlockBegin ifBlock;
        final BlockBegin succ;
        final Value checked;

        IfEdge(BlockBegin i, BlockBegin s, Value c) {
            this.ifBlock = i;
            this.succ = s;
            this.checked = c;
        }
    }

    static class BlockInfo {
        // used in first pass
        final BlockBegin block;
        boolean marked;
        BitMap localOut;
        BitMap localExcept;
        List<Value> localUses;
        // used in iteration and flow sensitivity
        IfEdge ifEdge;
        BitMap localIn;

        BlockInfo(BlockBegin b) {
            this.block = b;
        }
    }

    static class ValueInfo {
        final Value value;
        final int globalIndex;

        ValueInfo(Value value, int index) {
            this.value = value;
            globalIndex = index;
        }
    }

    final IR ir;
    final BlockWorkList workList = new BlockWorkList();

    final ArrayList<BlockInfo> blockInfos = new ArrayList<BlockInfo>(5);
    final ArrayList<ValueInfo> valueInfos = new ArrayList<ValueInfo>(5);
    final ArrayList<BlockInfo> remainingUses = new ArrayList<BlockInfo>(5);

    // maps used only in iteration
    boolean requiresIteration;
    int maximumIndex;

    BitMap currentBitMap;
    List<Value> currentUses;

    /**
     * Creates a new null check eliminator for the specified IR and performs the optimization.
     * @param ir the IR
     */
    public NullCheckEliminator(IR ir) {
        this.ir = ir;
        optimize();
    }

    private void optimize() {
        if (C1XOptions.PrintTimers) {
            C1XTimers.NCE.start();
        }
        BlockInfo start = getBlockInfo(ir.startBlock);
        mark(start);
        processBlock(start);
        while (!workList.isEmpty()) {
            processBlock(getBlockInfo(workList.removeFromWorkList()));
        }
        if (requiresIteration && C1XOptions.OptIterativeNCE) {
            // there was a loop, or blocks were not visited in reverse post-order;
            // iteration is required to compute the in sets for a second pass
            iterate();
        }
        clearInfo();
        if (C1XOptions.PrintTimers) {
            C1XTimers.NCE.stop();
        }
    }

    private void processBlock(BlockInfo info) {
        BlockBegin block = info.block;
        // first pass on a block
        computeLocalInSet(info);
        // process any phis in the block
        for (Phi phi : block.stateBefore().allPhis(block)) {
            visitPhi(phi);
        }
        // now visit the instructions in order
        for (Instruction i = block.next(); i != null; i = i.next()) {
            i.accept(this);
        }
        if (!currentUses.isEmpty()) {
            // remember any localUses in this block for later iterative processing
            info.localUses = currentUses;
            remainingUses.add(info);
        }
        queueSuccessors(block.end().successors());
        queueSuccessors(block.exceptionHandlerBlocks());
    }

    private void queueSuccessors(List<BlockBegin> successorList) {
        for (BlockBegin succ : successorList) {
            BlockInfo info = getBlockInfo(succ);
            if (!isMarked(info)) {
                workList.addSorted(succ, succ.depthFirstNumber());
                mark(info);
            }
        }
    }

    private void computeLocalInSet(BlockInfo info) {
        BlockBegin block = info.block;
        // compute the initial {in} set based on the {localOut} sets of predecessors, if possible
        currentBitMap = null;
        currentUses = new ArrayList<Value>();
        if (block.numberOfPreds() == 0) {
            // no predecessors => start block
            assert block == ir.startBlock : "block without predecessors should be start block";
            currentBitMap = newBitMap();
        } else {
            // block has at least one predecessor
            for (BlockBegin pred : block.predecessors()) {
                if (getPredecessorMap(pred, block.isExceptionEntry()) == null) {
                    // one of the predecessors of this block has not been visited,
                    // we have to be conservative and start with nothing known
                    currentBitMap = newBitMap();
                    requiresIteration = true;
                }
            }
            if (currentBitMap == null) {
                // all the predecessors have been visited, compute the intersection of their {localOut} sets
                for (BlockBegin pred : block.predecessors()) {
                    BlockInfo predInfo = getBlockInfo(pred);
                    BitMap predMap = getPredecessorMap(predInfo, block.isExceptionEntry());
                    currentBitMap = intersectLocalOut(predInfo, currentBitMap, predMap, block);
                }
            }
        }
        assert currentBitMap != null : "current bitmap should be computed one or the other way";
        // if there are exception handlers for this block, then clone {in} and put it in {localExcept}
        if (block.numberOfExceptionHandlers() > 0) {
            info.localExcept = currentBitMap.copy();
        }
        info.localOut = currentBitMap;
    }

    private BitMap intersectLocalOut(BlockInfo pred, BitMap current, BitMap predMap, BlockBegin succ) {
        predMap = intersectFlowSensitive(pred, predMap, succ);
        if (current == null) {
            current = predMap.copy();
        } else {
            current.setIntersect(predMap);
        }
        return current;
    }

    private BitMap intersectFlowSensitive(BlockInfo pred, BitMap n, BlockBegin succ) {
        if (C1XOptions.OptFlowSensitiveNCE) {
            // check to see if there is an if edge between these two blocks
            if (pred.ifEdge != null && pred.ifEdge.succ == succ) {
                // if there is a special edge between pred and block, add the checked instruction
                n = n.copy();
                setValue(pred.ifEdge.checked, n);
            }
        }
        return n;
    }

    private void iterate() {
        // the previous phase calculated all the {localOut} sets; use iteration to
        // calculate the {in} sets
        if (remainingUses.size() > 0) {
            // only perform iterative flow analysis if there are checks remaining to eliminate
            C1XMetrics.NullCheckIterations++;
            clearMarked();
            // start off by propagating a new set to the start block
            propagate(getBlockInfo(ir.startBlock), newBitMap(), ir.startBlock);
            while (!workList.isEmpty()) {
                BlockInfo block = getBlockInfo(workList.removeFromWorkList());
                unmark(block);
                iterateBlock(block);
            }
            // now that the fixed point is reached, reprocess any remaining localUses
            currentUses = null; // the list won't be needed this time
            for (BlockInfo info : remainingUses) {
                reprocessUses(info.localIn, info.localUses);
            }
        }
    }

    private void iterateBlock(BlockInfo info) {
        BitMap prevMap = info.localIn;
        assert prevMap != null : "how did the block get on the worklist without an initial in map?";
        BitMap localOut = info.localOut;
        BitMap out;
        // copy larger and do union with smaller
        if (localOut.length() > prevMap.length()) {
            out = localOut.copy();
            out.setUnion(prevMap);
        } else {
            out = prevMap.copy();
            out.setUnion(localOut);
        }
        propagateSuccessors(info, out, info.block.end().successors()); // propagate {in} U {localOut} to successors
        propagateSuccessors(info, prevMap, info.block.exceptionHandlerBlocks()); // propagate {in} to exception handlers
    }

    private void propagateSuccessors(BlockInfo block, BitMap out, List<BlockBegin> successorList) {
        for (BlockBegin succ : successorList) {
            propagate(block, out, succ);
        }
    }

    private void propagate(BlockInfo pred, BitMap bitMap, BlockBegin succ) {
        boolean changed;
        propagateFlowSensitive(pred, bitMap, succ);
        BlockInfo succInfo = getBlockInfo(succ);
        if (succInfo.localIn == null) {
            // this is the first time this block is being iterated
            succInfo.localIn = bitMap.copy();
            changed = true;
        } else {
            // perform intersection with previous map
            changed = succInfo.localIn.setIntersect(bitMap);
        }
        if (changed && !isMarked(succInfo)) {
            mark(succInfo);
            workList.addSorted(succ, succ.depthFirstNumber());
        }
    }

    private void propagateFlowSensitive(BlockInfo pred, BitMap bitMap, BlockBegin succ) {
        if (C1XOptions.OptFlowSensitiveNCE) {
            if (pred.ifEdge != null && pred.ifEdge.succ == succ) {
                // there is a special if edge between these blocks, add the checked instruction
                setValue(pred.ifEdge.checked, bitMap);
            }
        }
    }

    private void reprocessUses(BitMap in, List<Value> uses) {
        // iterate over each of the use instructions again, using the input bitmap
        // and the hash sets
        assert in != null;
        currentBitMap = in;
        for (Value i : uses) {
            i.accept(this);
        }
    }

    private boolean processUse(Value use, Value object, boolean implicitCheck) {
        if (object.isNonNull()) {
            // the object itself is known for sure to be non-null, so clear the flag.
            // the flag is usually cleared in the constructor of the using instruction, but
            // later optimizations may more reveal more non-null objects
            use.eliminateNullCheck();
            return true;
        } else {
            // check if the object is non-null in the bitmap or hashset
            if (checkValue(object, currentBitMap)) {
                // the object is non-null at this site
                use.eliminateNullCheck();
                return true;
            } else {
                if (implicitCheck) {
                    // the object will be non-null after executing this instruction
                    setValue(object, currentBitMap);
                }
                if (currentUses != null) {
                    currentUses.add(use); // record a use for potential later iteration
                }
            }
        }
        return false;
    }

    private boolean isNonNullOnEdge(BlockBegin pred, BlockBegin succ, Value i) {
        if (C1XOptions.OptFlowSensitiveNCE) {
            IfEdge e = getBlockInfo(pred).ifEdge;
            if (e != null && e.succ == succ && e.checked == i) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void visitPhi(Phi phi) {
        for (int j = 0; j < phi.operandCount(); j++) {
            Value operand = phi.operandAt(j);
            if (processUse(phi, operand, false)) {
                continue;
            }
            if (C1XOptions.OptFlowSensitiveNCE) {
                BlockBegin phiBlock = phi.block();
                if (!phiBlock.isExceptionEntry() && isNonNullOnEdge(phiBlock.predecessors().get(j), phiBlock, operand)) {
                    continue;
                }
            }
            return;
        }
        // all inputs are non-null
        phi.setFlag(Value.Flag.NonNull);
    }

    @Override
    public void visitLoadField(LoadField i) {
        Value object = i.object();
        if (object != null) {
            processUse(i, object, true);
        }
    }

    @Override
    public void visitStoreField(StoreField i) {
        Value object = i.object();
        if (object != null) {
            processUse(i, object, true);
        }
    }

    @Override
    public void visitArrayLength(ArrayLength i) {
        processUse(i, i.array(), true);
    }

    @Override
    public void visitLoadIndexed(LoadIndexed i) {
        processUse(i, i.array(), true);
    }

    @Override
    public void visitStoreIndexed(StoreIndexed i) {
        processUse(i, i.array(), true);
    }

    @Override
    public void visitNullCheck(NullCheck i) {
        processUse(i, i.object(), true);
    }

    @Override
    public void visitInvoke(Invoke i) {
        if (!i.isStatic()) {
            processUse(i, i.receiver(), true);
        }
    }

    @Override
    public void visitCheckCast(CheckCast i) {
        if (processUse(i, i.object(), false)) {
            // if the object is non null, the result of the cast is as well
            i.setFlag(Value.Flag.NonNull);
        }
    }

    @Override
    public void visitInstanceOf(InstanceOf i) {
        processUse(i, i.object(), false); // instanceof can check faster if object is non-null
    }

    @Override
    public void visitMonitorEnter(MonitorEnter i) {
        processUse(i, i.object(), true);
    }

    @Override
    public void visitIntrinsic(Intrinsic i) {
        if (!i.isStatic()) {
            processUse(i, i.receiver(), true);
        }
    }

    @Override
    public void visitIf(If i) {
        if (C1XOptions.OptFlowSensitiveNCE) {
            if (i.trueSuccessor() != i.falseSuccessor()) {
                // if the two successors are different, then we may learn something on one branch
                Value x = i.x();
                if (x.kind == CiKind.Object) {
                    // this is a comparison of object references
                    Value y = i.y();
                    if (processUse(i, x, false)) {
                        // x is known to be non-null
                        compareAgainstNonNull(i, y);
                    } else if (processUse(i, y, false)) {
                        // y is known to be non-null
                        compareAgainstNonNull(i, x);
                    } else if (x.isNullConstant()) {
                        // x is the null constant
                        compareAgainstNull(i, y);
                    } else if (y.isNullConstant()) {
                        // y is the null constaint
                        compareAgainstNull(i, x);
                    }
                }
                // XXX: also check (x instanceof T) tests
            }
        }
    }

    private void compareAgainstNonNull(If i, Value use) {
        if (i.condition() == Condition.eql) {
            propagateNonNull(i, use, i.trueSuccessor());
        }
    }

    private void compareAgainstNull(If i, Value use) {
        if (i.condition() == Condition.eql) {
            propagateNonNull(i, use, i.falseSuccessor());
        }
        if (i.condition() == Condition.neq) {
            propagateNonNull(i, use, i.trueSuccessor());
        }
    }

    private void propagateNonNull(If i, Value use, BlockBegin succ) {
        getBlockInfo(i.begin()).ifEdge = new IfEdge(i.begin(), succ, use);
    }

    private ValueInfo getValueInfo(Value value) {
        Object info = value.optInfo;
        if (info instanceof ValueInfo) {
            return (ValueInfo) info;
        }
        C1XMetrics.NullCheckIdsAssigned++;
        ValueInfo ninfo = new ValueInfo(value, maximumIndex++);
        value.optInfo = ninfo;
        valueInfos.add(ninfo);
        return ninfo;
    }

    private BlockInfo getBlockInfo(BlockBegin block) {
        Object info = block.optInfo;
        if (info instanceof BlockInfo) {
            return (BlockInfo) info;
        }
        BlockInfo ninfo = new BlockInfo(block);
        block.optInfo = ninfo;
        blockInfos.add(ninfo);
        return ninfo;
    }

    private void setValue(Value value, BitMap bitmap) {
        int index = getValueInfo(value).globalIndex;
        bitmap.grow(index + 1);
        bitmap.set(index);
    }

    private boolean checkValue(Value value, BitMap bitmap) {
        Object info = value.optInfo;
        return info instanceof ValueInfo && bitmap.getDefault(((ValueInfo) info).globalIndex);
    }

    private boolean isMarked(BlockInfo block) {
        return block.marked;
    }

    private void mark(BlockInfo block) {
        block.marked = true;
    }

    private void unmark(BlockInfo block) {
        block.marked = false;
    }

    private void clearInfo() {
        // be a good citizen and clear all the information added to nodes
        // (also avoids confusing a later application of this same optimization)
        for (BlockInfo info : blockInfos) {
            assert info.block.optInfo instanceof BlockInfo : "inconsistent block information";
            info.block.optInfo = null;
        }
        for (ValueInfo info : valueInfos) {
            assert info.value.optInfo instanceof ValueInfo : "inconsistent value information";
            info.value.optInfo = null;
        }
    }

    private void clearMarked() {
        for (BlockInfo info : blockInfos) {
            info.marked = false;
        }
    }

    private BitMap newBitMap() {
        return new BitMap(maximumIndex > 32 ? maximumIndex : 32);
    }

    private BitMap getPredecessorMap(BlockBegin pred, boolean exceptionBlock) {
        BlockInfo predInfo = getBlockInfo(pred);
        return exceptionBlock ? predInfo.localExcept : predInfo.localOut;
    }

    private BitMap getPredecessorMap(BlockInfo predInfo, boolean exceptionBlock) {
        return exceptionBlock ? predInfo.localExcept : predInfo.localOut;
    }
}
