package com.guicedee.wasi.experiment;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.ArrayList;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.guicedee.wasi.runtime.NativeLog;

/** The same real Jackson 3 DTO workload runs on the JVM and in WASI. */
public final class JacksonProbe {
    public static class Item {
        @JsonProperty("display_name") public String name;
        public int quantity;
        public List<String> tags;
        public double price;
        public long sequence;
        public Detail detail;
        public Item() { }
        public Item(String name, int quantity, List<String> tags) {
            this.name = name; this.quantity = quantity; this.tags = new ArrayList<>(tags);
            this.price = 15.5; this.sequence = 9007199254740993L; this.detail = new Detail();
        }
    }
    public static class Detail {
        public boolean active = true;
        public String note = "quote \" slash \\ newline\n";
        public Detail() { }
    }

    public static ObjectMapper mapper() {
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public static String verify() {
        ObjectMapper mapper = mapper();
        Item source = new Item("WASI café ☕", 42, List.of("edge", "java"));
        byte[] json = mapper.writeValueAsBytes(source);
        Item decoded = mapper.readValue(json, Item.class);
        check(decoded.name.equals(source.name) && decoded.quantity == 42 && decoded.tags.equals(source.tags), "DTO round trip");
        check(decoded.price == 15.5 && decoded.sequence == 9007199254740993L
            && decoded.detail.active && decoded.detail.note.equals(source.detail.note), "nested DTO, numeric precision and escaping");
        for (double value : new double[] {0.0, -0.0, 0.125, -12345.5, Double.MIN_VALUE, Double.MAX_VALUE}) {
            byte[] numeric = mapper.writeValueAsBytes(value);
            double restored = mapper.readValue(numeric, Double.class);
            check(Double.doubleToLongBits(value) == Double.doubleToLongBits(restored), "floating point byte output: "
                + new String(numeric, java.nio.charset.StandardCharsets.UTF_8) + " bits=" + Long.toHexString(Double.doubleToLongBits(value))
                + " parsed=" + Long.toHexString(Double.doubleToLongBits(restored)));
            restored = mapper.readValue(mapper.writeValueAsString(value), Double.class);
            check(Double.doubleToLongBits(value) == Double.doubleToLongBits(restored), "floating point character output");
        }
        var listType = mapper.getTypeFactory().constructType(new TypeReference<List<Item>>() {}.getType());
        List<Item> list = mapper.readValue(mapper.writeValueAsBytes(new ArrayList<>(List.of(source))), listType);
        check(list.size() == 1 && list.get(0).name.equals(source.name), "generic DTO list");
        Item absent = mapper.readValue("{\"display_name\":null,\"quantity\":0,\"tags\":[]}", Item.class);
        check(absent.name == null && absent.tags.isEmpty(), "null and empty list");
        for (String invalid : new String[] {"{", "{\"quantity\":\"not-a-number\"}", "{\"unknown\":1}"}) {
            boolean failed = false;
            try { mapper.readValue(invalid, Item.class); }
            catch (JacksonException expected) { failed = true; }
            check(failed, "invalid input rejected");
            check(mapper.readValue(json, Item.class).quantity == 42, "mapping recovery");
        }
        return "WASI_JACKSON_OK dto annotation generic-list unicode null malformed recovery";
    }
    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        try { System.out.println(verify()); }
        catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                System.err.println("JACKSON_FAILURE " + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace();
            }
            if (args.length > 0) NativeLog.flush();
            throw failure;
        }
        if (args.length > 0) NativeLog.flush();
    }
}
