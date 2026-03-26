# VoidClam (Fabric)

Server-side Fabric mod for **Minecraft 1.21.x**. Spreading SIVA-like organisms that feed on light sources, seek ores, and convert blocks.

## Behavior and porting

Loader-specific wiring (Fabric entrypoint, tick events, commands) lives in code under `src/`. **Loader-agnostic rules** — ticks, locks, queues, pathfinding, grow/repair, persistence — are documented in [`docs/logic/README.md`](docs/logic/README.md) (Obsidian-friendly `[[wikilinks]]` between notes).

## Features (summary)

- **Logic**: Module types, energy, growth, A* pathfinding, shell building, light “food” list, base cost, blast resistance checks.
- **Concurrency**: `busyFlagMainCycle` (reach/path lifecycle); path results queued for the main thread; staggered placement via `VoidClamModScheduler` (world-time delayed runnables).
- **Persistence**: Each clam’s **heart block** stores state in the world (block entity / data component). Optional CSV `modules.siva` at the world save root for legacy import/export — see `docs/logic/State-and-save.md`.

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

All commands are under `/voidclam`. Run **`/voidclam`** or **`/voidclam help`** for a short in-game summary.

**Target** for most subcommands is either:

- A **UUID** (with hyphens, or 32 hex characters without hyphens), or  
- Three integers **`x y z`** — the **heart block** center in the current world.

| Command | Meaning |
|--------|---------|
| `make <x> <y> <z>` | Create a new clam (heart + stub) |
| `kill <target>` | Remove clam (coordinated kill) |
| `resize <size> <target>` | Set shell size (**size before target**) |
| `repair <target>` | Safe repair when pathfinding idle |
| `reach <target>` | Pathfind toward nearest light/ore per flags |
| `grow <target>` | Safe grow +2 when idle |
| `seek ores\|lights\|protect set <true\|false> <target>` | **Bool before target** |
| `seek ores\|lights\|protect get <target>` | Read flag |
| `info` | Nearest clam (player) or list all (console) |
| `info <target>` | Detail for one clam |
| `save` | Write `modules.siva` (**creates** file if missing) |
| `ingestlegacy` | Read `modules.siva` and spawn hearts + stubs for new rows |
| `cleanup` / `roughcleanup` | Tendril display cleanup |
| `ping` | World name + registered clam count |
| `testfile` | Print `modules.siva` lines if present |

## Implementation notes

- **Environment**: Fabric server; `ServerWorld`, `BlockPos`, Brigadier commands, Fabric lifecycle and tick events.
- **Async pathfinding**: Fixed thread pool; pathfinding and some BFS run off-thread. Results and block writes are applied on the main tick thread. See `docs/logic/Threading-queues-locks.md`.
- **Delayed tasks**: `VoidClamModScheduler` runs runnables when `world.getTime()` reaches a stored deadline (see `docs/logic/Tick-order-and-intervals.md`).
- **Auto grow/repair**: Staggered per heart block entity on the overworld (~5 min cadence), not a global scan of all clams.

The Maven `pom.xml` in the repo is legacy reference only; the build is Gradle.
