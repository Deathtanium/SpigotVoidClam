# Threading, queues, and locks

## Threads

1. **Server main thread** — All `ServerTickEvents`, command handlers, `buildPath` scheduled steps that call `world.setBlockState`, and `world.getServer().execute(...)` (container apply after off-thread BFS) run here.
2. **Pathfinder executor** — Fixed pool of **2** threads (package-private in `CommandToolbox`, recreated after each server session). Used for:
   - `clamReach` scan + `Pathfinder.calculatePath` (world reads from worker thread),
   - Off-thread **container BFS** on the live world (`Pathfinder.runContainerBfsOnWorld` — no separate snapshot map).

**Caveat:** Vanilla `ServerWorld` is not documented as safe for arbitrary concurrent reads; the design matches historical behavior. Safer ports may snapshot chunk sections or confine reads to the main thread.

## Async pathfinding abort conditions

Work tied to a specific clam stops when **any** of: the server is stopping; a **coordinated clam kill** has marked this clam’s **UUID** as victim; that clam’s **center chunk is unloaded** (`world.isChunkLoaded(cx >> 4, cz >> 4)`).

- **Combined API**: `VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ, pathfindingClamId)` — pass the path job’s `clamId` for kill matching. When the job’s clam is resolved and its heart chunk is loaded, abort also applies if `!VoidClamMod.isSearingHeartThermallyActive` (ice dormancy, `status != 1`, etc.), not ice-only.
- **Shutdown flag**: `isAsyncPathfindingShutdownRequested()` — volatile; when `true`, worker tasks must **stop quickly**, **not** call `enqueueTarget`, and **not** schedule main-thread work from pathfinding.
- **Kill barrier**: `isAsyncPathfindingKillBarrierInEffect()` — while `true`, `submitPathfinding` **rejects without queuing** (runs `onAbortedBeforeRun` immediately). Used so no new async tasks are created during a kill drain. `asyncPathfindingKillVictimClamId` identifies which clam is aborted cooperatively in workers.
- **Set (shutdown)**: `VoidClamMod.onAsyncPathfindingSessionStop()` at `SERVER_STOPPING` (before save). Clears kill barrier state, sets the shutdown flag, **shuts down and awaits** the pathfinder executor, **replaces** the pool, and clears every clam’s `mainCycleBusy`.
- **Cleared (shutdown)**: `VoidClamMod.onAsyncPathfindingSessionStart()` at `SERVER_STARTED` (before `load`).
- **`submitPathfinding`**: `submitPathfinding(world, cx, cz, pathfindingClamId, onAbortedBeforeRun, task)`. Rejects if kill barrier **or** shutdown (no `execute`). Otherwise, if abort before the body, runs `onAbortedBeforeRun` (clears `mainCycleBusy` / `pathStoppedAwaitingContainer` where needed).
- **Where abort is polled**:
  - `CommandToolbox.submitPathfinding` (reject vs before task body),
  - `clamReach` (before submit; periodic checks in the light/ore scan),
  - `Pathfinder.calculatePath` (A* loop + goal enqueue guard),
  - `Pathfinder.isGoalReachableByPrepass` / `runContainerBfsOnWorld` via `BlockBfs.AbortChecker`,
  - Main-thread `applyContainerResult` path after container BFS (via `world.getServer().execute`): skips applying if abort is true.
- **`mainCycleBusy` on interrupt**: Aborted paths clear the flag via `onAbortedBeforeRun`, A* early exit, `calculatePath` failure, or container callback guards. Kill starts by clearing the victim’s flag and purging that slot from `targets` to narrow enqueue races.

## Coordinated clam kill (`clamKill` / `clamKillBlocking`)

`/voidclam kill` and automatic core death call `VoidClamMod.clamKill(server, clamId, saveAfter)` (alias for `clamKillBlocking`). Sequence:

1. **Queue** the request (serialized with `asyncKillCoordinatorLock`).
2. **Start drain**: clear victim `mainCycleBusy`, **purge `targets`** entries for that `clamId`, set victim UUID + kill barrier.
3. **Background thread** runs `shutdownPathfinderExecutorAfterKillDrain()` (same await/replace as server stop) so the server thread is not blocked while workers may call `server.execute`.
4. **Server thread** (`server.execute`): **purge `targets`**, clear grow-pending if it was for this victim, remove clam from registry, clear victim/barrier, optional legacy `save`, then start the next queued kill if any.

