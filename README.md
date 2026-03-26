# VoidClam (Fabric)

Server-side Fabric mod for **Minecraft 1.21.x**. Spreading SIVA-like organisms that feed on light sources, seek ores, and convert blocks.

## Behavior and porting

Loader-specific wiring (Fabric entrypoint, tick events, commands) lives in code under `src/`. **Loader-agnostic rules** — ticks, locks, queues, pathfinding, grow/repair, save format — are documented for humans and agents in [`docs/logic/README.md`](docs/logic/README.md) (Obsidian-friendly `[[wikilinks]]` between notes).

## Features (summary)

- **Logic**: Module types, energy, growth, A* pathfinding, shell building, light “food” list, base cost, blast resistance checks.
- **Concurrency**: `busyFlagMainCycle` (reach/path lifecycle); path results queued for the main thread; staggered placement via `VoidClamModScheduler` (world-time delayed runnables).
- **Save format**: CSV in the world save root: `modules.siva` (with `modules.siva.old` rotation). Fields: type, x, y, z, currentSize, status, energy, age, seekLights, seekOres.

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

## Implementation notes

- **Environment**: Fabric server; `ServerWorld`, `BlockPos`, Brigadier commands, Fabric lifecycle and tick events.
- **Async pathfinding**: Fixed thread pool; pathfinding and some BFS run off-thread. Results and block writes are applied on the main tick thread. World reads from workers are not thread-safe by vanilla contract — see `docs/logic/Threading-queues-locks.md`.
- **Delayed tasks**: `VoidClamModScheduler` runs runnables when `world.getTime()` reaches a stored deadline (see `docs/logic/Tick-order-and-intervals.md`).
- **Save path**: `server.getSavePath(WorldSavePath.ROOT).resolve("modules.siva")`.
- **Core check**: Iterates module indices **backwards** when killing invalid cores so index shifts do not skip entries.

The Maven `pom.xml` in the repo is legacy reference only; the build is Gradle.
