package io.github.brooswitminecraft.dynamicverse.lifecycle;

@FunctionalInterface
public interface VerseCompletionSink<T> {
    void complete(T result);
}
