package com.guicedee.wasi.experiment;

/** Exercise heap growth/live references, object-array class packing and cooperative timer waits. */
public final class RuntimeProbe {
    public static String verify() throws InterruptedException {
        byte[][] live = new byte[64][];
        for (int i = 0; i < live.length; i++) {
            live[i] = new byte[256 * 1024];
            live[i][0] = (byte) i;
            live[i][live[i].length - 1] = (byte) (i + 1);
        }
        System.gc();
        for (int i = 0; i < live.length; i++) {
            if (live[i][0] != i || live[i][live[i].length - 1] != i + 1) {
                throw new IllegalStateException("Live data lost during collection");
            }
        }
        String[] original = {"array", "clone"};
        String[] copy = original.clone();
        copy[0] = "independent";
        if (!original[0].equals("array") || !copy[1].equals("clone")) throw new IllegalStateException("Array clone failed");
        long before = System.nanoTime();
        Thread.sleep(25);
        if (System.nanoTime() - before < 20_000_000L) throw new IllegalStateException("Timer returned early");
        return "WASI_RUNTIME_OK heap-gc array-clone timer";
    }
    public static void main(String[] args) throws InterruptedException { System.out.println(verify()); }
}
