package com.guicedee.wasi.compiler;

import java.util.ArrayList;
import java.util.Collection;
import org.teavm.extension.introspect.*;
import org.teavm.extension.spi.reflection.ReflectionPolicy;

/** Explicit closed set of application members retained for runtime reflection. */
public final class ProbeReflectionPolicy implements ReflectionPolicy {
    private static org.teavm.parsing.resource.ResourceProvider inputs;
    private static boolean typedProfile;
    static void initialize(org.teavm.parsing.resource.ResourceProvider resources) {
        inputs = resources;
        typedProfile = Boolean.getBoolean("guicedee.wasi.typed");
    }
    private static boolean dto(String name) {
        return name.equals("com.guicedee.wasi.experiment.JacksonProbe$Item")
            || name.equals("com.guicedee.wasi.experiment.JacksonProbe$Detail")
            || name.equals("com.guicedee.wasi.experiment.TypedHttpProbe$BrokenBody");
    }
    private static boolean injectable(String name) {
        return name.equals("com.guicedee.wasi.experiment.TypedHttpProbe$Clients")
            || name.equals("com.guicedee.wasi.experiment.TypedHttpProbe$Application")
            || name.equals("com.guicedee.wasi.experiment.TypedHttpProbe$Bindings")
            || name.equals("com.guicedee.wasi.experiment.WasiHttpTransport");
    }
    private static boolean applicationInput(IntrospectClass<?> cls) {
        if (cls == null || !cls.name().startsWith("com.guicedee.wasi.experiment.")) return false;
        if (inputs == null) throw new IllegalStateException("Reflection inputs not initialized");
        // Generated annotation implementations have native-data fields, not Java
        // reflection types. Retain members only for actual application class files.
        return inputs.getResource(cls.name().replace('.', '/') + ".class") != null;
    }
    @Override public Collection<IntrospectMember> classAccessibleMembers(IntrospectClass<?> cls) {
        var result = new ArrayList<IntrospectMember>();
        if (applicationInput(cls)) {
            if (!typedProfile || dto(cls.name())) {
                result.addAll(cls.declaredMethods());
                result.addAll(cls.declaredFields());
            } else if (injectable(cls.name())) {
                // Register injection constructors and provider methods, not probe
                // runners/main methods that recursively bootstrap the whole graph.
                for (var method : cls.declaredMethods()) {
                    if (method.isConstructor() || (cls.name().endsWith("$Bindings")
                        && java.util.Set.of("get", "post", "list").contains(method.name()))) result.add(method);
                }
            }
        }
        if (cls != null && (cls.name().equals("com.google.common.util.concurrent.AbstractFutureState")
                || cls.name().equals("com.google.common.util.concurrent.AbstractFutureState$Waiter"))) {
            // Guava's fallback creates real AtomicReferenceFieldUpdaters for these fields.
            result.addAll(cls.declaredFields());
        }
        if (cls != null) {
            if (cls.name().equals("com.google.inject.internal.Annotations")) {
                result.add(cls.declaredJavaMethod("determineAnnotationToStringConfig"));
            }
            String parser = switch (cls.name()) {
                case "java.lang.Integer" -> "parseInt";
                case "java.lang.Long" -> "parseLong";
                case "java.lang.Boolean" -> "parseBoolean";
                case "java.lang.Byte" -> "parseByte";
                case "java.lang.Short" -> "parseShort";
                case "java.lang.Float" -> "parseFloat";
                case "java.lang.Double" -> "parseDouble";
                default -> null;
            };
            if (parser != null) {
                // Guice installs these standard converters by reflective method lookup.
                var method = cls.declaredJavaMethod(parser, String.class);
                if (method == null) throw new IllegalStateException("Missing primitive parser: " + cls.name());
                result.add(method);
            }
        }
        return result;
    }
    @Override public boolean isClassFoundByName(IntrospectClass<?> cls) {
        return applicationInput(cls) && (!typedProfile || dto(cls.name()) || injectable(cls.name()));
    }
}
