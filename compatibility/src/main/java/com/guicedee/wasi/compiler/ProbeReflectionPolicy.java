package com.guicedee.wasi.compiler;

import java.util.ArrayList;
import java.util.Collection;
import org.teavm.extension.introspect.*;
import org.teavm.extension.spi.reflection.ReflectionPolicy;

/** Explicit closed set of application members retained for runtime reflection. */
public final class ProbeReflectionPolicy implements ReflectionPolicy {
    @Override public Collection<IntrospectMember> classAccessibleMembers(IntrospectClass<?> cls) {
        var result = new ArrayList<IntrospectMember>();
        if (cls != null && cls.name().startsWith("com.guicedee.wasi.experiment.")) {
            result.addAll(cls.declaredMethods());
            result.addAll(cls.declaredFields());
        }
        return result;
    }
    @Override public boolean isClassFoundByName(IntrospectClass<?> cls) {
        return cls != null && cls.name().startsWith("com.guicedee.wasi.experiment.");
    }
}
