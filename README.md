# VoidClam (Fabric)

Server-side-only Fabric mod (1.21.1). Port of the Spigot VoidClam plugin: spreading SIVA-like organisms that feed on light sources and convert blocks.

## What’s preserved

- **Logic**: Module types, energy, growth, A* pathfinding, shell building, light “food” list, base cost, blast resistance checks.
- **Locks / queues**: `busyFlagPlaceEvent`, `busyFlagMainCycle`; path results enqueued and applied on main thread; delayed tasks via `VoidClamModScheduler`.
- **Save format**: Same CSV in world folder: `world/modules.siva` (and `modules.siva.old` rotation). Fields: type, x, y, z, currentSize, status, energy, age.

## Build

Requires **Java 17+**. The Gradle wrapper (Gradle 9.2.1) is included.

```bash
./gradlew build
```

Output: `build/libs/voidclam-1.0.0.jar`.

To run the server (optional):

```bash
./gradlew runServer
```

## Commands (OP level 2; hidden from non-OPs)

All commands are under `/voidclam` and only visible to players with OP level 2 or higher.

- `/voidclam make <x> <y> <z>` – create a new stub module
- `/voidclam kill <index>` – remove module
- `/voidclam resize <index> <size>` – set shell size
- `/voidclam repair <index>` – repair shell to current size
- `/voidclam reach <index>` – pathfind to nearest light
- `/voidclam info [index]` – info for nearest module or by index
- `/voidclam grow <index>` – force grow by 2
- `/voidclam save` – save to disk
- `/voidclam ping` – debug: world name + module count
- `/voidclam testfile` – debug: print `modules.siva` lines

## Differences from Spigot version

- **Environment**: Fabric server-only; no Bukkit. Uses `ServerWorld`, `BlockPos`, Brigadier commands, Fabric lifecycle and tick events.
- **Block place**: No Fabric “block place” event; uses a mixin on `Block.onBlockAdded` to detect light blocks and notify modules.
- **Async pathfinding**: Same idea (executor + queue); pathfinding runs off-thread, results applied on main tick. World reads from worker thread are unchanged from the plugin (same caveats).
- **Delayed tasks**: `VoidClamModScheduler` replaces Bukkit’s `runTaskLater` (world time + delay, run on next tick when due).
- **Save path**: `server.getSavePath(WorldSavePath.ROOT).resolve("modules.siva")` (world root = default world folder).
- **Bug fix**: Core-check loop iterates backwards when killing modules so indices stay correct after shift.

Old Spigot sources (`Main.java`, `VoidclamEventListener.java`) were removed so the project builds as a single Fabric mod; the Maven `pom.xml` is left for reference only.
