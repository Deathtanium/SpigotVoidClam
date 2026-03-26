# Grow, repair, and energy

## Command `/voidclam resize`

Calls **`CommandToolbox.clamReSize`** immediately (not the safe pending flow). Use when you accept possible overlap with path activity.

## Safe repair / grow (`requestRepairCommand`, `requestGrowCommand`)

Used by `/voidclam repair` and `/voidclam grow`.

1. If no grow is pending: snapshot **only the target clam’s** `seekLights` / `seekOres`, set **that** module’s seeks to **false**. If a different clam was already pending, restore its seeks first, then snapshot the new target.
2. Set `growPendingWorld` and store `growCommandClamId` and `growCommandTargetSize` (positive = explicit resize target).
3. **Later**, `tickGrowPendingCheck` runs when:
   - Same dimension as `growPendingWorld`
   - That module’s `busyFlagMainCycle == 0`
   - `targets` queue empty
   - No pending delayed tasks for that dimension (`hasPendingTasks`)
4. Then: run `clamReSize(world, clamId, targetSize)`; restore that clam’s seeks from the snapshot maps; clear pending state.

**Important:** If multiple grow requests arrive while pending, **only the last** request for the same world wins (`growCommandClamId` / `growCommandTargetSize` overwritten); the superseded clam’s seeks are restored when superseded.

## Auto repair / grow (per heart, overworld)

**Removed:** global `tickAutoRepairAndGrow` that iterated all registered modules every 5 minutes.

**Now:** Each **`VoidClamHeartBlockEntity`** tick on the **overworld** checks `Module#nextAutoGrowRepairWorldTime` against `world.getTime()`. When due, it calls **`tryScheduleAutoGrowRepairForClam`** (if no grow is already pending globally). After scheduling, the deadline advances by **`VoidClamMod.AUTO_GROW_REPAIR_INTERVAL_TICKS`** (5 minutes).

Completion still goes through **`tickGrowPendingCheck`** with **`growCommandTargetSize == -1`**, which runs **`runAutoGrowRoutineSingle`** for that clam only (repair to `currentSize`, clear blacklists, optional +2 grow using the same heuristics as before).

**Stagger:** First deadline after registration is spread across one interval using heart position (`x,y,z`), so clams do not all fire in the same tick.

**Server start:** After optional CSV load / migration, **`seedAutoGrowScheduleForAllModules(overworld)`** assigns initial deadlines for any clams already in the map.

Clams whose chunks are unloaded **miss** that interval’s window; they reschedule on the next tick after load (entity-like behavior).

## `runAutoGrowRoutineSingle` (one module, loaded chunk)

1. `clamReSize(world, clamId, currentSize)` — repair shell to recorded size.
2. Clear both blacklists.
3. If `energy <= clam_grow_energymultiplier * currentSize` or `currentSize >= clam_size_max`, skip growth.
4. Else scan interior volume for blast resistance / “has room” heuristics (`cst > 10 * cSize` fails).
5. If room: zero energy, `clamReSize` to `currentSize + 2`, then increment `currentSize` by 2.
6. **`maybeSaveLegacyModulesSiva`** at end (only if `modules.siva` exists).

## `clamReSize` (summary)

- Schedules layered shell rebuild with nether wart (staggered delays), converts obsidian in old volume to nether wart, updates vertical spine, schedules final obsidian shell pass, sets `m.currentSize`, triggers optional legacy CSV save.

## Energy rules (summary)

- **Gain +1** when a **light** block is consumed at the path goal (`buildPath`).
- **Lose 1** when stamina fails mid-path toward a non-fluid block (and goal blacklisted).

## Related notes

- [[Pathfinding-and-reach]]
- [[State-and-save]]
- [[Tick-order-and-intervals]]
