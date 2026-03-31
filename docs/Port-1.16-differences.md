# VoidClam on Minecraft 1.16.5 (branch `fabric-1.16`)

This branch targets **Fabric, Minecraft 1.16.5**, **Java 8** bytecode, and older Yarn/API shapes. The following summarizes **behavior and feature differences** versus the main **1.21.x** line (`master`).

## Removed or replaced

### Data-driven items and block entities (1.20.5+ stack components)

- **Removed:** `DataComponentTypes`, `ComponentMap`, `NbtComponent`, `applyComponentsFrom`, `getComponents`, etc.
- **Replacement:** **`ItemStack` NBT** (`getTag` / `setTag`) for the Searing Heart; **`AbstractFurnaceBlockEntity.writeNbt` / `fromTag`** for in-world heart persistence. Dimension keys use **`RegistryKey.of(Registry.WORLD_KEY, id)`**.

### Tendril pulse visuals (block display entities)

- **Removed:** **`DisplayEntity` / `BlockDisplayEntity`** (introduced after 1.16): no scaled nether-wart “pulse” meshes, no reflection on `TrackedData`, no FOV/culling checks for displays.
- **Replacement:** When VFX is enabled and a player could have seen the cell, pulses still run the **same completion callback** after the same tick delay, but **there is no on-screen animation**—only timing is preserved.

### Display cleanup commands

- **`cleanupAllNetherWartDisplays`** / related global sweeps: **effectively no-op** (no block display entities exist).
- **`cleanupStrayDisplays`**: **no-op** (tags were used for our displays only).

### Commands and chat (1.21-era APIs)

- **Removed:** **`CommandRegistrationCallback` v2** (`CommandRegistryAccess`, `RegistrationEnvironment`).
- **Replacement:** **v1** registration (`CommandRegistrationCallback` with `(dispatcher, dedicated)`).

- **Removed:** **Brigadier permission types** (`Permission`, `PermissionLevel.GAMEMASTERS`).
- **Replacement:** **`ServerCommandSource.hasPermissionLevel(2)`** for operator/trusted checks.

- **Removed:** **`Text.literal`**, **`ServerCommandSource.sendMessage`** where unavailable.
- **Replacement:** **`LiteralText`** + **`sendFeedback(..., boolean)`**, **`getMinecraftServer()`** instead of **`getServer()`**.

- **Removed:** **`/voidclam ping`** click-to-copy UUID (**`ClickEvent` / `HoverEvent`** styling used on 1.21).
- **Replacement:** **Plain text** UUID line only.

### Natural spawning (Fabric lifecycle)

- **Removed:** **`ServerChunkEvents.CHUNK_GENERATE`** (not present on Fabric API for 1.16).
- **Replacement:** **`ServerChunkEvents.CHUNK_LOAD`**. Same handler is invoked; semantics differ from “freshly generated chunk only” (see code comments). Dungeon spawner scanning still runs on chunk load.

### World generation / block sets (blocks that do not exist in 1.16)

- **Ore recognition and fortune tables:** **Deepslate** ore variants and **copper** ore/ingot entries **removed** from static sets and `Pathfinder` fortune-3 tables.
- **Growth pass-through:** Tags **`sculk_replaceable`** / **`pale_moss_replace`** **do not exist** in 1.16; that branch of **`isGrowthPassThrough`** **always false** (no sculk/pale-moss-style pass-through Growth).

### Defense SFX

- **Removed:** **Goat horn** subset for defense audio (1.19+).
- **Replacement:** **`SoundEvents.BLOCK_NOTE_BLOCK_BASS`** only for that effect.

### Mixin and loot targets

- **`AbstractFurnaceBlockEntity`:** 1.18+ **static** `tick(World, BlockPos, …)` injection **replaced** by **instance** **`tick()`** tail inject (1.16 signature).
- **Blast furnace duplicate loot:** inject moved to **`AbstractBlock#getDroppedStacks`** with **`LootContext.Builder`** (not `LootWorldContext`).

### Java platform (this branch vs modern JDK)

- **Language/runtime:** **Java 8** (no records, pattern `instanceof`, `var`, `Set.of`, `Stream.toList()`, `Path.of`, `Files.readString` in shipped code paths).
- **Libraries:** **`slf4j-api`** and **`gson`** are **jar-in-jar** (`include` in `build.gradle`) so they load with the mod on a vanilla game classpath.

## Unchanged in spirit

Core loop remains: clams, pathfinding, grow/repair, searing heart blast furnace storage, commands, config file, mixins for light-cache deltas and placement, etc.—adapted to 1.16 types and packages (`net.minecraft.util.registry.*`, older `ItemStack`/NBT/block entity APIs).

## Primary line

Gameplay and documentation for **current** versions live on **`master`** (see `AGENTS.md` and `docs/logic/`).
