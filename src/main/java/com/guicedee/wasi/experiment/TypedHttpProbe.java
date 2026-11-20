package com.guicedee.wasi.experiment;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import static com.guicedee.wasi.experiment.JacksonProbe.check;
import com.guicedee.wasi.experiment.JacksonProbe.Item;

public final class TypedHttpProbe {
    public static final class Clients {
        final ObjectMapper mapper;
        final WasiHttpTransport transport;
        @Inject public Clients(ObjectMapper mapper, WasiHttpTransport transport) { this.mapper = mapper; this.transport = transport; }
        public <S, R> WasiRestClient<S, R> at(String method, String path, TypeLiteral<R> type, int timeout, int limit) {
            return new WasiRestClient<>(mapper, transport, mapper.getTypeFactory().constructType(type.getType()),
                new WasiRestClient.Endpoint(method, path, timeout, limit));
        }
        public WasiRestClient<Void, Item> get(String path, int timeout, int limit) {
            return at("GET", path, TypeLiteral.get(Item.class), timeout, limit);
        }
    }
    public static final class Bindings extends AbstractModule {
        private final WasiHttpTransport.Config config;
        public Bindings(WasiHttpTransport.Config config) { this.config = config; }
        @Override protected void configure() {
            binder().disableCircularProxies();
            bind(WasiHttpTransport.Config.class).toInstance(config);
            bind(ObjectMapper.class).toInstance(JacksonProbe.mapper());
            bind(Clients.class).in(Singleton.class);
        }
        @Provides @Singleton @Named("get-item")
        WasiRestClient<Void, Item> get(Clients clients) { return clients.get("/dto/{id}", 2000, 4096); }
        @Provides @Singleton @Named("post-item")
        WasiRestClient<Item, Item> post(Clients clients) { return clients.at("POST", "/post", TypeLiteral.get(Item.class), 2000, 4096); }
        @Provides @Singleton @Named("list-items")
        WasiRestClient<Void, List<Item>> list(Clients clients) {
            return clients.at("GET", "/list", new TypeLiteral<List<Item>>() {}, 2000, 4096);
        }
    }
    public static final class Application {
        final WasiRestClient<Void, Item> get;
        final WasiRestClient<Item, Item> post;
        final WasiRestClient<Void, List<Item>> list;
        final Provider<WasiRestClient<Void, Item>> provider;
        final Clients clients;
        @Inject public Application(@Named("get-item") WasiRestClient<Void, Item> get,
                @Named("post-item") WasiRestClient<Item, Item> post,
                @Named("list-items") WasiRestClient<Void, List<Item>> list,
                @Named("get-item") Provider<WasiRestClient<Void, Item>> provider, Clients clients) {
            this.get = get; this.post = post; this.list = list; this.provider = provider; this.clients = clients;
        }
        Item fetch() {
            Item item = get.send(null, Map.of("id", "café /?"), Map.of("search", "a b&c"));
            check(item.quantity == 42 && item.name.equals("WASI café ☕"), "typed GET values");
            return item;
        }
        void run() {
            check(provider.get() == get && clients.transport.calls() == 0, "named singleton provider; no eager requests");
            Item original = fetch();
            Item echoed = post.send(original);
            check(echoed.name.equals(original.name) && echoed.tags.equals(original.tags), "typed POST echo");
            List<Item> items = list.send(null);
            check(items.size() == 2 && items.get(1).quantity == 7 && items.get(1).getClass() == Item.class, "generic list element type");
            check(clients.get("/empty", 2000, 4096).send(null) == null, "empty response maps to null");
            checkpoint("WASI_TYPED_HTTP_INJECTION_OK named provider singleton full-generic-types");
            int before = clients.transport.calls();
            try { get.send(null); throw new AssertionError("missing path accepted"); }
            catch (WasiRestClient.Failure failure) { check(failure.stage.equals("prepare") && failure.getCause() != null, "missing path context"); }
            check(clients.transport.calls() == before, "preparation failure must not send");
            fetch();
            var broken = clients.<BrokenBody, Item>at("POST", "/post", TypeLiteral.get(Item.class), 2000, 4096);
            before = clients.transport.calls();
            try { broken.send(new BrokenBody()); throw new AssertionError("broken serialization accepted"); }
            catch (WasiRestClient.Failure failure) { check(failure.stage.equals("prepare") && failure.getCause() != null, "serialization context"); }
            check(clients.transport.calls() == before, "serialization failure must not send");
            fetch();
            for (int round = 0; round < 9; round++) {
                failure("/status/404", "status", 404, "error-404", 2000, 4096);
                failure("/status/500", "status", 500, "error-500", 2000, 4096);
                failure("/malformed", "decode", 200, "{", 2000, 4096);
                failure("/wrong-shape", "decode", 200, "[]", 2000, 4096);
                failure("/slow-headers", "transport-4", 0, "", 200, 4096);
                failure("/slow-body", "transport-4", 200, "ab", 200, 4096);
                failure("/large", "transport-3", 200, "", 2000, 32);
                failure("/truncated", "transport-1", 200, "ab", 2000, 4096);
            }
            check(clients.transport.calls() == 150, "typed request count");
            checkpoint("WASI_TYPED_HTTP_FAILURES_OK prepare encode status decode timeout size stream recovery");
            checkpoint("WASI_TYPED_HTTP_CLEANUP_OK calls=150");
        }
        void failure(String path, String stage, int status, String body, int timeout, int limit) {
            try { clients.get(path, timeout, limit).send(null); throw new AssertionError("failure expected " + path); }
            catch (WasiRestClient.Failure failure) {
                check(failure.stage.equals(stage) && failure.status == status && failure.body.equals(body), "failure details " + path);
                if (!stage.equals("status")) check(failure.getCause() != null, "failure cause " + path);
            }
            fetch();
        }
    }
    public static final class BrokenBody {
        public String getValue() { throw new IllegalStateException("serialization-fixture-failure"); }
    }
    private static void checkpoint(String text) { System.out.println(text); WasiHttpTransport.flushLog(); }
    public static void main(String[] args) {
        try {
            check(args.length == 3, "fixture arguments");
            checkpoint(JacksonProbe.verify());
            var injector = Guice.createInjector(new Bindings(new WasiHttpTransport.Config(args[0], args[2])));
            injector.getInstance(Application.class).run();
            checkpoint("WASI_TYPED_HTTP_OK jackson3 real-guice get post errors cleanup");
        } catch (Throwable failure) {
            failure.printStackTrace(); WasiHttpTransport.flushLog(); throw failure;
        }
    }
}
