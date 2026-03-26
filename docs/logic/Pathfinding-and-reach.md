# Pathfinding and reach

## `clamReach` (`CommandToolbox`)

**When:** Called every `TICK_TARGETS` ticks for each module whose center chunk is loaded in the **overworld**, and from `/voidclam reach`.

**Guards:** Valid index, module non-null, center chunk loaded, **`busyFlagMainCycle == 0`**.

**Flow:**

1. Set `busyFlagMainCycle = 1`.
2. On executor thread: scan a box around the module from `y - 4*cSize` to `y + 4*cSize` and horizontal `±4*cSize` for each axis.
3. Track closest **light** (if `seekLights`) and closest **ore** (if `seekOres`), excluding blacklisted positions.
4. **Tie-break:** If both exist, choose the one with **smaller squared distance**; if equal distance, **light wins** (`closestLightDist <= closestOreDist`).
5. Chosen target is added to the appropriate blacklist **before** pathfinding (so retries skip it until removed).
6. If a target exists: `Pathfinder.calculatePath(world, tno, ...)`. If not: clear `busyFlagMainCycle`.

## `calculatePath` (`Pathfinder`)

- A\* on the 6-neighbor grid with custom edge costs (wart cheap, tile entities / very hard blocks cost `2500`, air/water over solid cheap, etc.).
- Search is bounded roughly by **±4×currentSize** on X/Z and **±5×currentSize** on Y from module center (see conditions on `nextNode`).
- **Success:** Enqueue goal `Node` on `targets`; leave busy flag for `buildPath` to clear.
- **Failure:** Set `busyFlagMainCycle = 0`.

## `buildPath` (`Pathfinder`)

Runs on **main thread** from `tickTargets`.

**Early exits** (clear `busyFlagMainCycle`):

- Goal node cost `f >= 2500`
- Module center chunk unloaded
- Goal block is ore but `!seekOres`, or light but `!seekLights` (stale enqueue after seek toggles / grow pending)

**Stamina:** `stamina[0]` starts at `module.currentSize`. Each scheduled step subtracts a **cost** derived from block hardness (except goal step forced to 0). If stamina would go negative:

- Set `blocked`; if the block was not air/water/lava, **blacklist the goal** for both lights and ores and **`addEnergy(tno, -1)`**.

**Path stop (`pathStopped`):** If the step would replace a non-goal block that has a non-air item drop, the path **stops** (no energy change for that case): schedule container routing with a snapshot, clear busy flag.

**Ore goal:** Fortune-3 style drops from `getFortune3Drops`, container BFS from snapshot, then replace or barrel; clear busy flag.

**Light goal:** Replacing the light with wart grants **`addEnergy(tno, 1)`**; clear busy flag on that final scheduled step.

**Blacklist cleanup:** Schedules `removeLightsBlackList` / `removeOresBlackList` for the goal position after a delay derived from path length (`timer`).

**Scheduling:** Walks from goal toward start; each step schedules a `VoidClamMod.scheduleDelayed` at `timer`, `timer-2`, … so pulses fire in order along the path.

## Container snapshot (`buildContainerSnapshot` / `runContainerBfsOnSnapshot`)

**Intended behavior:** Storage routing is anchored to the **module center** (clam core coordinates), not the block being broken. The main thread builds a **BFS map** from that center, classifying nether wart, warped wart, chest / trapped chest / barrel, and everything else. Exploration is limited to the **same axis-aligned box as `calculatePath`**: ±4×`currentSize` on X from center, ±5×`currentSize` on Y and Z (see `PATHFINDING_RANGE_*_HALF` in `Pathfinder`). The worker runs **the same BFS from the same root** on that map and returns containers in **BFS order from the clam center**. The main thread then tries `tryInsertInto` in that order via `world.getServer().execute`.

So “nearer” means **fewer graph steps from the clam center through wart (and the root cell’s neighbors)**, not Euclidean distance from the break position. A chest sitting on wart closer to the center than another chest should appear **earlier** in the list unless it is never reached (see below).

**Situations where pre-placed “near” storage might be skipped or not receive items:**

1. **Outside pathfinding range** — Storage beyond the `calculatePath` box (same limits as above) is never included in the snapshot, even if connected by wart outside that box.
2. **No path through the snapshot** — Traversal continues only through nether wart, warped wart, and the root cell. A chest that is not adjacent to wart reachable from the center inside the box does not appear in the list.
3. **Block not treated as storage** — Only `CHEST`, `TRAPPED_CHEST`, and `BARREL` are containers. Other inventories (e.g. shulker, hopper, decorated pot) are not candidates.
4. **Insert failure** — `tryInsertInto` uses `Inventory` on the block entity; if the entity is missing or the inventory is not usable as expected, that position effectively does nothing and the stack may fall through to a barrel at the break position.
5. **Stale snapshot vs. concurrent breaks** — Each break schedules work from a snapshot taken when that step runs; rapid successive breaks can see slightly different world state ordering, but should not systematically ignore center-near chests unless one of the above applies.

## Related notes

- [[Threading-queues-locks]]
- [[Grow-repair-and-energy]]
