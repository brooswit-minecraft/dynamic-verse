package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exact-selector wrapper; intentionally delegates only new seeded bridge regressions. */
public final class SeededBridgeRegressionGameTest {
    private final ProviderDuplicationBridgeGameTest delegate = new ProviderDuplicationBridgeGameTest();
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void seedPropagation(GameTestHelper helper) throws Exception { delegate.controlledBridgePropagatesSeedToBothReloads(helper); }
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void seedCapabilityAndObservedFailures(GameTestHelper helper) throws Exception { delegate.controlledBridgeSeededCapabilityAndObservedSeedFailuresFailClosed(helper); }
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void delayedLinkage(GameTestHelper helper) throws Exception { delegate.controlledBridgeDelayedLinkageErrorQuarantinesOnce(helper); }
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void capabilityAndCurrentLinkage(GameTestHelper helper) throws Exception { delegate.controlledBridgeCapabilityLinkageAndThrowingCurrentFailClosed(helper); }
}
