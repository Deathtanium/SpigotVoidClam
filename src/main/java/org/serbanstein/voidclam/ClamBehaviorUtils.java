package org.serbanstein.voidclam;

import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;


//import static org.serbanstein.voidclam.Main.clamList;
import static org.serbanstein.voidclam.Main.config;

public class ClamBehaviorUtils {
    public static List<Material> lightBlocks = new ArrayList<>();
    public static List<PathfinderNode> buildPathQueue = new ArrayList<>();
    public static void populateLightList() {
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

    public static List<BukkitTask> asyncTasks = new ArrayList<>();

    public static Location getNearestLight(Location loc, int radius, int clamID){
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

    public static Location getRandomLight(Location loc, int radius, int clamID){
        List<Location> lightList = getLocations(loc, radius);
        if(!lightList.isEmpty()){
            return lightList.get((int) (Math.random() * lightList.size()));
        }
        return null;
    }

    //getrandomlight but weighted by distance
    public static Location getRandomLightWeighted(Location loc, int radius, int clamID){
        List<Location> lightList = getLocations(loc, radius);
        if(!lightList.isEmpty()){
            double[] weights = new double[lightList.size()];
            double totalWeight = 0;
            for(int i = 0; i < lightList.size(); i++){
                weights[i] = 1/lightList.get(i).distance(loc);
                totalWeight += weights[i];
            }
            double random = Math.random() * totalWeight;
            for(int i = 0; i < lightList.size(); i++){
                if(random < weights[i]){
                    return lightList.get(i);
                }
                random -= weights[i];
            }
        }
        return null;
    }

    private static List<Location> getLocations(Location loc, int radius) {
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
        return lightList;
    }

    //repeating task that plays the conduit ambient sound for all the clams every few seconds
    public static BukkitTask registerHeartbeatSoundTask(){
        return Bukkit.getScheduler().runTaskTimer(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                List<Clam> lstCopy = new ArrayList<>(Main.clamList);
                for(Clam clam:lstCopy){
                    if ((new Location(Bukkit.getWorld(clam.world),clam.x,clam.y,clam.z)).getBlock().getType() != Material.NETHER_WART_BLOCK){
                        System.out.println("clam at " + clam.x + " " + clam.y + " " + clam.z + " is not a nether wart block, removing");
                        //Main.clamList.remove(clam);
                        //saveClams();
                        //continue;
                    }
                    Objects.requireNonNull(Bukkit.getWorld(clam.world)).playSound(
                            new Location(Bukkit.getWorld(clam.world),clam.x,clam.y,clam.z),
                            Sound.BLOCK_CONDUIT_AMBIENT,
                            SoundCategory.BLOCKS,
                            ((float)config.getDouble("vol_heartbeat")),((float)config.getDouble("pitch_heartbeat"))
                    );
                }
            }
        },0L,config.getLong("ticks_heartbeat"));
    }

    public interface getLightFunction {
        Location call(Location loc, int radius, int clamID);
    }

    //function that takes a clam id, start location, end location and a world and registers an ASYNC task that calculates a path between the two locations and places the result in the buildPathQueue
    public static void registerAsyncTask(int clamID, Clam clam, int clamSize, Location start, World world){
        System.out.println("looking for light for clam " + clamID);
        getLightFunction getLight;
        Location light;
        if(Objects.requireNonNull(config.getString("lightseekmode")).equalsIgnoreCase("nearest")){
            getLight = ClamBehaviorUtils::getNearestLight;
        } else if (Objects.requireNonNull(config.getString("lightseekmode")).equalsIgnoreCase("random")) {
            getLight = ClamBehaviorUtils::getRandomLight;
        } else if (Objects.requireNonNull(config.getString("lightseekmode")).equalsIgnoreCase("randomweighted")) {
            getLight = ClamBehaviorUtils::getRandomLightWeighted;
        }
        else {
            getLight = (Location loc, int radius, int ClamID)->{return null;};
        }
        light = getLight.call(start,clamSize*config.getInt("seekrangemult"),clamID);
        if(light != null) {
            System.out.println("found light for clam " + clamID);
            asyncTasks.add(Bukkit.getScheduler().runTaskAsynchronously(Main.getPlugin(Main.class), new Runnable() {
                @Override
                public void run() {
                    PathfinderNode path = PathfinderAStar.singleBlockPathfind(clamID, start, light, world);
                    clam.busyFlagMainCycle = false;
                    if (path != null) {
                        buildPathQueue.add(path);
                    }
                }
            }));
        }else{
            clam.busyFlagMainCycle = false;
        }
    }

