A Voidclam is a feature made of blocks that looks like two pyramid shells with a fleshy interior. Instead of being a regular entity, it is effectively a living structure in the world, with a heart block storing its data, flesh mass which includes inside the shell and the growing tendrils it uses to reach targets, and its outer hard shell, which is hardened flesh.

❌ - not implemented or nowhere near functional
? - possibly partially implemtented but never tested or unsure about functionality
✅ - reasonably confident about proper implementation

**Branches (roadmap):** `master` — Fabric **1.21.x**, server-first (blast furnace heart). **`fabric-1.16`** — gameplay parity, older MC. **Future full-feature** — custom heart/flesh/shell + client presentation (not started here).

Abstract feature list:
- ❌ TODO: for ALL features that can be configured to run async or sync_batched, there will be a :
    - ❌ unified pool of "actions per tick" for sync_batched; each action made by any clam, regardless of what action it is, takes up 1 from this global action pool; 
    - ❌ for async, have one thread per async job with no per-tick cap and pray the server using this mod never has too many clams active at once; later implement a configurable minimum distance between clams so clams too close to each other won't wake up, then write it in this feature list and remove this snippet; naturally, async processes need to submit their results to a cross-thread-accessible location where they can be consumed by the main thread; A* already does this properly, not sure about the others;
    - ? current implementation has separate async vs sync_batched flags for various processes and separate action per tick pools; the goal is to keep sync toggle separation but unify action-per-tick pools into one and ensure all of this is settable in the config file

