# Threading, queues, and locks

## Threads

1. **Server main thread** — All `ServerTickEvents`, command handlers, `buildPath` scheduled steps that call `world.setBlockState`, and `world.getServer().execute(...)` (container apply after off-thread BFS) run here.
2. **Pathfinder executor** — Fixed pool of **2** threads (package-private in `CommandToolbox`, recreated after each server session). Used for:
   - `clamReach` scan + `Pathfinder.calculatePath` (world reads from worker thread),
   - Off-thread **container BFS** on the live world (`Pathfinder.runContainerBfsOnWorld` — no separate snapshot map).

**Caveat:** Vanilla `ServerWorld` is not documented as safe for arbitrary concurrent reads; the design matches historical behavior. Safer ports may snapshot chunk sections or confine reads to the main thread.

## Async pathfinding abort conditions

Work tied to a specific module stops when **either** the server is stopping **or** that module’s **center chunk is unloaded** (same check as `isModuleInLoadedChunk`: `world.isChunkLoaded(cx >> 4, cz >> 4)` for the module’s X/Z).

- **Combined API**: `VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ)` — true when `isAsyncPathfindingShutdownRequested()` **or** the center chunk is not loaded.
- **Shutdown flag**: `isAsyncPathfindingShutdownRequested()` — volatile; when `true`, worker tasks must **stop quickly**, **not** call `enqueueTarget`, and **not** schedule main-thread work from pathfinding.
- **Set (shutdown)**: `VoidClamMod.onAsyncPathfindingSessionStop()` at `SERVER_STOPPING` (before save). It sets the flag, **shuts down and awaits** the pathfinder executor (with timeout + `shutdownNow`), **replaces** the pool for a possible next world in the same JVM, and clears every module’s `busyFlagMainCycle` so nothing stays “stuck busy” after skipped queue entries.
- **Cleared (shutdown)**: `VoidClamMod.onAsyncPathfindingSessionStart()` at `SERVER_STARTED` (before `load`).
- **`submitPathfinding`**: Takes `world`, clam center X/Z, `onAbortedBeforeRun`, and the task. If abort is true when the runnable would run, it invokes `onAbortedBeforeRun` (e.g. clear `busyFlagMainCycle` / `pathStoppedAwaitingContainer`) and returns without running the task body.
- **Where abort is polled**:
  - `CommandToolbox.submitPathfinding` (before task body),
  - `clamReach` (before submit; periodic checks in the light/ore scan),
  - `Pathfinder.calculatePath` (A* loop + goal enqueue guard),
  - `Pathfinder.isGoalReachableByPrepass` / `runContainerBfsOnWorld` via `BlockBfs.AbortChecker`,
  - Main-thread `applyContainerResult` path after container BFS (via `world.getServer().execute`): skips applying if the clam center chunk unloaded or shutdown.

**Porting:** Any new off-thread VoidClam work should use `shouldAbortAsyncPathfindingWork` (or equivalent) so work does not continue for an unloaded clam or after shutdown has begun.

## `targets` queue

- **Producer**: `Pathfinder.calculatePath` (worker thread) calls `VoidClamMod.enqueueTarget(goalNode)` when the goal is reached.
- **Consumer**: Main thread `VoidClamMod.tickTargets` polls until empty and calls `Pathfinder.buildPath(world, node)`.
- **Thread safety**: `ConcurrentLinkedQueue`.

## `VoidClamModScheduler`

- **Schedule**: `schedule(world, delayTicks, runnable)` stores `runAtTick = world.getTime() + delayTicks` at schedule time.
- **Tick**: `tick(world)` collects tasks where `t.world == world` (reference equality) and `now >= runAtTick`, removes them, sorts by `runAtTick`, runs in order.
- **`hasPendingTasks(world)`**: Uses **`world.getRegistryKey()`** (dimension), not reference equality — so it can report pending work for a dimension even if the tick uses a different `ServerWorld` reference than the one used when scheduling.

**Porting note:** If delayed tasks are scheduled with per-dimension worlds, ensure the tick passes the same world reference used for scheduling, or change `tick` to match on registry key like `hasPendingTasks`.

## `busyFlagMainCycle`

Per-module **binary lock** (0 = idle, non-zero = busy) for the **reach → pathfind → apply path** cycle.

| Phase | Set / clear |
|--------|-------------|
| `clamReach` start | Set to `1` if was `0`; if already busy, return immediately |
| No target after scan | Cleared to `0` on worker thread |
| `calculatePath` fails (no path) | Cleared to `0` at end of search |
| `calculatePath` succeeds | Left at `1` until `buildPath` finishes or aborts |
| `buildPath` | Cleared on early exit (bad cost, unloaded chunk, seek flags off, stamina blocked, final light/empty goal step) or **after** container apply for ore / path-stopped block breaks (see `pathStoppedAwaitingContainer` in `Pathfinder`) |

**Grow/repair idle check** (`tickGrowPendingCheck`): Waits until `busyFlagMainCycle == 0` for the relevant module(s), **`targets` is empty**, and **`!VoidClamModScheduler.hasPendingTasks(world)`** for that dimension before running resize logic.

## `busyFlagPlaceEvent`

Present on `Module` but **unused** in current sources — document any future use here when implemented.

## Priority between operations

There is no explicit priority queue between modules. **Order of effects** comes from:

1. Tick phase order ([[Tick-order-and-intervals]]).
2. **FIFO** drain of `targets` in `tickTargets`.
3. **Sorted** execution of due delayed tasks by `runAtTick` in `VoidClamModScheduler.tick`.
4. **`buildPath`** schedules steps along the path with **decreasing** delay timers walking from goal toward start so execution order tends **start → goal** when tasks run.

## Related notes

- [[Pathfinding-and-reach]]
- [[Grow-repair-and-energy]]
