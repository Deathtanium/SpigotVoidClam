# VoidClam — logic documentation (loader-agnostic)

This folder describes **game and simulation behavior** in terms any port can reuse: ticks, locks, queues, priorities, and why key functions exist. It is written for **humans** (e.g. in [Obsidian](https://obsidian.md)) and **coding agents**; links use Obsidian-style `[[wikilinks]]`.

**Technical reference (schema, mixins, caches, verification):** start at [[Technical-documentation]].

## Map

| Note | Topic |
|------|--------|
| [[Technical-documentation]] | Index and reading order for implementers / porters |
| [[Overview]] | What a clam is, high-level loop, main classes |
| [[Configuration]] | `config/voidclam.json` keys, pathfinding modes, cadence |
| [[Persistence-and-schema]] | `module` keys, components, migration, not-persisted fields |
| [[Loader-integration]] | Fabric metadata, mixins, events, vanilla block IDs |
| [[Natural-spawn]] | Chunk-generated spawn (`NaturalSpawnHandler`) |
| [[Seek-caches-and-block-deltas]] | Batched rebuild, `setBlockState` delta queue |
| [[State-and-save]] | `Clam` fields, heart NBT, grow-pending globals |
| [[Tick-order-and-intervals]] | Per-tick order, repeating intervals, which world drives what |
| [[Threading-queues-locks]] | Executor, `targets` queue, scheduler, `mainCycleBusy` |
| [[Pathfinding-and-reach]] | A*, light vs ore tie-break, `buildPath`, stamina |
| [[Grow-repair-and-energy]] | Safe grow/repair, per-heart auto routine, energy rules |
| [[Resources-and-caps]] | `energy` / `material` / `soul` caps and sources |
| [[Verification-and-porting]] | Smoke tests, save compatibility, logging |
| [[Hivemind-future]] | Future inter-clam coordination (duplicates, WorldEdit) |
| [[Presentation-and-effects]] | Tendril pulses, defense, heartbeat (behavioral summary) |
| [[Key-classes]] | File → responsibility cheat sheet |

## Conventions

- **Main thread** means the server tick thread that mutates the world.
- **Fabric class names** appear where they name the current implementation; the *behavior* described is what a port must preserve unless intentionally changed.

## Source of truth

Java under `src/main/java/com/serbanstein/voidclam/` and resources under `src/main/resources/`.
