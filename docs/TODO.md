# Voidclams — backlog / to-do

Working notes for planned behavior and balance changes. Items are not implemented until removed or checked off here.

---

## Critical — pathfinding / A* (investigate further)

**Context:** Clams could appear “stuck” or fail toward distant lights (e.g. tower torch) with **async** `astar_mode` and open terrain. Fixed one bug: `leastF` used a hard `f < 100_000` cutoff and could return null / NPE when all frontier nodes had `f ≥ 100_000`. **Further validation and hardening are still required** before closing this topic.

- [ ] **Production verification:** Confirm the `leastF` fix resolves real-world failures; watch logs for `[voidclam/Pathfinder]` WARNs (async expansion cap, prepass unreachable).
- [ ] **Off-thread world access:** Audit `Pathfinder.calculatePath` / `PathfindChunkCache` / `getHardness` on `pathfindingExecutor` threads vs Minecraft’s threading model (stale reads, chunk loading, synchronization with the server thread).
- [ ] **Prepass vs A* parity:** Re-check BFS prepass vs A* neighbor rules (goal just outside pathfinding AABB, `cst == 2500` / impassable, torches / non-cube blocks).
- [ ] **Caps and failure modes:** Review `effectiveSyncMaxTotalExpansionsPerJob` for async A* and light-cache removal on failure — ensure failures are attributable and recoverable (no permanent “wrong” blacklists without repair/reach).
- [ ] **Observability:** If `/voidclam debug` + `workerTaskLabels` still leave stalls unexplained, add short-lived metrics or structured logging (clamId, start/goal, phase, expansion count at exit).

---

## Beacons

- [ ] Add **beacons** to the desired block list (lights / reach targets — align with existing light-seeking and pathfinding rules).
- [ ] **Special behavior when “eating” a beacon:** preserve the **nether star** (do not destroy with the rest of the break flow); place it into a **barrel** using the same storage routing patterns as other loot (container BFS / barrel fallback near clam center).

---

## Anti-player detection

- [ ] Reduce anti-player detection range by **2 blocks** (current behavior is overzealous).
- [ ] **Disable anti-player behavior entirely** for clam **sizes smaller than 5** (`currentSize < 5`).

---

## Organic behavior

### 1. Stamina / distance penalty

- [ ] **Stamina cost** (or equivalent movement/action cost for tendrils / path application) should increase **exponentially** with distance from the clam’s **shell** (surface / volume definition TBD — not the blast-furnace **center** alone).
- [ ] Clam **size** should **reduce** this distance penalty (larger clams pay less marginal cost at the same world distance from shell).

### 2. Energy economy

- [ ] Rework **energy** gains: assign **different energy values per light block type** (torch vs lantern vs glowstone vs … — enum/table in config or code).
- [ ] Treat **ores** as a **separate resource** from light energy; **ore search / pathfind to ores** only runs when that resource is **below a configurable threshold** (and/or when light energy is sufficient — exact gating TBD).

### 3. Repair / grow cycle and shell integrity

- [ ] During **repair-or-grow cycle** (`repairorgrowcycle` / auto grow–repair path):
  - Detect **damage to the shell** (missing / wrong blocks vs expected wart–obsidian shell).
  - **Expend energy** and **ores** (when available) to **repair** damage.
  - **Only run growth** when **no shell damage** is detected **and** **sufficient room** exists for expansion (reuse or extend existing room checks).

---

## Notes

- Implementation should stay consistent with existing tick order, `busyFlagMainCycle`, and resize cooldown rules documented under `docs/logic/`.
- After shipping a bullet, either remove it from this file or mark it done and link the PR/commit if the team wants history.
