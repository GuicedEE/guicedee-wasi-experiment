package com.guicedee.wasi.experiment.test;

import com.guicedee.wasi.experiment.BaselineProbe;
import com.guicedee.wasi.experiment.GuiceProbe;
import com.guicedee.wasi.experiment.GuiceMinimalProbe;
import com.guicedee.wasi.experiment.ReflectionProbe;
import com.guicedee.wasi.experiment.RuntimeProbe;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProbeTest {
    @Test void reflectiveOperationsWorkOnJvm() throws Exception {
        assertEquals("WASI_REFLECTION_OK constructor annotation generic-field method", ReflectionProbe.verify());
    }
    @Test void runtimeOperationsWorkOnJvm() throws Exception {
        assertEquals("WASI_RUNTIME_OK heap-gc array-clone timer", RuntimeProbe.verify());
    }
    @Test void minimalGuiceWorksOnJvm() {
        assertEquals("WASI_GUICE_MINIMAL_OK constructor", GuiceMinimalProbe.verify());
    }
    @Test void baselineWorksOnJvm() {
        assertEquals("WASI_BASELINE_OK sum=42 exception=caught", BaselineProbe.verify());
    }
    @Test void actualGuiceWorksOnJvm() {
        assertEquals("WASI_GUICE_OK constructor provider singleton scope interceptor", GuiceProbe.verify());
    }
}
