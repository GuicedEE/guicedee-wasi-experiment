package com.guicedee.wasi.experiment.test;

import com.guicedee.wasi.experiment.JacksonProbe;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonProbeTest {
    @Test void selectedDtoContractOnJvm() {
        assertEquals("WASI_JACKSON_OK dto annotation generic-list unicode null malformed recovery", JacksonProbe.verify());
    }
}
