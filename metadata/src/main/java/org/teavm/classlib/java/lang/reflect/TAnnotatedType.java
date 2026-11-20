package org.teavm.classlib.java.lang.reflect;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Type;
public interface TAnnotatedType extends AnnotatedElement {
    Type getType();
    default AnnotatedType getAnnotatedOwnerType() { return null; }
}
