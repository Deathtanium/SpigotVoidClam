# State and save

## Runtime registry

- Modules are stored in a **`ConcurrentHashMap<UUID, Module>`** (`VoidClamMod`), keyed by **`Module#clamId`**.
- A clam is **registered** when its **heart block entity** first ticks after the chunk loads (`ensureRuntimeModuleForHeart`), or when placed via item / commands / legacy CSV load.
- Clams in **unloaded chunks** are absent from the map until those chunks load again.

## `Module` fields (behavioral)

| Field | Meaning |
|--------|---------|
| `clamId` | Stable UUID; pathfinding, kill barrier, commands |
| `type`, `x`, `y`, `z` | Module kind and center |
| `currentSize` | Shell radius parameter used in geometry, path bounds, defense |
| `status` | `1` awake / `0` asleep (persisted on heart) |
| `energy` | Consumed by failed path steps; gained when a **light** block is consumed at path end |
| `age` | Age / phase timing (persisted on heart) |
| `seekLights`, `seekOres` | Whether periodic reach scans consider lights and/or ores |
| `protectItself` | Defense behavior when large enough |
| `lightsBlackList`, `oresBlackList` | `BlockPos` sets; **persisted in heart NBT** (`VoidClamHeartItemData`) |
| `busyFlagPlaceEvent` | Declared on `Module`; reserved / legacy |
| `busyFlagMainCycle` | **Reach / path lifecycle lock** — see [[Threading-queues-locks]] |
| `nextAutoGrowRepairWorldTime` | Overworld `getTime()` deadline for the next **per-clam** auto repair/grow (not saved to CSV; in-memory scheduling) |

## Registry vs heart block entity

- **Persistent source of truth** for a placed clam is the **heart block entity** (chunk save + `voidclam:heart_stack` component).
- On first tick after load, `ensureRuntimeModuleForHeart` builds a runtime `Module` from the BE **unless** the map already has the same `clamId` at the **same** `(x,y,z)` — then the existing runtime entry is kept (assumed in sync with recent main-thread updates).
- External edits to chunk data only (datapacks, direct NBT edit) while the chunk stays loaded could theoretically desync until something calls `syncFromModule` again. Normal gameplay keeps BE and `Module` aligned.

See also [[Hivemind-future]] for duplicate-ID / pasted-heart scenarios.

## Grow-pending globals (`VoidClamMod`)

When a **safe** grow or repair runs, seeks for **that clam only** are snapshotted and cleared until the action completes:

- `growPendingWorld` — Which dimension is waiting.
- `growCommandClamId`, `growCommandTargetSize` — Target clam; **`targetSize == -1`** means **auto** repair/grow for that single clam (not a command resize).
- `growSavedSeekLights` / `growSavedSeekOres` — Maps keyed by `clamId` (typically one entry while pending).

Only **one** pending grow/repair runs at a time globally. If a new request targets a different clam, the previous clam’s seeks are restored before snapshotting the new one.

See [[Grow-repair-and-energy]].

## Optional legacy file: `modules.siva`

- **Path**: server save root `WorldSavePath.ROOT` / `modules.siva`.
- **Load** (`loadOptionalLegacyModulesSiva`): If the file **exists** at server start, the registry is filled from it; if **absent**, the registry starts empty and clams appear as chunks load.
- **Write**:
  - **`save`**: Always writes (creates or overwrites) — use `/voidclam save` to export or enable the mirror.
  - **`maybeSaveLegacyModulesSiva`**: Writes only if `modules.siva` **already exists** (e.g. server stop without deleting the file).
- **Rotation**: On full `save`, existing file moves to `modules.siva.old` (best-effort), then a new file is written.
- **Format**: One CSV line per module (includes `clamId` column after seek flags).

Ports should preserve **column order** if exchanging saves.

## Related

- [[Grow-repair-and-energy]]
- [[Tick-order-and-intervals]]
- [[Hivemind-future]]
