package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

/**
 * Production caller boundary. The caller acquires this before copying and does
 * not release its source write/reload exclusion until the returned lease closes.
 */
@FunctionalInterface
public interface SourceWriteReloadFence {
    Lease retain(String sourceKey, long generation);

    interface Lease extends AutoCloseable {
        @Override void close();
    }
}
