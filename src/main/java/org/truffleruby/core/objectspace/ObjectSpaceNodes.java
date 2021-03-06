/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.objectspace;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.YieldingCoreMethodNode;
import org.truffleruby.core.FinalizerReference;
import org.truffleruby.core.numeric.BigIntegerOps;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.DoesRespondDispatchHeadNode;
import org.truffleruby.language.objects.IsANode;
import org.truffleruby.language.objects.ObjectGraph;
import org.truffleruby.language.objects.ObjectIDOperations;
import org.truffleruby.language.objects.ReadObjectFieldNode;
import org.truffleruby.language.objects.WriteObjectFieldNode;
import org.truffleruby.language.objects.shared.WriteBarrierNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;

@CoreModule("ObjectSpace")
public abstract class ObjectSpaceNodes {

    @CoreMethod(names = "_id2ref", isModuleFunction = true, required = 1)
    @ImportStatic(ObjectIDOperations.class)
    public abstract static class ID2RefNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "id == NIL")
        protected Object id2RefNil(long id) {
            return nil;
        }

        @Specialization(guards = "id == TRUE")
        protected boolean id2RefTrue(long id) {
            return true;
        }

        @Specialization(guards = "id == FALSE")
        protected boolean id2RefFalse(long id) {
            return false;
        }

        @Specialization(guards = "isSmallFixnumID(id)")
        protected long id2RefSmallInt(long id) {
            return ObjectIDOperations.toFixnum(id);
        }

        @TruffleBoundary
        @Specialization(guards = "isBasicObjectID(id)")
        protected Object id2Ref(long id,
                @Cached ReadObjectFieldNode readObjectIdNode) {
            for (Object object : ObjectGraph.stopAndGetAllObjects(this, getContext())) {
                assert ObjectGraph.isSymbolOrDynamicObject(object);

                long objectID = 0L;
                if (object instanceof DynamicObject) {
                    objectID = (long) readObjectIdNode
                            .execute((DynamicObject) object, Layouts.OBJECT_ID_IDENTIFIER, 0L);
                } else if (object instanceof RubySymbol) {
                    objectID = ((RubySymbol) object).getObjectId();
                }

                if (objectID == id) {
                    return object;
                }
            }

            throw new RaiseException(
                    getContext(),
                    coreExceptions().rangeError(StringUtils.format("0x%016x is not id value", id), this));
        }

        @Specialization(guards = { "isRubyBignum(id)", "isLargeFixnumID(id)" })
        protected Object id2RefLargeFixnum(DynamicObject id) {
            return BigIntegerOps.longValue(id);
        }

        @Specialization(guards = { "isRubyBignum(id)", "isFloatID(id)" })
        protected double id2RefFloat(DynamicObject id) {
            return Double.longBitsToDouble(BigIntegerOps.longValue(id));
        }

        protected boolean isLargeFixnumID(DynamicObject id) {
            return ObjectIDOperations.isLargeFixnumID(Layouts.BIGNUM.getValue(id));
        }

        protected boolean isFloatID(DynamicObject id) {
            return ObjectIDOperations.isFloatID(Layouts.BIGNUM.getValue(id));
        }

    }

    @CoreMethod(
            names = "each_object",
            isModuleFunction = true,
            needsBlock = true,
            optional = 1,
            returnsEnumeratorIfNoBlock = true)
    public abstract static class EachObjectNode extends YieldingCoreMethodNode {

        @TruffleBoundary // for the iterator
        @Specialization
        protected int eachObject(NotProvided ofClass, DynamicObject block) {
            int count = 0;

            for (Object object : ObjectGraph.stopAndGetAllObjects(this, getContext())) {
                if (!isHidden(object)) {
                    yield(block, object);
                    count++;
                }
            }

            return count;
        }

        @TruffleBoundary // for the iterator
        @Specialization(guards = "isRubyModule(ofClass)")
        protected int eachObject(DynamicObject ofClass, DynamicObject block,
                @Cached IsANode isANode) {
            int count = 0;

            for (Object object : ObjectGraph.stopAndGetAllObjects(this, getContext())) {
                if (!isHidden(object) && isANode.executeIsA(object, ofClass)) {
                    yield(block, object);
                    count++;
                }
            }

            return count;
        }

        private boolean isHidden(Object object) {
            if (object instanceof RubySymbol) {
                return false;
            } else {
                if (RubyGuards.isRubyDynamicObject(object)) {
                    return RubyGuards.isSingletonClass((DynamicObject) object);
                } else {
                    return true;
                }
            }
        }

    }

    private static class CallableFinalizer implements Runnable {

        private final RubyContext context;
        private final Object callable;

        public CallableFinalizer(RubyContext context, Object callable) {
            this.context = context;
            this.callable = callable;
        }

        public void run() {
            context.send(callable, "call");
        }

        @Override
        public String toString() {
            return callable.toString();
        }

    }

    @CoreMethod(names = "define_finalizer", isModuleFunction = true, required = 2)
    public abstract static class DefineFinalizerNode extends CoreMethodArrayArgumentsNode {

        // MRI would do a dynamic call to #respond_to? but it seems better to warn the user earlier.
        // Wanting #method_missing(:call) to be called for a finalizer seems highly unlikely.
        @Child private DoesRespondDispatchHeadNode respondToCallNode = DoesRespondDispatchHeadNode.create();

        @Child private ReadObjectFieldNode getFinaliserNode = ReadObjectFieldNode.create();
        @Child private WriteObjectFieldNode setFinalizerNode = WriteObjectFieldNode.create();

        @Specialization
        protected DynamicObject defineFinalizer(VirtualFrame frame, DynamicObject object, Object finalizer,
                @Cached BranchProfile errorProfile,
                @Cached WriteBarrierNode writeBarrierNode) {
            if (respondToCallNode.doesRespondTo(frame, "call", finalizer)) {
                if (getContext().getSharedObjects().isSharing()) {
                    // Share the finalizer, as it might run on a different Thread
                    writeBarrierNode.executeWriteBarrier(finalizer);
                }

                defineFinalizer(object, finalizer);
                return createArray(new Object[]{ 0, finalizer });
            } else {
                errorProfile.enter();
                throw new RaiseException(
                        getContext(),
                        coreExceptions().argumentErrorWrongArgumentType(finalizer, "callable", this));
            }
        }

        @TruffleBoundary
        private void defineFinalizer(DynamicObject object, Object finalizer) {
            final DynamicObject root = (finalizer instanceof DynamicObject) ? (DynamicObject) finalizer : null;
            final CallableFinalizer action = new CallableFinalizer(getContext(), finalizer);

            synchronized (object) {
                final FinalizerReference ref = (FinalizerReference) getFinaliserNode
                        .execute(object, Layouts.FINALIZER_REF_IDENTIFIER, null);
                if (ref == null) {
                    final FinalizerReference newRef = getContext()
                            .getFinalizationService()
                            .addFinalizer(object, ObjectSpaceManager.class, action, root);
                    setFinalizerNode.write(object, Layouts.FINALIZER_REF_IDENTIFIER, newRef);
                } else {
                    getContext()
                            .getFinalizationService()
                            .addAdditionalFinalizer(ref, object, ObjectSpaceManager.class, action, root);
                }
            }
        }

    }

    @CoreMethod(names = "undefine_finalizer", isModuleFunction = true, required = 1)
    public abstract static class UndefineFinalizerNode extends CoreMethodArrayArgumentsNode {

        @Child private ReadObjectFieldNode getFinaliserNode = ReadObjectFieldNode.create();
        @Child private WriteObjectFieldNode setFinalizerNode = WriteObjectFieldNode.create();

        @TruffleBoundary
        @Specialization
        protected Object undefineFinalizer(DynamicObject object) {
            synchronized (object) {
                FinalizerReference ref = (FinalizerReference) getFinaliserNode
                        .execute(object, Layouts.FINALIZER_REF_IDENTIFIER, null);
                if (ref != null) {
                    FinalizerReference newRef = getContext()
                            .getFinalizationService()
                            .removeFinalizers(object, ref, ObjectSpaceManager.class);
                    if (ref != newRef) {
                        setFinalizerNode.write(object, Layouts.FINALIZER_REF_IDENTIFIER, newRef);
                    }
                }
            }
            return object;
        }

    }

}
