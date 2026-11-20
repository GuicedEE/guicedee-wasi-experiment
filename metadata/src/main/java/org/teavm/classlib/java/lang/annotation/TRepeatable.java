package org.teavm.classlib.java.lang.annotation;
import java.lang.annotation.*;
@Documented @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.ANNOTATION_TYPE)
public @interface TRepeatable { Class<? extends Annotation> value(); }
