/*
 * Copyright (c) 2014, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.dispatch;

import org.truffleruby.RubyContext;
import org.truffleruby.core.module.MethodLookupResult;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.methods.InternalMethod;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.object.DynamicObject;

/** Like {@link CachedBoxedDispatchNode}, but on singleton objects. Checking identity of the DynamicObject is therefore
 * faster than reading the Shape and checking the Shape identity. */
public class CachedSingletonDispatchNode extends CachedDispatchNode {

    private final DynamicObject expectedReceiver;
    @CompilationFinal(dimensions = 1) private final Assumption[] assumptions;

    private final InternalMethod method;
    @Child private DirectCallNode callNode;

    public CachedSingletonDispatchNode(
            RubyContext context,
            Object cachedName,
            DispatchNode next,
            DynamicObject expectedReceiver,
            MethodLookupResult methodLookup,
            DispatchAction dispatchAction) {
        super(context, cachedName, next, dispatchAction);

        this.expectedReceiver = expectedReceiver;
        this.assumptions = methodLookup.getAssumptions();
        this.next = next;
        this.method = methodLookup.getMethod();
        this.callNode = Truffle.getRuntime().createDirectCallNode(method.getCallTarget());
    }

    @Override
    protected void reassessSplittingInliningStrategy() {
        applySplittingInliningStrategy(callNode, method);
    }

    @Override
    public boolean guard(Object methodName, Object receiver) {
        return guardName(methodName) &&
                receiver == expectedReceiver;
    }

    @Override
    public Object executeDispatch(
            VirtualFrame frame,
            Object receiverObject,
            Object methodName,
            DynamicObject blockObject,
            Object[] argumentsObjects) {
        try {
            checkAssumptions(assumptions);
        } catch (InvalidAssumptionException e) {
            return resetAndDispatch(
                    frame,
                    receiverObject,
                    methodName,
                    blockObject,
                    argumentsObjects,
                    "class modified");
        }

        if (!guard(methodName, receiverObject)) {
            return next.executeDispatch(
                    frame,
                    receiverObject,
                    methodName,
                    blockObject,
                    argumentsObjects);
        }

        switch (getDispatchAction()) {
            case CALL_METHOD:
                return call(callNode, frame, method, expectedReceiver, blockObject, argumentsObjects);

            case RESPOND_TO_METHOD:
                return true;

            default:
                throw CompilerDirectives.shouldNotReachHere();
        }
    }

    @Override
    public String toString() {
        return StringUtils.format(
                "CachedSingletonDispatchNode(:%s, %s@%x, %s)",
                getCachedName(),
                expectedReceiver,
                expectedReceiver.hashCode(),
                method);
    }

}
