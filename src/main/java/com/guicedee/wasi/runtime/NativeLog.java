package com.guicedee.wasi.runtime;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.Unmanaged;
import org.teavm.interop.c.Include;

/** Native signatures are deliberately outside application reflection registration. */
@Include("stdio.h")
public final class NativeLog {
    @Import(name = "fflush") @Unmanaged
    private static native int flush(Address stream);
    public static void flush() { flush(Address.fromInt(0)); }
}
