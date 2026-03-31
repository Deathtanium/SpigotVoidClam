# Key classes and functions

Quick index: **what to read** when preserving behavior. Package: `com.serbanstein.voidclam`.

## `VoidClamModEntry`

| Symbol | Role |
|--------|------|
| `onInitialize` | Register server start/stop, end tick, commands |
| `onServerTick` | Encodes tick order (sync A*, per-world cores → grow check → pulses → scheduler → `tickTargets` → orphan activity `WARN`) |
| `registerCommands` | Brigadier `/voidclam` tree |

## `VoidClamMod`

| Symbol | Role |
|--------|------|
| `clamsById`, `getClamById`, `findClamAt`, `getAllClams` | Runtime clam registry |
| `makeStub`, `clamKill(server, clamId, saveAfter)` | Create clam / coordinated kill (async drain) |
| `migrateLoadedClamsToHeartBlocks` | Migration toward heart-block persistence |
| `tryRegisterFromClamCoreBlockEntity` | Map heart blast furnace BE → runtime `Clam` on load (mixin + tick) |
| `syncClamCoreBlockEntityFromClam`, `SearingHeartItems.syncClamToBlockEntity` | Heart NBT ↔ `Clam` |
| `tickLoadedClamCores` | Per-dimension tick: sync NBT, caches, reach, auto-grow, heartbeat, defense, core check |
| `tickOrphanedClamActivityWarnings`, `clamHasResidualPathfindingOrGrowActivity`, `isHeartSurfaceLoadedWithCoreBlock` | Author `WARN` if mod work is stuck with no tickable heart |
| `enqueueTarget`, `tickTargets`, `isTargetsQueueEmpty` | Path result queue |
| `requestGrowCommand`, `requestRepairCommand`, `tickGrowPendingCheck` | Safe grow/repair (single-clam seek snapshot) |
| `tryScheduleAutoGrowRepairForClam`, `runAutoGrowRoutineSingle` | Per-heart auto repair/grow |
| `ensureAutoGrowScheduled`, `seedAutoGrowScheduleForAllClams` | Staggered auto-grow deadlines |
| `tickCoreCheck` | Integrity kill (legacy / global) |
| `tickCoreCheckAtHeart` | Per-heart integrity kill from `tickLoadedClamCores` |
| `tickDefenseForClam`, `tickHeartbeatForClam` | Effects (from `tickLoadedClamCores` when phased) |
| `scheduleDelayed` | Delegates to scheduler |
| `isLight`, `isOre`, `isBaseCost` | Block categorization |
| Blacklist / energy helpers | Path retry behavior |

## `VoidClamModScheduler`

| Symbol | Role |
|--------|------|
| `schedule` | Delay from current `world.getTime()` |
| `tick` | Run due tasks for that world reference |
| `hasPendingTasks` | Dimension-keyed pending check |

## `CommandToolbox`

| Symbol | Role |
|--------|------|
| `pathfinderExecutor`, `submitPathfinding` | Off-thread work; kill barrier / unload checks |
| `buildStub` | Initial shape with staggered wart/obsidian |
| `buildShell` | Octahedral shell of arbitrary material |
| `clamReSize` | Animated resize/repair |
| `clamReach` | Scan + start pathfind |
| `isInsideOctahedronInterior`, `isPlayerInsideOctahedron` | Defense geometry |

## `Pathfinder`

| Symbol | Role |
|--------|------|
| `calculatePath` | A\*; enqueue or clear busy |
| `tickSyncAStarJobs`, `enqueueSyncAStarJob`, `hasSyncAStarWorkForClam`, `clearSyncAStarJobsForClam` | Sync-batched A* stepping |
| `buildPath` | Schedule placement along path, stamina, drops |
| `getFortune3Drops` | Ore rewards |
| Container helpers | BFS, insert, barrel fallback |

## `Clam`, `Node`, `Cursor`

Data carriers for runtime state and A\* graph.

## `TendrilPulseManager`

Pulse entities, omni job, cleanup commands, sky brightness helper for displays.

## Technical cross-reference

| Note | Use when |
|------|----------|
| [[Technical-documentation]] | Porter entry, full reading order |
| [[Persistence-and-schema]] | Exact `module` / component persistence |
| [[Loader-integration]] | Mixin targets and Fabric events |
| [[Seek-caches-and-block-deltas]] | Rebuild math and delta queue |

## Related notes

- [[Overview]]
- [[Threading-queues-locks]]
- [[Hivemind-future]]
