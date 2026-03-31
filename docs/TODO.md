# Voidclams — backlog / to-do

Working notes for planned behavior and balance changes. Items are not implemented until removed or checked off here.

---

## Organic behavior

### 1. Stamina / distance penalty

- **Stamina cost** (or equivalent movement/action cost for tendrils / path application) should increase **exponentially** with distance from the clam’s **shell** (the octahedron built by the clam builder functions — not the blast-furnace **center** alone).
- Clam **size** should **reduce** this distance penalty (larger clams pay less marginal cost at the same world distance from shell).
- **Current status:** escalating stamina is intentionally disabled in code right now; path application uses flat stamina cost again until we revisit a stable model.

### 2. Energy economy (polish)

- Optional: move the per-light **energy** table into **config** (today: beacon / “full block” set / default in `VoidClamMod.lightEnergyForBlock`).

### 3. Repair / grow cycle and shell integrity

- **Repair cost model:** auto repair still focuses on **material** for shell cells; this doc used to call for explicitly spending **energy** and **ores** on repair—decide if that should layer on top of material or replace part of it.
- **Growth nudge:** brainstorm computationally-cheap ways to try small positional adjustments of the whole clam (heart + volume) when growth is blocked, preferably when no players have line of sight.
  - Idea A: try a tiny fixed offset set (`(+/-1,0,0)`, `(0,0,+/-1)`, optionally `(0,+/-1,0)`), evaluate growth-room score with early-exit budget, pick first valid candidate.
  - Idea B: maintain a rolling "blocked direction" counter from failed growth checks, and only test offsets opposite the most frequent blockers.
  - Idea C: when no players have LOS, run one low-budget local search over offsets in Manhattan radius 2 with memoized obstacle samples from the previous attempt.

### 4. Testing — space checks

- **Test space / room checks** for **natural growth** (auto grow path) and **natural spawning** (wherever new clams are placed); cover edge cases (partial obstruction, tight cavities, shared boundaries, invalid floor).

---

## Notes

- Implementation should stay consistent with existing tick order, `busyFlagMainCycle`, and resize cooldown rules documented under `docs/logic/` (the logic docs may need to be updated btw)
- After shipping a bullet, either remove it from this file or mark it done and link the PR/commit if the team wants history.
- Investigate whether packet-forgery-based visual illusions are computationally cheaper than spawning and ticking full display entities for tendril VFX.
