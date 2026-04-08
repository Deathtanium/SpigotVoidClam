# State and save

**Serialized field-level spec:** [[Persistence-and-schema]] (keys, types, read defaults, what stays RAM-only).

## Runtime registry

- Clams are stored in a **`ConcurrentHashMap<UUID, Clam>`** (`VoidClamMod#clamsById`), keyed by **`Clam#clamId`**.
- A clam is **registered** when its **heart block entity** (clam-core blast furnace) is linked after the chunk loads (`tryRegisterFromClamCoreBlockEntity`), or when placed via item / commands / migration helpers.
- Activity for a clam **pauses** when its heart chunk is unloaded (seek caches may be cleared after an unload expiry interval — see config and `tickSeekEphemeralExpiry`).

## `Clam` fields (behavioral)

| Field | Meaning |
|--------|---------|
| `clamId` | Stable UUID; pathfinding, kill barrier, commands |
| `type`, `x`, `y`, `z` | Clam kind and center |
| `currentSize` | Shell radius parameter used in geometry, path bounds, defense |
| `status` | `1` awake / `0` asleep (persisted on heart) |
| `energy` | Consumed by failed path steps; gained when a **light** block is consumed at path end |
| `material` | Ores, shell repair, grow cost — see [[Resources-and-caps]] |
| `soul` | +1 per **soul-fire family** light eaten at path goal; same cap as `energy`; **no spend** yet |
| `age` | Age / phase timing (persisted on heart) |
| `seekLights`, `seekOres` | Whether periodic reach scans consider lights and/or ores |
| `protectItself` | Defense behavior when large enough |
| `busyFlagPlaceEvent` | Declared on `Clam`; reserved / legacy |
| `mainCycleBusy` | **Reach / path lifecycle lock** — see [[Threading-queues-locks]] |
| `nextAutoGrowRepairWorldTime` | Per-dimension `getTime()` deadline for the next **per-clam** auto repair/grow (in-memory scheduling; heart NBT carries other persisted fields) |

## Registry vs heart block entity

- **Persistent source of truth** for a placed clam is the **heart blast furnace block entity** (chunk save + `CUSTOM_DATA` tree built by `SearingHeartItems`, NBT subkey **`module`** for wire compatibility).
- On load, `tryRegisterFromClamCoreBlockEntity` builds a runtime `Clam` from the BE when the map has no conflicting entry.
- The main thread keeps BE and `Clam` aligned via `syncClamCoreBlockEntityFromClam` / `SearingHeartItems.syncClamToBlockEntity` during `tickLoadedClamCores` and other updates.

See also [[Hivemind-future]] for duplicate-ID / pasted-heart scenarios.

## Grow-pending globals (`VoidClamMod`)

When a **safe** grow or repair runs, seeks for **that clam only** are snapshotted and cleared until the action completes:

- `growPendingWorld` — Which dimension is waiting.
- `growCommandClamId`, `growCommandTargetSize` — Target clam; **`targetSize == -1`** means **auto** repair/grow for that single clam (not a command resize).
- `growSavedSeekLights` / `growSavedSeekOres` — Maps keyed by `clamId` (typically one entry while pending).

Only **one** pending grow/repair runs at a time globally. If a new request targets a different clam, the previous clam’s seeks are restored before snapshotting the new one.

See [[Grow-repair-and-energy]].

## Related

- [[Persistence-and-schema]]
- [[Grow-repair-and-energy]]
- [[Tick-order-and-intervals]]
- [[Hivemind-future]]
- [[Technical-documentation]]
