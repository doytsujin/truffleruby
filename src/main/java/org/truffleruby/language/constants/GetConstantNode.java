/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.constants;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.module.ModuleFields;
import org.truffleruby.core.module.ModuleOperations;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.RubyContextNode;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.loader.FeatureLoader;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

public abstract class GetConstantNode extends RubyContextNode {

    private final boolean callConstMissing;

    @Child private CallDispatchHeadNode constMissingNode;

    public static GetConstantNode create() {
        return create(true);
    }

    public static GetConstantNode create(boolean callConstMissing) {
        return GetConstantNodeGen.create(callConstMissing);
    }

    public Object lookupAndResolveConstant(LexicalScope lexicalScope, DynamicObject module, String name,
            LookupConstantInterface lookupConstantNode) {
        final RubyConstant constant = lookupConstantNode.lookupConstant(lexicalScope, module, name);
        return executeGetConstant(lexicalScope, module, name, constant, lookupConstantNode);
    }

    // name is always the same as constant.getName(), but is needed as constant can be null.
    protected abstract Object executeGetConstant(LexicalScope lexicalScope, DynamicObject module, String name,
            Object constant, LookupConstantInterface lookupConstantNode);

    public GetConstantNode(boolean callConstMissing) {
        this.callConstMissing = callConstMissing;
    }

    @Specialization(guards = { "constant != null", "constant.hasValue()" })
    protected Object getConstant(
            LexicalScope lexicalScope,
            DynamicObject module,
            String name,
            RubyConstant constant,
            LookupConstantInterface lookupConstantNode) {
        return constant.getValue();
    }

    @TruffleBoundary
    @Specialization(guards = { "autoloadConstant != null", "autoloadConstant.isAutoload()" })
    protected Object autoloadConstant(
            LexicalScope lexicalScope,
            DynamicObject module,
            String name,
            RubyConstant autoloadConstant,
            LookupConstantInterface lookupConstantNode,
            @Cached("createPrivate()") CallDispatchHeadNode callRequireNode) {

        final DynamicObject feature = autoloadConstant.getAutoloadConstant().getFeature();

        if (autoloadConstant.getAutoloadConstant().isAutoloadingThread()) {
            // Pretend the constant does not exist while it is autoloading
            return doMissingConstant(module, name, getSymbol(name));
        }

        final FeatureLoader featureLoader = getContext().getFeatureLoader();
        final String expandedPath = featureLoader
                .findFeature(autoloadConstant.getAutoloadConstant().getAutoloadPath());
        if (expandedPath != null && featureLoader.getFileLocks().isCurrentThreadHoldingLock(expandedPath)) {
            // We found an autoload constant while we are already require-ing the autoload file,
            // consider it missing to avoid circular require warnings and calling #require twice.
            // For instance, autoload :RbConfig, "rbconfig"; require "rbconfig" causes this.
            // Also see https://github.com/oracle/truffleruby/pull/1779 and GR-14590
            if (getContext().getOptions().LOG_AUTOLOAD) {
                RubyLanguage.LOGGER.info(() -> String.format(
                        "%s: %s::%s is being treated as missing while loading %s",
                        RubyContext.fileLine(getContext().getCallStack().getTopMostUserSourceSection()),
                        Layouts.MODULE.getFields(module).getName(),
                        name,
                        expandedPath));
            }
            return doMissingConstant(module, name, getSymbol(name));
        }

        if (getContext().getOptions().LOG_AUTOLOAD) {
            RubyLanguage.LOGGER.info(() -> String.format(
                    "%s: autoloading %s with %s",
                    RubyContext.fileLine(getContext().getCallStack().getTopMostUserSourceSection()),
                    autoloadConstant,
                    autoloadConstant.getAutoloadConstant().getAutoloadPath()));
        }

        // Mark the autoload constant as loading already here and not in RequireNode so that recursive lookups act as "being loaded"
        autoloadConstantStart(autoloadConstant);
        try {
            callRequireNode.call(coreLibrary().mainObject, "require", feature);

            // This needs to run while the autoload is marked as isAutoloading(), to avoid infinite recursion
            return autoloadResolveConstant(lexicalScope, module, name, autoloadConstant, lookupConstantNode);
        } finally {
            autoloadConstantStop(autoloadConstant);
        }
    }

