package com.guicedee.wasi.compiler;

import java.lang.reflect.InvocationTargetException;
import org.teavm.runtime.reflect.MethodInfo;

/** Wrap only the target call, after the JDK reflection argument/access checks. */
public final class ReflectionInvocation {
    private ReflectionInvocation() { }
    public static Object call(MethodInfo method, Object receiver, Object[] arguments) throws InvocationTargetException {
        try { return method.call(receiver, arguments); }
        catch (Throwable target) { throw new InvocationTargetException(target); }
    }
}
