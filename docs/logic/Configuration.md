# Configuration (`voidclam.json`)

Authoritative schema: `VoidClamConfig.java` (Gson loads **`config/voidclam.json`** under the server’s Fabric config directory). The file is created with defaults if missing. Keys use **`snake_case`**.

## Natural spawn

| Key | Meaning |
|-----|---------|
| `clam_spawn_natural` | If `true`, try to place clams in newly generated chunks. |
| `clam_spawn_natural_method` | `"default"` (overworld chunk roll) or `"dungeon"` (mob spawner replacement). |
| `clam_spawn_natural_dungeon_rate` | Per-spawner chance when method is dungeon (0–1). |
| `clam_spawn_natural_default_chunk_chance` | Per overworld chunk for default method (0–1). |

## New-clam defaults

| Key | Meaning |
|-----|---------|
| `clam_light_flag_default` | Default `seekLights` for new clams. |
| `clam_ores_flag_default` | Default `seekOres`. |
| `clam_protect_itself_default` | Default defense flag. |

## Pathfinding and BFS scheduling

| Key | Meaning |
|-----|---------|
| `astar_mode` | `async` (worker pool) or `sync_batched` (main-thread stepped A* via `Pathfinder.tickSyncAStarJobs`). |
| `astar_sync_global_max_steps_per_tick` | Sync mode: budget numerator before ÷4 (and ÷5 when auto). `0` = estimate from host CPU MHz. |
| `astar_async_global_max_threads` | Async pool size; `0` = max(2, availableProcessors). |
| `astar_sync_max_total_expansions_per_job` | Cap on prepass + A* expansions per sync job; `null`/omitted ≈ 400k; `-1` = uncapped (dangerous). |
| `bfs_mode` | `sync_batched` vs `async`: how heavy BFS (omni pulse graph, storage container discovery) runs. Does **not** change prepass threading relative to A* on one worker. |
| `pathfind_chunk_cache` | `true` (default): per-job `PathfindChunkCache` snapshot. `false`: live `getBlockState` only (legacy/debug). |
| `seek_target_cache` | `true` (default): maintain `Clam` light/ore seek caches + block deltas. `false`: full box rescan each `clamReach` (still follows `astar_mode` for threading). Legacy `light_block_cache` / `ore_block_cache` migrate into this. |

## Size, economy, cadence

| Key | Meaning |
|-----|---------|
| `clam_size_max` | Upper bound on shell size. |
| `clam_grow_energymultiplier` | Natural grow only when `energy > multiplier * currentSize` (energy per light feed ≈ 1). |
| `clam_repair_grow_cycle_interval_seconds` | Base period for auto repair/grow scheduling (world time). |
| `clam_seek_attempt_interval_seconds` | How often an awake clam may start a seek attempt (staggered per clam). |
| `clam_seek_attempt_probability` | After each seek interval, probability in `0..1` that `clamReach` actually runs (`1` = always). |
| `clam_defense_detection_interval_seconds` | Player-defense check cadence (keep ≥ horn length). |
| `clam_material_seek_threshold` | Ore-seeking hunger when `material` is below this. |
| `clam_ore_detect_with_c_ores_tag` | When `true`, blocks in `c:ores` count as ores in addition to the mod’s built-in vanilla list. |

## Presentation

| Key | Meaning |
|-----|---------|
| `vfx_enabled` | Tendril / pulse visuals path. |
| `sfx_volume_multiplier` | Sound gain scale (≥ 0). |

## Related notes

- [[Technical-documentation]]
- [[Tick-order-and-intervals]] — when config-driven steps run each tick.
- [[Threading-queues-locks]] — how `astar_mode` and `bfs_mode` map to executors.
- [[Pathfinding-and-reach]] — cache and path behavior in gameplay terms.
- [[Seek-caches-and-block-deltas]] — `seek_target_cache` behavior.
- [[Natural-spawn]] — natural spawn config keys in gameplay context.
