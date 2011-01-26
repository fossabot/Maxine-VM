/*
 * Copyright (c) 2007, 2009, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package test.com.sun.max.vm.cps.cir.generate;

import junit.framework.*;
import test.com.sun.max.vm.cps.*;

import com.sun.max.vm.cps.cir.*;

/**
 * @author Hiroshi Yamauchi
 * @author Bernd Mathiske
 */
public class CirTranslatorTest_regressions extends CompilerTest_regressions<CirMethod> {

    public static void main(String[] args) {
        junit.textui.TestRunner.run(CirTranslatorTest_regressions.suite());
    }

    public static Test suite() {
        final TestSuite suite = new TestSuite(CirTranslatorTest_regressions.class.getSimpleName());
        suite.addTestSuite(CirTranslatorTest_regressions.class);
        return new CirTranslatorTestSetup(suite);
    }

    public CirTranslatorTest_regressions(String name) {
        super(name);
    }

}
