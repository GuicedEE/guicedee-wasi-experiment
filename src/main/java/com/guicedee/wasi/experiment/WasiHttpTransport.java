package com.guicedee.wasi.experiment;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import com.guicedee.wasi.runtime.NativeHttp;
import com.guicedee.wasi.runtime.NativeLog;

/** Bounded synchronous experimental transport. All host handles close before return. */
@Singleton
public final class WasiHttpTransport implements HttpTransport {
    private final Config config;
    private int calls;
    public record Config(String authority, String fixtureToken) { }
    public record Reply(int outcome, int status, int wasiError, byte[] body) { }

    @Inject public WasiHttpTransport(Config config) { this.config = config; }
    public static void flushLog() { NativeLog.flush(); }

    public Reply send(String method, String path, byte[] body, int timeout, int capacity) {
        if (!method.equals("GET") && !method.equals("POST")) throw new IllegalArgumentException("Only GET/POST qualified");
        if (body != null && (body.length > 4096 || method.equals("GET"))) throw new IllegalArgumentException("Invalid bounded request body");
        if (timeout <= 0 || capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Invalid response limits");
        byte[] host = config.authority.getBytes(StandardCharsets.UTF_8);
        byte[] target = path.getBytes(StandardCharsets.UTF_8);
        byte[] token = config.fixtureToken.getBytes(StandardCharsets.UTF_8);
        byte[] payload = body == null ? new byte[0] : body;
        byte[] response = new byte[capacity];
        int[] meta = new int[6];
        int outcome = NativeHttp.send(host, target, token, method.equals("POST"), payload, timeout, response, meta);
        calls++;
        JacksonProbe.check(meta[3] == 0 && meta[1] >= 0 && meta[1] <= capacity, "native resource/body invariant");
        if (meta[0] != 0) JacksonProbe.check(meta[5] == 1, "fixture response header");
        System.out.println("TYPED_HTTP_RESULT path=" + path + " outcome=" + outcome + " status=" + meta[0] + " live=" + meta[3]);
        flushLog();
        return new Reply(outcome, meta[0], meta[2], Arrays.copyOf(response, meta[1]));
    }
    public int calls() { return calls; }
}
