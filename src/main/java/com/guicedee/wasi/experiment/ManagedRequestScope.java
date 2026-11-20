package com.guicedee.wasi.experiment;

import com.google.inject.Key;
import com.google.inject.OutOfScopeException;
import com.google.inject.Provider;
import com.google.inject.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Experimental Guice scope for one logical Java thread, not Vert.x CallScoper.
 * Successful scoped AutoCloseables are owned by the request; Guice singletons are not.
 * The module disables circular proxies. Context transfer to another thread is not supported. */
public final class ManagedRequestScope implements Scope {
    private final ThreadLocal<Request> current = new ThreadLocal<>();

    public Request enter() {
        if (current.get() != null) throw new IllegalStateException("Request already active or closing");
        Request request = new Request(Thread.currentThread());
        current.set(request);
        return request;
    }

    public boolean isActive() {
        Request request = current.get();
        return request != null && !request.closed;
    }

    @Override public <T> Provider<T> scope(Key<T> key, Provider<T> unscoped) {
        return () -> {
            Request request = current.get();
            if (request == null || request.closed) throw new OutOfScopeException("No active request");
            if (request.objects.containsKey(key)) {
                @SuppressWarnings("unchecked") T value = (T) request.objects.get(key);
                return value;
            }
            request.provisioning++;
            try {
                T value = unscoped.get(); // A failed provision is never cached or registered for disposal.
                request.objects.put(key, value);
                if (value instanceof AutoCloseable resource) request.own(resource);
                return value;
            } finally { request.provisioning--; }
        };
    }

    public static final class CleanupException extends RuntimeException {
        CleanupException(Throwable cause) { super("Request cleanup failed", cause); }
    }

    public final class Request implements AutoCloseable {
        private final Thread owner;
        private final Map<Key<?>, Object> objects = new HashMap<>();
        private final ArrayList<AutoCloseable> resources = new ArrayList<>();
        private boolean closed;
        private int provisioning;

        private Request(Thread owner) { this.owner = owner; }
        private void own(AutoCloseable resource) {
            for (AutoCloseable existing : resources) if (existing == resource) return;
            resources.add(resource);
        }

        /** Diagnostic count verifies that even a retained request handle releases its objects. */
        public int retainedReferences() { return objects.size() + resources.size(); }

        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Request closed by another thread");
            if (closed) return;
            if (current.get() != this || provisioning != 0) throw new IllegalStateException("Request still provisioning or not current");
            closed = true; // Reject injection and nested entry during disposal.
            objects.clear();
            CleanupException failure = null;
            try {
                for (int i = resources.size() - 1; i >= 0; i--) {
                    try { resources.get(i).close(); }
                    catch (Throwable error) {
                        if (failure == null) failure = new CleanupException(error);
                        else failure.addSuppressed(error);
                    }
                }
            } finally {
                resources.clear();
                current.remove();
            }
            if (failure != null) throw failure;
        }
    }
}
