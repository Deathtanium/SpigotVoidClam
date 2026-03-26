# Hivemind / inter-clam coordination (future)

## Problem sketch

Today each voidclam is identified by `Module#clamId` and keyed in `VoidClamMod`’s runtime map. **WorldEdit**, structure paste, or duplicated heart blocks can create **two blocks claiming the same UUID** or **two UUIDs at one position**. The linker (`ensureRuntimeModuleForHeart`) applies local rules (evict conflicting entries); there is **no** cross-clam protocol to reconcile “which copy is canonical” or to merge state.

## Direction (not implemented)

A **hivemind** layer could:

- Maintain a registry of active `clamId`s with optional **leader** or **shard** roles.
- Let hearts **query neighbors** (same dimension, within N blocks) before registering.
- Surface conflicts to operators (log, particles, command) instead of silent eviction.

No API exists yet; treat this file as a **design placeholder** when extending multiplayer or automation tooling.

## Related

- [[State-and-save]]
- `VoidClamMod.ensureRuntimeModuleForHeart`
