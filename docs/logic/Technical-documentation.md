# Technical documentation (index)

This note is the **entry point for implementers and porters** who need bytecode-accurate detail: save format, Fabric hooks, cache algorithms, and verification. Behavioral “why” and game rules stay in the other `docs/logic/` notes; this layer ties them to **current** classes and data shapes.

## Reading order

| Order | Note | Purpose |
|-------|------|---------|
| 1 | [[Overview]] | Nouns and control flow |
| 2 | [[Configuration]] | `voidclam.json` |
| 3 | [[Persistence-and-schema]] | Heart/item NBT, what is **not** saved |
| 4 | [[Loader-integration]] | Mixins, Fabric events, mod metadata |
| 5 | [[Natural-spawn]] | Chunk-gen spawn methods |
| 6 | [[Seek-caches-and-block-deltas]] | Incremental caches + `setBlockState` hook |
| 6b | [[Performance-and-abuse-considerations]] | Scale, lag factors, config mitigations |
| 7 | [[Tick-order-and-intervals]] | Phases and locks |
| 8 | [[Threading-queues-locks]] | Executor, kill barrier, busy flag |
| 9 | [[Pathfinding-and-reach]] | A\*, `buildPath`, containers |
| 10 | [[Grow-repair-and-energy]] | Safe grow/repair, energy |
| 10b | [[Resources-and-caps]] | `energy` / `material` / `soul` caps and gains |
| 11 | [[Verification-and-porting]] | Regression-style checks, version pin |

## Source of truth

- **Java**: `src/main/java/com/serbanstein/voidclam/`
- **Resources**: `src/main/resources/` (`fabric.mod.json`, `voidclam.mixins.json`)
- **Minecraft / Fabric versions**: see `fabric.mod.json` `depends` (not duplicated here so one file stays authoritative)

## Related

- [[State-and-save]] — registry vs heart (conceptual); **schema detail** is in [[Persistence-and-schema]]
- [[Natural-spawn]] — worldgen integration
- [[Verification-and-porting]] — smoke tests after changes
- [[Key-classes]] — class → responsibility map
