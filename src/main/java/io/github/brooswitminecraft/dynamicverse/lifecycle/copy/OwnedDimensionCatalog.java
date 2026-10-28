package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit application ownership boundary; unknown descriptors never reach provider or filesystem APIs. */
public final class OwnedDimensionCatalog {
    private final Map<String, OwnedDimensionDescriptor> descriptors = new HashMap<>();

    public synchronized void register(OwnedDimensionDescriptor descriptor) {
        Objects.requireNonNull(descriptor);
        if (descriptors.putIfAbsent(descriptor.id(), descriptor) != null) {
            throw new IllegalStateException("Descriptor is already owned: " + descriptor.id());
        }
    }

    public synchronized OwnedDimensionDescriptor require(String id) throws ClosedDimensionCopyException {
        OwnedDimensionDescriptor descriptor = descriptors.get(id);
        if (descriptor == null) throw new ClosedDimensionCopyException("Unsupported or unowned dimension descriptor: " + id);
        return descriptor;
    }
}
