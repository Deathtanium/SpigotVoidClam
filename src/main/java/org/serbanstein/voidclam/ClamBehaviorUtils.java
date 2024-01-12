package org.serbanstein.voidclam;

import org.bukkit.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.serbanstein.voidclam.Main.clamList;

public class ClamBehaviorUtils {
    public static List<Material> lightBlocks = new ArrayList<>();
    public static BukkitTask heartbeatSoundTask,seekLightTask,checkRepairGrowTask,buildPathTask;
    public static int maxClamSize = 10;
    public static List<PathfinderNode> buildPathQueue = new ArrayList<>();
    public void populateLightList() {
        lightBlocks.add(Material.TORCH);
        lightBlocks.add(Material.SOUL_TORCH);
        lightBlocks.add(Material.SOUL_WALL_TORCH);
        lightBlocks.add(Material.END_ROD);
        lightBlocks.add(Material.LANTERN);
        lightBlocks.add(Material.SEA_LANTERN);
        lightBlocks.add(Material.GLOWSTONE);
        lightBlocks.add(Material.SHROOMLIGHT);
        lightBlocks.add(Material.BEACON);
        lightBlocks.add(Material.JACK_O_LANTERN);
        lightBlocks.add(Material.CAMPFIRE);
        lightBlocks.add(Material.SOUL_CAMPFIRE);
        lightBlocks.add(Material.LAVA);
        lightBlocks.add(Material.FIRE);
        lightBlocks.add(Material.MAGMA_BLOCK);
        lightBlocks.add(Material.GLOWSTONE);
    }

    public Location getNearestLight(Location loc, int radius, int clamID){
        Location bestLight = null;
        double bestLightDistance = 0;
        for(int x = -radius; x <= radius; x++){
            for(int y = -radius; y <= radius; y++){
                for(int z = -radius; z <= radius; z++){
                    Location currentLoc = new Location(loc.getWorld(), loc.getX() + x, loc.getY() + y, loc.getZ() + z);
                    if(lightBlocks.contains(currentLoc.getBlock().getType())){
                        double currentDistance = currentLoc.distance(loc);
                        if(currentDistance < bestLightDistance || bestLight == null){
                            bestLight = currentLoc;
                            bestLightDistance = currentDistance;
                        }
                    }
                }
            }
        }
        return bestLight;
    }

    public Location getRandomLight(Location loc, int radius, int clamID){
        List<Location> lightList = new ArrayList<>();
        for(int x = -radius; x <= radius; x++){
            for(int y = -radius; y <= radius; y++){
                for(int z = -radius; z <= radius; z++){
                    Location currentLoc = new Location(loc.getWorld(), loc.getX() + x, loc.getY() + y, loc.getZ() + z);
                    if(lightBlocks.contains(currentLoc.getBlock().getType())){
                        lightList.add(currentLoc);
                    }
                }
            }
        }
        if(!lightList.isEmpty()){
            return lightList.get((int) (Math.random() * lightList.size()));
        }
        return null;
    }



    //repeating task that plays the conduit ambient sound for all the clams every few seconds
    public BukkitTask registerHeartbeatSoundTask(){
        return Bukkit.getScheduler().runTaskTimer(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                for(Clam clam:clamList){
                    Objects.requireNonNull(Bukkit.getWorld(clam.world)).playSound(
                            new Location(Bukkit.getWorld(clam.world),clam.x,clam.y,clam.z),
                            Sound.BLOCK_CONDUIT_AMBIENT,
                            SoundCategory.BLOCKS,
                            1.0f,1.0f
                    );
                }
            }
        },0L,100L);
    }

    //function that takes a clam id, start location, end location and a world and registers an ASYNC task that calculates a path between the two locations and places the result in the buildPathQueue
    public void registerBuildPathTask(int clamID, Location start, Location end, World world){
        Bukkit.getScheduler().runTaskAsynchronously(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                PathfinderNode path = PathfinderAStar.singleBlockPathfind(clamID,start,end,world);
                clamList.get(clamID).busyFlagMainCycle = false;
                if(path != null){
                    buildPathQueue.add(path);
                }
            }
        });
    }

    public BukkitTask registerLightSeekTask(){
        return Bukkit.getScheduler().runTaskTimer(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                for(Clam clam:clamList){
                    if(!clam.busyFlagMainCycle){
                        Location randomLight = getRandomLight(new Location(Bukkit.getWorld(clam.world),clam.x,clam.y,clam.z),clam.currentSize*4,clamList.indexOf(clam));
                        if(randomLight != null){
                            clam.busyFlagMainCycle = true;

                            registerBuildPathTask(clamList.indexOf(clam),new Location(Bukkit.getWorld(clam.world),clam.x,clam.y,clam.z),randomLight,Bukkit.getWorld(clam.world));
                        }
                }
            }
        },0L,100L);
    }


}
