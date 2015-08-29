package org.jruby.util.collections;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * A proxy cache that uses Java 7's ClassValue.
 */
public class Java7ClassValue<T> extends ClassValue<T> {

    public Java7ClassValue(ClassValueCalculator<T> calculator) {
        super(calculator);
    }

    public T get(Class cls) {
        // We don't check for null on the WeakReference since the
        // value is strongly referenced by proxy's list
        return proxy.get(cls).get();
    }

    // If we put any objects that reference an org.jruby.Ruby runtime
    // (like RubyClass instances) in here we run into a circular
    // reference situation that GC isn't handling where they will
    // always be strongly referenced and never garbage collected. We
    // break that by holding the computed values with a WeakReference.
    private final java.lang.ClassValue<WeakReference<T>> proxy = new java.lang.ClassValue<WeakReference<T>>() {
        // Strongly reference all computed values so they don't get
        // garbage collected until this Java7ClassValue instance goes
        // out of scope
        private final List<T> values = new ArrayList<T>();

        @Override
        protected WeakReference<T> computeValue(Class<?> type) {
            T value = calculator.computeValue(type);
            values.add(value);
            return new WeakReference(value);
        }
    };
}
