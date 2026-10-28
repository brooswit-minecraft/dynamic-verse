package io.github.brooswitminecraft.dynamicverse;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderDuplicationBridgeGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderBridgeIntegrationGameTest;

/**
 * Isolated feasibility fixture only. No lifecycle operation is registered or
 * executed by this bootstrap.
 */
@Mod(DynamicVerse.MOD_ID)
public final class DynamicVerse {
    public static final String MOD_ID = "dynamicverse";

    public DynamicVerse(IEventBus modEventBus) {
        modEventBus.addListener(this::registerGameTests);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(Boolean.getBoolean("dynamicverse.providerBridgeIntegration")
                ? ProviderBridgeIntegrationGameTest.class : ProviderDuplicationBridgeGameTest.class);
    }
}
