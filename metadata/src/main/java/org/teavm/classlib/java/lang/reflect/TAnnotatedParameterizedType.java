package org.teavm.classlib.java.lang.reflect;
import java.lang.reflect.AnnotatedType;
public interface TAnnotatedParameterizedType extends AnnotatedType {
    AnnotatedType[] getAnnotatedActualTypeArguments();
}
