package com.guicedee.wasi.experiment.test;

import com.google.inject.Guice;
import com.guicedee.wasi.experiment.TypedHttpProbe;
import com.guicedee.wasi.experiment.WasiHttpTransport;
import com.guicedee.wasi.experiment.WasiRestClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypedBindingsTest {
    @Test void realGuiceResolvesNamedGenericClientsWithoutSending() {
        var injector = Guice.createInjector(new TypedHttpProbe.Bindings(new WasiHttpTransport.Config("127.0.0.1:1", "fixture")));
        var app = injector.getInstance(TypedHttpProbe.Application.class);
        assertNotNull(app);
        assertSame(injector.getInstance(WasiHttpTransport.class), injector.getInstance(WasiHttpTransport.class));
        assertEquals(0, injector.getInstance(WasiHttpTransport.class).calls());
    }
    @Test void invalidEndpointIsRejectedBeforeNativeDispatch() {
        var injector = Guice.createInjector(new TypedHttpProbe.Bindings(new WasiHttpTransport.Config("127.0.0.1:1", "fixture")));
        var client = injector.getInstance(TypedHttpProbe.Clients.class).get("/dto/{id}", 2000, 4096);
        var failure = assertThrows(WasiRestClient.Failure.class, () -> client.send(null));
        assertEquals("prepare", failure.stage);
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(0, injector.getInstance(WasiHttpTransport.class).calls());
    }
}
