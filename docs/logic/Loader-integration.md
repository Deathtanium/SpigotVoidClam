# Loader integration (Fabric)

**Target loader:** Fabric, **server-only** (`fabric.mod.json` `environment: server`). No client entrypoint.

## Mod metadata (`src/main/resources/fabric.mod.json`)

| Field | Value (conceptual) |
|-------|---------------------|
| `id` | `voidclam` |
| `entrypoints.main` | `com.serbanstein.voidclam.VoidClamModEntry` |
| `mixins` | `voidclam.mixins.json` |
| `depends` | `fabricloader`, `minecraft` (see file for exact range), `java >= 17`, `fabric-api` |

**Version pin:** use `depends.minecraft` in `fabric.mod.json` as the single source of truth for the supported game version line.

## Mixin catalogue (`voidclam.mixins.json`)

Package: `com.serbanstein.voidclam.mixin`. **Compatibility level:** `JAVA_17`.

| Mixin class | Target | Injection | Purpose |
|-------------|--------|-----------|---------|
| `AbstractFurnaceBlockEntityMixin` | `AbstractFurnaceBlockEntity` | `tick` **TAIL** | If state is **`VoidClamCoreBlocks.CORE_BLOCK`**: `tryRegisterFromClamCoreBlockEntity`, then force **`LIT`** blockstate to match `clam.status == 1` |
| `AbstractFurnaceBlockMixin` | `AbstractFurnaceBlock` | `getDroppedStacks` **HEAD**, cancellable | If loot position has a registered clam at that pos in `ServerWorld`, return **empty list** (suppress default furnace drops; heart drop handled elsewhere) |
| `BlockItemPlaceMixin` | `BlockItem` | `place` **HEAD** / **RETURN** | Capture stack copy when placing **Searing Heart** blast furnace; on success in `ServerWorld` on **core block** state, `onSearingHeartItemPlaced` |
| `WorldLightCacheMixin` | `World` | `setBlockState` **HEAD** (push old state) / **RETURN** (on success) | Queue **light/ore cache deltas** for `ServerWorld` (avoid scanning inside `setBlockState`) |

Another loader must reproduce **equivalent** behavior: furnace tick hook for registration + lit sync, loot suppression at clam cores, placement hook for heart items, and a global block-change notification for cache maintenance.

## Fabric API events (`VoidClamModEntry.onInitialize`)

| Event | Callback | Role |
|-------|----------|------|
| `PlayerBlockBreakEvents.BEFORE` | `captureClamCoreComponentsBeforeBreak` | Capture BE components for heart drop |
| `PlayerBlockBreakEvents.CANCELED` | `clearBreakingClamCoreComponentsCapture` | Clear thread-local |
| `PlayerBlockBreakEvents.AFTER` | `onClamCoreBroken` if core block | Kill clam, drops, cleanup |
| `UseBlockCallback.EVENT` | If core + clam exists → `applySearingHeartBlockLabel` | Rename hint on interact |
| `ServerChunkEvents.CHUNK_GENERATE` | `NaturalSpawnHandler.onChunkGenerated` | Optional natural spawn |
| `ServerLifecycleEvents.SERVER_STARTED` | Reload config, `onAsyncPathfindingSessionStart`, `migrateLoadedClamsToHeartBlocks`, `seedAutoGrowScheduleForAllClams` per world |
| `ServerLifecycleEvents.SERVER_STOPPING` | `onAsyncPathfindingSessionStop` | Shutdown pathfinder pool, barriers |
| `ServerTickEvents.END_SERVER_TICK` | `onServerTick` | Full mod tick (see [[Tick-order-and-intervals]]) |
| `CommandRegistrationCallback.EVENT` | `registerCommands` | Brigadier `/voidclam` tree |

## Vanilla blocks used as identities

Defined in **`VoidClamCoreBlocks`**:

- **`CORE_BLOCK`**: `Blocks.BLAST_FURNACE` — clam heart (vanilla ID so vanilla clients can join).
- Path / pulse traversal: **`Blocks.NETHER_WART_BLOCK`** and core share walk rules (`isWartOrCore`).

No custom block registry entries for the core.

## Commands (permission)

- Tree: `CommandManager.literal("voidclam")`.
- **`PermissionLevel.GAMEMASTERS`** (OP 2) **or** hard-coded trusted player name set in **`VoidClamModEntry`** (case-insensitive) may run commands.

Full subcommand list: project **`README.md`** table; parity-sensitive behavior is in [[Grow-repair-and-energy]], [[Pathfinding-and-reach]].

## Related notes

- [[Persistence-and-schema]]
- [[Seek-caches-and-block-deltas]] (World mixin)
- [[Natural-spawn]] (this file’s spawn entry point; see dedicated note)
- [[Technical-documentation]]
