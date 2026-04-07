# Performance and abuse considerations

Operational notes for large player-built voidclam farms and hostile builders trying to stress the server. **Configuration** (`[[Configuration]]`) is the primary throttle; defaults aim for single-digit clams per loaded area, not hundreds.

## Scale factors (per awake clam, loaded heart chunk)

| Work | Grows with | Notes |
|------|------------|--------|
| **`clamReach` interval** | Clam count × `clam_seek_attempt_interval_seconds` × `clam_seek_attempt_probability` | Each attempt may scan the seek box or iterate caches, then pathfind. |
| **A\*** / prepass | Worst-case graph in pathfinding AABB; `astar_sync_max_total_expansions_per_job` caps cost | Async mode uses a **fixed thread pool**; many simultaneous jobs **queue** and add latency, not unbounded threads. |
| **Seek cache rebuild** | Volume ∝ `(8×effectiveSize+1)³` per clam, spread over 100 ticks | **Paused** while `isPathfindingAllowedYet` is false (resize cooldown). |
| **Block-change deltas** | **O(clams × deltas)** per tick | Each `setBlockState` that touches **light/ore** classification can scan **every clam in the same dimension** with a loaded heart (`applyLightCacheDelta`). A player rapidly placing/breaking lights in one chunk still iterates **all** registered clams in that dimension (up to `[[Overview]]` registry cap). |
| **Path apply** | Delayed steps + optional **container BFS** off-thread | Solid breaks and beacon/ore goals can enqueue extra pathfinding work. |
| **`tickLoadedClamCores`** | Linear in **registered clams** for the world | Defense, heartbeat, seek attempts, cache rebuild slices — all per loaded heart per tick. |

## Can many voidclams be used to lag a server?

**Yes, in principle**, if an operator allows unconstrained placement and “friendly” config:

1. **Sheer count** — Hundreds of **loaded** hearts each tick a slice of logic (`tickLoadedClamCores`). Cost scales **linearly** with active clams in loaded chunks.
2. **Seek attempts** — Lower **`clam_seek_attempt_interval_seconds`** and set **`clam_seek_attempt_probability`** to `1` so almost every clam pathfinds often.
3. **Per-player block spam** — Building a **rapid clock** of torch/ore placement in range of many clams inflates **`lightCache`/`oreCache` delta work** (dimension-wide clam scan per delta, budget 16384 deltas/tick).
4. **Async pathfinding pool** — Default pool is **`max(2, processors)`**. Many clams can **saturate** the pool; work backs up (CPU + RAM for frontiers), while sync mode pushes cost onto the **server tick** unless budgets are tight.
5. **`astar_sync_max_total_expansions_per_job = -1`** — **Uncapped** expansions (debug); trivial to abuse with hard path instances.
6. **`clam_reachability_volatile_map = true`** — Extra **full BFS flood** per `clamReach` for ordering targets (CPU/RAM).
7. **Natural spawn** — High **`clam_spawn_natural_default_chunk_chance`** or dungeon rate can spread clams in overworld without player intent (still bounded by MAX_CLAMS registry).

**Mitigations (configuration + ops):**

- Raise **`clam_seek_attempt_interval_seconds`** and/or lower **`clam_seek_attempt_probability`** on busy servers.
- Keep **`astar_sync_max_total_expansions_per_job`** at a finite value (omit or explicit number); **never** use `-1` in production.
- Prefer **`astar_mode: async`** with a **modest** `astar_async_global_max_threads` if the main thread is the bottleneck; or **`sync_batched`** with a **low** `astar_sync_global_max_steps_per_tick` to cap tick time (trades latency).
- Set **`clam_reachability_volatile_map: false`** unless needed.
- Set **`seek_target_cache: false`** only if **box rescans** are acceptable vs **delta fan-out**; profile for your workload (many block changes vs few).
- Disable or tune **`clam_spawn_natural`** on public servers.
- Use **spawn protection / claims** so players cannot surround a farm with **light-flashing** redstone.

## Dormancy and memory

Ice-dormant clams **keep in-memory seek caches** while loaded (see [[Seek-caches-and-block-deltas]] § *Dormancy (ice) vs seek caches*). Set cardinality is bounded by seek-box volume per clam; dormant clams do **not** run `clamReach`, so CPU from pathfinding drops, but **RAM** for sets remains until unload or rebuild.

## Related notes

- [[Configuration]]
- [[Seek-caches-and-block-deltas]]
- [[Tick-order-and-intervals]]
- [[Threading-queues-locks]]
- [[Pathfinding-and-reach]]
