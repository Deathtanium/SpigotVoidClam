# VoidClam (Fabric)

Server-side Fabric mod for **Minecraft 1.21.x**. **Clams** are creatures made of blocks that consume energy and materials from their environment to repair and grow. They feed on light sources, seek ores, and convert blocks as they spread.

**Design overview (for promotion and non-developers):** [`VOIDCLAM-DESIGN.md`](VOIDCLAM-DESIGN.md).

## Behavior and porting

Loader-specific wiring (Fabric entrypoint, tick events, commands) lives in code under `src/`. **Loader-agnostic rules** — ticks, locks, queues, pathfinding, grow/repair, persistence — are documented in [`docs/logic/README.md`](docs/logic/README.md) (Obsidian-friendly `[[wikilinks]]` between notes). **Full technical reference** (save schema, mixins, cache algorithm, verification): [`docs/logic/Technical-documentation.md`](docs/logic/Technical-documentation.md).

## Features (summary)

- **Logic**: Clam types, energy, growth, A* pathfinding, shell building, light “food” list, base cost, blast resistance checks.
- **Concurrency**: `busyFlagMainCycle` (reach/path lifecycle); path results queued for the main thread; staggered placement via `VoidClamModScheduler` (world-time delayed runnables).
- **Persistence**: Each clam’s **heart block** (searing blast furnace) stores authoritative state in chunk save via `CUSTOM_DATA` (`SearingHeartItems`). See `docs/logic/State-and-save.md`.

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
| `repair <target>` | Safe repair when pathfinding idle |
| `reach <target>` | Pathfind toward nearest light/ore per flags |
| `grow <target>` | Safe grow +2 when idle |
| `seek ores\|lights\|protect set <true\|false> <target>` | **Bool before target** |
| `seek ores\|lights\|protect get <target>` | Read flag |
| `info` | Nearest clam (player) or list all (console) |
| `info <target>` | Detail for one clam |
| `status <target>` | Flags, grow/async, pools, scheduler (debug) |
| `storage <target>` | Count storage blocks reachable via wart from heart |
| `dumpnbt <target>` | Write searing heart block-entity NBT under `run/voidclam-nbt-dumps/` |
| `cleanup` / `roughcleanup` | Tendril display cleanup |
| `ping` | World name + registered clam count |

## Implementation notes

- **Environment**: Fabric server; `ServerWorld`, `BlockPos`, Brigadier commands, Fabric lifecycle and tick events.
- **Async pathfinding**: Fixed thread pool; pathfinding and some BFS run off-thread. Results and block writes are applied on the main tick thread. See `docs/logic/Threading-queues-locks.md`.
- **Delayed tasks**: `VoidClamModScheduler` runs runnables when `world.getTime()` reaches a stored deadline (see `docs/logic/Tick-order-and-intervals.md`).
- **Auto grow/repair**: Staggered per heart block entity on the overworld (~5 min cadence), not a global scan of all clams.

The Maven `pom.xml` in the repo is legacy reference only; the build is Gradle.
