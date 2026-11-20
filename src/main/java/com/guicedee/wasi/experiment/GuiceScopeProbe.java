package com.guicedee.wasi.experiment;

import com.google.inject.*;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Same real Guice assertions on the JVM and WASI; no application interception. */
public final class GuiceScopeProbe {
    public static final String MARKER = "WASI_GUICE_SCOPES_OK providers singleton scope cleanup failures isolation";
    @ScopeAnnotation @Retention(RUNTIME) @Target({TYPE, METHOD})
    public @interface RequestScoped { }

    public static final class Fault extends RuntimeException {
        public Fault(String message) { super(message); }
    }
    public static final class Counters {
        int products, singletonConstructions, providedSingletons, constructorAttempts, providerAttempts, singletonAttempts;
        int closedResources, closedServices, closedSingletons;
        boolean failResourceClose, failServiceClose;
        final Fault constructorFailure = new Fault("constructor-failure");
        final Fault providerFailure = new Fault("provider-failure");
        final Fault singletonFailure = new Fault("singleton-failure");
        final Fault resourceCloseFailure = new Fault("resource-close-failure");
        final Fault serviceCloseFailure = new Fault("service-close-failure");
        final List<String> closeOrder = new ArrayList<>();
    }
    @Singleton public static class Shared implements AutoCloseable {
        final Counters counts;
        @Inject public Shared(Counters counts) { this.counts = counts; counts.singletonConstructions++; }
        @Override public void close() { counts.closedSingletons++; }
    }
    public static class Product {
        final Shared shared;
        public Product(Shared shared) { this.shared = shared; }
    }
    public static class ProductProvider implements Provider<Product> {
        final Shared shared;
        @Inject public ProductProvider(Shared shared) { this.shared = shared; }
        @Override public Product get() { shared.counts.products++; return new Product(shared); }
    }
    public static class ProvidedSingleton {
        final Shared shared;
        public ProvidedSingleton(Shared shared) { this.shared = shared; }
    }
    public interface ResourceAlias { }
    public static class Resource implements ResourceAlias, AutoCloseable {
        final Counters counts;
        final ManagedRequestScope scope;
        final Provider<Resource> self;
        boolean closed;
        @Inject public Resource(Counters counts, ManagedRequestScope scope, Provider<Resource> self) {
            this.counts = counts; this.scope = scope; this.self = self;
        }
        @Override public void close() {
            require(!closed, "resource closed twice");
            expectOutOfScope(() -> self.get());
            expectIllegalState(() -> scope.enter(), "request entry during cleanup");
            closed = true; counts.closedResources++; counts.closeOrder.add("resource");
            if (counts.failResourceClose) throw counts.resourceCloseFailure;
        }
    }
    @RequestScoped public static class RequestService implements AutoCloseable {
        final Resource resource;
        final Shared shared;
        @Inject public RequestService(Resource resource, Shared shared) { this.resource = resource; this.shared = shared; }
        @Override public void close() {
            require(!resource.closed, "dependency closed before its consumer");
            resource.counts.closedServices++; resource.counts.closeOrder.add("service");
            if (resource.counts.failServiceClose) throw resource.counts.serviceCloseFailure;
        }
    }
    public static class ProvidedRequest {
        final Resource resource;
        public ProvidedRequest(Resource resource) { this.resource = resource; }
    }
    public static class Client {
        final Provider<Product> products;
        final Provider<Resource> requests;
        @Inject public Client(Provider<Product> products, Provider<Resource> requests) {
            this.products = products; this.requests = requests;
        }
    }
    @RequestScoped public static class FlakyConstructor {
        final Resource resource;
        @Inject public FlakyConstructor(Resource resource, Counters counts) {
            this.resource = resource;
            if (++counts.constructorAttempts == 1) throw counts.constructorFailure;
        }
    }
    public static class FlakyProduct {
        final Resource resource;
        public FlakyProduct(Resource resource) { this.resource = resource; }
    }
    public static class FlakyProvider implements Provider<FlakyProduct> {
        final Provider<Resource> resources;
        final Counters counts;
        @Inject public FlakyProvider(Provider<Resource> resources, Counters counts) { this.resources = resources; this.counts = counts; }
        @Override public FlakyProduct get() {
            Resource resource = resources.get();
            if (++counts.providerAttempts == 1) throw counts.providerFailure;
            return new FlakyProduct(resource);
        }
    }
    @Singleton public static class FlakySingleton {
        @Inject public FlakySingleton(Counters counts) {
            if (++counts.singletonAttempts == 1) throw counts.singletonFailure;
        }
    }
    public static class ProbeModule extends AbstractModule {
        final ManagedRequestScope scope;
        final Counters counts;
        public ProbeModule(ManagedRequestScope scope, Counters counts) { this.scope = scope; this.counts = counts; }
        @Override protected void configure() {
            binder().disableCircularProxies();
            bindScope(RequestScoped.class, scope);
            bind(Counters.class).toInstance(counts);
            bind(ManagedRequestScope.class).toInstance(scope);
            bind(Product.class).toProvider(ProductProvider.class);
            bind(Resource.class).in(RequestScoped.class);
            bind(ResourceAlias.class).to(Resource.class).in(RequestScoped.class);
            bind(FlakyProduct.class).toProvider(FlakyProvider.class).in(RequestScoped.class);
        }
        @Provides @Singleton public ProvidedSingleton shared(Shared shared) {
            counts.providedSingletons++;
            return new ProvidedSingleton(shared);
        }
        @Provides @RequestScoped public ProvidedRequest request(Resource resource) { return new ProvidedRequest(resource); }
    }
    private static final class Fixture {
        final Counters counts = new Counters();
        final ManagedRequestScope scope = new ManagedRequestScope();
        final Injector injector = Guice.createInjector(Stage.DEVELOPMENT, new ProbeModule(scope, counts));
    }

