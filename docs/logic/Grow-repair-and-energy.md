# Grow, repair, and energy

## Autogrow into a prebuilt shell (searing heart placement)

When a **searing heart** successfully places the core block, **`VoidClamMod.tryAutogrowIntoPrebuiltShell`** scans sizes from **`currentSize`** up to **`clam_size_max`**. The **smallest** size where all of the following hold triggers **`CommandToolbox.clamReSize`** immediately (if that size is **greater** than `currentSize`):

- Expected shell lattice has at least one cell, and **strictly more than half** of those cells are **obsidian** (`inspectObsidianShellDamageAt`).
- The octahedron **interior** (per `CommandToolbox.isInsideOctahedronInterior`, heart block excluded) contains only **air**, **water**, or **nether wart**.
- All chunks overlapping the scan volume for that size are **loaded**; if not, autogrow **aborts** for that placement (no partial resize).

The **`/voidclam resize`** command was removed; use **`/voidclam grow`** (pending safe path), **repair**, or **auto** routine for size changes.

## Safe repair / grow (`requestRepairCommand`, `requestGrowCommand`)

Used by `/voidclam repair` and `/voidclam grow`.

1. If no grow is pending: snapshot **only the target clam’s** `seekLights` / `seekOres`, set **that** clam’s seeks to **false**. If a different clam was already pending, restore its seeks first, then snapshot the new target.
2. Set `growPendingWorld` and store `growCommandClamId` and `growCommandTargetSize` (positive = explicit resize target).
3. **Later**, `tickGrowPendingCheck` runs when:
   - Same dimension as `growPendingWorld`
   - No async kill barrier
   - That clam’s `busyFlagMainCycle == 0` (or clam already removed)
   - No **`targets`** entries for **`growCommandClamId`**
   - **`!isResizeShellAnimationPending(world)`** for that dimension
4. Then: run `clamReSize(world, clamId, targetSize)` (or auto routine when `targetSize == -1`); restore that clam’s seeks from the snapshot maps; clear pending state.

**Important:** If multiple grow requests arrive while pending, **only the last** request for the same world wins (`growCommandClamId` / `growCommandTargetSize` overwritten); the superseded clam’s seeks are restored when superseded.

## Auto repair / grow (per heart, overworld)

**Removed:** global `tickAutoRepairAndGrow` that iterated all registered clams every 5 minutes.

**Now:** Each server tick, **`VoidClamMod.tickLoadedClamCores(world)`** (per dimension) checks each awake clam’s **`Clam#nextAutoGrowRepairWorldTime`** against `world.getTime()`. When due, it calls **`tryScheduleAutoGrowRepairForClam`** (if no grow is already pending globally). After scheduling, the deadline advances by **`VoidClamMod.AUTO_GROW_REPAIR_INTERVAL_TICKS`** (5 minutes).

Completion still goes through **`tickGrowPendingCheck`** with **`growCommandTargetSize == -1`**, which runs **`runAutoGrowRoutineSingle`** for that clam only (repair to `currentSize`, clear blacklists, optional +2 grow using the same heuristics as before).

**Stagger:** First deadline after registration is spread across one interval using heart position (`x,y,z`), so clams do not all fire in the same tick.

**Server start:** After optional CSV load / migration, **`seedAutoGrowScheduleForAllClams`** is called for **each** `ServerWorld` so clams in that dimension get initial deadlines.

Clams whose chunks are unloaded **miss** that interval’s window; they reschedule on the next tick after load (entity-like behavior).

## `runAutoGrowRoutineSingle` (one clam, loaded chunk)

1. If **shell damage** (`inspectObsidianShellDamage.shellMissing() > 0`): repair-sized `clamReSize`, clear blacklists, set `prioritizeRepairOreSeek` from material vs damage, return — **no** change to `materialSeekThreshold`.
2. If **no shell damage** (healthy cycle): increment **`materialSeekThreshold`** by 1 (persisted on heart), sync block entity, then continue.
3. `clamReSize(world, clamId, currentSize)` — refresh flesh/path to recorded size.
4. Clear both blacklists.
5. If `energy <= clam_grow_energymultiplier * currentSize` or `currentSize >= clam_size_max`, skip growth.
6. Else scan interior volume for blast resistance / “has room” heuristics (`cst > 10 * cSize` fails).
7. If room and `clam_grow_material_cost > 0` but `material` is below that cost, skip growth (energy is not zeroed).
8. If room: optionally debit `clam_grow_material_cost`, zero energy, `clamReSize` to a larger size per config/heuristics, updating `currentSize`.

**Material hunger:** `CommandToolbox.clamReach` treats ore-flow / hunger when `seekLights && material < materialSeekThreshold` (or repair-priority ore seek). The threshold is not the static config value after load — it climbs on each healthy auto cycle so intact clams gradually demand more stockpiled material before feeling “full,” encouraging mining toward growth costs.

## `clamReSize` (summary)

- Schedules layered shell rebuild with nether wart (staggered delays), converts obsidian in old volume to nether wart, updates vertical spine, schedules final obsidian shell pass, sets `m.currentSize`, triggers optional legacy CSV save.

## Energy rules (summary)

- **Gain +1** when a **light** block is consumed at the path goal (`buildPath`).
- **Lose 1** when stamina fails mid-path toward a non-fluid block (and goal blacklisted).

## Related notes

- [[Technical-documentation]]
- [[Pathfinding-and-reach]]
- [[State-and-save]]
- [[Tick-order-and-intervals]]
- [[Persistence-and-schema]]
