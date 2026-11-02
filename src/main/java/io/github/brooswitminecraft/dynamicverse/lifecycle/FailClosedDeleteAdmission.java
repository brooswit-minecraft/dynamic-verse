package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * The production-used delete front door.  It establishes only that a name is
 * one of this runtime's READY owned verses; deletion itself remains unavailable
 * until the verified save-and-close/delete protocol exists.
 */
final class FailClosedDeleteAdmission {
    private FailClosedDeleteAdmission() { }

    static VerseLifecycleCommandService.DeleteAdmission validate(String requestedName, OwnedVerseLookup verses) {
        Objects.requireNonNull(verses);
        String id = normalizeOwnedName(requestedName);
        if (id == null || !verses.isOwned(id)) {
            return VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_MANAGED;
        }
        if (!verses.isReady(id)) return VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_READY;
        return VerseLifecycleCommandService.DeleteAdmission.ERR_SAFE_DELETE_UNAVAILABLE;
    }

    /** Same approved bare/dynamicverse-qualified grammar used at the runtime boundary. */
    private static String normalizeOwnedName(String name) {
        if (name == null) return null;
        if (name.matches("[a-z0-9_-]{1,32}")) return ResourceLocation.fromNamespaceAndPath("dynamicverse", name).toString();
        ResourceLocation id = ResourceLocation.tryParse(name);
        return id != null && id.getNamespace().equals("dynamicverse") && id.getPath().matches("[a-z0-9_-]{1,32}") ? id.toString() : null;
    }

    interface OwnedVerseLookup {
        boolean isOwned(String id);
        boolean isReady(String id);
    }
}
