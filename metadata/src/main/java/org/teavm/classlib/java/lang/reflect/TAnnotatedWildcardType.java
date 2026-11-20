package org.teavm.classlib.java.lang.reflect;
import java.lang.reflect.AnnotatedType;
public interface TAnnotatedWildcardType extends AnnotatedType {
    AnnotatedType[] getAnnotatedLowerBounds();
    AnnotatedType[] getAnnotatedUpperBounds();
}
