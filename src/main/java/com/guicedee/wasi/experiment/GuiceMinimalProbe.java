package com.guicedee.wasi.experiment;

import com.google.inject.Guice;
import com.google.inject.Inject;

/** Isolate constructor injection without registering interceptors, custom scopes or providers. */
public final class GuiceMinimalProbe {
    public static class Dependency { public String value() { return "injected"; } }
    public static class Service {
        private final Dependency dependency;
        @Inject public Service(Dependency dependency) { this.dependency = dependency; }
        public String value() { return dependency.value(); }
    }
    public static String verify() {
        var injector = Guice.createInjector();
        if (!"injected".equals(injector.getInstance(Service.class).value())) {
            throw new IllegalStateException("Constructor injection failed");
        }
        return "WASI_GUICE_MINIMAL_OK constructor";
    }
    public static void main(String[] args) throws Throwable {
        try { System.out.println(verify()); }
        catch (Throwable failure) {
            System.err.println("GUICE_PROBE_FAILURE " + failure.getClass().getName());
            try { System.err.println(failure.getMessage()); }
            catch (Throwable rendering) { System.err.println("GUICE_MESSAGE_FAILURE " + rendering.getClass().getName()); }
            failure.printStackTrace();
            throw failure;
        }
    }
}
