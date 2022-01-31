package serbanstein.voidclam;

import java.util.*;

import me.angeschossen.lands.api.flags.Flag;
import me.angeschossen.lands.api.flags.Flags;
import me.angeschossen.lands.api.land.Area;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import static org.bukkit.Bukkit.getWorld;

public class Pathfinder {
	static List<Cursor> xc = new ArrayList<>();
	static List<Cursor> yc = new ArrayList<>();
	//decides which path candidate has the lowest estimated cost
	public static Node leastF(List<Node> list) {
		double minf = 100000;
		Node mini = null;
		for (Node n : list) {
			if (n.f < minf) {
				minf = n.f;
				mini = n;
			}
		}
		return mini;
	}

	//returns node that exists in provided list, null otherwise
	public static Node nodeExists(List<Node> list, Node firstNode) {
		if(!list.isEmpty()) for (Node n : list) {
			if (n.x == firstNode.x && n.y == firstNode.y && n.z == firstNode.z)
				return n;
		} else return null;
		return null;
	}

	public static boolean calculatePath(int tno, int sx, int sy, int sz, int gx, int gy, int gz) {

		List<Node> open = new ArrayList<>();
		List<Node> closed = new ArrayList<>();

		//double cst = 0;
		Node firstNode = new Node(sx, sy, sz, null,tno);	//starting node; first added the open list
		//g is material cost, h is Pythagorean distance, f is the sum between g and h
		firstNode.g = 0; //initializing the values 
		firstNode.h = Math.pow((firstNode.x - gx), 2) + Math.pow((firstNode.y - gy), 2) + Math.pow((firstNode.z - gz), 2);
		firstNode.f = firstNode.h;
		open.add(firstNode); //adding it to the open list

		while (!open.isEmpty() && Main.getPlugin(Main.class).isEnabled()) {

			Node nextCheapestNode = leastF(open);
			open.remove(nextCheapestNode);
			//Collections.shuffle(x);
			//now, going through the cursor list (6 directions, no diagonals)
			for (Cursor c : xc) {

				Node nextNode = new Node(nextCheapestNode.x + c.x, nextCheapestNode.y + c.y, nextCheapestNode.z + c.z, nextCheapestNode,tno);

				//if the neighboring node is the target
				if (nextNode.x == gx && nextNode.y == gy && nextNode.z == gz) {
					Node toAdd;
					toAdd = nextNode;
					toAdd.tno = tno;
					Main.targets.add(toAdd);
					return true;
				}
				else {
					double cst;
					Material bl = Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x,nextNode.y,nextNode.z).getType();
					if(bl==Material.NETHER_WART_BLOCK){
						cst = 0;
					} else if (bl==Material.AIR || bl == Material.WATER || bl == Material.LAVA) {        //cost of Wart Blocks (module meat)
						cst = 1;
						for( Cursor cc : yc){
							//would rather stick to walls than suspending tendrils mid-air
							Material mat = Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x+cc.x,nextNode.y+cc.y,nextNode.z+cc.z).getType();
							if(!(mat==Material.AIR || mat == Material.WATER || mat == Material.LAVA)){
								cst = 0.1;
							}
						}
					} else {
						cst = 2500;
					}
					Area area = Main.landsIntegration.getAreaByLoc(new Location(Bukkit.getWorld(Main.worldName),nextNode.x,nextNode.y,nextNode.z));
					if(area!=null){
						if(!area.hasFlag(Flags.ENTITY_GRIEFING)){
							cst = 2500;
						}
					}

					//this below is to avoid pets, animals, armor stands, item frames bla bla
					Collection<Entity> coll = Objects.requireNonNull(getWorld(Main.worldName)).getNearbyEntities(new Location(Bukkit.getWorld(Main.worldName),nextNode.x,nextNode.y,nextNode.z),2,2,2);
					if(!coll.isEmpty()){
						cst = 2500;
					}

					//g is cst, h is Manhattan distance, f is the sum between g and h
					nextCheapestNode.g += cst;
					nextNode.g = cst;
					nextNode.h = Math.abs(nextNode.x - gx) + Math.abs(nextNode.y - gy) + Math.abs(nextNode.z - gz);
					nextNode.f = nextNode.g + nextNode.h;

					Node tempNode1 = nodeExists(open, nextNode);
					Node tempNode2 = nodeExists(closed, nextNode);
					if (!(Math.abs(nextNode.x-Main.modules[tno].x)>4*Main.modules[tno].currentSize || Math.abs(nextNode.y-Main.modules[tno].y)>5*Main.modules[tno].currentSize || Math.abs(nextNode.z-Main.modules[tno].z)>5*Main.modules[tno].currentSize) &&
							!(tempNode1!=null && tempNode1.f <= nextNode.f) &&
							!(tempNode2!=null && tempNode2.f <= nextNode.f) &&
							(cst!=2500))
						open.add(nextNode);
					nextCheapestNode.g-=cst;
				}
			}
			closed.add(nextCheapestNode);
		}
		closed.clear();
		return false;
	}

	//path builder function
	public static void buildpath(Node gnode) {
		if (gnode.f < 2500) {
			Node firstNode = gnode, copy = gnode;
			int x = Main.modules[gnode.tno].x, y = Main.modules[gnode.tno].y, z = Main.modules[gnode.tno].z;
			final Node refnode = gnode;
			long timer = 2;
			while (copy.parent != null) {
				timer += 2;
				copy = copy.parent;
			}
			Random r = new Random();

			Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () ->
					Main.modules[gnode.tno].lightsBlackList.remove(new Location(getWorld(Main.worldName), gnode.x, gnode.y, gnode.z)), timer);
			Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () ->
					Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(refnode.x, refnode.y, refnode.z).setType(Material.NETHER_WART_BLOCK), timer);
			timer -= 2;


			while (firstNode.parent != null) {
				firstNode = firstNode.parent;
				final Node refnode2 = firstNode;
				Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () -> {
					Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(refnode2.x, refnode2.y, refnode2.z).setType(Material.NETHER_WART_BLOCK);
					if ((new Location(getWorld(Main.worldName), refnode.x, refnode.y, refnode.z)).distance(new Location(getWorld(Main.worldName), x, y, z)) > 1.2 * Main.modules[gnode.tno].currentSize + 2 && Main.modules[gnode.tno].currentSize >= 15 && Main.modules[gnode.tno].energy >= 35)
						for (Cursor c : xc)
							if (r.nextInt(45) == 0)
								Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(refnode2.x + c.x, refnode2.y + c.y, refnode2.z + c.z).setType(Material.OBSIDIAN);

					Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(getWorld(Main.worldName), refnode2.x, refnode2.y, refnode2.z), Sound.valueOf("BLOCK_CHORUS_FLOWER_GROW"), 1, 0.01f);
				}, (timer));
				timer -= 2;
			}
		}
	}
}
