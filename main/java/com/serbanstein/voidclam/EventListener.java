package com.serbanstein.voidclam;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.Objects;
import java.util.Random;

public class EventListener implements Listener {
	@EventHandler
	public void placeLightEvent(BlockPlaceEvent placeEvent) {
		Location eventLoc = placeEvent.getBlockPlaced().getLocation();

		if(Main.lights.contains(placeEvent.getBlockPlaced().getType()))
		{
			for(int i=1;i<=Main.moduleNumber;i++)
			{
				if(eventLoc.distance(new Location(Bukkit.getWorld(Main.worldName), Main.modules[i].x, Main.modules[i].y, Main.modules[i].z)) <= 4*Main.modules[i].currentSize &&
						!Main.modules[i].lightsBlackList.contains(eventLoc)) {
					if (Main.modules[i].status == 1) {
						if (Main.modules[i].busyFlagPlaceEvent == 0) { //Module not busy with other placeEvent
							final int ii = i;
							Main.aSyncTasks.add(new BukkitRunnable() {
								public void run() {
									Main.modules[ii].busyFlagPlaceEvent = 1;
									Main.modules[ii].lightsBlackList.add(new Location(Bukkit.getServer().getWorld(Main.worldName),
											(int) eventLoc.getX(),
											(int) eventLoc.getY(),
											(int) eventLoc.getZ()));
									if (Pathfinder.calculatePath(ii, Main.modules[ii].x, Main.modules[ii].y, Main.modules[ii].z,
											(int) eventLoc.getX(),
											(int) eventLoc.getY(),
											(int) eventLoc.getZ())) Main.modules[ii].energy++;
									Main.modules[ii].busyFlagPlaceEvent = 0;
								}
							}.runTaskAsynchronously(Main.getPlugin(Main.class)));
						}
	}}}}}

	@EventHandler
	public void chunkLoadEvent(ChunkLoadEvent event) {
		Entity ent = null;
		Random r = new Random();
		Chunk chunk = event.getChunk();
		int i,y;
		if(event.isNewChunk())
		{
			i = r.nextInt(10);
			if(i == 1)	//1 in 50 chance to even try (2% per chunk with entity in it)
			{
				y = 255;
				i=0;
				while(y>48 && i<chunk.getEntities().length){
					ent = chunk.getEntities()[i];
					y = (int)ent.getLocation().getY();
					i++;
				}
				if(y<=48) {
					CommandToolbox.makestub((int)ent.getLocation().getX(),(int)ent.getLocation().getY(),(int)ent.getLocation().getZ());
					CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
					CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
					CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
					Main.saveToFile();
				}
				else
				{
					int x,z;
					Block b;
					x = chunk.getX()*16;
					z = chunk.getZ()*16;
					int ix,iy,iz;
					short hasRoom = 1;
					double layerCst,totalCst=0,c,minCst = 0;
					int minLayer = -1;
					for(iy=4;iy<=32;iy++)	//looking for space in the chunk
					{
						layerCst = 0;
						for(ix=1;ix<=16;ix++)
						{
							for(iz=1;iz<=16;iz++)
							{
								b = Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(ix,iy,iz);
								c = b.getType().getHardness();
								if(b.getType().getHardness() == -1) hasRoom = 0;
								if(c >= 100) c = 0; //liquids...
								layerCst+=c;
								totalCst+=c;
							}
						}
						if(minLayer == -1 || layerCst<minCst){
							minLayer = iy;
							minCst = layerCst;
						}
					}
					if(totalCst<=64 && hasRoom == 1) {
						CommandToolbox.makestub(x,minLayer,z);
						CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
						CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
						CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
						Main.saveToFile();
					}
				}
			}
		}
	}

	@EventHandler
	public void trespassEvent(BlockBreakEvent event){
		Location loc = event.getBlock().getLocation();
		if(Objects.requireNonNull(loc.getWorld()).getName().equals(Main.worldName)) {
			for (int i = 1; i <= Main.moduleNumber; i++) {
				Location modloc = new Location(loc.getWorld(), Main.modules[i].x, Main.modules[i].y, Main.modules[i].z);
				if(modloc.distance(loc)<0.35*Main.modules[i].currentSize){
					for(double x=loc.getX()-1;x<=loc.getX()+1;x+=1){
						for(double z=loc.getZ()-1;z<=loc.getZ()+1;z+=1){
							for(double y=loc.getY()-1;y<=loc.getY()+2;y++){
								if(!(x==loc.getX() && z==loc.getZ() && (y==loc.getY()||y==loc.getY()+1))){
									loc.getWorld().getBlockAt((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)).setType(Material.OBSIDIAN);
					}}}}
					int finalI = i;
					new BukkitRunnable(){
						public void run(){
							event.getPlayer().damage(9999);
							CommandToolbox.clamReSize(finalI,Main.modules[finalI].currentSize);
						}
					};
				}
			}
		}
	}
}
