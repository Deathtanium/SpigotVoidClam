A Voidclam is a feature made of blocks that looks like two pyramid shells with a fleshy interior. Instead of being a regular entity, it is effectively a living structure in the world, with a heart block storing its data, flesh mass which includes inside the shell and the growing tendrils it uses to reach targets, and its outer hard shell, which is hardened flesh.

❌ - not implemented or nowhere near functional
? - possibly partially implemtented but never tested or unsure about functionality
✅ - reasonably confident about proper implementation

Abstract feature list:
- ❌ TODO: for ALL features that can be configured to run async or sync_batched, there will be a :
    - ❌ unified pool of "actions per tick" for sync_batched; each action made by any clam, regardless of what action it is, takes up 1 from this global action pool; 
    - ❌ for async, have one thread per async job with no per-tick cap and pray the server using this mod never has too many clams active at once; later implement a configurable minimum distance between clams so clams too close to each other won't wake up, then write it in this feature list and remove this snippet; naturally, async processes need to submit their results to a cross-thread-accessible location where they can be consumed by the main thread; A* already does this properly, not sure about the others;
    - ? current implementation has separate async vs sync_batched flags for various processes and separate action per tick pools; the goal is to keep sync toggle separation but unify action-per-tick pools into one and ensure all of this is settable in the config file

- ✅ "reach" function; able to detect, pathfind to, and consume block targets within 4 x currentSize of itself:
    - ? each clam has a flag for "reach" that prevents it from working on multiple paths at once (need to make actual flag name in the code more logical and ensure it's properly implemented)
    - ✅ A* pathfinding; needs to look organic. This was made to effectively find paths for the clam's tendrils to dig through
        - ✅ conditional costs
            - ? cost of the goal block needs to be ignored (need to check if I haven't hardcoded an exception for this instead of a proper fix)
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
            - ? sync_batched vs async for the BFS
    - NOTE: considering replacing A* altogether with creating a 
        
- ✅ resource budget
    - ✅ clams mainly eat lights for the "energy" resource via the "reach" function
        - ? each light block has an arbitrary energy value depending on how "strong" I think they are as a light source
        - ? eaten Beacon blocks refund the nether star via "block break preservation"
        - ❌ method to dynamically determine what blocks are light source blocks, no matter what future minecraft versions bring to the table or what mods are installed, with an optional whitelist and blacklist in the config
        
    - ? clams eat ores for the "material" resource
        - ✅ while clams will always look for lights to eat, they will only look for materials if the material amount is below 5.
        - ✅ method to include **modded ores**: when `clam_ore_detect_with_c_ores_tag` is true (default), `c:ores` counts as ore **in addition to** the built-in vanilla list; turn off for legacy behavior only
        
- ? growing
    - ? room check
        - ? blocks replaceable by either sculk or moss do not count as obstacles (this is a cheap way to avoid breaking potentially precious blocks while growing)
        - ? natural blocks not considered obstacles
        - ❌ material cost for growing (not sure how much this should be)
        
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
    - ❌ manually-implemented 
    - ❌ make use of minecraft built-in structure generator (less work)
    - ❌ on natural spawning, it will be spawned with a set size between 5 and 9
    - ❌ check room first; as with other room checks, multiple vanilla tags may need to be used to determine what blocks (preferably not super important ones) can be replaced
    
- composition
    - Searing Heart
        - ✅ vanilla: Blast Furnace that stores custom data that the mod uses to tick clams in loaded chunks; 
            - ✅ state - dormant: it stores nearly no data except for the fact that it's a voidclam so that the mod knows to awaken it when 
                - ✅ a fuel item is placed in the fuel slot (i want to replace this with any valid light block)
                - ? at least 2 repair cycles have passed since it was placed; the 2nd repair cycle will awaken it automatically
            - ✅ state - active: stores all the data necessary to function normally
                - ❌ cannot be broken in this state;
                - ✅ it's extremely hot. At a certain range interval, it pushes players away with damage over time from the heat roughly inversely proportional with distance
                - ✅ at a certain range interval, farther than the heat interval, clams will detect and try to digest players
                - ✅ NOTE for above: self-defense behavior is ticked on **phased server ticks** (same model as seek / repair cadence), not vanilla block random ticks
                - ✅ if a clam is encased on all sides with ice, dormancy (no thermal activity / seek / etc.) applies **immediately** on the server tick path — **not** waiting for a vanilla block random tick; deferring that to “random tick only” is **postponed** until / unless random-tick-based heart ticking is implemented (same long-term bucket as full-feature revisit)
                - ❌ if a clam awakens, it will check for an existing shell to immediately grow into (? performing a more loose room check plus a shell check)
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
                       check ice encased─y─► dormant immediately: no thermal seek/defense; **cancel** sync A*, busy flag, and queued path targets (early in tick, before sync A* step); async workers abort via ice check; delayed path-apply steps exit if no longer thermally active  
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