    public static void verifyProvidersAndSingletons() {
        Fixture f = new Fixture();
        Provider<Product> provider = f.injector.getProvider(Product.class);
        Client client = f.injector.getInstance(Client.class);
        require(f.counts.products == 0 && f.counts.providedSingletons == 0, "provider lookup/injection must be lazy");
        Product first = provider.get();
        Product second = client.products.get();
        require(first != second && f.counts.products == 2, "unscoped provider creates fresh values");
        require(first.shared == second.shared && first.shared == f.injector.getInstance(Shared.class)
                && f.counts.singletonConstructions == 1, "constructor singleton identity");
        ProvidedSingleton supplied = f.injector.getInstance(ProvidedSingleton.class);
        require(supplied == f.injector.getProvider(ProvidedSingleton.class).get() && supplied.shared == first.shared
                && f.counts.providedSingletons == 1, "Provides singleton identity and injected dependency");
        expectOutOfScope(() -> client.requests.get());
        try (var request = f.scope.enter()) {
            require(client.requests.get() == f.injector.getInstance(Resource.class), "injected provider resolves current scope");
        }
        Fixture other = new Fixture();
        require(other.injector.getInstance(Shared.class) != first.shared, "singletons belong to their injector");
    }

    public static void verifyScopeLifecycle() {
        Fixture f = new Fixture();
        Provider<Resource> captured = f.injector.getProvider(Resource.class);
        expectOutOfScope(() -> captured.get());
        ManagedRequestScope.Request handle = f.scope.enter();
        Resource previous;
        try (handle) {
            previous = captured.get();
            require(previous == f.injector.getInstance(ResourceAlias.class), "linked alias shares resource");
            RequestService service = f.injector.getInstance(RequestService.class);
            require(service == f.injector.getInstance(RequestService.class) && service.resource == previous, "annotated request reuse");
            ProvidedRequest provided = f.injector.getInstance(ProvidedRequest.class);
            require(provided == f.injector.getInstance(ProvidedRequest.class) && provided.resource == previous, "Provides request scope");
            expectIllegalState(() -> f.scope.enter(), "nested entry");
            require(captured.get() == previous, "rejected nested entry preserves active request");
        }
        require(!f.scope.isActive() && handle.retainedReferences() == 0, "scope detached and references cleared");
        require(previous.closed && f.counts.closedResources == 1 && f.counts.closedServices == 1
                && f.counts.closeOrder.equals(List.of("service", "resource")), "reverse cleanup with alias deduplication");
        handle.close();
        require(f.counts.closedResources == 1, "close is idempotent");
        expectOutOfScope(() -> captured.get());
        try (var next = f.scope.enter()) {
            require(captured.get() != previous, "fresh request identity");
        }
        require(f.counts.closedResources == 2 && f.counts.closedSingletons == 0, "scope does not own injector singletons");
    }

