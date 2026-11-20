package org.teavm.classlib.java.lang.reflect;

import com.guicedee.wasi.metadata.MetadataValues;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Objects;

public final class TParameter implements AnnotatedElement {
    private final Executable executable;
    private final int index;
    private final MetadataValues.ParameterData data;
    public TParameter(Executable executable, int index, MetadataValues.ParameterData data) {
        this.executable = executable; this.index = index; this.data = data;
    }
    public Executable getDeclaringExecutable() { return executable; }
    public String getName() { return data.name; }
    public boolean isNamePresent() { return data.named; }
    public int getModifiers() { return data.modifiers; }
    public boolean isImplicit() { return (data.modifiers & 0x8000) != 0; }
    public boolean isSynthetic() { return (data.modifiers & 0x1000) != 0; }
    public boolean isVarArgs() { return data.varArgs; }
    public Class<?> getType() { return executable.getParameterTypes()[index]; }
    public Type getParameterizedType() { return data.genericType; }
    public AnnotatedType getAnnotatedType() { return data.annotatedType; }
    @Override public <A extends Annotation> A getAnnotation(Class<A> type) {
        Objects.requireNonNull(type);
        for (Annotation annotation : data.annotations) if (annotation.annotationType() == type) return type.cast(annotation);
        return null;
    }
    @Override public Annotation[] getAnnotations() { return data.annotations.clone(); }
    @Override public Annotation[] getDeclaredAnnotations() { return getAnnotations(); }
    @Override public <A extends Annotation> A[] getAnnotationsByType(Class<A> type) { return MetadataValues.annotationsByType(data.annotations, type); }
    @Override public <A extends Annotation> A[] getDeclaredAnnotationsByType(Class<A> type) { return getAnnotationsByType(type); }
    @Override public boolean equals(Object other) { return other instanceof TParameter p && index == p.index && executable.equals(p.executable); }
    @Override public int hashCode() { return executable.hashCode() ^ index; }
    @Override public String toString() { return getParameterizedType().getTypeName() + " " + data.name; }
}
