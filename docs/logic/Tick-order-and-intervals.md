# Tick order and intervals

All intervals below are expressed in **server ticks** (20 ticks ≈ 1 second). Constants live in `VoidClamModEntry` or `VoidClamMod` unless noted.

## `END_SERVER_TICK` callback (`VoidClamModEntry.onServerTick`)

Strict **phase order** on every server tick (earlier phases always complete before later ones on the same tick):

1. **`VoidClamMod.tickSeekEphemeralExpiry(server)`** — Per-clam unload timers that eventually clear in-memory seek caches / path bookmarks when the **heart chunk stays unloaded** (all dimensions).
2. **`VoidClamMod.drainPendingLightCacheDeltas()`** — Apply batched light-cache updates queued from block changes (must run before per-world clam logic that reads caches).
3. **Ice dormancy (instant):** For each world, **`VoidClamMod.cancelActivePathfindingForFullyIceEncasedClams(w)`** clears sync A* jobs, **`mainCycleBusy`**, and **`targets`** for any clam whose heart is fully ice-encased — **before** sync A* so stepped jobs do not run one tick late.
4. **Optional — sync A***: **`Pathfinder.tickSyncAStarJobs(...)`** when config `astar_mode` is `sync_batched`. Runs **before** per-world clam ticks so stepped jobs make progress in a fixed place in the frame.
5. **For each `ServerWorld` `w`** (iteration order follows `server.getWorlds()`):
   - **`VoidClamMod.tickLoadedClamCores(w)`** — Only clams whose **heart chunk is loaded**. Registry link via mixin on furnace tick; sync heart NBT; cache rebuild slices; fuel wake; auto-grow schedule; phased **`clamReach`**, core check, heartbeat, defense.
   - **`VoidClamMod.tickGrowPendingCheck(w)`** — May complete a pending grow/repair when **idle** (see locks below).
   - **`TendrilPulseManager.tick(w)`** — In-world pulse display jobs for that world.
   - **`VoidClamModScheduler.tick(w)`** — Due delayed tasks scheduled with **this** `ServerWorld` reference (`runAtTick <= world.getTime()`).
   - **`TendrilPulseManager.tickOmniPulseJob(w)`** — Incremental omnidirectional pulse BFS batching for that world.
6. **`VoidClamMod.tickTargets(server)`** — Drain the global **`targets`** queue (FIFO) and run **`Pathfinder.buildPath`** for each node (may enqueue delayed steps on specific worlds).
7. **`VoidClamMod.tickOrphanedClamActivityWarnings(server)`** — Throttled **author-facing `WARN` log** if a registered clam still has path/grow/cache activity while its heart is **not tickable** (dimension unloaded, heart chunk unloaded, or core block missing/wrong at the recorded center).

Then **gated** by overworld time (`ServerWorld` overworld, or first world as fallback):

| Interval constant | Ticks | What runs |
|-------------------|-------|-----------|
| `TICK_OMNI_PULSE` (100) | 5 s | `TendrilPulseManager.runOmnidirectionalPulse(w)` for **each** world |
| `TICK_CLEANUP` (1200) | 1 min | `TendrilPulseManager.cleanupStrayDisplays(w)` for every world |

**Why order matters:** `tickLoadedClamCores` can enqueue scheduler work and path targets; **`tickTargets` runs after all worlds** have advanced pulse/scheduler state for that tick, so path consumption is **global per tick**, not interleaved per world. **`tickGrowPendingCheck`** runs **before** `tickTargets`, so it sees **`targets`** and **`mainCycleBusy`** as left by the *previous* tick’s drain (this tick’s `tickTargets` has not run yet).

## Priority and locks (behavioral deadlocks)

There is **no cross-clam priority queue**. Ordering within a tick is **phase order** (above) plus **FIFO** `targets` and **time-ordered** `VoidClamModScheduler` tasks. The main **per-clam lock** is **`mainCycleBusy`**: while non-zero, **`clamReach` / `calculatePath`** will not start another cycle for that clam.

| Mechanism | What it blocks |
|-----------|----------------|
| **`mainCycleBusy`** | New reach/path cycle; held from reach through path apply (and container routing / path-stopped waits — see [[Pathfinding-and-reach]]). |
| **`targets` non-empty** (for the pending grow/repair clam id) | **`tickGrowPendingCheck`** will not finish grow/repair until that clam has no queued path ends. |
| **`VoidClamMod.isResizeShellAnimationPending(world)`** | Grow/repair waits for **`clamReSize`** shell steps scheduled via **`scheduleResizeShellDelayed`**, not all scheduler tasks. |
| **Coordinated kill barrier** | **`CommandToolbox.submitPathfinding`** rejects; workers abort via `shouldAbortAsyncPathfindingWork`. |

**Idle before grow/repair** (`tickGrowPendingCheck`, same dimension as `growPendingWorld`): **`mainCycleBusy == 0`** for the command target (or clam removed), **`countTargetsQueuedForClam(cmdId) == 0`**, **`!isResizeShellAnimationPending(world)`**, and **`!asyncPathfindingKillBarrierInEffect`**. It does **not** consult **`VoidClamModScheduler.hasPendingTasks`**. See [[Threading-queues-locks]] for **`mainCycleBusy`** transitions and [[Grow-repair-and-energy]] for the user-visible grow/repair pipeline.

## Heart block entity (clam core)

The clam **heart** is a **blast furnace** block (`VoidClamCoreBlocks.CORE_BLOCK`). **`AbstractFurnaceBlockEntityMixin`** runs at the end of the vanilla furnace **`tick`** when the block is the clam core: it calls **`tryRegisterFromClamCoreBlockEntity`** and keeps **`LIT`** in sync with **`Clam#status`**. Reach, defense, heartbeat, and auto-grow deadlines are driven from **`tickLoadedClamCores`**, not from a custom block entity class.

## Design notes for ports

- **`VoidClamModScheduler`** tasks are matched by **`ServerWorld` reference** in `tick(world)`. Schedule and tick with the same world instance for a dimension.
- **`tickTargets`** uses the **`MinecraftServer`** to run `buildPath`; path steps may schedule work on specific worlds.

## Related notes

- [[Technical-documentation]]
- [[Configuration]] — `astar_mode`, `bfs_mode`, intervals, caches.
- [[Seek-caches-and-block-deltas]] — delta drain phase relative to cores.
- [[Threading-queues-locks]]
- [[Pathfinding-and-reach]]
- [[Grow-repair-and-energy]]
- [[State-and-save]]
