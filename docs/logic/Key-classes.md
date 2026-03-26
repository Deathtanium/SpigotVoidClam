# Key classes and functions

Quick index: **what to read** when preserving behavior. Package: `com.serbanstein.voidclam`.

## `VoidClamModEntry`

| Symbol | Role |
|--------|------|
| `onInitialize` | Register server start/stop, end tick, commands |
| `onServerTick` | Encodes tick order and intervals |
| `registerCommands` | Brigadier `/voidclam` tree |

## `VoidClamMod`

| Symbol | Role |
|--------|------|
| `load` / `save` | CSV `modules.siva` + rotation |
| `makeStub`, `clamKill(server, tno, saveAfter)` | Create module / coordinated kill (async drain then shift) |
| `enqueueTarget`, `tickTargets`, `isTargetsQueueEmpty` | Path result queue |
| `requestGrowCommand`, `requestRepairCommand`, `tickGrowPendingCheck`, `tickAutoRepairAndGrow`, `runGrowRoutine` | Safe grow/repair sequencing |
| `tickCoreCheck` | Integrity kill |
| `tickDefense`, `tickHeartbeat` | Effects |
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
| `pathfinderExecutor`, `submitPathfinding(world, cx, cz, tno, onAbort, task)` | Off-thread work; reject if kill barrier/shutdown; else abort if victim/unload/shutdown |
| `buildStub` | Initial shape with staggered wart/obsidian |
| `buildShell` | Octahedral shell of arbitrary material |
| `clamReSize` | Animated resize/repair |
| `clamReach` | Scan + start pathfind |
| `isInsideOctahedronInterior`, `isPlayerInsideOctahedron` | Defense geometry |

## `Pathfinder`

| Symbol | Role |
|--------|------|
| `calculatePath` | A\*; enqueue or clear busy |
| `buildPath` | Schedule placement along path, stamina, drops |
| `getFortune3Drops` | Ore rewards |
| Container helpers | Snapshot, BFS, insert, barrel fallback |

## `Module`, `Node`, `Cursor`

Data carriers for persisted state and A\* graph.

## `TendrilPulseManager`

Pulse entities, omni job, cleanup commands, sky brightness helper for displays.

## Related notes

- [[Overview]]
- [[Threading-queues-locks]]
