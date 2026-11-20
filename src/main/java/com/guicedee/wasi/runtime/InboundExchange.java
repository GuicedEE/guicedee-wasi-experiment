package com.guicedee.wasi.runtime;

import java.nio.charset.StandardCharsets;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.Unmanaged;
import org.teavm.interop.c.Include;

/** Copies the bounded byte payload between the Java handler and the reactor. */
@Include("inbound_bridge.h")
public final class InboundExchange {
    private InboundExchange() { }

    @Import(name = "inbound_request_size") @Unmanaged
    private static native int requestSize();

    @Import(name = "inbound_copy_request") @Unmanaged
    private static native void copyRequest(Address bytes, int length);

    @Import(name = "inbound_set_response") @Unmanaged
    private static native void setResponse(int status, Address bytes, int length);

    public static byte[] read() {
        byte[] bytes = new byte[requestSize()];
        if (bytes.length != 0) copyRequest(Address.ofData(bytes), bytes.length);
        return bytes;
    }

    public static void send(int status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        setResponse(status, Address.ofData(bytes), bytes.length);
    }
}
