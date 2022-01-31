package serbanstein.voidclam;

import me.angeschossen.lands.api.flags.Flags;
import me.angeschossen.lands.api.land.Area;
import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;
import javax.annotation.Nullable;


public class EventListener implements Listener {
	@EventHandler
	public void placeLightEvent(BlockPlaceEvent placeEvent) {
		Location eventLoc = placeEvent.getBlockPlaced().getLocation();
		final @Nullable Area area = Main.landsIntegration.getAreaByLoc(eventLoc);
		if(area!=null) {
			//if mob griefing is disabled; alternatively, code in a separate flag for "cosmic abominations"
			if(!area.hasFlag(Flags.ENTITY_GRIEFING)){
				return;
			}
		}
		if(Main.lights.contains(placeEvent.getBlockPlaced().getType())){
			for(int i=1;i<=Main.moduleNumber;i++) {
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



	//this section is only for natural world generation
	/*
	@EventHandler
	public void chunkLoadEvent(ChunkLoadEvent event) {
		if(!event.getWorld().getName().equalsIgnoreCase(Main.worldName)) return;
		Entity ent = null;
		Random r = new Random();
		Chunk chunk = event.getChunk();
		int i,y;
		i = r.nextInt(10);
		if(i == 0)	//1 in 10
		{
			int tx=event.getChunk().getX()*16,tz=event.getChunk().getZ()*16;
			for(i=1;i<=Main.moduleNumber;i++){
				double dist = Math.abs(Main.modules[i].x-tx)+Math.abs(Main.modules[i].z-tz); //2D Manhattan distance between the chunk and the nearest module
				if(dist<300)return;
			}

			tx=tx+r.nextInt(16);
			int ty=4+r.nextInt(30);
			if(event.isNewChunk()) ty = 4+ r.nextInt(60);
			tz=tz+r.nextInt(16);

			boolean hasRoom = true;
			int ix,iy,iz;
			for(ix=tx-2;ix<=tx+2;ix++){
				for(iz=tz-2;iz<=tz+2;iz++){
					for(iy=ty-2;iy<=ty+2;iy++){
						Material mat = Objects.requireNonNull(Bukkit.getWorld(Main.worldName)).getBlockAt(ix,iy,iz).getType();
						if(!(mat == Material.WATER || mat == Material.LAVA || mat == Material.CAVE_AIR || mat.getHardness()<=0.75)){
							hasRoom = false;
						}
					}
				}
			}
			if(hasRoom){
				CommandToolbox.makestub(tx,ty,tz);
				CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
				CommandToolbox.clamReSize(Main.moduleNumber,Main.modules[Main.moduleNumber].currentSize+2);
				Main.saveToFile();
			}
		}
	}*/

	//this section is for handling "trespassers" but it's a horrible idea to track every single block break event when not needed
	/*
	@EventHandler
	public void trespassEvent(BlockBreakEvent event){
		Location loc = event.getBlock().getLocation();
		if(Objects.requireNonNull(loc.getWorld()).getName().equals(Main.worldName)) {
			for (int i = 1; i <= Main.moduleNumber; i++) {
				Location modloc = new Location(loc.getWorld(), Main.modules[i].x, Main.modules[i].y, Main.modules[i].z);
				if(modloc.distance(loc)<0.35*Main.modules[i].currentSize-1){
					Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(getWorld(Main.worldName), Main.modules[i].x, Main.modules[i].y, Main.modules[i].z),
							Sound.valueOf("EVENT_RAID_HORN"), 4*Main.modules[i].currentSize, (float) 0.01);

					for(double x=loc.getX()-1;x<=loc.getX()+1;x+=1){
						for(double z=loc.getZ()-1;z<=loc.getZ()+1;z+=1){
							for(double y=loc.getY()-1;y<=loc.getY()+2;y++){
								if(!(x==loc.getX() && z==loc.getZ() && (y==loc.getY()||y==loc.getY()+1))){
									loc.getWorld().getBlockAt((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)).setType(Material.OBSIDIAN);
								}}}}
					int finalI = i;
					new BukkitRunnable(){
						public void run(){
							event.getPlayer().setHealth(0);
							CommandToolbox.clamReSize(finalI,Main.modules[finalI].currentSize);
						}
					}.runTaskLater(Main.getPlugin(Main.class),20*5);
				}
			}
		}
	}
	 */
}
