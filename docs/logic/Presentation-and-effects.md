# Presentation and effects

This note summarizes **player-visible** behavior that still matters for parity when porting. Implementation details stay in `TendrilPulseManager` and `VoidClamMod`.

## Tendril pulse (`TendrilPulseManager`)

When many blocks are replaced (path steps, some special cases), the mod spawns a **BlockDisplay** with scaled-up nether wart, animates scale down over `PULSE_DURATION_TICKS`, then applies the real block state and removes the entity. Brightness is sampled so the display matches local light.

**Omnidirectional pulse:** Periodic job expands from each clam with BFS, schedules delayed block updates similar to path stepping (`OMNI_TICKS_PER_STEP`), with per-tick batching (`OMNI_BFS_BATCH_PER_TICK`) to avoid long tick stalls.

## Defense (`VoidClamMod.tickDefenseForClam`)

From **`tickLoadedClamCores`**, phased per heart using **`VoidClamConfig#defenseDetectionIntervalTicks`** (default 8 s). For each clam with `currentSize >= 11`, `protectItself`, awake status, and loaded chunk: players inside the **octahedron** (see `CommandToolbox.isPlayerInsideOctahedron`) get:

- Replaceable adjacent blocks around player become **nether wart**
- **Hunger** and **mining fatigue** for 6 seconds
- **Goat horn** (“dream” sound if registered) or bass fallback  
Spectators skipped; one hard-coded name is exempt (typo variant of an owner name in source — preserve if cloning behavior).

Legacy **`tickDefense(world)`** still iterates all clams but is not the primary path.

## Heartbeat (`VoidClamMod.tickHeartbeatForClam`)

From **`tickLoadedClamCores`**: every **4 s** (80 ticks), staggered by heart position, **per dimension** with loaded heart chunk: conduit ambient sound at clam center, volume scales with size.

## Core integrity (`VoidClamMod.tickCoreCheckAtHeart`)

From **`tickLoadedClamCores`**: every **1 s** (20 ticks), phased per heart: if the block at the heart position is **neither** nether wart **nor** obsidian **nor** the clam core block, the clam is **killed**. (Legacy **`tickCoreCheck`** may still exist for global scans.)

## Related notes

- [[Tick-order-and-intervals]]
- [[Overview]]
- [[Loader-integration]] — mixin-driven `LIT` sync on furnace core
- [[Technical-documentation]]
