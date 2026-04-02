# VoidClam — design document

**Audience:** Players, server owners, content creators, and anyone evaluating the mod **without reading the source code**.  
**Mod:** **VoidClam** — a **server-side Fabric mod** for **Minecraft Java 1.21.x** (`voidclam`).  
**Companion docs:** Implementation detail lives under [`docs/logic/`](docs/logic/README.md); this file is the **promotional and conceptual** overview.

---

## One-sentence pitch

**VoidClam adds “clams” — block-made organisms** that **pathfind through the world**, **eat light sources**, **seek ores**, **grow and repair layered shells**, and **reshape terrain**, while staying **joinable by vanilla clients** (no custom blocks or client mod required).

---

## What problem does vanilla compatibility solve?

The mod is **server-only**: players use the **stock Minecraft client**. That means the heart, body, and visuals must be built from **vanilla blocks and entities the client already understands** (for example a repurposed blast furnace core, nether wart “tissue,” obsidian shell, block display pulses).  

**Tradeoff:** Some behaviors are implemented in ways that are heavier or more indirect than a hypothetical Mojang feature with **custom blocks and client-side models**. The **gameplay design** does not depend on those tricks; they exist so **unmodded players can join** the same server.

---

## Core fantasy: a creature made of blocks

A **clam** is not a standard mob with a single hitbox. It is a **persistent structure** in the world:

- A **heart** (center block) holds the creature’s identity and saved state.
- **Tissue** (nether wart paths and replaced blocks) is how it **moves and grows**.
- An **obsidian shell** (and related geometry) defines its **size** and **protection**.
- Everything is **real blocks**: mining, explosions, fluids, and redstone interact with it like terrain.

That makes the clam feel like **part of the world** rather than an overlay on it.

---

## The heart (“Searing Heart”)

- The heart uses the **blast furnace** block type so **vanilla clients render it** without a resource pack.
- In-world, it is distinguished as a **Searing Heart** (naming and data on the item/block entity).
- **Thermal / wake behavior:** The furnace’s **fuel slot** can accept items that **wake** a dormant clam; an **active**, “hot” heart is harder to break in survival until the clam is **cooled** (for example by **fully surrounding the heart with ice**, which can put it into a dormant state where the core can be mined).
- **Breaking the heart** removes the clam from the world (coordinated cleanup), with drops that can carry **saved clam data** for placement elsewhere.

---

## Awake vs asleep

- **Awake** clams **seek**, **pathfind**, **defend** (when enabled and large enough), and **pulse** visually.
- **Asleep** clams are **dormant** until fueled or otherwise woken per mod rules.
- The heart’s **lit state** is kept in sync with whether the clam is awake, so the block **looks** active or cold from a distance.

---

## Growth and size

- Each clam has a **size** value that controls **how far** it scans for food, **how large** its shell is, and **defense volume**.
- **Growing** increases size within a **configured maximum**.
- **Repair** rebuilds damaged shell geometry to match the recorded size.
- **Auto repair / grow** can run on a **staggered schedule** (per clam, tied to world time) so many clams do not all update in the same tick.
- **Safe grow and repair** (including player commands) wait until the clam is **not busy** pathfinding and **shell animations** are not mid-flight, so states stay consistent.

---

## Energy and materials

- **Energy** is an internal resource used when paths **fail** or stall (roughly: difficult movement **costs** energy).
- **Light blocks** consumed at the **end** of a successful path typically **restore** energy (feeding).
- **Material** tracks another internal economy tied to **ores** and repair hunger (including thresholds in config for when ore-seeking is prioritized).
- Together, these push the clam to **keep moving toward food** and **pay a cost** when the world fights back.

---

## Seeking: lights and ores

- **Seek lights:** The clam periodically looks for **light-emitting blocks** within a **box around itself** (scaled by size), then tries to **path toward the nearest** (with tie-breaking rules when both light and ore exist).
- **Seek ores:** Same idea for **ore blocks**—useful for **strip-mining adjacency** and **emergent “prospecting”** (seeing where the clam tries to go can hint at **nearby ore**, at the cost of **terrain damage**).
- **Toggles:** Lights, ores, and **self-defense** can be turned on or off (defaults and commands), so server owners can tune aggression.

### Emergent effects (gameplay side)

- **Ore probing:** Because the creature **routes toward ores** when enabled, players can sometimes **infer ore presence** from its behavior—trading **information** against **destruction** and **risk**.
- **Cave darkness:** By **removing or consuming lights** along its routes, clams **indirectly make caves darker**, which can **increase hostile mob spawns** where players relied on torches—an **ecological** pressure separate from direct combat.

---

## Pathfinding and movement