    @TruffleBoundary
    public static void autoloadConstantStart(RubyConstant autoloadConstant) {
        autoloadConstant.getAutoloadConstant().startAutoLoad();

        // We need to notify cached lookup that we are autoloading the constant, as constant
        // lookup changes based on whether an autoload constant is loading or not (constant
        // lookup ignores being-autoloaded constants).
        Layouts.MODULE.getFields(autoloadConstant.getDeclaringModule()).newConstantsVersion();
    }

    @TruffleBoundary
    public static void autoloadConstantStop(RubyConstant autoloadConstant) {
        autoloadConstant.getAutoloadConstant().stopAutoLoad();
    }

    /** Subset of {@link #autoloadResolveConstant} which does not try to resolve the constant. */
    @TruffleBoundary
    public static void autoloadUndefineConstantIfStillAutoload(RubyConstant autoloadConstant) {
        final DynamicObject autoloadConstantModule = autoloadConstant.getDeclaringModule();
        final ModuleFields fields = Layouts.MODULE.getFields(autoloadConstantModule);
        fields.undefineConstantIfStillAutoload(autoloadConstant);
    }

    @TruffleBoundary
    public Object autoloadResolveConstant(LexicalScope lexicalScope, DynamicObject module, String name,
            RubyConstant autoloadConstant, LookupConstantInterface lookupConstantNode) {
        final DynamicObject autoloadConstantModule = autoloadConstant.getDeclaringModule();
        final ModuleFields fields = Layouts.MODULE.getFields(autoloadConstantModule);

        RubyConstant resolvedConstant = lookupConstantNode.lookupConstant(lexicalScope, module, name);

        // check if the constant was set in the ancestors of autoloadConstantModule
        if (resolvedConstant != null &&
                (ModuleOperations.inAncestorsOf(resolvedConstant.getDeclaringModule(), autoloadConstantModule) ||
                        resolvedConstant.getDeclaringModule() == coreLibrary().objectClass)) {
            // all is good, just return that constant
        } else {
            // If the autoload constant was not set in the ancestors, undefine the constant
            fields.undefineConstantIfStillAutoload(autoloadConstant);

            // redo lookup, to consider the undefined constant
            resolvedConstant = lookupConstantNode.lookupConstant(lexicalScope, module, name);
        }

        return executeGetConstant(lexicalScope, module, name, resolvedConstant, lookupConstantNode);
    }

    @Specialization(
            guards = { "isNullOrUndefined(constant)", "guardName(name, cachedName, sameNameProfile)" },
            limit = "getCacheLimit()")
    protected Object missingConstantCached(
            LexicalScope lexicalScope,
            DynamicObject module,
            String name,
            Object constant,
            LookupConstantInterface lookupConstantNode,
            @Cached("name") String cachedName,
            @Cached("getSymbol(name)") RubySymbol symbolName,
            @Cached ConditionProfile sameNameProfile) {
        return doMissingConstant(module, name, symbolName);
    }

    @Specialization(guards = "isNullOrUndefined(constant)")
    protected Object missingConstantUncached(
            LexicalScope lexicalScope,
            DynamicObject module,
            String name,
            Object constant,
            LookupConstantInterface lookupConstantNode) {
        return doMissingConstant(module, name, getSymbol(name));
    }

    private Object doMissingConstant(DynamicObject module, String name, RubySymbol symbolName) {
        if (callConstMissing) {
            if (constMissingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                constMissingNode = insert(CallDispatchHeadNode.createPrivate());
            }

            return constMissingNode.call(module, "const_missing", symbolName);
        } else {
            return null;
        }
    }

    protected boolean isNullOrUndefined(Object constant) {
        return constant == null || ((RubyConstant) constant).isUndefined();
    }

    protected boolean guardName(String name, String cachedName, ConditionProfile sameNameProfile) {
        // This is likely as for literal constant lookup the name does not change and Symbols
        // always return the same String.
        if (sameNameProfile.profile(name == cachedName)) {
            return true;
        } else {
            return name.equals(cachedName);
        }
    }

    protected int getCacheLimit() {
        return getContext().getOptions().CONSTANT_CACHE;
    }

}
