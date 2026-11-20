package com.guicedee.wasi.experiment;

/** Shared bounded exchange contract for the WASI transport and JVM comparison. */
public interface HttpTransport {
    WasiHttpTransport.Reply send(String method, String path, byte[] body, int timeoutMillis, int bodyLimit);
}
