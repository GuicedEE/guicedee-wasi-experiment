package com.guicedee.wasi.metadata;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Objects;

/** Runtime values emitted from the input class files' generic signatures. */
public final class RuntimeTypes {
    private RuntimeTypes() { }
    public static Type ownerOrDeclaring(Type owner, Type raw) {
        return owner != null ? owner : ((Class<?>) raw).getDeclaringClass();
    }
    public static boolean typeEquals(Type type, Object other) {
        if (type == other) return true;
        if (type instanceof ParameterizedType p) return other instanceof ParameterizedType q
                && Objects.equals(p.getOwnerType(), q.getOwnerType()) && p.getRawType().equals(q.getRawType())
                && Arrays.equals(p.getActualTypeArguments(), q.getActualTypeArguments());
        if (type instanceof GenericArrayType a) return other instanceof GenericArrayType b && a.getGenericComponentType().equals(b.getGenericComponentType());
        if (type instanceof WildcardType w) return other instanceof WildcardType v
                && Arrays.equals(w.getUpperBounds(), v.getUpperBounds()) && Arrays.equals(w.getLowerBounds(), v.getLowerBounds());
        if (type instanceof TypeVariable<?> v) return other instanceof TypeVariable<?> w
                && v.getName().equals(w.getName()) && v.getGenericDeclaration().equals(w.getGenericDeclaration());
        return false;
    }
    public static int typeHash(Type type) {
        if (type instanceof ParameterizedType p) return Objects.hashCode(p.getOwnerType()) ^ p.getRawType().hashCode() ^ Arrays.hashCode(p.getActualTypeArguments());
        if (type instanceof GenericArrayType a) return a.getGenericComponentType().hashCode();
        if (type instanceof WildcardType w) return Arrays.hashCode(w.getUpperBounds()) ^ Arrays.hashCode(w.getLowerBounds());
        if (type instanceof TypeVariable<?> v) return v.getName().hashCode() ^ v.getGenericDeclaration().hashCode();
        throw new IllegalArgumentException("Unsupported type implementation");
    }
    public static final class Parameterized implements ParameterizedType {
        private final Type owner;
        private final Class<?> raw;
        private final Type[] arguments;
        public Parameterized(Type owner, Class<?> raw, Type[] arguments) {
            this.owner = owner; this.raw = raw; this.arguments = arguments.clone();
        }
        @Override public Type getOwnerType() { return owner; }
        @Override public Type getRawType() { return raw; }
        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof ParameterizedType p && Objects.equals(owner, p.getOwnerType())
                    && raw.equals(p.getRawType()) && Arrays.equals(arguments, p.getActualTypeArguments());
        }
        @Override public int hashCode() { return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode(); }
        @Override public String getTypeName() { return toString(); }
        @Override public String toString() {
            StringBuilder text = new StringBuilder(owner == null ? raw.getName() : owner.getTypeName() + "$" + raw.getSimpleName());
            if (arguments.length > 0) {
                text.append('<');
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) text.append(", ");
                    text.append(arguments[i].getTypeName());
                }
                text.append('>');
            }
            return text.toString();
        }
    }
    public static final class GenericArray implements GenericArrayType {
        private final Type component;
        public GenericArray(Type component) { this.component = component; }
        @Override public Type getGenericComponentType() { return component; }
        @Override public boolean equals(Object other) { return other instanceof GenericArrayType a && component.equals(a.getGenericComponentType()); }
        @Override public int hashCode() { return component.hashCode(); }
        @Override public String getTypeName() { return component.getTypeName() + "[]"; }
        @Override public String toString() { return getTypeName(); }
    }
    public static final class Wildcard implements WildcardType {
        private final Type[] upper;
        private final Type[] lower;
        public Wildcard(Type[] upper, Type[] lower) { this.upper = upper.clone(); this.lower = lower.clone(); }
        @Override public Type[] getUpperBounds() { return upper.clone(); }
        @Override public Type[] getLowerBounds() { return lower.clone(); }
        @Override public boolean equals(Object other) { return other instanceof WildcardType w && Arrays.equals(upper, w.getUpperBounds()) && Arrays.equals(lower, w.getLowerBounds()); }
        @Override public int hashCode() { return Arrays.hashCode(upper) ^ Arrays.hashCode(lower); }
        @Override public String getTypeName() {
            if (lower.length > 0) return "? super " + lower[0].getTypeName();
            return upper.length == 0 || upper[0] == Object.class ? "?" : "? extends " + upper[0].getTypeName();
        }
        @Override public String toString() { return getTypeName(); }
    }
    public static TypeVariable<?> variable(Class<?> owner, String name) {
        for (TypeVariable<?> variable : owner.getTypeParameters()) if (variable.getName().equals(name)) return variable;
        throw new IllegalStateException("Missing type variable " + owner.getName() + ":" + name);
    }
    public static TypeVariable<?> methodVariable(Class<?> owner, String signature, String name) {
        for (Method method : owner.getDeclaredMethods()) {
            if (MetadataValues.signature(method).equals(signature)) {
                for (TypeVariable<?> variable : method.getTypeParameters()) if (variable.getName().equals(name)) return variable;
            }
        }
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            if (MetadataValues.signature(constructor).equals(signature)) {
                for (TypeVariable<?> variable : constructor.getTypeParameters()) if (variable.getName().equals(name)) return variable;
            }
        }
        throw new IllegalStateException("Missing method variable " + signature + ":" + name);
    }
}
