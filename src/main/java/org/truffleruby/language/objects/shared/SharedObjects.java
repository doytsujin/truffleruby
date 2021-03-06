/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.objects.shared;

import java.util.ArrayDeque;
import java.util.Deque;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.objects.ObjectGraph;
import org.truffleruby.language.objects.ShapeCachingGuards;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.SourceSection;

public class SharedObjects {

    private final RubyContext context;
    // No need for volatile since we change this before starting the 2nd Thread
    private boolean sharing = false;

    public SharedObjects(RubyContext context) {
        this.context = context;
    }

    public boolean isSharing() {
        return sharing;
    }

    public void startSharing(String reason) {
        if (!sharing) {
            sharing = true;
            if (context.getOptions().SHARED_OBJECTS_DEBUG) {
                RubyLanguage.LOGGER.info("starting sharing due to " + reason);
            }
            shareContextRoots(context);
        }
    }

    private static void shareContextRoots(RubyContext context) {
        final Deque<Object> stack = new ArrayDeque<>();

        // Share global variables (including new ones)
        for (Object object : context.getCoreLibrary().globalVariables.objectGraphValues()) {
            stack.push(object);
        }

        // Share the native configuration
        for (Object object : context.getNativeConfiguration().objectGraphValues()) {
            stack.push(object);
        }

        // Share all named modules and constants
        stack.push(context.getCoreLibrary().objectClass);

        // Share all threads since they are accessible via Thread.list
        for (DynamicObject thread : context.getThreadManager().iterateThreads()) {
            stack.push(thread);
        }

        long t0 = System.currentTimeMillis();
        shareObjects(context, stack);
        if (context.getOptions().SHARED_OBJECTS_DEBUG) {
            RubyLanguage.LOGGER.info("sharing roots took " + (System.currentTimeMillis() - t0) + " ms");
        }
    }

    public static void shareDeclarationFrame(RubyContext context, DynamicObject block) {

        if (context.getOptions().SHARED_OBJECTS_DEBUG) {
            final SourceSection sourceSection = Layouts.PROC.getSharedMethodInfo(block).getSourceSection();
            RubyLanguage.LOGGER.info("sharing decl frame of " + RubyContext.fileLine(sourceSection));
        }

        final MaterializedFrame declarationFrame = Layouts.PROC.getDeclarationFrame(block);
        final Deque<Object> stack = new ArrayDeque<>(ObjectGraph.getObjectsInFrame(declarationFrame));

        shareObjects(context, stack);
    }

    private static void shareObjects(RubyContext context, Deque<Object> stack) {
        while (!stack.isEmpty()) {
            final Object object = stack.pop();
            assert ObjectGraph.isSymbolOrDynamicObject(object);

            if (object instanceof DynamicObject) {
                if (share(context, (DynamicObject) object)) {
                    stack.addAll(ObjectGraph.getAdjacentObjects((DynamicObject) object));
                }
            }
        }
    }

    @TruffleBoundary
    private static void shareObject(RubyContext context, DynamicObject value) {
        final Deque<Object> stack = new ArrayDeque<>();
        stack.add(value);
        shareObjects(context, stack);
    }

    public static boolean isShared(RubyContext context, Object object) {
        return object instanceof RubySymbol ||
                (object instanceof DynamicObject && isShared(context, ((DynamicObject) object).getShape()));
    }

    public static boolean isShared(RubyContext context, Shape shape) {
        return context.getOptions().SHARED_OBJECTS_ENABLED && shape.isShared();
    }

    public static boolean assertPropagateSharing(RubyContext context, DynamicObject source, Object value) {
        if (isShared(context, source) && value instanceof DynamicObject) {
            return isShared(context, value);
        } else {
            return true;
        }
    }

    public static void writeBarrier(RubyContext context, Object value) {
        if (context.getOptions().SHARED_OBJECTS_ENABLED && value instanceof DynamicObject &&
                !isShared(context, value)) {
            shareObject(context, (DynamicObject) value);
        }
    }

    public static void propagate(RubyContext context, DynamicObject source, Object value) {
        if (isShared(context, source)) {
            writeBarrier(context, value);
        }
    }

    private static boolean share(RubyContext context, DynamicObject object) {
        if (isShared(context, object)) {
            return false;
        }

        ShapeCachingGuards.updateShape(object);
        final Shape oldShape = object.getShape();
        final Shape newShape = oldShape.makeSharedShape();
        object.setShapeAndGrow(oldShape, newShape);

        onShareHook(object);
        return true;
    }

    public static void onShareHook(DynamicObject object) {
    }

    @TruffleBoundary
    public static void shareInternalFields(RubyContext context, DynamicObject object) {
        onShareHook(object);
        final Deque<Object> stack = new ArrayDeque<>();
        // This will also share user fields, but that's OK
        stack.addAll(ObjectGraph.getAdjacentObjects(object));
        shareObjects(context, stack);
    }

}
