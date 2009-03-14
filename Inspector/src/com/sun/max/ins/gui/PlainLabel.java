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
package com.sun.max.ins.gui;

import com.sun.max.ins.*;


/**
 * A text label specialized for use in the Maxine Inspector.
 *
 * @author Michael Van De Vanter
 */
public class PlainLabel extends InspectorLabel {

    /**
     * Creates a new text label displaying the value of an integer.
     */
    public PlainLabel(Inspection inspection, int n) {
        this(inspection, Integer.toString(n));
    }

    /**
     * Creates a new text label displaying specified text.
     */
    public PlainLabel(Inspection inspection, String text) {
        super(inspection, text);
        redisplay();
    }

    public void setValue(int n) {
        setText(Integer.toString(n));
    }

    public void setValue(String text) {
        setText(text);
    }

    public final void refresh(long epoch, boolean force) {
        // No remote data to refresh
    }

    public void redisplay() {
        setFont(style().defaultFont());
        setForeground(style().defaultTextColor());
        setBackground(style().defaultTextBackgroundColor());
    }

}
