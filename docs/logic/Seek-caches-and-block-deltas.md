# Seek caches and block deltas

Runtime **light** and **ore** seek caches live on **`Clam`** (`lightsCache`, `oresCache` as `Set<Long>` packed positions). They are **not** persisted (see [[Persistence-and-schema]]). Behavior depends on **`VoidClamConfig.seek_target_cache`** (shared switch for light + ore).

## When caches are maintained

- If **`seek_target_cache`** is effectively `false` (“live” mode), **`startLightCacheRebuild` / `startOreCacheRebuild`** no-op; **`tick*RebuildStep`** clears counters; **`clamReach`** uses full box scans per run (see [[Pathfinding-and-reach]]).
- If `true`, **`clamReach`** reads from the sets; caches are filled by **batched rebuild** and **block-change deltas**.

## Batched rebuild

Constants in **`VoidClamMod`**:

- **`LIGHT_CACHE_REBUILD_TICKS`** = **100** — number of server ticks to spread a full volume scan.

**Start:** **`startLightCacheRebuild`** / **`startOreCacheRebuild`** clear the set, set `*RebuildTicksRemaining = 100`, `*RebuildCursor = 0`.

**Volume:** Same **AXIS-ALIGNED BOX** as reach: half-extent **`4 * max(1, currentSize)`** on X, Y, Z from heart (`lightSeekHalfExtent`, `lightSeekScanVolume`).

**Per-tick work (`tickLightCacheRebuildStep` / `tickOreCacheRebuildStep`):**

- If heart chunk unloaded → return without consuming tick (ore/light steps bail early where coded).
- **`isPathfindingAllowedYet`** must be true (resize/path cooldown) — rebuild **pauses** during forbidden window.
- Let `total = volume`, `cursor` = linear index, `ticksLeft` = remaining rebuild ticks, `remaining = total - cursor`.
- Process **`toProcess = ceil(remaining / ticksLeft)`** cells this tick (fair spread across remaining ticks).
- **Linear indexing:** `span = 2*e+1`, `layer = span*span`; for `cursor`:  
  `xi = cursor / layer`, `rem = cursor % layer`, `yi = rem / span`, `zi = rem % span`; world block = `(m.x - e + xi, m.y - e + yi, m.z - e + zi)`.
- Skip cells in **unloaded** chunks (continue; cursor still advances in inner loop).
- If block is light/ore per **`isLight`** / **`isOre`**, add `BlockPos.asLong()` to cache.

## Block-change deltas

**Injection:** **`WorldLightCacheMixin`** on **`World.setBlockState`**: **HEAD** pushes prior `getBlockState(pos)` on a thread-local deque; **RETURN** on success pops old state and calls **`VoidClamMod.enqueueLightCacheDeltaFromBlockChange(serverWorld, pos, oldState, newState)`**.

**Enqueue filter:** Only if old or new block is classified as light **or** ore **and** the corresponding cache mode is enabled.

**Drain:** **`VoidClamMod.drainPendingLightCacheDeltas`** runs early each server tick (**before** per-world clam logic per [[Tick-order-and-intervals]]). Budget **16384** deltas per drain pass.

**Apply (`applyLightCacheDelta` / `applyOreCacheDelta`):** For **each registered clam** in the **same dimension** as the delta, heart chunk loaded, core block present, position inside that clam’s seek box:

- Transitions **light added/removed** update `lightsCache` and blacklist/goal fields as appropriate.
- Ore analog for `oresCache`.
- **`ensureLightSeekCacheForIncomingDelta`** / **`ensureOreSeekCacheForIncomingDelta`**: if seek flag on, cache empty, no rebuild in progress → **start full rebuild** so deltas are not the sole source forever.

**Why queued:** Avoids scanning all clams inside **`setBlockState`** (beacon/pyramid updates).

## Unload expiry

Heart chunk unloaded: **`tickSeekEphemeralExpiry`** arms a dimension **`world.getTime()`** deadline; when it fires, **`clearSeekCachesAndBlacklistsAfterChunkUnloadExpiry`** clears ephemeral path/seek state. Reload may set **`seekEphemeralNeedSeekDataRefresh`** to restart rebuilds when chunk loads (see `tickLoadedClamCores`).

## Related notes

- [[Pathfinding-and-reach]]
- [[Configuration]]
- [[State-and-save]]
- [[Loader-integration]]
- [[Tick-order-and-intervals]]
- [[Technical-documentation]]
