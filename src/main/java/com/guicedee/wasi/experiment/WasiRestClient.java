package com.guicedee.wasi.experiment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/** Experimental synchronous profile; not the production Vert.x/Mutiny RestClient API. */
public final class WasiRestClient<Send, Receive> {
    private final ObjectMapper mapper;
    private final HttpTransport transport;
    private final JavaType receiveType;
    private final Endpoint endpoint;
    public record Endpoint(String method, String path, int timeoutMillis, int bodyLimit) { }

    public WasiRestClient(ObjectMapper mapper, HttpTransport transport, JavaType receiveType, Endpoint endpoint) {
        this.mapper = mapper; this.transport = transport; this.receiveType = receiveType; this.endpoint = endpoint;
    }
    public Receive send(Send body) { return send(body, Map.of(), Map.of()); }
    public Receive send(Send body, Map<String, String> paths, Map<String, String> query) {
        final byte[] encoded;
        final String path;
        try {
            path = resolve(endpoint.path, paths, query);
            encoded = body == null ? null : mapper.writeValueAsBytes(body);
        } catch (RuntimeException cause) {
            throw new Failure("prepare", 0, null, cause);
        }
        final WasiHttpTransport.Reply reply;
        try { reply = transport.send(endpoint.method, path, encoded, endpoint.timeoutMillis, endpoint.bodyLimit); }
        catch (RuntimeException cause) { throw new Failure("transport", 0, null, cause); }
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        if (reply.outcome() != 0) throw new Failure("transport-" + reply.outcome(), reply.status(), text,
            new IllegalStateException("WASI HTTP outcome=" + reply.outcome() + ", error=" + reply.wasiError()));
        if (reply.status() < 200 || reply.status() >= 300) throw new Failure("status", reply.status(), text, null);
        if (reply.body().length == 0) return null;
        try { return mapper.readValue(reply.body(), receiveType); }
        catch (RuntimeException cause) { throw new Failure("decode", reply.status(), text, cause); }
    }
    // Same UTF-8 percent encoding convention as RestClient.resolvePathParams.
    static String resolve(String template, Map<String, String> paths, Map<String, String> query) {
        String result = template;
        for (var entry : paths.entrySet()) result = result.replace("{" + entry.getKey() + "}", encode(entry.getValue()));
        if (result.contains("{") || result.contains("}")) throw new IllegalArgumentException("Missing path parameter");
        for (var entry : query.entrySet()) result += (result.contains("?") ? "&" : "?") + encode(entry.getKey()) + "=" + encode(entry.getValue());
        return result;
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    public static final class Failure extends RuntimeException {
        public final String stage;
        public final int status;
        public final String body;
        Failure(String stage, int status, String body, Throwable cause) {
            super("REST " + stage + " status=" + status, cause);
            this.stage = stage; this.status = status; this.body = body;
        }
    }
}
