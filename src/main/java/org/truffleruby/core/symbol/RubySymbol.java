/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.symbol;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import org.truffleruby.RubyLanguage;
import org.truffleruby.cext.ValueWrapper;
import org.truffleruby.core.Hashing;
import org.truffleruby.core.rope.Rope;

@ExportLibrary(InteropLibrary.class)
public class RubySymbol implements TruffleObject {

    private static final int CLASS_SALT = 92021474; // random number, stops hashes for similar values but different classes being the same, static because we want deterministic hashes

    private final String string;
    private final Rope rope;
    private final int javaStringHashCode;
    private long objectId;
    private ValueWrapper valueWrapper;

    public RubySymbol(String string, Rope rope, int javaStringHashCode) {
        this.string = string;
        this.rope = rope;
        this.javaStringHashCode = javaStringHashCode;
        this.valueWrapper = null;
    }

    public String getString() {
        return string;
    }

    public Rope getRope() {
        return rope;
    }

    public long getObjectId() {
        return objectId;
    }

    public void setObjectId(long objectId) {
        this.objectId = objectId;
    }

    public ValueWrapper getValueWrapper() {
        return valueWrapper;
    }

    public void setValueWrapper(ValueWrapper valueWrapper) {
        this.valueWrapper = valueWrapper;
    }

    public long computeHashCode(Hashing hashing) {
        return hashing.hash(CLASS_SALT, javaStringHashCode);
    }

    @ExportMessage
    protected boolean isString() {
        return true;
    }

    @ExportMessage
    public static class AsString {

        @Specialization(guards = "symbol == cachedSymbol", limit = "getLimit()")
        protected static String asString(RubySymbol symbol,
                @Cached("symbol") RubySymbol cachedSymbol,
                @Cached("uncachedAsString(cachedSymbol)") String cachedString) {
            return cachedString;
        }

        @Specialization(replaces = "asString")
        protected static String uncachedAsString(RubySymbol symbol) {
            return symbol.getString();
        }

        protected static int getLimit() {
            return RubyLanguage.getCurrentContext().getOptions().INTEROP_CONVERT_CACHE;
        }
    }

    @Override
    public String toString() {
        // return ":" + string; // TODO refactor https://github.com/oracle/truffleruby/blob/aa9d75a31be7fa58b1a4c0ed5173670229f35082/src/main/java/org/truffleruby/core/basicobject/BasicObjectNodes.java#L437
        return string;
    }

}
