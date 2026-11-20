package com.guicedee.wasi.experiment.test;

import com.guicedee.wasi.experiment.GuiceScopeProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class GuiceScopeProbeTest {
    @Test void providersAndSingletons() { GuiceScopeProbe.verifyProvidersAndSingletons(); }
    @Test void requestLifecycle() { GuiceScopeProbe.verifyScopeLifecycle(); }
    @Test void provisioningFailures() { GuiceScopeProbe.verifyProvisionFailures(); }
    @Test void cleanupFailures() throws Exception { GuiceScopeProbe.verifyCleanupFailures(); }
    @Test void overlappingRequests() throws Exception { GuiceScopeProbe.verifyThreadIsolation(); }
}
