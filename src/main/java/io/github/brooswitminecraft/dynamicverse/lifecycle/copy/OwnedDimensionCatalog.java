package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.HashMap;
import java.util.List;
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

    public synchronized boolean contains(String id) { return descriptors.containsKey(id); }
    public synchronized void unregister(String id) { descriptors.remove(id); }
    /** Roll back only the exact descriptor reserved by a known-no-effect admission. */
    public synchronized boolean unregisterIfSame(OwnedDimensionDescriptor descriptor) {
        if (descriptors.get(descriptor.id()) != descriptor) return false;
        descriptors.remove(descriptor.id());
        return true;
    }

    /** Snapshot only; catalog ownership never implies runtime readiness. */
    public synchronized List<OwnedDimensionDescriptor> descriptors() { return List.copyOf(descriptors.values()); }
}
