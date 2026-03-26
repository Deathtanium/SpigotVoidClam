# Tick order and intervals

All intervals below are expressed in **server ticks** (20 ticks ≈ 1 second). Constants live in `VoidClamModEntry` or `VoidClamMod` unless noted.

## Single `END_SERVER_TICK` callback (overworld-driven)

Each server tick, **in order**:

1. **`VoidClamMod.tickTargets(overworld)`** — Drain the `targets` queue and run `Pathfinder.buildPath` for each node (may schedule more delayed work).
2. **`VoidClamModScheduler.tick(overworld)`** — Run delayed tasks whose `runAtTick <= overworld.getTime()` **for the overworld instance only** (reference equality on `ServerWorld`).
3. **For each `ServerWorld` `w`**: `VoidClamMod.tickGrowPendingCheck(w)` — May complete a pending grow/repair when idle.
4. **For each world `w`**: `TendrilPulseManager.tick(w)` — Advances in-world pulse jobs.
5. **`TendrilPulseManager.tickOmniPulseJob(overworld)`** — Omnidirectional pulse BFS batching (uses overworld time/tick context).

Then **gated** by `overworld.getTime()`:

| Interval constant | Ticks | What runs |
|-------------------|-------|-----------|
| `TICK_OMNI_PULSE` (100) | 5 s | `TendrilPulseManager.runOmnidirectionalPulse(overworld)` |
| `TICK_CLEANUP` (1200) | 1 min | `TendrilPulseManager.cleanupStrayDisplays(w)` for every world |

## Per–block-entity tick (heart)

Registered via `VoidClamHeartBlock.getTicker`: each loaded heart runs **`VoidClamHeartBlockEntity.tick`** every game tick (when the chunk ticks). That drives:

- First-load **registry link** (`ensureRuntimeModuleForHeart`)
- **Auto grow/repair** scheduling (overworld only; see [[Grow-repair-and-energy]])
- Staggered **reach** + core check, **heartbeat**, **defense**

So reach/core/heartbeat/defense are **not** driven by the global server tick table in `VoidClamModEntry`; they are **heart-local** intervals.

## Design notes for ports

- **Delayed tasks** are ticked with the **overworld** reference; tasks scheduled with a different `ServerWorld` instance may not fire until `VoidClamModScheduler` is extended to match by dimension key (see [[Threading-queues-locks]]).

## Related notes

- [[Threading-queues-locks]]
- [[Pathfinding-and-reach]]
- [[Grow-repair-and-energy]]
- [[State-and-save]]
