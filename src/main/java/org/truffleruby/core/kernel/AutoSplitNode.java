/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.kernel;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.truffleruby.language.RubyContextSourceNode;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.globals.ReadGlobalVariableNode;
import org.truffleruby.language.globals.ReadGlobalVariableNodeGen;
import org.truffleruby.language.globals.WriteSimpleGlobalVariableNode;

public class AutoSplitNode extends RubyContextSourceNode {

    @Child private CallDispatchHeadNode callSplitNode = CallDispatchHeadNode.createPrivate();
    @Child private ReadGlobalVariableNode readGlobalVariableNode = ReadGlobalVariableNodeGen.create("$_");
    @Child private WriteSimpleGlobalVariableNode writeSimpleGlobalVariableNode = WriteSimpleGlobalVariableNode
            .create("$F");

    @Override
    public Object execute(VirtualFrame frame) {
        // $F = $_.split
        final Object lastLine = readGlobalVariableNode.execute(frame);
        return writeSimpleGlobalVariableNode.execute(callSplitNode.call(lastLine, "split"));
    }

}
