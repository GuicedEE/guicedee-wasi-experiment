package com.guicedee.wasi.metadata;

import java.io.IOException;
import org.teavm.parsing.resource.ResourceProvider;

/** Host-only loader: application/library definitions come from TeaVM's actual inputs,
 * never Maven's parent-loaded Guice/Guava versions. JDK definitions come from the platform. */
public final class InputMetadata {
    private static ClassLoader inputs;
    public static void initialize(ResourceProvider resources) {
        inputs = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                var resource = resources.getResource(name.replace('.', '/') + ".class");
                if (resource == null) throw new ClassNotFoundException(name);
                try (var stream = resource.open()) {
                    byte[] bytes = stream.readAllBytes();
                    if (name.equals("com.google.inject.TypeLiteral") || name.equals("com.google.common.util.concurrent.AbstractFuture")) {
                        try {
                            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                            System.out.println("WASI_METADATA_INPUT " + name + " sha256=" + sha);
                        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) { throw new ClassNotFoundException(name, e); }
            }
        };
    }
    public static Class<?> load(String name) {
        if (inputs == null) throw new IllegalStateException("Input metadata loader was not initialized");
        try { return Class.forName(name, false, inputs); }
        catch (ClassNotFoundException | LinkageError e) { return null; }
    }
}
