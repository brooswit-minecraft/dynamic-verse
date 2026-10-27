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

## Resource and mutation ledger required before implementation

Pinned call sites establish why recovery cannot simply create another level:
`MinecraftServerMixin.unloadLevel` uses `try (ServerLevel level = ...)`, so
`ServerLevel.close()` owns resources that can fail after partial release;
`DynamicDimensionRegistryImpl.createDynamicLevel` builds a new level from the
storage source, level stem, and world data. A second construction while the old
resource ownership is uncertain could create concurrent writers for the same
dimension path. There is no pinned public close-state query, reopen method, or
atomic registry/client transaction.

The future recovery operation therefore needs a durable per-generation ledger:

| Phase | Mutation | failure handling |
| --- | --- | --- |
| `QUARANTINED` | old instance removed from active map; descriptor retained | do not close/reinsert it again; deny all lifecycle/data operations |
| `FRESH_INSTANCE` | construct a new level only after proving the old instance is no longer active and no storage handle is reusable | constructor failure closes any newly allocated resource, preserves quarantine, emits `FAILED_RECOVERY` |
| `STEM_TYPE_REGISTERED` | register stem/type | failure unregisters only entries created by this generation, closes fresh instance, retains quarantine |
| `MEMBER_REGISTERED` | add dynamic membership and active map, then establish one initial tick-ready state | failure removes this generation's map/member entries before close; no stale instance may tick |
| `CLIENT_NOTIFIED` | send add/update notifications per recipient with an acknowledgement/compensation ledger | packet failure is non-atomic: remove fresh active state, close it, send best-effort compensating remove to recipients already notified, retain quarantine and report `FAILED_RECOVERY` |
| `RECOVERED_READY` | publish only after identity/generation revalidation | any mismatch returns to quarantine; never report the old instance ready |

Required controlled tests before a recovery implementation is considered: each
failure phase above must leave no new active instance or registry entry, must
close the fresh instance exactly once, retain the admitted descriptor and
generation, and preserve initially-unloaded descriptors as unloaded. This is a
protocol specification only; it grants no runtime recovery operation.
