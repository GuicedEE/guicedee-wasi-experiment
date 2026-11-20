package org.teavm.classlib.java.util.concurrent;

/** Exception contract used by Guava's executor rejection handlers. */
public class TRejectedExecutionException extends RuntimeException {
    public TRejectedExecutionException() { }
    public TRejectedExecutionException(String message) { super(message); }
    public TRejectedExecutionException(String message, Throwable cause) { super(message, cause); }
    public TRejectedExecutionException(Throwable cause) { super(cause); }
}
