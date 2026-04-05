# Persistence and schema

Authoritative serialization is implemented in **`SearingHeartItems`**. The clam **heart** is a **vanilla blast furnace** block entity; state is stored in **`DataComponentTypes#CUSTOM_DATA`** (data components), not only classic block-entity NBT, though disk format is NBT-like when exported.

## Component tree shape

- **`CUSTOM_DATA`** root compound: arbitrary keys preserved from existing data; VoidClam owns **`voidclam`**.
- **`voidclam`**: wrapper compound.
- **`module`**: the **clam payload** (`SearingHeartItems` constant `CLAM_NBT_SUBKEY`). Historical name for wire compatibility.

**Searing Heart identification** (item or block entity):

- `CUSTOM_DATA.voidclam.module` exists (non-empty compound after read), **and** for items, stack is `minecraft:blast_furnace`.

Display:

- **`CUSTOM_NAME`**: red bold literal `"Searing Heart"` on synced hearts (`SEARING_NAME`).

## Fields inside `module` (`writeClamNbt` / `readClamNbt`)

| Key | Type | Write | Read default | Notes |
|-----|------|-------|----------------|-------|
| `clamId` | string (UUID) | if non-null | absent → `null` until `ensureClamId()` | Invalid UUID string ignored on read |
| `type` | int | always | `0` | See `Clam` type javadoc |
| `currentSize` | int | always | `1`, clamped ≥ 1 | |
| `status` | int | always | `0` | `1` awake, `0` asleep |
| `material` | int | always | `0`, clamped ≥ 0 on read | Persisted |
| `materialSeekThreshold` | int | always | absent → `clam_material_seek_threshold` from config | Ore “comfort” target; +1 each auto cycle with no shell damage |
| `energy` | int | always | `0` | Clamped non-negative when syncing to BE |
| `age` | int | always | `0` | |
| `seekLights` | boolean | always | `false` | |
| `seekOres` | boolean | always | `false` | |
| `protectItself` | boolean | always | `true` | |
| `stubBuilt` | boolean | always | **`true`** if absent | Fresh placements set `false` until stub/fuel path runs |
| `dimension` | string | if `worldKey != null` | absent | Namespace:id of `RegistryKey<World>`; `Identifier.tryParse` |

**Not stored in `module`:**

- **Block position** `x,y,z` — taken from the block entity’s world position (or set on place from `BlockPos`).
- **Seek caches** (`lightsCache`, `oresCache`), **blacklists**, **path goals**, **busy flags**, **`pathApplyPendingSteps`**, **`pathfindingResumeWorldTime`**, **`nextAutoGrowRepairWorldTime`**, unload-expiry fields, rebuild cursors/ticks — all **runtime or derived** after load (`applyTemplateOntoClam` clears them explicitly when applying a snapshot).

Legacy hearts that once stored cache lists in NBT are no longer written; readers should not expect list keys.

## Where data is read/written

| Operation | Mechanism |
|-----------|-----------|
| Chunk load / furnace tick | `tryRegisterFromClamCoreBlockEntity` reads BE **`ComponentMap`** via `readClamTemplateFromComponentMap`, merges into runtime `Clam` if registry allows |
| Ongoing sync | `syncClamToBlockEntity` / `syncClamCoreBlockEntityFromClam` rewrite `CUSTOM_DATA` + name when dirty |
| Item drop on break | `captureClamCoreComponentsBeforeBreak` + `createDropFromBreak` merge furnace components with `module` |
| `/giveheart` | `createFreshHeartStack` — template clam defaults from config; **`clamId` not set** until placement assigns UUID |
| Heart placed from item | `onSearingHeartItemPlaced`: **new** `clamId` UUID; template fields from stack; `stubBuilt=false`, `status=0`; `registerClamForSearingPlace` |

## Migration (`migrateLoadedClamsToHeartBlocks`)

On **`SERVER_STARTED`**, for each registered clam whose heart chunk is loaded: if center block is **nether wart** or **obsidian** but not already the core block, **`placeHeartBlockForClam`** replaces it with the blast furnace heart so older worlds gain a furnace heart with synced `module`.

## Porting notes

- Recreate the same **logical** fields under `module` for save compatibility, or bump a format version (not present today) and migrate.
- **`dimension`** disambiguates same coordinates in different worlds; missing → `Clam` treats as overworld for `dimensionWorldKey()`.

## Related notes

- [[State-and-save]]
- [[Loader-integration]] — block identity (`VoidClamCoreBlocks.CORE_BLOCK`)
- [[Seek-caches-and-block-deltas]]
- [[Technical-documentation]]