    public static void verifyProvisionFailures() {
        Fixture f = new Fixture();
        expectProvision(() -> f.injector.getInstance(FlakySingleton.class), f.counts.singletonFailure);
        FlakySingleton singleton = f.injector.getInstance(FlakySingleton.class);
        require(singleton == f.injector.getInstance(FlakySingleton.class) && f.counts.singletonAttempts == 2, "failed singleton can retry then cache");
        ManagedRequestScope.Request handle = f.scope.enter();
        try (handle) {
            expectProvision(() -> f.injector.getInstance(FlakyConstructor.class), f.counts.constructorFailure);
            FlakyConstructor value = f.injector.getInstance(FlakyConstructor.class);
            require(value == f.injector.getInstance(FlakyConstructor.class) && f.counts.constructorAttempts == 2, "failed scoped constructor not cached");
            expectProvision(() -> f.injector.getInstance(FlakyProduct.class), f.counts.providerFailure);
            FlakyProduct product = f.injector.getInstance(FlakyProduct.class);
            require(product == f.injector.getInstance(FlakyProduct.class) && product.resource == value.resource
                    && f.counts.providerAttempts == 2, "failed scoped provider not cached");
        }
        require(handle.retainedReferences() == 0 && f.counts.closedResources == 1, "partial construction dependency cleaned once");
        Fixture abandoned = new Fixture();
        ManagedRequestScope.Request failedRequest = abandoned.scope.enter();
        expectProvision(() -> {
            try (failedRequest) { abandoned.injector.getInstance(FlakyConstructor.class); }
        }, abandoned.counts.constructorFailure);
        require(!abandoned.scope.isActive() && failedRequest.retainedReferences() == 0 && abandoned.counts.closedResources == 1,
                "escaping provision failure cleans partial dependencies and context");
        try (var recovered = abandoned.scope.enter()) { abandoned.injector.getInstance(FlakyConstructor.class); }
        require(abandoned.counts.constructorAttempts == 2 && abandoned.counts.closedResources == 2,
                "fresh request recovers after aborted construction");
        Fault body = new Fault("request-body-failure");
        Resource[] aborted = {null};
        try (var request = f.scope.enter()) {
            aborted[0] = f.injector.getInstance(Resource.class);
            throw body;
        } catch (Fault expected) { require(expected == body, "body failure identity"); }
        require(aborted[0].closed && !f.scope.isActive(), "body failure closes resources and detaches scope");
        expectOutOfScope(() -> f.injector.getInstance(Resource.class));
        try (var recovered = f.scope.enter()) {
            require(f.injector.getInstance(Resource.class) != aborted[0], "request recovers after failure");
        }
        Counters startup = new Counters();
        try {
            Guice.createInjector(new AbstractModule() {
                @Override protected void configure() {
                    bind(Counters.class).toInstance(startup);
                    bind(FlakySingleton.class).asEagerSingleton();
                }
            });
            throw new IllegalStateException("Eager singleton failure ignored");
        } catch (CreationException expected) {
            require(hasCause(expected, startup.singletonFailure) && expected.getMessage().contains("singleton-failure"), "startup failure cause and diagnostic");
        }
    }

    public static void verifyCleanupFailures() throws InterruptedException {
        Fixture f = new Fixture();
        f.counts.failServiceClose = true; f.counts.failResourceClose = true;
        ManagedRequestScope.Request handle = f.scope.enter();
        f.injector.getInstance(RequestService.class);
        try { handle.close(); throw new IllegalStateException("Cleanup failure ignored"); }
        catch (ManagedRequestScope.CleanupException expected) { checkCleanup(expected, f.counts); }
        require(f.counts.closedServices == 1 && f.counts.closedResources == 1 && handle.retainedReferences() == 0
                && !f.scope.isActive(), "cleanup continues after failure and releases context");
        handle.close();
        Fault body = new Fault("primary-body-failure");
        try (var request = f.scope.enter()) {
            f.injector.getInstance(RequestService.class);
            throw body;
        } catch (Fault expected) {
            require(expected == body && expected.getSuppressed().length == 1
                    && expected.getSuppressed()[0] instanceof ManagedRequestScope.CleanupException, "body remains primary with suppressed cleanup");
            checkCleanup((ManagedRequestScope.CleanupException) expected.getSuppressed()[0], f.counts);
        }
        require(f.counts.closedServices == 2 && f.counts.closedResources == 2 && !f.scope.isActive(), "failed cleanup is exactly once");
        f.counts.failServiceClose = false; f.counts.failResourceClose = false;
        try (var cancelled = f.scope.enter()) {
            f.injector.getInstance(Resource.class);
            Thread.currentThread().interrupt();
            Thread.sleep(1);
            throw new IllegalStateException("Request interruption ignored");
        } catch (InterruptedException expected) { }
        require(!Thread.currentThread().isInterrupted() && !f.scope.isActive() && f.counts.closedResources == 3, "interruption cleans request");
        try (var next = f.scope.enter()) { f.injector.getInstance(RequestService.class); }
        require(f.counts.closedResources == 4 && f.counts.closedSingletons == 0, "healthy request after cleanup failure/interruption");
    }

