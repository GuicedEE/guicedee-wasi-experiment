package com.guicedee.wasi.metadata;

/** Distinguish a supported null value from an unregistered dispatch case. */
public final class MetadataResult {
    public final String owner;
    public final Object value;
    public MetadataResult(String owner, Object value) { this.owner = owner; this.value = value; }
}
