# AGENTS.md — guidance for automated coding agents

This file orients tools and contributors who edit the repository without deep prior context.

## Project

**VoidClam** is a **server-side Fabric mod** for **Minecraft 1.21.x**. It implements SIVA-like organisms that spread, consume light sources, seek ores, and convert blocks.

## Stack and layout

- **Language**: Java **17** (see `build.gradle`).
- **Build**: Gradle with **Fabric Loom**; wrapper is in-repo.
- **Mod ID / package**: `com.serbanstein.voidclam`.
- **Authoritative sources**: `src/main/java/com/serbanstein/voidclam/` and `src/main/resources/`.
- **Do not treat as source**: `jar_extracted/` is a decompiled or extracted copy for reference; **edit `src/` instead**.
- **Mixins**: `voidclam.mixins.json` declares package `com.serbanstein.voidclam.mixin`; the `mixins` array may be empty until mixins are added under `src/main/java/.../mixin/` and registered in that JSON.

## Commands agents should use

```bash
./gradlew build          # compile; output under build/libs/
./gradlew runServer      # optional local server (run dir: run/)
```

Requires a JDK **17+** on `PATH`.

## Architecture notes (high level)

- **Entry**: `VoidClamModEntry` — Fabric lifecycle, tick hooks, Brigadier commands (`/voidclam`, OP level 2).
- **Core state / logic**: `VoidClamMod`, `Module`, `Pathfinder`, `Cursor`, `Node`.
- **Threading**: Pathfinding runs off-thread; results are queued and applied on the **server main thread**. Respect `busyFlagMainCycle` (and any future use of `busyFlagPlaceEvent`) when changing concurrency.
- **Scheduling**: `VoidClamModScheduler` — delayed runnables keyed off **world time** (`world.getTime()`).
- **Persistence**: Heart block entities hold authoritative clam data in chunk NBT. Optional legacy CSV `modules.siva` at the save root (loaded if present; written on `/voidclam save` or when the file already exists). See `docs/logic/State-and-save.md`.

## Logic documentation (porting / behavior)

See **`docs/logic/README.md`** — Obsidian-style graph of notes on tick order, locks, queues, pathfinding, grow/repair, and key functions.

## Editing principles

- Prefer **minimal, behavior-preserving** changes unless the task explicitly changes gameplay or saves.
- Match existing style (naming, structure, comment density) in nearby files.
- After Java or resource changes, run **`./gradlew build`** before considering work done.
- If adding commands or server APIs, follow patterns in `VoidClamModEntry` and `CommandToolbox`.

## Further reading

- `README.md` — commands, build, high-level summary.
- `docs/logic/` — detailed behavior specification.
