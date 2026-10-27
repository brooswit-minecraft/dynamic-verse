# Quarantine recovery protocol (design only)

Pinned provider source: DynamicDimensions `ecfbdf694d4f4c103404a77858d00c54a91b8020`.
The local close adapter may observe an `IOException` or unchecked exception from
`ServerLevel.close()`. The instance may already have released resources. It is
therefore removed from the active `levels` map and retained by identity in the
quarantine ledger. This is containment, not restoration.

The pinned provider exposes no reopen operation for a closed `ServerLevel` and
no transactional registry rollback. The legacy `unloadLevel` removes level,
stems, type, dynamic registry membership, and client state in separate steps;
its existing close failure path removes the active map entry in `finally`.
Reinserting the quarantined instance or clearing quarantine would consequently
claim readiness without a supported resource reopen and is prohibited.

## Required provider recovery operation

Any future recovery support must be a new tick-queued operation, for example
`beginRecoverQuarantined(key, generation, completion)`, with these phases:

1. At close admission, persist an immutable recovery descriptor: source key,
   operation generation, initially-loaded state, level stem/generator/type
   identity, dynamic properties, and pre-operation registry membership. Do not
   retain the old `ServerLevel` as a reopen candidate.
2. On a partial close or cleanup failure, atomically fence the key from travel,
   duplicate, delete, new load/unload, and snapshot work; retain the descriptor
   and the failure cause. The active map must not contain the uncertain level.
3. Recovery constructs a **new** `ServerLevel` from the descriptor and existing
   on-disk data through an explicit provider factory, then registers level stem,
   dimension type, dynamic membership, and client notification as one guarded
   recovery transaction. It must validate the same source generation before
   each mutation.
4. Only after the new level is registered, tick-ready, and identity revalidated
   may it emit `RECOVERED_READY`. If construction, save data, registration, or
   notification fails, emit terminal `FAILED_RECOVERY`, preserve quarantine, and
   leave copy/delete prohibited.
5. For initially-unloaded sources, recovery restores the unloaded descriptor
   state only; it must not create/load a replacement implicitly.

This requires a separately reviewed provider implementation and controlled
real-provider validation. Until then, `QUARANTINED` remains terminally
unavailable, with no source-restoration claim. Explicit per-verse seed remains
`ERR_PER_VERSE_SEED_UNSUPPORTED` and no copy/delete admission is opened.
