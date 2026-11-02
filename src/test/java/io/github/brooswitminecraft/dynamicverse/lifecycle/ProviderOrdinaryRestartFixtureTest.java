package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Ordinary local coverage of the exact O2 provider-reload precondition.
 * Native O1/O2 remains separately assigned runtime evidence.
 */
class ProviderOrdinaryRestartFixtureTest {
    @Test
    void retainedStorageReloadRequiresFreeDynamicRegistration() {
        assertTrue(ProviderOrdinaryRestartFixture.providerReloadAdmission(false, true, false));
        assertFalse(ProviderOrdinaryRestartFixture.providerReloadAdmission(true, true, false),
                "a registered key would make DynamicDimensionRegistry.loadDynamicDimension refuse reconstruction");
        assertFalse(ProviderOrdinaryRestartFixture.providerReloadAdmission(false, false, false));
        assertFalse(ProviderOrdinaryRestartFixture.providerReloadAdmission(false, true, true));
    }
}
