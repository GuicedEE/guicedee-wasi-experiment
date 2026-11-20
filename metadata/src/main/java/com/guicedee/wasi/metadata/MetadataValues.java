package com.guicedee.wasi.metadata;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

public final class MetadataValues {
    private MetadataValues() { }
    public static boolean isRecord(Class<?> type) { return ((Integer) unpack(type, WasiMetadata.classFacts(type)) & 1) != 0; }
    public static boolean isSealed(Class<?> type) { return ((Integer) unpack(type, WasiMetadata.classFacts(type)) & 2) != 0; }
    public static Class<?>[] permittedSubclasses(Class<?> type) { return (Class<?>[]) unpack(type, WasiMetadata.permittedSubclasses(type)); }
    public static Method enclosingMethod(Class<?> type) { return (Method) unpack(type, WasiMetadata.enclosingMethod(type)); }
    public static Method findMethod(Class<?> owner, String name, Type[] params) {
        Class<?>[] classes = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) classes[i] = (Class<?>) params[i];
        try { return owner.getDeclaredMethod(name, classes); }
        catch (NoSuchMethodException e) { throw new UnsupportedOperationException("Enclosing method was not registered", e); }
    }
    public static String signature(Executable executable) {
        StringBuilder result = new StringBuilder(executable.getName()).append('(');
        for (Class<?> parameter : executable.getParameterTypes()) result.append(parameter.getName()).append(';');
        return result.append(')').append(executable instanceof Method m ? m.getReturnType().getName() : "void").toString();
    }
    private static Object unpack(Class<?> owner, MetadataResult result) {
        if (result == null || !result.owner.equals(owner.getName())) return unavailable(owner.getName());
        return result.value;
    }
    public static Type genericSuperclass(Class<?> type) {
        if (type == Object.class || type.isPrimitive() || type.isInterface()) return null;
        if (type.isArray()) return Object.class;
        return (Type) unpack(type, WasiMetadata.genericSuperclass(type));
    }
    public static Type[] genericInterfaces(Class<?> type) {
        if (type.isPrimitive() || type == Object.class) return new Type[0];
        if (type.isArray()) return new Type[] { Cloneable.class, java.io.Serializable.class };
        return (Type[]) unpack(type, WasiMetadata.genericInterfaces(type));
    }
    public static AnnotatedType annotatedSuperclass(Class<?> type) {
        if (type == Object.class || type.isPrimitive() || type.isInterface() || type.isArray()) return null;
        return (AnnotatedType) unpack(type, WasiMetadata.annotatedSuperclass(type));
    }
    public static AnnotatedType fieldType(Field field) { return (AnnotatedType) unpack(field.getDeclaringClass(), WasiMetadata.annotatedField(field.getDeclaringClass(), field.getName())); }
    public static AnnotatedType[] parameterTypes(Executable method) {
        ParameterData[] data = (ParameterData[]) unpack(method.getDeclaringClass(), WasiMetadata.parameters(method.getDeclaringClass(), signature(method)));
        AnnotatedType[] types = new AnnotatedType[data.length];
        for (int i = 0; i < data.length; i++) types[i] = data[i].annotatedType;
        return types;
    }
    public static Object defaultValue(Method method) {
        if (!method.getDeclaringClass().isAnnotation()) return null;
        return unpack(method.getDeclaringClass(), WasiMetadata.annotationDefault(method.getDeclaringClass(), method.getName()));
    }
    public static Parameter[] parameters(Executable method) {
        ParameterData[] data = (ParameterData[]) unpack(method.getDeclaringClass(), WasiMetadata.parameters(method.getDeclaringClass(), signature(method)));
        Parameter[] parameters = new Parameter[data.length];
        for (int i = 0; i < data.length; i++) {
            parameters[i] = (Parameter) (Object) new org.teavm.classlib.java.lang.reflect.TParameter(method, i, data[i]);
        }
        return parameters;
    }
    public static <T> T unavailable(String target) { throw new UnsupportedOperationException("Metadata was not emitted for " + target); }
    public static Object copyArray(Object source) {
        int length = Array.getLength(source);
        Object copy = Array.newInstance(source.getClass().getComponentType(), length);
        System.arraycopy(source, 0, copy, 0, length);
        return copy;
    }
    private static Object invoke(Method method, Object target) {
        try { return method.invoke(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Annotation member access failed", e); }
    }
    private static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (!a.getClass().isArray()) return a.equals(b);
        if (a.getClass() != b.getClass() || Array.getLength(a) != Array.getLength(b)) return false;
        for (int i = 0; i < Array.getLength(a); i++) if (!valueEquals(Array.get(a, i), Array.get(b, i))) return false;
        return true;
    }
    private static int valueHash(Object value) {
        if (!value.getClass().isArray()) return value.hashCode();
        int result = 1;
        for (int i = 0; i < Array.getLength(value); i++) result = 31 * result + valueHash(Array.get(value, i));
        return result;
    }
    public static boolean annotationEquals(Annotation annotation, Object other) {
        if (!annotation.annotationType().isInstance(other)) return false;
        for (Method member : annotation.annotationType().getDeclaredMethods()) {
            if (!valueEquals(invoke(member, annotation), invoke(member, other))) return false;
        }
        return true;
    }
    public static int annotationHash(Annotation annotation) {
        int hash = 0;
        for (Method member : annotation.annotationType().getDeclaredMethods()) hash += (127 * member.getName().hashCode()) ^ valueHash(invoke(member, annotation));
        return hash;
    }
    public static String annotationString(Annotation annotation) {
        StringBuilder result = new StringBuilder("@").append(annotation.annotationType().getName()).append('(');
        boolean first = true;
        for (Method member : annotation.annotationType().getDeclaredMethods()) {
            if (!first) result.append(", ");
            first = false;
            Object value = invoke(member, annotation);
            result.append(member.getName()).append('=');
            if (value.getClass().isArray()) {
                result.append('{');
                for (int i = 0; i < Array.getLength(value); i++) {
                    if (i > 0) result.append(", ");
                    result.append(Array.get(value, i));
                }
                result.append('}');
            } else result.append(value);
        }
        return result.append(')').toString();
    }
    @SuppressWarnings("unchecked") public static <A extends Annotation> A[] annotationsByType(Annotation[] annotations, Class<A> type) {
        Objects.requireNonNull(type);
        List<A> values = new ArrayList<>();
        Repeatable repeated = type.getAnnotation(Repeatable.class);
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == type) values.add(type.cast(annotation));
            else if (repeated != null && annotation.annotationType() == repeated.value()) {
                try { Collections.addAll(values, (A[]) invoke(repeated.value().getDeclaredMethod("value"), annotation)); }
                catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
            }
        }
        return values.toArray((A[]) Array.newInstance(type, values.size()));
    }
    public static final class ParameterData {
        public final String name;
        public final int modifiers;
        public final boolean named;
        public final boolean varArgs;
        public final Type genericType;
        public final AnnotatedType annotatedType;
        public final Annotation[] annotations;
        public ParameterData(String name, int modifiers, boolean named, boolean varArgs, Type genericType,
                             AnnotatedType annotatedType, Annotation[] annotations) {
            this.name = name; this.modifiers = modifiers; this.named = named; this.varArgs = varArgs;
            this.genericType = genericType; this.annotatedType = annotatedType; this.annotations = annotations.clone();
        }
    }
}
