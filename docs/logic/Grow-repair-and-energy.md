# Grow, repair, and energy

## Command `/voidclam resize`

Calls **`CommandToolbox.clamReSize`** immediately (not the safe pending flow). Use when you accept possible overlap with path activity.

## Safe repair / grow (`requestRepairCommand`, `requestGrowCommand`)

Used by `/voidclam repair` and `/voidclam grow`.

1. If no grow is pending yet: snapshot **all** modules’ `seekLights` / `seekOres` into `savedSeek*`, then set **every** module’s seeks to **false**.
2. Set `growPendingWorld` and, if the request world matches, store `growCommandTno` and `growCommandTargetSize`.
3. **Later**, `tickGrowPendingCheck` runs when:
   - Same dimension as `growPendingWorld`
   - For command path: that module’s `busyFlagMainCycle == 0`
   - `targets` queue empty
   - No pending delayed tasks for that dimension (`hasPendingTasks`)
4. Then: run `clamReSize` for command case, or **`runGrowRoutine`** for auto case; restore all `seekLights` / `seekOres` from snapshots.

**Important:** If multiple grow requests arrive while pending, **only the last** request for the same world wins (`growCommandTno` / `growCommandTargetSize` overwritten).

## Auto repair / grow (`tickAutoRepairAndGrow`)

Every 5 minutes (overworld tick gate): if no grow already pending, snapshots seeks, clears seeks, sets `growPendingWorld` with **`growCommandTno = 0`** so completion runs **`runGrowRoutine`** instead of a single-module resize.

### `runGrowRoutine` (per module, loaded chunk only)

1. `clamReSize(world, i, currentSize)` — repair shell to recorded size.
2. Clear both blacklists.
3. If `energy <= 4 * currentSize` or `currentSize >= 15`, skip growth.
4. Else scan interior volume for blast resistance / “has room” heuristics (`cst > 10 * cSize` fails).
5. If room: zero energy, `clamReSize` to `currentSize + 2`, then increment `currentSize` by 2.
6. **`save(server)`** at end.

## `clamReSize` (summary)

- Schedules layered shell rebuild with nether wart (staggered delays), converts obsidian in old volume to wart/warped material by type, updates vertical spine, schedules final obsidian shell pass, sets `m.currentSize`, **saves**.

## Energy rules (summary)

- **Gain +1** when a **light** block is consumed at the path goal (`buildPath`).
- **Lose 1** when stamina fails mid-path toward a non-fluid block (and goal blacklisted).

## Related notes

- [[Pathfinding-and-reach]]
- [[State-and-save]]
