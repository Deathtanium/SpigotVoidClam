# VoidClam Fabric Mod — Project Tour

A short tour of the project for someone new to Fabric modding. Covers structure, important files, and known issues or sub-optimal practices.

---

## 1. How a Fabric mod is wired

- **`fabric.mod.json`** (in `src/main/resources/`) is the mod manifest. Fabric Loader reads it when the game starts.
  - **`entrypoints.main`** → your mod’s “main” class that runs at load time.
  - **`environment`** — see manifest; server logic is driven from the server tick.
  - **`mixins`** → list of mixin config JSON files (see below).
- **`ModInitializer`** is the main entrypoint interface. Your class (`VoidClamModEntry`) implements it; Fabric calls `onInitialize()` once when the mod loads.
- **Fabric API** provides events (lifecycle, tick, commands, etc.). You register callbacks; the game calls them at the right time.

So the flow is: **Fabric Loader** → reads **fabric.mod.json** → loads your mod → calls **VoidClamModEntry.onInitialize()** → you register lifecycle, tick, and command callbacks.

---

## 2. Project layout (what lives where)

```
VoidClam/
├── build.gradle              # Build config (Loom, deps, Java 17)
├── gradle.properties         # Versions (Minecraft, Fabric API, mod version)
├── settings.gradle           # Project name
├── docs/logic/               # Behavior docs (Obsidian-friendly, loader-agnostic)
├── src/main/
│   ├── java/.../voidclam/
│   │   ├── VoidClamModEntry.java   # ★ Entrypoint: init, commands, tick hook
│   │   ├── VoidClamMod.java        # ★ Core state, save/load, module helpers
│   │   ├── VoidClamModScheduler.java  # Delayed “run later” on main thread
│   │   ├── CommandToolbox.java     # Building shells, stub, pathfinding trigger
│   │   ├── Pathfinder.java         # A* pathfinding + applying path (blocks)
│   │   ├── Module.java             # Data: one VoidClam node
│   │   ├── Node.java               # A* node
│   │   ├── Cursor.java             # 6-direction offset for A*
│   │   └── mixin/                  # Mixin classes (when registered in voidclam.mixins.json)
│   └── resources/
│       ├── fabric.mod.json   # ★ Mod manifest (id, entrypoints, mixins)
│       └── voidclam.mixins.json   # Declares which mixin classes apply
└── pom.xml                   # Legacy Maven reference; build is Gradle
```

---

## 3. Files you should pay attention to

### Must-know

| File | Role |
|------|------|
| **`fabric.mod.json`** | Mod id, version, entrypoint, mixins, dependencies. Change mod id/version here. |
| **`VoidClamModEntry.java`** | Entrypoint: `onInitialize()` registers server start/stop, **every-tick** callback, and all commands. Start here to see “when” things run. |
| **`VoidClamMod.java`** | Global state (modules array, targets queue), save/load (CSV in world folder), and helpers called by Pathfinder/CommandToolbox/scheduler. |
| **`voidclam.mixins.json`** | Tells Mixin which package/classes to load. Add new mixins here and in the `mixins` array. |

### Core logic

| File | Role |
|------|------|
| **`Pathfinder.java`** | A* from a module to a target block; enqueues result for main thread; `buildPath` applies the path (place blocks + sounds). |
| **`CommandToolbox.java`** | Builds stub/shells in the world, triggers pathfinding (via executor), resize/repair. Uses `VoidClamMod.scheduleDelayed` for delayed block placement. |
| **`VoidClamModScheduler.java`** | “Run this runnable on the main thread after N game ticks.” Used for staggered path application and stub building. |

### Data / support

| File | Role |
|------|------|
| **`Module.java`** | One VoidClam: position, size, energy, type, blacklists, busy flags. |
| **`Node.java`** | One A* node (position, f/g/h, parent, module index). |

### Build / config (change when upgrading or tweaking)

| File | Role |
|------|------|
| **`build.gradle`** | Loom version, dependencies (Minecraft, Yarn mappings, Fabric API). |
| **`gradle.properties`** | Minecraft version, Fabric API version, mod version. |

---

## 4. How the main systems interact

- **Server start**: `ServerLifecycleEvents.SERVER_STARTED` → `VoidClamMod.load(server)` (read `modules.siva` from world save path).
- **Every tick**: `ServerTickEvents.END_SERVER_TICK` →  
  - Drain path queue and apply paths (`tickTargets` → `Pathfinder.buildPath`).  
  - Run due delayed tasks (`VoidClamModScheduler.tick`).  
  - Every 20 ticks: trigger reach for all modules (overworld), core integrity check.  
  - Every 4s: heartbeat sound.  
  - Every 5 min: auto-repair/grow, then save.
