package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.List;
import java.util.function.Consumer;

/** Lifecycle command boundary. Admission is deliberately distinct from a terminal outcome. */
public interface VerseLifecycleCommandService {
    DuplicateAdmission beginDuplicate(String source, String destination, Consumer<DuplicateOutcome> completion);
    List<ManagedVerseListing> listManaged();

    enum DuplicateAdmission { ADMITTED, ERR_VERSE_NOT_FOUND, ERR_VERSE_ALREADY_EXISTS, ERR_VERSE_BUSY, ERR_PROVIDER_UNAVAILABLE }
    enum DuplicateOutcome { LOADED, RESTORED, QUARANTINED }
}
