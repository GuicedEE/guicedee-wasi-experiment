package com.guicedee.wasi.experiment;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.Unmanaged;
import org.teavm.interop.c.Include;
import java.nio.charset.StandardCharsets;

/** Real guest-originated requests; all assertions run inside the Java-compiled Wasm. */
@Include("wasi_http_probe.h")
public final class HttpProbe {
    private static String authority, refused, token;
    private static int calls;

    @Import(name = "guicedee_http_flush") @Unmanaged
    private static native void flushLog();

    @Import(name = "guicedee_http_exchange") @Unmanaged
    private static native int exchange(Address host, int hostLength, Address path, int pathLength,
            Address token, int tokenLength, int post, Address body, int bodyLength, int timeout,
            Address response, int capacity, Address metadata);

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("HTTP_ASSERTION_FAILED " + message);
            flushLog();
            throw new AssertionError(message);
        }
    }

    private static Reply request(String host, String path, String body, int timeout, int capacity) {
        byte[] h = bytes(host), p = bytes(path), t = bytes(token), b = bytes(body == null ? "" : body);
        byte[] output = new byte[capacity]; int[] meta = new int[6];
        long start = System.nanoTime();
        int result = exchange(Address.ofData(h), h.length, Address.ofData(p), p.length,
                Address.ofData(t), t.length, body == null ? 0 : 1, Address.ofData(b), b.length,
                timeout, Address.ofData(output), capacity, Address.ofData(meta));
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        check(meta[3] == 0, "resource leak after " + path);
        check(meta[1] >= 0 && meta[1] <= capacity, "body bound");
        if (meta[0] != 0) check(meta[5] == 1, "missing echoed header: " + path);
        calls++;
        System.out.println("HTTP_RESULT path=" + path + " outcome=" + result + " status=" + meta[0]
                + " error=" + meta[2] + " bytes=" + meta[1] + " live=" + meta[3] + " peak=" + meta[4] + " ms=" + elapsed);
        flushLog();
        return new Reply(result, meta[0], meta[2], new String(output, 0, meta[1], StandardCharsets.UTF_8), elapsed);
    }

    private static void ok() {
        Reply r = request(authority, "/get?name=a%20b&n=42", null, 2000, 4096);
        check(r.outcome == 0 && r.status == 200 && r.body.equals("hello-" + token), "GET response");
    }

    private static void failures() {
        for (int status : new int[]{404, 500}) {
            Reply r = request(authority, "/status/" + status, null, 2000, 4096);
            check(r.outcome == 0 && r.status == status && r.body.equals("error-" + status), "HTTP status/body");
            ok();
        }
        Reply r = request(refused, "/refused", null, 4000, 4096);
        check(r.outcome == 1 && r.status == 0 && r.error == 6, "connection refused mapping"); ok();
        for (String path : new String[]{"/slow-headers", "/slow-body"}) {
            r = request(authority, path, null, 200, 4096);
            if (path.equals("/slow-headers"))
                check(r.outcome == 4 && r.status == 0 && r.error == -1, "guest header deadline");
            else
                check(r.outcome == 4 && r.status == 200 && r.error == -1 && r.body.equals("ab"), "guest body deadline");
            check(r.millis >= 100 && r.millis < 700, "deadline " + path); ok();
        }
        r = request(authority, "/large", null, 2000, 32);
        check(r.outcome == 3, "response size bound"); ok();
        r = request(authority, "/truncated", null, 2000, 4096);
        check(r.outcome == 1, "truncated body must fail"); ok();
    }

    public static void main(String[] args) {
        check(args.length == 3, "expected server authority, refused authority and token");
        authority = args[0]; refused = args[1]; token = args[2];
        ok();
        String payload = "{\"message\":\"WASI café ☕\",\"token\":\"" + token + "\"}";
        Reply r = request(authority, "/post", payload, 2000, 4096);
        check(r.outcome == 0 && r.status == 201 && r.body.equals(payload), "POST UTF-8 bytes");
        r = request(authority, "/empty", null, 2000, 4096);
        check(r.outcome == 0 && r.status == 204 && r.body.isEmpty(), "empty body");
        System.out.println("WASI_HTTP_GET_POST_OK");
        failures();
        System.out.println("WASI_HTTP_FAILURE_RECOVERY_OK");
        for (int i = 0; i < 8; i++) failures();
        check(calls == 129, "expected request count " + calls);
        System.out.println("WASI_HTTP_CLEANUP_OK calls=" + calls);
        System.out.println("WASI_HTTP_OK get post status transport timeout bounded-body cleanup");
        flushLog();
    }

    private record Reply(int outcome, int status, int error, String body, long millis) { }
}