**Stale scheduled path steps:** `Pathfinder.buildPath` records origin `(x,y,z)` and `clamId`; each `scheduleDelayed` step checks `clamMatchesAt(clamId, …)` so work does not run against the wrong clam after kills or moves.

## `Clam` mutation from pathfinding workers

`clamReach` runs on the pathfinder pool and reads/writes the same **`Clam`** instance the main thread uses (`mainCycleBusy`, blacklists). Ordering is relied on so the busy flag is not corrupted in normal operation; blacklists are also cleared on grow/repair. This matches historical risk; a stricter port could confine `Clam` mutation to the main thread only.

**Porting:** New off-thread work must respect `shouldAbortAsyncPathfindingWork` and the kill barrier; new kill paths should use the same coordinator or extend it.

## `targets` queue

- **Producer**: `Pathfinder.calculatePath` (worker thread) calls `VoidClamMod.enqueueTarget(goalNode)` when the goal is reached.
- **Consumer**: Main thread `VoidClamMod.tickTargets` polls until empty and calls `Pathfinder.buildPath(world, node)`.
- **Thread safety**: `ConcurrentLinkedQueue`.

## `VoidClamModScheduler`

- **Schedule**: `schedule(world, delayTicks, runnable)` stores `runAtTick = world.getTime() + delayTicks` at schedule time.
- **Tick**: `tick(world)` collects tasks where `t.world == world` (reference equality) and `now >= runAtTick`, removes them, sorts by `runAtTick`, runs in order.
- **`hasPendingTasks(world)`**: Uses **`world.getRegistryKey()`** (dimension), not reference equality — so it can report pending work for a dimension even if the tick uses a different `ServerWorld` reference than the one used when scheduling.

**Porting note:** If delayed tasks are scheduled with per-dimension worlds, ensure the tick passes the same world reference used for scheduling, or change `tick` to match on registry key like `hasPendingTasks`.

## `mainCycleBusy`

Per-clam **binary lock** (0 = idle, non-zero = busy) for the **reach → pathfind → apply path** cycle.

| Phase | Set / clear |
|--------|-------------|
| `clamReach` start | Set to `1` if was `0`; if already busy, return immediately |
| No target after scan | Cleared to `0` on worker thread |
| `calculatePath` fails (no path) | Cleared to `0` at end of search |
| `calculatePath` succeeds | Left at `1` until `buildPath` finishes or aborts |
| `buildPath` | Cleared on early exit (bad cost, unloaded chunk, seek flags off, stamina blocked, final light/empty goal step) or **after** container apply for ore / path-stopped block breaks (see `pathStoppedAwaitingContainer` in `Pathfinder`) |

**Grow/repair idle check** (`tickGrowPendingCheck`): Waits until `mainCycleBusy == 0` for the command target clam, heart **chunk loaded**, **`countTargetsQueuedForClam(cmdId) == 0`**, and **`!isResizeShellAnimationPending(world)`** (same dimension as `growPendingWorld`) before running **`clamReSize`** / auto routine. It does **not** use **`hasPendingTasks`**. After **`runAutoGrowRoutineSingle`**, **`nextAutoGrowRepairWorldTime`** is set from **`world.getTime() + autoGrowRepairIntervalTicks()`**.

**Reach while grow queued:** **`clamReach`** returns immediately when **`isGrowRepairPendingForClam`** is true (auto or `/voidclam` grow/repair), matching “no reach during repair/grow cycle.”

## `busyFlagPlaceEvent`

Present on `Clam` but **unused** in current sources — document any future use here when implemented.

## Priority between operations

There is no explicit priority queue between clams. **Order of effects** comes from:

1. Tick phase order ([[Tick-order-and-intervals]]).
2. **FIFO** drain of `targets` in `tickTargets`.
3. **Sorted** execution of due delayed tasks by `runAtTick` in `VoidClamModScheduler.tick`.
4. **`buildPath`** schedules steps along the path with **decreasing** delay timers walking from goal toward start so execution order tends **start → goal** when tasks run.

## Related notes

- [[Technical-documentation]]
- [[Pathfinding-and-reach]]
- [[Grow-repair-and-energy]]
