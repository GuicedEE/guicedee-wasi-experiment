package com.guicedee.wasi.runtime;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.Unmanaged;
import org.teavm.interop.c.Include;

@Include("wasi_http_probe.h")
public final class NativeHttp {
    @Import(name = "guicedee_http_json_exchange") @Unmanaged
    private static native int exchange(Address host, int hostLength, Address path, int pathLength,
        Address token, int tokenLength, int post, Address body, int bodyLength, int timeout,
        Address response, int capacity, Address metadata);
    public static int send(byte[] host, byte[] target, byte[] token, boolean post, byte[] payload,
            int timeout, byte[] response, int[] metadata) {
        return exchange(Address.ofData(host), host.length, Address.ofData(target), target.length,
            Address.ofData(token), token.length, post ? 1 : 0, Address.ofData(payload), payload.length,
            timeout, Address.ofData(response), response.length, Address.ofData(metadata));
    }
}
