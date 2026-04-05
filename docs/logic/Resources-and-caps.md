# Resources and caps

Heart (`Clam`) resources are persisted in **`SearingHeartItems`** / `voidclam.module` NBT (see [[Persistence-and-schema]]).

| Field | Cap | Gained from | Spent on |
|-------|-----|-------------|----------|
| `energy` | `10 × max(1, currentSize)` | Eating **light** blocks at path goal (`Pathfinder.buildPath`), `lightEnergyForBlock` | Failed path stamina (−1 in some cases); auto-grow uses `energy > clam_grow_energymultiplier × currentSize` before +1 size |
| `material` | same as energy | Ores (goal), shell repair | Auto-grow `clam_grow_material_cost`, obsidian repair in `clamReSize` |
| `soul` | **same as energy** | **Soul** light sources only: `minecraft:soul_fire`, `soul_torch`, `soul_wall_torch`, `soul_lantern`, **lit** `soul_campfire` — **+1 soul** per consumed goal block, plus **energy** matching the non-soul counterpart (`fire`, `torch`, `lantern`, lit `campfire` rules in `lightEnergyForSoulCounterpart`) | *None yet* (reserved) |
| `materialSeekThreshold` | same cap | Healthy auto cycles (+1) | (not spent — hunger bar for ore seeking) |

Soul sources are always valid **light** targets even when luminance is low (hard-coded before dynamic luminance rules). They are **not** in the static `lights` hash set; detection is `VoidClamMod.isSoulLightSource`.

## Related

- [[Grow-repair-and-energy]]
- [[Configuration]]
- [[Pathfinding-and-reach]]

