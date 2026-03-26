# VoidClam — logic documentation (loader-agnostic)

This folder describes **game and simulation behavior** in terms any port can reuse: ticks, locks, queues, priorities, and why key functions exist. It is written for **humans** (e.g. in [Obsidian](https://obsidian.md)) and **coding agents**; links use Obsidian-style `[[wikilinks]]`.

## Map

| Note | Topic |
|------|--------|
| [[Overview]] | What a module is, high-level loop, main classes |
| [[State-and-save]] | Module fields, `modules.siva`, grow-pending globals |
| [[Tick-order-and-intervals]] | Per-tick order, repeating intervals, which world drives what |
| [[Threading-queues-locks]] | Executor, `targets` queue, scheduler, `busyFlagMainCycle` |
| [[Pathfinding-and-reach]] | A*, light vs ore tie-break, `buildPath`, stamina, blacklists |
| [[Grow-repair-and-energy]] | Safe grow/repair, per-heart auto routine, energy rules |
| [[Hivemind-future]] | Future inter-clam coordination (duplicates, WorldEdit) |
| [[Presentation-and-effects]] | Tendril pulses, defense, heartbeat (behavioral summary) |
| [[Key-classes]] | File → responsibility cheat sheet |

## Conventions

- **Main thread** means the server tick thread that mutates the world.
- **Fabric class names** appear where they name the current implementation; the *behavior* described is what a port must preserve unless intentionally changed.

## Source of truth

Java under `src/main/java/com/serbanstein/voidclam/` and resources under `src/main/resources/`.
