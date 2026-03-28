# Voidclams — backlog / to-do

Working notes for planned behavior and balance changes. Items are not implemented until removed or checked off here.

---

## Beacons

- Add **beacons** to the desired block list (lights / reach targets — align with existing light-seeking and pathfinding rules).
- **Special behavior when “eating” a beacon:** preserve the **nether star** (do not destroy with the rest of the break flow); place it into a **barrel** using the same storage routing patterns as other loot (container BFS / barrel fallback near clam center).

---

## Anti-player detection

- current sound is broken and using the default fallback. I want the sound to be the "Yearn" Goat Horn sound from vanilla minecraft, 0.5 pitch, with the default player detection interval being at least the length of this sound so it doesn't overlap

---

## Organic behavior

### 1. Stamina / distance penalty

- **Stamina cost** (or equivalent movement/action cost for tendrils / path application) should increase **exponentially** with distance from the clam’s **shell** (the octahedron built by the clam builder functions — not the blast-furnace **center** alone).
- Clam **size** should **reduce** this distance penalty (larger clams pay less marginal cost at the same world distance from shell).
- **Current status:** escalating stamina is intentionally disabled in code right now; path application uses flat stamina cost again until we revisit a stable model.

### 2. Energy economy

- Rework **energy** gains: assign **different energy values per light block type** (torch vs lantern vs glowstone vs … — enum/table in config or code).
- Treat **ores** as a **separate resource** from light energy; **ore search / pathfind to ores** only runs when that resource is **below a configurable threshold**. The search triggered by this hunger is different from the one used to extract resources and will not return ore loot

### 3. Repair / grow cycle and shell integrity

- During **repair-or-grow cycle** (`repairorgrowcycle` / auto grow–repair path):
  - Detect **damage to the shell** (missing / wrong blocks vs expected obsidian shell).
  - **Expend energy** and **ores** (when available) to **repair** damage.
  - **Only run growth** when **no shell damage** is detected **and** **sufficient room** exists for expansion (reuse or extend existing room checks). Blocks that have tag sculk_replaceable or pale_moss_replace (check if these are correct) can be considered non-obstacles for growth
  - brainstorm ideas for a computationally-cheap way of checking if small positional adjustments of the entire clam can be made (searing heart and all) to allow it to grow further, preferably when no players are looking at it
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