- ✅ "reach" function; able to detect, pathfind to, and consume block targets within 4 x currentSize of itself:
    - ✅ **one main activity pipeline at a time** via `Clam.mainCycleBusy` (reach / path enqueue through `CommandToolbox.clamReach`, cleared in `VoidClamMod.releasePathfindingMainCycle` and when path-apply finishes) plus `pathApplyPendingSteps`. **`VoidClamMod.isGrowRepairPendingForClam`** blocks `clamReach` while a repair/grow cycle is queued (auto or command), so reach cannot run in parallel with that phase.
    - ✅ A* pathfinding; needs to look organic. This was made to effectively find paths for the clam's tendrils to dig through
        - ✅ conditional costs
            - ✅ goal cell uses the shared `isGoalCell` path through `aStarNeighborCost` / `wallStep` so block-entity goals (e.g. beacon) are not treated as impassable walls; not a one-block special case
            - ✅ cost of the heart block needs to be ignored
            - ✅ hardness-based (manual overrides for blocks with unusual hardness)
            - ✅ gravity-based: open-air uses wall-adjacency cost; **water** does not use that gravity term — open water costs **2**, water next to any neighbor that is **not** water, **not** plain air, and **not** clam flesh/core (nether wart / heart) costs **1** (preferred, hugs *environment* undersea, not only tendrils)
        - ✅ sync_batched vs async mode
            
    - ✅ target reachability pre-pass with BFS; this suffers from "stale data" issue, where if target reachability changes in the brief window between the prepass and A*, A* can run for an unacceptable amount of time; Proposed fix is to save block type to memory while the BFS builds the reachability map, then run A* on that map; this cuts in half direct world accesses, and allows stale blocks in the BFS stage to be detected at the path build stage; this will be superseeded by the "reachability map" feature
        - ✅ prepass edge admission **matches A\***: a step is allowed iff that step’s `aStarNeighborCost` is strictly below the wall sentinel `2500` (same wall vs passable tie‑break as A\*, including goal exception for block entities); BFS does not sum numeric costs
        - ? async mode (haven't tested yet)
        - ✅ sync_batched vs async mode
    - ✅ volatile target cache, to ignore unreachable blocks; I want to deprecate this in favor of the "reachability map" feature
    - ❌ reachability map. This rethinks a lot of the process up to and including A*
        - ❌ BFS reachability map; clam performs BFS (range limit equal to "reach" range limit) with A* costs to decide reachable and unreachable and stores all the reachable blocks' coordinates, material type, cost and BFS distance. Need to carefully consider what data structure to store this in, to allow easy coordinate-based stepping.
            - ❌ debug function to monitor RAM usage of this. In the open, this basically stores ~99% of blocks in range; for clam size 15, this is 216k x bytes taken by one map location
            - ❌ config toggle between:
                - ❌ reachability map is volatile and computed on-demand and released when the job is done
                - ❌ reachability map is computed the moment the clam is in a loaded chunk, delta'd on block change event in range, rebuilt completely on clam repair event and released when the clam "no longer exists" (which happens on clam death or chunk unload)
                    - ❌ delta also needs to occur when voidclams themselves perform block changes
            - ❌ reachability map rebuild, sync_batched vs async
        - ❌ allow BFS distance to be used as heuristic in A*
        - ❌ target is chosen only from the reachability map; closest target is chosen based on BFS distance and A* begins computing the path on this map
    - ✅ path build after calculation
        - ✅ final, atomic, sanity check; path will stop early:
            - ✅ after a block has been broken
                - ✅ applies to **solid** breaks only (hardness heuristic aligned with pathfinding “stickiness”: soft replacement continues the path)
            - ? before stepping into a block that has changed since the pathfinding stage (e.g. if a player or another voidclam); **expect this to be superseded** by the **reachability map** (single coherent model for search + apply). After the map is implemented and validated, **remove this bullet** (and prune other reach/prepass/cache bullets the map replaces) so FEATURES stays minimal
        - ? stamina cost based on block cost to not have the entire path be built all at once; known issue (not planning to fix) is that this results in repeated re-calculations 
        - ✅ block break preservation; when path building is about to replace a solid block, its drop item is calculated (either itself or some arbitrary loot in some cases), then a BFS search from the heart is started for a storage block connected via the flesh blocks and the closest container is chosen that can fit the loot; if no such storage block exists, one is created on the spot, like an "organic storage nodule" (it's a barrel on vanilla-compatible versions) and future loot will naturally flow into that
            - ✅ storage/container BFS uses `VoidClamConfig#bfsBlockBfsExecutionMode` (same `bfs_mode` as other BFS: `sync_batched` vs background worker + server-thread completion)
    - NOTE: considering replacing A* altogether with creating a 
        
- ✅ resource budget
    - ✅ **`soul`** resource (persisted on heart): same cap as **`energy`** (`10×size`); +1 when a **soul-fire family** light is eaten at path goal (`soul_fire`, soul torch/wall torch/lantern, lit soul campfire); **not consumed** by any system yet (see `docs/logic/Resources-and-caps.md`)
    - ✅ clams mainly eat lights for the "energy" resource via the "reach" function
        - ✅ per-block light energy tiers in `VoidClamMod.lightEnergyForBlock` / `lightEnergyForSoulCounterpart` (manual table + soul-fire family parity)
        - ✅ beacon at goal: nether star preserved and routed through the same container BFS / barrel fallback as other break preservation (`Pathfinder.buildPath`)
        - ✅ optional **dynamic lights**: `clam_light_detect_dynamic` + `clam_light_luminance_min` + `c:lights` (`clam_light_detect_c_lights_tag`) + `clam_light_block_allowlist` / `clam_light_block_denylist`; default off (static set + copper heuristic only)
        
    - ✅ clams eat ores for the "material" resource (path goal handling + threshold-gated seeking below)
        - ✅ while clams always seek lights when enabled, **material / ore hunger** uses each heart’s persisted **`materialSeekThreshold`**: when `material < threshold`, ore-style replenishment pathing can run (with `seekLights`). Baseline is `min(config, 10×currentSize)` (same cap as max material); threshold **+1** each **auto** cycle with **no** shell damage, **capped** at that maximum; it **resets** to the baseline whenever the clam **grows** (`currentSize` up).
        - ✅ method to include **modded ores**: when `clam_ore_detect_with_c_ores_tag` is true (default), `c:ores` counts as ore **in addition to** the built-in vanilla list; turn off for legacy behavior only
        
- ✅ growing (auto routine)
    - ✅ room check for **auto** grow: `runAutoGrowRoutineSingle` scans a box around the heart and sums **blast resistance** of non-pass-through blocks; grow allowed if sum ≤ `10 × currentSize` (`VoidClamMod.isGrowthPassThrough`)
        - ✅ pass-through: air, water, lava, obsidian, nether wart, heart, plus blocks in **`minecraft:sculk_replaceable`** and **`minecraft:pale_moss_replace`**
        - ❌ generic “all natural blocks ignore” — only the explicit pass-through set above; no broad biome tag
        - ✅ material cost for **auto** grow: `clam_grow_material_cost` (default `0`); each successful +1 size in the scheduled auto routine debits this much material if non-zero; `/voidclam grow` unchanged
        
- ✅ repairing 
    - ✅ costs and consumes "material"
    - ✅ one block at a time
    
- ✅ behavior
    - ✅ every (configurable) seconds, the clam attempts to locate and "reach" for a target, depending on its priorities
        - ✅ ticking uses **server world time** with **phase offsets** per clam (loaded chunks); this is the supported model for vanilla-compat (blast furnace heart) and fabric-1.16 (**parity**). **Decided not to implement** using Minecraft’s **random block tick** sampling to stagger reach/core logic for now: it does not map cleanly onto the blast-furnace heart and would fork behavior or require fragile mixins; **revisit only** if useful on the **full-feature** build (custom heart + client presentation), not as the driver of core reach on server-only flavors
        - ✅ every seek tick, `clam_seek_attempt_probability` (default `1`) gates whether `clamReach` runs (orthogonal to random block ticks)
    - ✅ every (configurable) seconds, (or once those seconds expire, I prefer this) the clam runs a repair/grow cycle. During this cycle, it's not allowed to "reach". If damage is detected, it will attempt to repair itself. If not, it will check if it can grow
    - see flow below for more details
    
- natural spawning:
    - ✅ partial — `NaturalSpawnHandler`: **default** mode places a stub at chunk surface after clearing a sphere (`clam_spawn_natural`, `clam_spawn_natural_default_chunk_chance`); **dungeon** mode can replace mob spawners (`clam_spawn_natural_method`, `clam_spawn_natural_dungeon_rate`). All use `VoidClamMod.makeStub`.
    - ❌ vanilla structure / jigsaw integration (still ad-hoc placement)
    - ✅ `makeStub` currently creates a **size 3** teen clam and runs `buildStub` (not a 5–9 size roll)
    - ? chunk-center mode clears a sphere then spawns; **not** the same pass-through / resistance scoring as auto-grow room check
    
- composition
    - Searing Heart
        - ✅ vanilla: Blast Furnace that stores custom data that the mod uses to tick clams in loaded chunks; 
            - ✅ state - dormant: it stores nearly no data except for the fact that it's a voidclam so that the mod knows to awaken it when 
                - ✅ a fuel item is placed in the fuel slot (i want to replace this with any valid light block)
                - ✅ two completed **repair / shell-resize** chains after placement (`repairWakeCyclesRemaining` / `SEARING_WAKE_REPAIR_CYCLES`): when the count reaches zero, `finishSearingWakeAfterRepairCycles` runs (unless woken earlier by fuel/light)
            - ✅ state - active: stores all the data necessary to function normally
                - ✅ cannot be broken in this state in **survival**: `VoidClamModEntry` + `shouldCancelBreakingSearingHeart` (creative bypass; **ice-encased** heart can be mined)
                - ✅ it's extremely hot. At a certain range interval, it pushes players away with damage over time from the heat roughly inversely proportional with distance
                - ✅ at a certain range interval, farther than the heat interval, clams will detect and try to digest players
                - ✅ NOTE for above: self-defense behavior is ticked on **phased server ticks** (same model as seek / repair cadence), not vanilla block random ticks
                - ✅ if a clam is encased on all sides with ice, dormancy (no thermal activity / seek / etc.) applies **immediately** on the server tick path — **not** waiting for a vanilla block random tick; deferring that to “random tick only” is **postponed** until / unless random-tick-based heart ticking is implemented (same long-term bucket as full-feature revisit)
                - ✅ on wake, `tryAutogrowIntoPrebuiltShell` attempts to match / resize into an existing obsidian shell; otherwise `buildStub` runs (`applyPostWakeShellAndSeek`)
        - ❌ full-feature: custom block that does the same things except right-clicking with fuel doesn't use the furnace menu and the animations orchestrated serverside are now handled client-side
    - ✅ flesh
        - ✅ vanilla: nether wart blocks
        - ❌ full-feature: custom block, animations can be handled client-side
    - ✅ shell
        - ✅ vanilla: obsidian
        - ❌ full-feature: same block as the flesh block, but in a different state, allowing for different properties and appearance.
            - additional feature; this is a contemplation, don't implement yet: the color/sheen of the custom shell blocks could be determined by which ore(s) the clam had in its inventory when creating it by sampling color from the ore block (or some other color determination method) and combining the colors into a sort of hash or seed that gets stored in the shell block (must somehow be done without making the shell blocks tile entities); patterns for the color mixture or sheen could be influenced by another future resource (maybe "soul" from consuming soul fire stuff?) this would give players a farmable source of blocks they can color in tons of ways
            
            
behavioral flow is below; current implementation may be more needlessly complex than this. If that's the case, simplify it in the code

                         ticked                                                              
                            │                                                                
                            ▼                                                                
                       check ice encased─y─► dormant immediately: no thermal seek/defense; **cancel** sync A*, busy flag, and queued path targets (early in tick, before sync A* step); async workers abort if not thermally active; delayed path-apply steps exit if no longer thermally active  
                            │                                                                
                            n                                                                
                            │                                                                
                            ▼                    lock acquire failed (already locked)        
                        acquire per-clam lock──────────────────────────────────────────► exit
                            │                                                                
                       acquired                                                              
                            │                                                                
                            ▼                                                                
      ┌────────────check repair/grow cycle timer───────┐                                     
      │                                                │                                     
      │                                                │                                     
      │                                                │                                     
      │                                                │                                     
      │                                                │                                     
      │                                                │                                     
      │                                                │                                     
      │                                                │                                     
      ▼                                                ▼                                     
  not-zero                                            zero                                   
      │                                                │                                     
      │                                                │                                     
      ▼                                                ▼                                     
    reach                                  y──────shell damaged?──────n                      
based on priorities                        │                          │                      
      │                                    │                          │                      
      │                                    │                          │                      
      │                                    │                          │                      
      │                                    ▼                          ▼                      
      │                       ┌──────────attempt repair           check space+resource       
      │                       │                                  & attempt grow              
      │                       │                                     │                        
      │                       ▼                                     │                        
      └───────────►multi-tick activity◄─────────────────────────────┘                        
                            │                                                                
                            │                                                                
                            ▼                                                                
                     release per-clam lock                                                   
