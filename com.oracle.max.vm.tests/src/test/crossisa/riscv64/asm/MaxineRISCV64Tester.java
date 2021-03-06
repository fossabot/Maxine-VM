/*
 * Copyright (c) 2018, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package test.crossisa.riscv64.asm;

import com.sun.cri.ci.CiRegister;
import com.sun.max.vm.runtime.FatalError;
import test.crossisa.*;

import static test.crossisa.CrossISATester.BitsFlag.All64Bits;
import static test.crossisa.CrossISATester.BitsFlag.Lower32Bits;

public class MaxineRISCV64Tester extends CrossISATester {
    public static final int NUM_REGS = 32;

    /*
     * riscv64-unknown-elf-gcc -g -march=rv64g -mabi=lp64d -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles -Ttest_riscv64.ld startup_riscv64.s test_riscv64.c -o test.elf
     * qemu-system-riscv64 -nographic -S -s -kernel test.elf
     */
    private MaxineRISCV64Tester(String[] args) {
        super();
        initializeQemu();
        expectedLongRegisters = new long[Integer.parseInt(args[0])];
        testIntRegisters = new boolean[Integer.parseInt(args[0])];

        for (int i = 1; i < args.length; i += 2) {
            expectedLongRegisters[Integer.parseInt(args[i])] = Long.parseLong(args[i + 1]);
            testIntRegisters[Integer.parseInt(args[i])] = true;
        }
    }

    public MaxineRISCV64Tester() {
        super();
        initializeQemu();
    }

    @Override
    protected ProcessBuilder getCompilerProcessBuilder() {
        if (gccProcessBuilder != null) {
            return gccProcessBuilder;
        }
        return new ProcessBuilder("riscv64-unknown-elf-gcc", "-g", "-march=rv64g", "-mabi=lp64d", "-static",
                                  "-mcmodel=medany", "-fvisibility=hidden", "-nostdlib", "-nostartfiles",
                                  "-Ttest_riscv64.ld", "startup_riscv64.s", "test_riscv64.c", "-o", "test.elf");
    }

    @Override
    protected ProcessBuilder getAssemblerProcessBuilder() {
        if (assemblerProcessBuilder != null) {
            return assemblerProcessBuilder;
        }
        return new ProcessBuilder("true");
    }

    @Override
    protected ProcessBuilder getLinkerProcessBuilder() {
        if (linkerProcessBuilder != null) {
            return linkerProcessBuilder;
        }
        return new ProcessBuilder("true");
    }

    @Override
    protected ProcessBuilder getGDBProcessBuilder() {
        if (gdbProcessBuilder != null) {
            return gdbProcessBuilder;
        }
        return new ProcessBuilder("riscv64-unknown-elf-gdb", "-q", "-x", "gdb_input_riscv");
    }

    @Override
    protected ProcessBuilder getQEMUProcessBuilder() {
        if (qemuProcessBuilder != null) {
            return qemuProcessBuilder;
        }
        return new ProcessBuilder("qemu-system-riscv64", "-M", "virt", "-m", "128M", "-nographic", "-s", "-S", "-kernel", "test.elf");
    }

    /**
     * Sets the expected value of a register and enables it for inspection.
     *
     * @param cpuRegister the number of the cpuRegister
     * @param expectedValue the expected value
     */
    @Override
    public void setExpectedValue(CiRegister cpuRegister, long expectedValue) {
        final int index = cpuRegister.getEncoding() - 1; // -1 to compensate for the zero register
        expectedLongRegisters[index] = expectedValue;
        testLongRegisters[index] = true;
        bitMasks[index] = All64Bits;
    }

    /**
     * Sets the expected value of a register and enables it for inspection.
     *
     * @param cpuRegister the number of the cpuRegister
     * @param expectedValue the expected value
     */
    @Override
    public void setExpectedValue(CiRegister cpuRegister, int expectedValue) {
        final int index = cpuRegister.getEncoding() - 1; // -1 to compensate for the zero register
        expectedLongRegisters[index] = expectedValue;
        testLongRegisters[index] = true;
        bitMasks[index] = Lower32Bits;
    }

    /**
     * Sets the expected value of a register and enables it for inspection.
     *
     * @param fpuRegister the number of the cpuRegister
     * @param expectedValue the expected value
     */
    @Override
    public void setExpectedValue(CiRegister fpuRegister, float expectedValue) {
        final int index = fpuRegister.getEncoding() - 1; // -1 to compensate for the zero register
        expectedFloatRegisters[index] = expectedValue;
        testFloatRegisters[index] = true;
    }

    /**
     * Sets the expected value of a register and enables it for inspection.
     *
     * @param fpuRegister the number of the cpuRegister
     * @param expectedValue the expected value
     */
    @Override
    public void setExpectedValue(CiRegister fpuRegister, double expectedValue) {
        final int index = fpuRegister.getEncoding() - 1; // -1 to compensate for the zero register
        expectedDoubleRegisters[index] = expectedValue;
        testDoubleRegisters[index] = true;
    }

    /**
     * Parses a float register (32-bit) from the output of the gdb command {@code info all-registers}.  The output is
     * expected to be in the form:
     *
     * <pre>
     *     TODO
     * </pre>
     *
     * @param line The line from the gdb output to be parsed
     * @return The parsed float value of the register
     */
    @Override
    protected float parseFloatRegister(String line) {
        throw FatalError.unimplemented();
    }

    /**
     * Parses a double register (64-bit) from the output of the gdb command {@code info all-registers}.  The output is
     * expected to be in the form:
     *
     * <pre>
     *     TODO
     * </pre>
     *
     * @param line The line from the gdb output to be parsed
     * @return The parsed double value of the register
     */
    protected double parseDoubleRegister(String line) {
        throw FatalError.unimplemented();
    }

    public void runSimulation() throws Exception {
        super.runSimulation();
        parseLongRegisters("ra ", "pc");
    }

    public static void main(String[] args) throws Exception {
        MaxineRISCV64Tester tester = new MaxineRISCV64Tester(args);
        tester.run();
    }

}
