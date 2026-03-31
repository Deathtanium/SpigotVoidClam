# Natural spawn

Optional world generation hooks: **`NaturalSpawnHandler`**, registered from **`VoidClamModEntry`** on **`ServerChunkEvents.CHUNK_GENERATE`**.

## Config gates

All off unless **`VoidClamConfig.clam_spawn_natural`** is `true`. Method selected by **`clam_spawn_natural_method`**: `default` or `dungeon` (see [[Configuration]]).

## Default method

1. One attempt per chunk key per session: **`spawnedChunks`** map (dimension hash + chunk long) dedupes.
2. Roll **`clam_spawn_natural_default_chunk_chance`** per new chunk (non-dungeon).
3. **`trySpawnAtChunkCenter`**: pick XZ near chunk center, **`WORLD_SURFACE_WG`** top Y, carve air **sphere** (`clearSphere`), then **`VoidClamMod.makeStub`**.

## Dungeon method

**`scanChunkForSpawners`**: iterate chunk blocks; for each **`minecraft:spawner`**, roll **`clam_spawn_natural_dungeon_rate`**; on success remove spawner, **`makeStub`** at that position, or restore spawner if registration fails.

## Session reset

**`NaturalSpawnHandler.clearForSessionEnd`** clears the chunk dedupe map; called from **`VoidClamMod`** during async pathfinding / session teardown (alongside executor shutdown) so a subsequent world load can roll spawn chances again.

## Related notes

- [[Configuration]]
- [[Loader-integration]]
- [[Technical-documentation]]
