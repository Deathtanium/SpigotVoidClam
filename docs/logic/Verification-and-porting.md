# Verification and porting

## Version alignment

- Match **Minecraft** and **Fabric API** to `fabric.mod.json` **`depends`**.
- Java **17+** per `build.gradle` / mod metadata.

After porting hooks, run a dedicated server with the mod plus **vanilla client** Smoke tests below assume OP or trusted-player access to `/voidclam`.

## Smoke checklist

| # | Scenario | Expected |
|---|----------|----------|
| 1 | `/voidclam make` at clear spot | Heart blast furnace + stub; clam registered; `info` shows UUID |
| 2 | Break heart (survival/creative per design) | Coordinated kill; drop or break behavior per `onClamCoreBroken`; no duplicate vanilla furnace loot from mixin |
| 3 | Place **Searing Heart** item on valid spot | New `clamId`; template fields; `stubBuilt` false until fuel/stub path |
| 4 | `/voidclam reach` while awake (`status 1`) | `busyFlagMainCycle` cycle; path or clear busy; no server hang |
| 5 | Unload heart chunk during async path | Worker aborts (`shouldAbortAsyncPathfindingWork`); no stuck busy forever |
| 6 | `/voidclam grow` or `repair` | Seeks disabled for target until idle gate passes; then `clamReSize` or auto routine |
| 7 | `/voidclam kill` during path | Kill barrier; executor drain; clam removed from registry |
| 8 | Restart server with placed heart | `module` hydrates clam from BE; or migration places furnace if center still wart/obsidian |
| 9 | `seek_target_cache true` | Light/ore block changes near clam update caches without requiring manual `/reach` |
| 10 | `astar_mode sync_batched` | Progress without blocking tick indefinitely; jobs respect expansion cap config |

## Save compatibility

- Worlds must keep **`voidclam.module`** field meanings (see [[Persistence-and-schema]]). Removing or renaming keys requires a data fixer / migration step.
- **Coordinates** are not in `module`; corrupt moved hearts without updating BE position are user/world-edit concerns ([[Hivemind-future]]).

## Logging

- Unexpected stuck state when heart not tickable: **`voidclam/VoidClamMod`** logger **`WARN`** (residual activity); cite in bug reports with reason token (`dimension_not_loaded`, `heart_chunk_unloaded`, `heart_block_missing_or_wrong`).

## Related notes

- [[Technical-documentation]]
- [[Loader-integration]]
- [[Persistence-and-schema]]
- [[Tick-order-and-intervals]]
- [[Threading-queues-locks]]
