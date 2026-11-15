package com.guicedee.wasi.experiment;

import com.google.inject.*;
import com.google.inject.matcher.Matchers;
import java.util.HashMap;
import java.util.Map;

/** Runs the actual GuicedEE Guice artifact. No replacement container or manual service wiring. */
public final class GuiceProbe {
    public static class Dependency {
        public String value() { return "injected"; }
    }

    public static class Service {
        private final Dependency dependency;
        @Inject public Service(Dependency dependency) { this.dependency = dependency; }
        public String value() { return dependency.value(); }
    }

    public static class RequestObject { }

    /** Small explicit scope tests the Guice Scope contract, not GuicedEE's Vert.x CallScoper. */
    private static final class RequestScope implements Scope {
        private Map<Key<?>, Object> objects;
        void enter() { objects = new HashMap<>(); }
        void exit() { objects = null; }
        @Override public <T> Provider<T> scope(Key<T> key, Provider<T> unscoped) {
            return () -> {
                if (objects == null) throw new OutOfScopeException("No active request");
                @SuppressWarnings("unchecked") T value = (T) objects.computeIfAbsent(key, ignored -> unscoped.get());
                return value;
            };
        }
    }

    public static String verify() {
        RequestScope scope = new RequestScope();
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Dependency.class).in(Scopes.SINGLETON);
                bind(Service.class);
                bind(RequestObject.class).in(scope);
                bindInterceptor(Matchers.subclassesOf(Service.class), new com.google.inject.matcher.AbstractMatcher<java.lang.reflect.Method>() {
                    @Override public boolean matches(java.lang.reflect.Method method) { return method.getName().equals("value"); }
                },
                        invocation -> "intercepted:" + invocation.proceed());
            }
        });
        require(injector.getInstance(Dependency.class) == injector.getInstance(Dependency.class), "singleton");
        Provider<Service> provider = injector.getProvider(Service.class);
        require("intercepted:injected".equals(provider.get().value()), "constructor/provider/interceptor");
        RequestObject previous;
        scope.enter();
        try {
            previous = injector.getInstance(RequestObject.class);
            require(previous == injector.getInstance(RequestObject.class), "request reuse");
        } finally { scope.exit(); }
        scope.enter();
        try {
            require(previous != injector.getInstance(RequestObject.class), "request isolation");
        } finally { scope.exit(); }
        return "WASI_GUICE_OK constructor provider singleton scope interceptor";
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Failed: " + name);
    }

    public static void main(String[] args) { System.out.println(verify()); }
}
