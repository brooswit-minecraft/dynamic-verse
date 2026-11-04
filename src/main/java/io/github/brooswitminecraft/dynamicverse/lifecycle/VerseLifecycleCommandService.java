package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** Lifecycle command boundary. Admission is deliberately distinct from a terminal outcome. */
public interface VerseLifecycleCommandService {
    DuplicateAdmission beginDuplicate(String source, String destination, Consumer<DuplicateOutcome> completion);
    CreateAdmission beginCreate(String name, CreateType type, OptionalLong requestedSeed, Consumer<CreateOutcome> completion);
    /**
     * Delete is deliberately admission-only until the provider can prove a
     * forced save-and-close before any owned data is removed.
     */
    DeleteAdmission beginDelete(String name);
    /** Called only by the explicit /verse delete <name> confirm command. */
    default DeleteAdmission beginConfirmedDelete(String name, Consumer<DeleteOutcome> completion) {
        return DeleteAdmission.ERR_SAFE_DELETE_UNAVAILABLE;
    }
    List<ManagedVerseListing> listManaged();

    enum DuplicateAdmission { ADMITTED, ERR_VERSE_NOT_FOUND, ERR_VERSE_ALREADY_EXISTS, ERR_VERSE_BUSY, ERR_PROVIDER_UNAVAILABLE, ERR_PER_VERSE_SEED_UNSUPPORTED }
    enum DuplicateOutcome { LOADED, RESTORED, QUARANTINED }
    enum CreateType { NORMAL, SUPERFLAT }
    enum CreateAdmission { ADMITTED, ERR_PER_VERSE_SEED_UNSUPPORTED, ERR_INVALID_NAME_OR_TYPE, ERR_VERSE_ALREADY_EXISTS, ERR_VERSE_BUSY, ERR_PROVIDER_UNAVAILABLE }
    enum CreateOutcome { READY, QUARANTINED }
    enum DeleteAdmission { ADMITTED, ERR_VERSE_NOT_MANAGED, ERR_VERSE_NOT_READY, ERR_SAFE_DELETE_UNAVAILABLE,
        ERR_CONFIRMATION_REQUIRED, ERR_PLAYERS_PRESENT, ERR_VERSE_BUSY, ERR_PROVIDER_UNAVAILABLE }
    enum DeleteOutcome { DELETED, QUARANTINED }
}
