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

- **Reachability pre-pass:** Before A\*, a 6-neighbor **BFS** from the start cell checks whether the goal lies in the same connected component under the same search bounds and **hard impassability** rules as A\* (tile entities and blocks with hardness &gt; 5 are walls; wart is walkable). Movement **costs** are ignored—only disconnects from impassable cells. If there is no such path, `busyFlagMainCycle` is cleared and A\* is skipped (avoids exhausting the open set for caged targets).
- **Performance note:** Today `calculatePath` is invoked from the pathfinder **worker** thread (`CommandToolbox.submitPathfinding`), same as A\*. If this pre-pass is ever called from the **server main thread** and proves too expensive for TPS, run it on worker threads the same way as A\*.
- A\* on the 6-neighbor grid with custom edge costs (wart cheap, tile entities / very hard blocks cost `2500`, air/water over solid cheap, etc.). **Heuristic:** Manhattan distance to goal (`manhattanH`). **`g`** is cumulative edge cost with re-open when a cheaper path to a cell is found.
- Search is bounded roughly by **±4×currentSize** on X/Z and **±5×currentSize** on Y from module center (per-neighbor checks before enqueue).
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

For storing drops: main thread builds a **BFS map** from clam center (cap `CONTAINER_SNAPSHOT_MAX_STEPS`), classifying nether wart, warped wart, chest-like blocks, other. Worker runs BFS on the map; main thread applies insertions via `world.getServer().execute`.

## Related notes

- [[Threading-queues-locks]]
- [[Grow-repair-and-energy]]
