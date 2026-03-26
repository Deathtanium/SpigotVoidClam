# AGENTS.md — guidance for automated coding agents

This file orients tools and contributors who edit the repository without deep prior context.

## Project

**VoidClam** is a **server-side-only Fabric mod** for **Minecraft 1.21.1**. It ports a Spigot plugin: SIVA-like organisms that spread, consume light sources, and convert blocks. Game logic, threading model, and save format are intentionally aligned with the original plugin (see `README.md`).

## Stack and layout

- **Language**: Java **17** (see `build.gradle`).
- **Build**: Gradle with **Fabric Loom**; wrapper is in-repo.
- **Mod ID / package**: `com.serbanstein.voidclam`.
- **Authoritative sources**: `src/main/java/com/serbanstein/voidclam/` and `src/main/resources/`.
- **Do not treat as source**: `jar_extracted/` is a decompiled or extracted copy for reference; **edit `src/` instead**.
- **Mixins**: `voidclam.mixins.json` declares package `com.serbanstein.voidclam.mixin` and may list no mixins yet; new mixins belong under `src/main/java/.../mixin/` and must be registered in that JSON.

## Commands agents should use

```bash
./gradlew build          # compile; output under build/libs/
./gradlew runServer      # optional local server (run dir: run/)
```

Requires a JDK **17+** on `PATH`.

## Architecture notes (high level)

- **Entry**: `VoidClamModEntry` — Fabric lifecycle, tick hooks, Brigadier commands (`/voidclam`, OP level 2).
- **Core state / logic**: `VoidClamMod`, `Module`, `Pathfinder`, `Cursor`, `Node`.
- **Threading**: Pathfinding runs off-thread; results are queued and applied on the **server main thread**. Respect existing locks/flags (`busyFlagPlaceEvent`, `busyFlagMainCycle`, etc.) when changing concurrency.
- **Scheduling**: `VoidClamModScheduler` replaces Bukkit-style delayed tasks (world time + delay).
- **Persistence**: World save CSV `modules.siva` (and `modules.siva.old` rotation) at the world root — format and field order matter for compatibility.

## Editing principles

- Prefer **minimal, behavior-preserving** changes unless the task explicitly changes gameplay or saves.
- Match existing style (naming, structure, comment density) in nearby files.
- After Java or resource changes, run **`./gradlew build`** before considering work done.
- If adding commands or server APIs, follow patterns in `VoidClamModEntry` and `CommandToolbox`.

## Further reading

- `README.md` — player-facing commands, Spigot vs Fabric differences, and save path details.
