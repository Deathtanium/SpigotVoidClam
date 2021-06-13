package com.serbanstein.voidclam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

import static org.bukkit.Bukkit.getWorld;

public class CommandToolbox extends JavaPlugin {

    public static void makestub(int x,int y,int z){
        for (int ix = x - 1; ix <= x + 1; ix++) {
            for (int iy = y - 2; iy <= y + 2; iy++) {
                for (int iz = z - 1; iz <= z + 1; iz++) {
                    boolean black = !(((iy != y + 2 && iy != y - 2) || iz != z || ix != x)
                            && ((iy != y - 1 && iy != y + 1) || ((iz != z || (ix != x + 1 && ix != x - 1))
                            && (ix != x || (iz != z + 1 && iz != z - 1)))));
                    boolean red = (ix == x && iz == z && iy < y + 2 && iy > y - 2);
                    final int ux = ix, uy = iy, uz = iz;
                    if (red || black) {
                        Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () -> {
                            Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(ux, uy, uz).setType(Material.NETHER_WART_BLOCK);
                            Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(Bukkit.getWorld(Main.worldName),ux,uy,uz),
                                    Sound.valueOf("BLOCK_CHORUS_FLOWER_GROW"),3,0.01f);
                        }, (Math.abs(iy - y) * 20L));
                    }
                    if (black) {
                        Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () -> {
                            Objects.requireNonNull(Bukkit.getServer().getWorld((Main.worldName))).getBlockAt(ux, uy, uz).setType(Material.OBSIDIAN);
                            Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(Bukkit.getWorld(Main.worldName),ux,uy,uz),
                                    Sound.valueOf("BLOCK_CHORUS_FLOWER_GROW"),3,0.01f);
                        }, (Math.abs(iy - y) * 30L));
                    }}}}

        Main.moduleNumber++;
        (Main.modules[Main.moduleNumber]) = new Module();
        (Main.modules[Main.moduleNumber]).type = 1;
        (Main.modules[Main.moduleNumber]).x = x;
        (Main.modules[Main.moduleNumber]).y = y;
        (Main.modules[Main.moduleNumber]).z = z;
        (Main.modules[Main.moduleNumber]).currentSize = 1;
        (Main.modules[Main.moduleNumber]).status = 1;
        (Main.modules[Main.moduleNumber]).energy = 0;
        (Main.modules[Main.moduleNumber]).age = 0;

        Main.saveToFile();
    }

    public static void buildShell(int x,int y,int z,int tsize,Material mat){
        int iy;
        for (iy = y + tsize - 1; iy >= y + 1; iy--) {
            int k = Math.abs(iy - y);
            int j;
            for (j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }

            for (j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z + tsize - 1 - k - Math.abs(j - x);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }
            for (j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }
            for (j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z + tsize - 1 - k - Math.abs(x - j);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }
        }
        for (iy = y - tsize / 2; iy <= y - 1; iy++) {
            int k = Math.abs(iy - y);
            int j;
            for (j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }
            for (j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z + tsize - 1 - k - Math.abs(j - x);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }
            for (j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }

            for (j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z + tsize - 1 - k - Math.abs(x - j);
                Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(j, iy, iz).setType(mat);
            }
        }
    }

    public static void clamReSize(int tno,int tsize) {
        int ctype = Main.modules[tno].type;
        Material mat = Material.NETHER_WART_BLOCK;
        if(ctype == 2) mat = Material.WARPED_WART_BLOCK;
        int csize = Main.modules[tno].currentSize;
        int x = (Main.modules[tno]).x, y = (Main.modules[tno]).y, z = (Main.modules[tno]).z;
        int i,timer=0;

        for(i = 1;i<=tsize;i+=2){
            final int i_final=i;
            Bukkit.getScheduler().runTaskLater(Main.getPlugin(Main.class), () -> {
                buildShell(x,y,z,i_final,Material.NETHER_WART_BLOCK);
                Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(getWorld(Main.worldName),x,y,z), Sound.valueOf("BLOCK_CHORUS_FLOWER_GROW"), 3, 0.01f);
            },(timer++)*10);
        }

        for(int ix=(x - csize);ix<=(x + csize);ix++){
            for(int iy=(y - csize - 1);iy<=(y + csize + 1);iy++) {
                for(int iz=(z - csize);iz<=(z + csize);iz++) {
                    if(Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(ix,iy,iz).getType() == Material.OBSIDIAN)
                        Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(ix,iy,iz).setType(mat);
                }}}

        for (i = y + csize; i <= y + tsize - 1; i++) {
            Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(x, i, z).setType(mat);
        }
        for (i = y - csize; i >= y - tsize + 1; i--) {
            Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(x, i, z).setType(mat);
        }

        final int tsize_final = tsize;

        Bukkit.getScheduler().runTaskLater(Main.getPlugin(Main.class), () -> buildShell(x,y,z,tsize_final,Material.OBSIDIAN),(timer)* 20L);

        (Main.modules[tno]).currentSize = tsize;
        tsize -= 2;

        //crown code, if I come back to it
        int ix;
        for (ix = x - tsize + 1; ix <= x; ix++) {
            int iz = z - tsize + 1 + Math.abs(ix - x);
            Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(ix, y, iz).setType(mat);
        }
        for (ix = x - tsize + 1; ix <= x; ix++) {
            int iz = z + tsize - 1 - Math.abs(ix - x);
            Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(ix, y, iz).setType(mat);
        }
        for (ix = x + tsize - 1; ix >= x; ix--) {
            int iz = z - tsize + 1 + Math.abs(ix - x);
            Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(ix, y, iz).setType(mat);
        }
        for (ix = x + tsize - 1; ix >= x; ix--) {
            int iz = z + tsize - 1 - Math.abs(x - ix);
            Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(ix, y, iz).setType(mat);
        }
        Main.saveToFile();
    }

    public static void clamReach(int tno) {
        if(Main.modules[tno].busyFlagMainCycle==0) {
            Main.modules[tno].busyFlagMainCycle = 1;

            Main.aSyncTasks.add(new BukkitRunnable() {	//runs async due to A* and large area searches
                public void run() {					//no, trust me, plugin's unusable if this is sync

                    int x,y,z,ix,iy,iz,cSize;
                    x = Main.modules[tno].x;
                    y = Main.modules[tno].y;
                    z = Main.modules[tno].z;
                    cSize = Main.modules[tno].currentSize;
                    //pick closest light block from module's lightsMap
                    Location closest = new Location(getWorld(Main.worldName),0,-1,0);
                    Location modloc = new Location(getWorld(Main.worldName),x,y,z);

                    for(iy=y-4*cSize;iy<=y+4*cSize;iy++)
                    {
                        for(ix=x-4*cSize;ix<=x+4*cSize;ix++)
                        {
                            for(iz=z-4*cSize;iz<=z+4*cSize;iz++)
                            {
                                Location loc = new Location(Bukkit.getWorld(Main.worldName),ix,iy,iz);
                                if(Main.lights.contains(Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(ix,iy,iz).getType()) && !(Main.modules[tno].lightsBlackList.contains(loc)))
                                    if(modloc.distance(loc)<modloc.distance(closest) || closest.getY() == -1) closest = loc;
                            }
                        }
                    }

                    if(closest.getY()!=-1 && !Main.modules[tno].lightsBlackList.contains(closest)){	//if valid candidate is found
                        //Bukkit.broadcastMessage(tno+" "+(int)closest.getX()+" "+(int)closest.getY()+" "+(int)closest.getZ());
                        Main.modules[tno].lightsBlackList.add(closest);
                        if(Pathfinder.calculatePath(tno,x,y,z,(int)closest.getX(),(int)closest.getY(),(int)closest.getZ()))
                        {
                            Main.modules[tno].energy++;
                        }
                    }
                    Main.modules[tno].busyFlagMainCycle = 0;
                }
            }.runTaskAsynchronously(Main.getPlugin(Main.class)));
        }
    }

    public static void clamKill(int tno){
        for (int i = tno; i < Main.moduleNumber; i++) {
            Module swap = Main.modules[i];
            Main.modules[i] = Main.modules[i + 1];
            Main.modules[i + 1] = swap;
        }
        Main.modules[Main.moduleNumber] = new Module();
        Main.moduleNumber--;
    }
}
