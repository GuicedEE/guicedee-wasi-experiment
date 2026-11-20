package com.guicedee.wasi.metadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Objects;

public final class AnnotatedValues {
    public static class Plain implements AnnotatedType {
        private final Type type;
        private final Annotation[] annotations;
        private final AnnotatedType owner;
        public Plain(Type type, Annotation[] annotations, AnnotatedType owner) {
            this.type = type; this.annotations = annotations.clone(); this.owner = owner;
        }
        @Override public Type getType() { return type; }
        @Override public AnnotatedType getAnnotatedOwnerType() { return owner; }
        @Override public <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
            Objects.requireNonNull(annotationClass);
            for (Annotation a : annotations) if (a.annotationType() == annotationClass) return annotationClass.cast(a);
            return null;
        }
        @Override public Annotation[] getAnnotations() { return annotations.clone(); }
        @Override public Annotation[] getDeclaredAnnotations() { return annotations.clone(); }
        @Override public <A extends Annotation> A[] getAnnotationsByType(Class<A> type) { return MetadataValues.annotationsByType(annotations, type); }
        @Override public <A extends Annotation> A[] getDeclaredAnnotationsByType(Class<A> type) { return getAnnotationsByType(type); }
    }
    public static final class Parameterized extends Plain implements AnnotatedParameterizedType {
        private final AnnotatedType[] arguments;
        public Parameterized(Type type, Annotation[] annotations, AnnotatedType owner, AnnotatedType[] arguments) {
            super(type, annotations, owner); this.arguments = arguments.clone();
        }
        @Override public AnnotatedType[] getAnnotatedActualTypeArguments() { return arguments.clone(); }
    }
    public static final class Array extends Plain implements AnnotatedArrayType {
        private final AnnotatedType component;
        public Array(Type type, Annotation[] annotations, AnnotatedType component) { super(type, annotations, null); this.component = component; }
        @Override public AnnotatedType getAnnotatedGenericComponentType() { return component; }
    }
    public static final class Wildcard extends Plain implements AnnotatedWildcardType {
        private final AnnotatedType[] upper;
        private final AnnotatedType[] lower;
        public Wildcard(Type type, Annotation[] annotations, AnnotatedType[] upper, AnnotatedType[] lower) {
            super(type, annotations, null); this.upper = upper.clone(); this.lower = lower.clone();
        }
        @Override public AnnotatedType[] getAnnotatedUpperBounds() { return upper.clone(); }
        @Override public AnnotatedType[] getAnnotatedLowerBounds() { return lower.clone(); }
    }
    public static final class Variable extends Plain implements AnnotatedTypeVariable {
        private AnnotatedType[] bounds;
        public Variable(Type type, Annotation[] annotations) { super(type, annotations, null); }
        public void initializeBounds(AnnotatedType[] bounds) {
            if (this.bounds != null) throw new IllegalStateException("Bounds already initialized");
            this.bounds = bounds.clone();
        }
        @Override public AnnotatedType[] getAnnotatedBounds() { return bounds.clone(); }
    }
}