    public static BukkitTask registerBuildTaskExecutor(){
        return Bukkit.getScheduler().runTaskTimer(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                List<PathfinderNode> lstCopy = new ArrayList<>(buildPathQueue);
                buildPathQueue.clear();
                lstCopy.forEach(
                        pathfinderNode -> {
                            while (pathfinderNode != null) {
                                World world = Objects.requireNonNull(Bukkit.getWorld(pathfinderNode.worldName));
                                Location loc = new Location(world, pathfinderNode.x, pathfinderNode.y, pathfinderNode.z);
                                loc.getBlock().setType(Material.NETHER_WART_BLOCK);
                                world.playSound(loc, Sound.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, ((float) config.getDouble("vol_buildpath")), ((float) config.getDouble("pitch_buildpath")));
                                pathfinderNode = pathfinderNode.parent;
                            }
                        }
                );
                lstCopy.clear();
            }
        },0L,config.getLong("ticks_buildpath"));
    }

    public static BukkitTask registerLightSeekTask(){
        return Bukkit.getScheduler().runTaskTimer(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                for(Clam clam:Main.clamList){
                    if(!clam.busyFlagMainCycle){
                        clam.busyFlagMainCycle = true;
                        registerAsyncTask(Main.clamList.indexOf(clam),clam,clam.currentSize,new Location(Bukkit.getWorld(clam.world),clam.x,clam.y,clam.z),Bukkit.getWorld(clam.world));
                    }
                }
            }
        },0L,config.getLong("ticks_lightseek"));
    }

    public static BukkitTask registerCheckRepairGrowTask() {
        return Bukkit.getScheduler().runTaskTimer(Main.getPlugin(Main.class), new Runnable() {
            @Override
            public void run() {
                for (Clam clam : Main.clamList) {
                    boolean isMax = clam.currentSize < config.getInt("maxclamsize");
                    boolean hasEnergy = clam.energy >= config.getInt("energypersizetogrow")*clam.currentSize;
                    //todo: may want to check if it destroys valuable stuff in the process
                    if (!isMax && hasEnergy) {
                        //grow
                        clam.currentSize++;
                        clam.energy = 0;
                    }
                    clam.lightsBlackList.clear();
                    BuildUtils.buildVoidclamScript(clam.x, clam.y, clam.z, clam.currentSize, Bukkit.getWorld(clam.world));
                }
                saveClams();
            }
        }, config.getLong("ticks_repairgrow"),config.getLong("ticks_repairgrow")); //10m
    }

    public static void loadClams(){
        CopyOnWriteArrayList<Clam> clamList = new CopyOnWriteArrayList<>();
        //open voidclams.jsonl and load the clams into the list
        Main.getPlugin(Main.class);
        File file = Main.voidclamFile;
        Main.clamList.clear();

        //on each line is a json object with the fields x,y,z,worldname,size
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            for(String line:lines){

                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                int x = obj.get("x").getAsInt();
                int y = obj.get("y").getAsInt();
                int z = obj.get("z").getAsInt();
                String worldname = obj.get("worldname").getAsString();
                int size = obj.get("size").getAsInt();
                Clam clam = new Clam(x,y,z,worldname,size);
                Main.clamList.add(clam);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveClams(){
        //overwrite to clear the file
        try {
            Files.write(Main.voidclamFile.toPath(), "".getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        StringBuilder bigString = new StringBuilder();
        for(Clam clam:Main.clamList){
            JsonObject obj = new JsonObject();
            obj.addProperty("x",clam.x);
            obj.addProperty("y",clam.y);
            obj.addProperty("z",clam.z);
            obj.addProperty("worldname",clam.world);
            obj.addProperty("size",clam.currentSize);
            bigString.append(obj.toString()).append("\n");
        }
        try {
            Files.write(Main.voidclamFile.toPath(), bigString.toString().getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
