# Natural spawn

Optional world generation hooks: **`NaturalSpawnHandler`**, registered from **`VoidClamModEntry`** on **`ServerChunkEvents.CHUNK_GENERATE`**.

## Config gates

All off unless **`VoidClamConfig.clam_spawn_natural`** is `true`.
Per-world settings are selected from **`clam_spawn_natural_worlds`** using namespaced world ids (`namespace:path`,
for example `minecraft:overworld`), with overworld as fallback/default (see [[Configuration]]).

## Default method

1. One attempt per chunk key per session: **`spawnedChunks`** map (dimension hash + chunk long) dedupes.
2. Roll **`default_chunk_chance`** per new chunk (non-dungeon), with RNG mixed from **world seed + chunk coords + dimension salt**.
3. **`trySpawnAtChunkCenter`**: pick XZ near chunk center, **`WORLD_SURFACE_WG`** top Y, carve air **sphere** (`clearSphere`), then spawn a natural clam at target size.

## Dungeon method

**`scanChunkForSpawners`**: iterate chunk blocks; for each **`minecraft:spawner`**, roll per-world **`dungeon_rate`** (same seed+dimension salted RNG family); on success remove spawner, **`makeStub`** at that position, or restore spawner if registration fails.

## Session reset

**`NaturalSpawnHandler.clearForSessionEnd`** clears the chunk dedupe map; called from **`VoidClamMod`** during async pathfinding / session teardown (alongside executor shutdown) so a subsequent world load can roll spawn chances again.

## Related notes

- [[Configuration]]
- [[Loader-integration]]
- [[Technical-documentation]]
