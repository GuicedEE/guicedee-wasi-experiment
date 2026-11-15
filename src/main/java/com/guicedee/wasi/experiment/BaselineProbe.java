package com.guicedee.wasi.experiment;

/** Java 25 bytecode, allocation, collections, exceptions and WASI stdout. No JavaScript host. */
public final class BaselineProbe {
    public static String verify() {
        var values = new java.util.ArrayList<Integer>();
        values.add(19);
        values.add(23);
        int sum = values.stream().mapToInt(Integer::intValue).sum();
        if (sum != 42) throw new IllegalStateException("Collection computation failed");
        String caught;
        try {
            throw new IllegalArgumentException("caught");
        } catch (IllegalArgumentException expected) {
            caught = expected.getMessage();
        }
        if (!"caught".equals(caught)) throw new IllegalStateException("Exception handling failed");
        return "WASI_BASELINE_OK sum=" + sum + " exception=" + caught;
    }

    public static void main(String[] args) {
        System.out.println(verify());
    }
}
