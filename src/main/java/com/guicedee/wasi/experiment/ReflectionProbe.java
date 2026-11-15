package com.guicedee.wasi.experiment;

import com.google.inject.Inject;
import java.lang.reflect.ParameterizedType;
import java.util.List;

/** Verify real reflective construction/invocation and generic metadata on the C/WASI route. */
public final class ReflectionProbe {
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
        return "WASI_REFLECTION_OK constructor annotation generic-field method";
    }
    public static void main(String[] args) throws ReflectiveOperationException { System.out.println(verify()); }
}
