/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.pack;


import com.oracle.truffle.api.nodes.Node;
import org.truffleruby.RubyContext;
import org.truffleruby.core.format.FormatRootNode;
import org.truffleruby.core.format.LoopRecovery;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import org.truffleruby.core.rope.RopeOperations;

public class PackCompiler {

    private final RubyContext context;
    private final Node currentNode;

    public PackCompiler(RubyContext context, Node currentNode) {
        this.context = context;
        this.currentNode = currentNode;
    }

    public RootCallTarget compile(String format) {
        if (format.length() > context.getOptions().PACK_RECOVER_LOOP_MIN) {
            format = LoopRecovery.recoverLoop(format);
        }

        final SimplePackTreeBuilder builder = new SimplePackTreeBuilder(context, currentNode);

        builder.enterSequence();

        final SimplePackParser parser = new SimplePackParser(builder, RopeOperations.encodeAsciiBytes(format));
        parser.parse();

        builder.exitSequence();

        return Truffle.getRuntime().createCallTarget(
                new FormatRootNode(
                        context,
                        currentNode.getEncapsulatingSourceSection(),
                        builder.getEncoding(),
                        builder.getNode()));
    }

}
