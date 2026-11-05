package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Offline (non-native) validation that every fixed scenario name
 * {@link ProviderSafeDeleteScenariosGameTest} uses actually satisfies
 * {@code ServerVerseRuntime.ownedCreateId}'s own naming contract
 * ({@code [a-z0-9_-]{1,32}}). Requested after
 * {@code rp_scenario_failed_save_unrelated} (33 chars) was found to exceed
 * the 32-char limit, which would have made that specific scenario's own
 * create admission fail before any native run.
 */
class ProviderSafeDeleteScenariosNamingTest {
    private static final java.util.regex.Pattern OWNED_NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,32}");

    @Test void everyFixedScenarioNameSatisfiesTheOwnedNameContract() {
        for (String name : ProviderSafeDeleteScenariosGameTest.ALL_FIXED_NAMES) {
            assertTrue(OWNED_NAME.matcher(name).matches(),
                    "fixed scenario name violates the <=32-char owned-name contract: '" + name + "' (" + name.length() + " chars)");
        }
    }

    @Test void everyFixedScenarioNameIsDistinct() {
        var names = ProviderSafeDeleteScenariosGameTest.ALL_FIXED_NAMES;
        assertTrue(java.util.Set.of(names).size() == names.length, "fixed scenario names must all be distinct");
    }
}
