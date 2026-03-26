# Presentation and effects

This note summarizes **player-visible** behavior that still matters for parity when porting. Implementation details stay in `TendrilPulseManager` and `VoidClamMod`.

## Tendril pulse (`TendrilPulseManager`)

When many blocks are replaced (path steps, some special cases), the mod spawns a **BlockDisplay** with scaled-up nether wart, animates scale down over `PULSE_DURATION_TICKS`, then applies the real block state and removes the entity. Brightness is sampled so the display matches local light.

**Omnidirectional pulse:** Periodic job expands from each module with BFS, schedules delayed block updates similar to path stepping (`OMNI_TICKS_PER_STEP`), with per-tick batching (`OMNI_BFS_BATCH_PER_TICK`) to avoid long tick stalls.

## Defense (`VoidClamMod.tickDefense`)

Every 5 s (all worlds): for each module with `currentSize >= 11` and loaded chunk, players inside the **octahedron** (see `CommandToolbox.isPlayerInsideOctahedron`) get:

- Replaceable adjacent blocks around player become **nether wart**
- **Hunger** and **mining fatigue** for 6 seconds
- **Goat horn** (“dream” sound if registered) or bass fallback  
Spectators skipped; one hard-coded name is exempt (typo variant of an owner name in source — preserve if cloning behavior).

## Heartbeat (`VoidClamMod.tickHeartbeat`)

Every 4 s (overworld, loaded chunks): conduit ambient sound at module center, volume scales with size.

## Core integrity (`VoidClamMod.tickCoreCheck`)

Every 1 s with reach: modules whose center block is **neither** nether wart **nor** obsidian are **killed**. Loop runs **backwards** so index shifts from `clamKill` do not skip entries.

## Related notes

- [[Tick-order-and-intervals]]
- [[Overview]]