- **Commands**: Registered in `VoidClamModEntry.registerCommands` (Brigadier). All require op level 2.

So: **entrypoint** wires **lifecycle + tick + commands**; **state and save/load** live in **VoidClamMod**; **world changes** go through **Pathfinder** and **CommandToolbox**.

For exact order and intervals, see **`docs/logic/Tick-order-and-intervals.md`**.

---

## 5. Fabric concepts used here

- **ModInitializer** — entrypoint run once at load.
- **ServerLifecycleEvents** — server started / stopping (for load/save).
- **ServerTickEvents.END_SERVER_TICK** — run logic once per server tick (main thread).
- **CommandRegistrationCallback** — register commands.
- **Mixin** — inject into vanilla code without editing it. Config is in `voidclam.mixins.json`; classes live under `.../mixin/` when used.
- **ServerWorld** — the server-side world object. Block access, time, sounds, etc.
- **Yarn** — the mapping set that turns obfuscated names into readable ones. Loom applies it at build time.

---

## 6. Issues and sub-optimal practices

### Concurrency / thread safety

- **Pathfinding runs off the main thread** (`CommandToolbox.pathfinderExecutor`). It calls `world.getBlockState()` from that thread. In vanilla, the world is not guaranteed thread-safe; this can theoretically cause rare bugs or crashes if the world changes during pathfinding. If you see weird behaviour, consider moving pathfinding to the main thread (e.g. one module per tick) or copying chunk data for the search.
- **`VoidClamMod` state** (modules array, `moduleNumber`) is mutated from both main thread and executor. Index checks (e.g. `tno <= moduleNumber && modules[tno] != null`) reduce the risk but are not a full concurrency design; a stricter approach would be to confine all module mutations to the main thread and have the executor only produce path results.

### Design / structure

- **Global static state** in `VoidClamMod` (modules, queue, lights/baseCost sets) makes testing and “multiple worlds” harder. A cleaner design would be a per-server or per-world object holding modules and queue, injected or accessed from the server context.
- **Module array + index shifting**: Killing a module shifts indices and can make stored indices stale. The code tries to mitigate (e.g. core check iterates backwards). A list or map keyed by a stable id would avoid index invalidation.
- **`CommandToolbox`** both builds blocks and triggers pathfinding; splitting “world building” and “pathfinding trigger” could make the flow clearer.

### Minor / cleanup

- **Unused**: `VoidClamModEntry.TICK_REACH` is defined but the tick divisor used for reach is `TICK_TARGETS` (20). Either use `TICK_REACH` in the tick callback or remove it.
- **`busyFlagPlaceEvent`** on `Module` is not referenced in current sources.
- **Delayed tasks**: `VoidClamModScheduler.tick` matches `ServerWorld` by reference; `hasPendingTasks` uses dimension key — see `docs/logic/Threading-queues-locks.md`.

### Robustness

- **CSV save**: No validation of parsed numbers; malformed lines are skipped but bad data could leave `moduleNumber` or indices inconsistent. Adding basic validation or a single checksum line would help.
- **Executor**: The pathfinding executor is never shut down. On server stop you could add a shutdown step (e.g. in `ServerLifecycleEvents.SERVER_STOPPING`) to avoid “thread pool still running” warnings.
- **Delayed tasks**: `VoidClamModScheduler` never removes tasks for worlds that unload. If you add world unload handling, consider draining or cancelling pending tasks for that world.

---

## 7. Quick reference: where to change what

| Goal | File(s) to touch |
|------|-------------------|
| Change mod id / version | `fabric.mod.json`, `gradle.properties` |
| Add a command | `VoidClamModEntry.registerCommands` |
| Change when things tick | `VoidClamModEntry.onServerTick` (and constants) |
| Change save format or path | `VoidClamMod.load/save`, `getModulesPath` |
| Add another vanilla hook | New mixin class + entry in `voidclam.mixins.json` |
| Change which blocks are “light” or “base cost” | `VoidClamMod` static blocks (lights, baseCost) |
| Tweak A* or path application | `Pathfinder` |
| Tweak shell/stub building | `CommandToolbox` |

---

## 8. Running and building

- **Build**: `./gradlew build` (or `gradle build`). Output JAR: `build/libs/voidclam-<version>.jar`.
- **Run server (dev)**: `./gradlew runServer` (uses Loom’s `server` run config). Good for quick tests.

Put the JAR in the server’s `mods/` folder with Fabric Loader and Fabric API (and use the same Minecraft version as in `gradle.properties`).

---

This tour should be enough to navigate the project and understand how Fabric is used here. For deeper Fabric docs, see [Fabric Wiki](https://fabricmc.net/wiki/) and [Fabric API](https://github.com/FabricMC/fabric).
