# SpigotVoidClam
Spigot plugin for the VoidClam
Should work on 1.16. Contact me for any issues
So the expected behavior of this plugin is:
whenever a new chunk is loaded, a dice is rolled and, if there's room, a clam will spawn relatively close to bedrock but it can happen at higher altitudes in fringe cases
Clams are in essence, creatures made of blocks, namely Obsidian for the shell and Nether Wart Blocks for the "meat".
Clams are typically 5-7 blocks wide at birth but can grow by eating light source blocks.
They eat light source blocks by extending crawly tendrils towards them, using A* pathfinding asynchronously to calculate the path. They do not break blocks that are at least as hard as stone.
Their max eating range is 4 times their body radius
They can be killed by breaking their central block
They periodically regenerate their shells as long as they're alive. This means they can be farmed for obsidian.
If they have a heartbeat noise*, this means they can do all of the above automatically.
If there isn't a heartbeat noise, they are dormant and won't do anything unless nudged
Tentacles crawling also have their own distinct noise**

*derived from a low-pitch conduit ambient sound 
**derived from a low-pitch chorus flower sound
All of the above can also be done manually with commands. The permission node is debugVoidClam.
/makestub x y z; creates a clam at the specified location; adds it to a global array that keeps track of them
/moduleinfo index; shows info like location, size and power about the clam in the position [index]; can be run without parameters to show info about the nearest clam within range (4*radius)
/grow index; grows the [index]th clam by one layer; do not spam this
/reach [index]; makes the [index]th clam look for lights within range; might not work in dormancy; do not spam this either
/integritycheck [index]; repairs the [index]th clam