    public static void verifyThreadIsolation() throws InterruptedException {
        Fixture f = new Fixture();
        Shared shared = f.injector.getInstance(Shared.class); // No contention during singleton construction is claimed.
        Throwable[] failure = {null};
        ManagedRequestScope.Request parent = f.scope.enter();
        try (parent) {
            Resource outer = f.injector.getInstance(Resource.class);
            Thread worker = new Thread(() -> {
                try {
                    require(!f.scope.isActive(), "thread must not inherit request");
                    expectOutOfScope(() -> f.injector.getInstance(Resource.class));
                    expectIllegalState(parent::close, "non-owner close");
                    try (var own = f.scope.enter()) {
                        Resource inner = f.injector.getInstance(Resource.class);
                        require(inner != outer && f.injector.getInstance(Shared.class) == shared, "separate requests share only singleton");
                        Thread.sleep(2);
                        require(f.injector.getInstance(Resource.class) == inner, "scope retained across cooperative suspension");
                    }
                    require(!f.scope.isActive(), "worker request detached");
                } catch (Throwable error) { failure[0] = error; }
            });
            worker.start(); worker.join();
            if (failure[0] != null) throw new IllegalStateException("Request worker failed", failure[0]);
            require(f.injector.getInstance(Resource.class) == outer && !outer.closed, "worker cleanup preserves parent request");
        }
        require(!f.scope.isActive() && parent.retainedReferences() == 0 && f.counts.closedResources == 2, "both contexts released");
    }

    private static void checkCleanup(ManagedRequestScope.CleanupException error, Counters counts) {
        require(error.getCause() == counts.serviceCloseFailure && error.getSuppressed().length == 1
                && error.getSuppressed()[0] == counts.resourceCloseFailure, "cleanup preserves both failures in reverse order");
    }
    private static boolean hasCause(Throwable error, Throwable wanted) {
        for (int i = 0; error != null && i < 32; i++, error = error.getCause()) if (error == wanted) return true;
        return false;
    }
    private static void expectProvision(Runnable action, Throwable cause) {
        try { action.run(); throw new IllegalStateException("Provisioning failure ignored"); }
        catch (ProvisionException expected) {
            require(hasCause(expected, cause) && expected.getMessage().contains(cause.getMessage()), "provision failure cause and diagnostic");
        }
    }
    private static void expectOutOfScope(Runnable action) {
        try { action.run(); throw new IllegalStateException("Out-of-scope lookup accepted"); }
        catch (ProvisionException expected) {
            Throwable cause = expected;
            while (cause != null && !(cause instanceof OutOfScopeException)) cause = cause.getCause();
            require(cause != null, "out-of-scope cause preserved");
        }
    }
    private static void expectIllegalState(Runnable action, String name) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "Accepted " + name);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static void checkpoint(String message) { System.out.println(message); System.out.flush(); }
    public static void main(String[] args) throws Throwable {
        try {
            verifyProvidersAndSingletons(); checkpoint("WASI_GUICE_PROVIDERS_SINGLETON_OK");
            verifyScopeLifecycle(); checkpoint("WASI_GUICE_SCOPE_LIFECYCLE_OK");
            verifyProvisionFailures(); checkpoint("WASI_GUICE_PROVISION_FAILURES_OK");
            verifyCleanupFailures(); checkpoint("WASI_GUICE_CLEANUP_FAILURES_OK");
            verifyThreadIsolation(); checkpoint("WASI_GUICE_SCOPE_THREADS_OK");
            checkpoint(MARKER);
        } catch (Throwable failure) {
            System.err.println("GUICE_SCOPE_PROBE_FAILURE " + failure.getClass().getName());
            System.err.println(failure.getMessage()); failure.printStackTrace(); throw failure;
        }
    }
}
