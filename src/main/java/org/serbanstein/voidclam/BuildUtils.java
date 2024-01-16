package org.serbanstein.voidclam;

import org.bukkit.*;

import java.util.Objects;

public class BuildUtils {
    public static void buildOctahedronShell(int x, int y, int z, int radius, World bukkitWorld, Material material, boolean removeBelt, float bottomCut) {
        //octahedron formula: |rel_x| + |rel_y| + |rel_z| = radius; <= for filling in the octahedron
        //iterate through all the blocks inside the octahedron and replace obsidian with air using the Spigot API

        //bottomCut is a float between 0 and 1 that determines how much of the bottom of the octahedron is cut off
        //1 means no cut off, 0 means the whole bottom is cut off
        int bottomCutInt = (int) (bottomCut * radius);
        for (int rel_y = -radius; rel_y <= radius; rel_y++) {
            if (removeBelt && rel_y == 0) continue;
            if (rel_y < -bottomCutInt) continue;
            for (int rel_x = -radius; rel_x <= radius; rel_x++) {
                for (int rel_z = -radius; rel_z <= radius; rel_z++) {
                    if (Math.abs(rel_x) + Math.abs(rel_y) + Math.abs(rel_z) == radius) {
                        Objects.requireNonNull(bukkitWorld).getBlockAt(x + rel_x, y + rel_y, z + rel_z).setType(material);
                    }
                }
            }
        }
    }

    private static void buildTaskWrapper(int x,int y,int z,int radius,World bukkitWorld,Material material,boolean removeBelt,float bottomCut, long delay){
        Bukkit.getScheduler().runTaskLater(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                buildOctahedronShell(x,y,z,radius,bukkitWorld,material,removeBelt,bottomCut);
            }
        },delay);
    }

    /*
    this is a script that combines the above method with other things to build a full voidclam of a certain size
    the voidclam will be build concentrically, from the inside and out, iterating over radii, out of NETHER_WART_BLOCK; the last layer will have removeBelt set to true
    there will be a time delay between layers with the help of the Bukkit Scheduler and also a sound effect for each layer; the last layer will be repeated, but out of obsidian
    */
    public static void buildVoidclamScript(int x,int y,int z,int radius,World bukkitWorld){
        for(int i=0;i<radius;i++){
            int finalI = i+1;
            //have the bottomcut 1 for sizes 1 through 5 and 0.5 otherwise
            float bottomCut = 1.0f;
            if (radius > 5) bottomCut = 0.75f;
            final float finalBottomCut = bottomCut;
            //register the bukkittask i seconds later
            buildTaskWrapper(x,y,z,finalI,bukkitWorld,Material.NETHER_WART_BLOCK,finalI == radius,finalBottomCut,finalI*20L);
        }
        //register the bukkittask i seconds later
        buildTaskWrapper(x,y,z, radius,bukkitWorld,Material.OBSIDIAN,true,0.0f, radius *20L);
    }

}