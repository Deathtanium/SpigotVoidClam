# State and save

## Module array

- Modules are stored in a fixed upper bound array (`MAX_MODULES` = 1001); valid indices are **1 … `moduleNumber`** (`VoidClamMod`).
- **`moduleNumber`** is the count of allocated slots; index **0** is unused (same as 1-based indexing in commands).

## `Module` fields (behavioral)

| Field | Meaning |
|--------|---------|
| `type`, `x`, `y`, `z` | Module kind and center |
| `currentSize` | Shell radius parameter used in geometry, path bounds, defense |
| `status` | `1` awake / `0` asleep (persisted; semantics depend on type logic elsewhere) |
| `energy` | Consumed by failed path steps; gained when a **light** block is consumed at path end |
| `age` | Age / phase timing (persisted) |
| `seekLights`, `seekOres` | Whether periodic reach scans consider lights and/or ores |
| `lightsBlackList`, `oresBlackList` | `BlockPos` sets: targets to ignore or positions blacklisted after failures |
| `busyFlagPlaceEvent` | Declared on `Module`; **not referenced** in current `src` — treat as reserved for future or legacy parity |
| `busyFlagMainCycle` | **Reach / path lifecycle lock** — see [[Threading-queues-locks]] |

## Grow-pending globals (`VoidClamMod`)

When a **safe** grow or repair (or the periodic auto grow) runs, the mod must not race with active pathfinding:

- `growPendingWorld` — Which dimension/world is waiting (by registry key when checking).
- `growCommandTno`, `growCommandTargetSize` — For command-driven grow/repair: which module and target size once idle.
- `savedSeekLights[]`, `savedSeekOres[]` — Per-index snapshot of seek flags while pending; restored after the pending action completes.

See [[Grow-repair-and-energy]].

## Save file: `modules.siva`

- **Path**: server save root `WorldSavePath.ROOT` / `modules.siva`.
- **Rotation**: On save, existing file moves to `modules.siva.old` (best-effort), then a new file is written.
- **Format**: One CSV line per module, **no header**:

```
type,x,y,z,currentSize,status,energy,age,seekLights,seekOres
```

- **Load**: Lines with fewer than 8 columns are skipped; optional `seekLights` / `seekOres` default false if missing.
- **Errors**: IO failures are swallowed (empty or partial state possible — same as current implementation).

Ports should preserve **column order** and semantics if exchanging saves with this mod.
