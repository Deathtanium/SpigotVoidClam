# Overview

VoidClam simulates **modules** (SIVA-like nodes): each has a center block, a **shell size**, **energy**, **seek** toggles for lights and ores, and runs **A\*** pathfinding toward food. Paths are found asynchronously but **applied** on the main thread via a queue plus **delayed runnables** so block placement is staggered in time.

## Core nouns

- **Module** — One organism instance: position, `currentSize`, `type`, `status`, `energy`, `age`, `seekLights`, `seekOres`, blacklists for targets that failed or should be skipped, and busy flags (`Module.java`).
- **Path** — A chain of `Node` from module center toward a goal block; the goal node is enqueued; `buildPath` walks parent pointers from goal toward start and schedules placement per step.
- **Targets queue** — `ConcurrentLinkedQueue<Node>` holding path *results* ready for `buildPath` (`VoidClamMod.enqueueTarget` / `tickTargets`).

## Main classes (current Fabric code)

| Class | Role |
|--------|------|
| `VoidClamModEntry` | Registers server lifecycle, **end-of-server-tick** callback, commands |
| `VoidClamMod` | Static module array, save/load, grow-pending coordination, defense/heartbeat/core-check helpers |
| `CommandToolbox` | Stub/shell construction, `clamReach`, `clamReSize`, pathfinder executor |
| `Pathfinder` | A*, enqueue goal, `buildPath` (stamina, drops, container routing) |
| `VoidClamModScheduler` | Delayed runnables keyed by **world time** (`world.getTime() + delay`) |
| `TendrilPulseManager` | Block display “pulse” when replacing blocks; omni pulse job |

## Related notes

- [[Tick-order-and-intervals]]
- [[Threading-queues-locks]]