- Paths are computed with **A\*** on a 3D grid around the clam, with **custom costs** (soft tissue is cheap; very hard blocks or block entities are expensive or impassable).
- A **reachability pre-check** avoids wasting work when a target is **caged** or unreachable.
- **Stamina** limits how far a single path can “chew” through hard terrain in one go; running out can **blacklist** a target and **drain energy**.
- Pathfinding can run **asynchronously** (worker pool) or in **sync batched** mode depending on config; **block changes** always apply on the **main server thread**.
- **Delayed placement** spreads tendril steps over time so one path does not freeze the server tick.

---

## Building and replacing terrain

- As the clam moves, it **replaces** many blocks with **nether wart** (its walkable tissue) and **manages** shell layers including **obsidian** where the design calls for a hard boundary.
- **Blast resistance** and **interior volume** checks influence whether **growth** is allowed (room to expand).
- Some **replaceable** blocks are converted; **containers and special stops** can **halt** a path so drops are not deleted carelessly.
- **Ore goals** can use **fortune-like** drops routed into **storage** when possible.

---

## Storage routing

- Items from broken blocks can be routed into **chests, trapped chests, or barrels** that are **reachable through the clam’s tissue** from its center, using search rules documented for parity.
- This gives a **logistics angle**: encase storage in the right place and the clam **feeds** it while mining.

---

## Visual and audio presentation

- **Tendril pulse:** When blocks change, the mod often shows a brief **scaled block display** (nether wart) that **shrinks** before the real block appears—**spectacle** on vanilla clients.
- **Omnidirectional pulse:** A periodic wave can spread from each clam with **batched** updates so ticks stay smooth.
- **Heartbeat:** Conduit-like **ambient sound** at the core, **louder** as the clam grows.
- **Defense:** Large clams with protection enabled can **apply status effects** and **convert nearby blocks** when players intrude into a defined **octahedron** region around the core.
- **Config** can tone down or disable **VFX** and adjust **SFX** volume.

These effects exist because the server **cannot** rely on custom block models on the client; a native game implementation could move much of this to **client-side rendering** of dedicated blocks.

---

## Natural spawning (optional)

If enabled in config, new **Overworld** chunks can **roll** to spawn a clam near **chunk surface**, or a **dungeon** method can **replace spawners** with clams at configurable rates. **Defaults are conservative**; turn features on deliberately.

---

## Operator commands

Server operators (and configured trusted players) get a **`/voidclam`** command tree to **create**, **remove**, **inspect**, **trigger reach**, **repair**, **grow**, **toggle seeks**, **dump debug NBT**, and more. **Survival-facing balance** does not assume players use these; they are for **admins and showcases**.

---

## Persistence and worlds

- Each clam’s **authoritative save data** lives in its **heart block entity** in the chunk file (custom data components / NBT).
- **Ephemeral** runtime data (path blacklists, seek caches) is rebuilt after load.
- Worlds remain **compatible with vanilla clients**; only the **server** needs the mod.

---

## Configuration

A **`voidclam.json`** on the server controls spawn rates, pathfinding modes, intervals, size caps, defense timing, VFX/SFX, cache behavior, and more. See **`docs/logic/Configuration.md`** for the full key list.

---

## Design lineage and future directions

VoidClam is a **proof of concept** for **light-as-food**, **block-bodied** antagonists—**parallel in spirit** to **sound-based** threats like the Warden, but with a different verb (**illumination** vs **vibration**).  

A hypothetical **official** version could replace many implementation shortcuts with **dedicated blocks** (unified tissue, hardened states, custom core, tagged pathfinding helpers, native storage blocks), lowering server cost and clarifying visuals—while keeping the **same gameplay pillars**: **growth**, **hunger for light**, **ore tension**, **terrain risk**, and **emergent** player uses.

---

## Summary table

| Concept | Player-facing description |
|--------|----------------------------|
| **Heart** | Core block; holds save data; fuel wakes; ice can dormify; break removes clam |
| **Tissue** | Nether-wart paths the clam walks and expands along |
| **Shell** | Obsidian-heavy boundary; size scales with growth |
| **Seek lights** | Finds torches/glowstone/etc.; **eats** light for energy |
| **Seek ores** | Routes toward ore; **material** economy; **prospecting** side effect |
| **Defense** | Optional intrusion effects when large enough |
| **Pulse / VFX** | Vanilla-friendly spectacle on unmodded clients |
| **Commands** | Admin tooling under `/voidclam` |
| **Config** | Tunable aggression, performance, and spawn |

---

*Document version follows the mod in repository; for exact behavior, the implementation in `src/main/java/com/serbanstein/voidclam/` remains the source of truth.*
