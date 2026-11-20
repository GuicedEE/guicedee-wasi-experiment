package com.guicedee.wasi.experiment;

import com.google.inject.Inject;
import java.lang.reflect.ParameterizedType;
import java.util.List;

/** Verify real reflective construction/invocation and generic metadata on the C/WASI route. */
public final class ReflectionProbe {
    public static class ThrowingConstructor {
        public ThrowingConstructor() { throw new IllegalStateException("constructor failure"); }
    }
    public static class Service {
        public List<String> values;
        private final String text;
        @Inject public Service(String text) { this.text = text; }
        public String value() { return text; }
    }
    public static String verify() throws ReflectiveOperationException {
        var constructor = Service.class.getDeclaredConstructor(String.class);
        if (!constructor.isAnnotationPresent(Inject.class)) throw new IllegalStateException("Missing constructor annotation");
        var object = constructor.newInstance("reflected");
        if (!"reflected".equals(Service.class.getMethod("value").invoke(object))) {
            throw new IllegalStateException("Reflective invocation failed");
        }
        var type = Service.class.getField("values").getGenericType();
        if (!(type instanceof ParameterizedType generic)
                || generic.getRawType() != List.class || generic.getActualTypeArguments()[0] != String.class) {
            throw new IllegalStateException("Generic field metadata lost");
        }
        Class<?>[] wrappers = {Integer.class, Long.class, Boolean.class, Byte.class, Short.class, Float.class, Double.class};
        String[] parsers = {"parseInt", "parseLong", "parseBoolean", "parseByte", "parseShort", "parseFloat", "parseDouble"};
        Object[] expected = {42, 42L, true, (byte) 42, (short) 42, 42.0f, 42.0d};
        for (int i = 0; i < wrappers.length; i++) {
            Object value = wrappers[i].getMethod(parsers[i], String.class).invoke(null, i == 2 ? "true" : "42");
            if (!expected[i].equals(value)) throw new IllegalStateException("Reflective primitive parser: " + parsers[i]);
        }
        try {
            Integer.class.getMethod("parseInt", String.class).invoke(null, "invalid");
            throw new IllegalStateException("Reflective parser swallowed its failure");
        } catch (java.lang.reflect.InvocationTargetException expectedFailure) {
            if (!(expectedFailure.getCause() instanceof NumberFormatException)) throw expectedFailure;
        }
        try {
            Integer.class.getMethod("parseInt", String.class).invoke(null);
            throw new IllegalStateException("Invalid argument count was accepted");
        } catch (IllegalArgumentException expectedFailure) { }
        try {
            ThrowingConstructor.class.getConstructor().newInstance();
            throw new IllegalStateException("Constructor failure was lost");
        } catch (java.lang.reflect.InvocationTargetException expectedFailure) {
            if (!(expectedFailure.getCause() instanceof IllegalStateException)
                    || !"constructor failure".equals(expectedFailure.getCause().getMessage())) throw expectedFailure;
        }
        return "WASI_REFLECTION_OK constructor annotation generic-field method";
    }
    public static void main(String[] args) throws ReflectiveOperationException { System.out.println(verify()); }
}
