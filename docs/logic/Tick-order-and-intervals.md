# Tick order and intervals

All intervals below are expressed in **server ticks** (20 ticks ≈ 1 second). Constants live in `VoidClamModEntry` unless noted.

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
| `TICK_TARGETS` (20) | 1 s | For each module in **loaded overworld chunk**: `CommandToolbox.clamReach`; then `VoidClamMod.tickCoreCheck(overworld)` |
| `TICK_HEARTBEAT` (80) | 4 s | `VoidClamMod.tickHeartbeat(overworld)` |
| `TICK_OMNI_PULSE` (100) | 5 s | `TendrilPulseManager.runOmnidirectionalPulse(overworld)` |
| `TICK_DEFENSE` (100) | 5 s | `VoidClamMod.tickDefense(w)` for **every** world |
| `TICK_AUTO_GROW` (6000) | 5 min | `VoidClamMod.tickAutoRepairAndGrow(overworld)` — starts safe-pending flow |
| `TICK_CLEANUP` (1200) | 1 min | `TendrilPulseManager.cleanupStrayDisplays(w)` for every world |

## Design notes for ports

- **Reach and core check** use the **overworld** only for the periodic pass (even though grow pending and defense iterate all worlds).
- **Delayed tasks** are ticked with the **overworld** reference; tasks scheduled with a different `ServerWorld` instance may not fire until `VoidClamModScheduler` is extended to match by dimension key (see [[Threading-queues-locks]]).
- `TICK_REACH` exists in code but is **unused**; the periodic reach uses `TICK_TARGETS`.

## Related notes

- [[Threading-queues-locks]]
- [[Pathfinding-and-reach]]
