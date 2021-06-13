package com.serbanstein.voidclam;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;

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

		while (!open.isEmpty()) {

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

					if (bl == Material.NETHER_WART_BLOCK) {		//cost of Wart Blocks (module meat)
						cst = 0.1;
					}

					else if (bl == Material.AIR || bl==Material.LAVA || bl==Material.WATER ||
							Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x, nextNode.y, nextNode.z).getType().getHardness() <=1.4) {    //cost of fluids
						cst = 10;
						for (Cursor q : yc)        //if fluid is next to a solid block or is in a fluid
						{                        //basically prefer crawling on ground/walls
							if (Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x + q.x, nextNode.y + q.y, nextNode.z + q.z).getType() != Material.AIR &&
									Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x + q.x, nextNode.y + q.y, nextNode.z + q.z).getType() != Material.WATER &&
									Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x + q.x, nextNode.y + q.y, nextNode.z + q.z).getType() != Material.NETHER_WART_BLOCK)
								cst = 0.2;
						}
					}
					else {
						cst = 2500;
					}
					//below is stock, above is the cageable version
					/*else if (bl == Material.AIR || bl==Material.LAVA || bl==Material.WATER) {	//cost of fluids
						cst = 10;
						for (Cursor q : yc)		//if fluid is next to a solid block or is in a fluid
						{						//basically prefer crawling on ground/walls
							if (Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x + q.x, nextNode.y + q.y, nextNode.z + q.z).getType() != Material.AIR &&
								Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x + q.x, nextNode.y + q.y, nextNode.z + q.z).getType() != Material.WATER &&
								Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x + q.x, nextNode.y + q.y, nextNode.z + q.z).getType() != Material.NETHER_WART_BLOCK)
								cst = 0.2;
						}
					}
						//as default case, calculate cst from block blast resistance
					else {
						cst = Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x, nextNode.y, nextNode.z).getType().getHardness();
						if (cst == -1) {
							cst = 2500;
						}
						if (cst == 100 || cst == 0)
							cst = 1.2; // soft blocks get cost 3
						cst*=100;
					}*/
					//g is cst, h is Pythagorean distance, f is the sum between g and h
					nextCheapestNode.g += cst;
					nextNode.g = cst;
					nextNode.h = Math.pow((nextNode.x - gx),2) + Math.pow((nextNode.y - gy), 2) + Math.pow((nextNode.z - gz), 2);
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
		/*boolean hasRoom = true;
		for(int i = gnode.x-2;i<=gnode.x+2;i++){
			for(int j=gnode.z-2;j<=gnode.z+2;j++){
				for(int q=gnode.y+1;q<=gnode.y+4;q++){
					if(Bukkit.getWorld(Main.worldName).getBlockAt(i,j,q).getType()!=Material.AIR){
						hasRoom = false;
					}
				}
			}
		}*/

			int hasRoom = 1;
			if(gnode.y>5) {
				int ix, iy, iz;
				//double cst = 0;
				for (ix = x - gnode.x - 2 + 2; ix <= x + gnode.x - 2 - 2; ix++) {
					for (iz = z - gnode.z - 2 + 2; iz <= z + gnode.z - 2 - 2; iz++) {
						for (iy = y - 2; iy <= y + (gnode.y - 2) / 2 + 2; iy++) {
							Block b = Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(ix, iy, iz);
							Material m = b.getType();
							if (m != Material.AIR && m != Material.WATER && m != Material.LAVA && m != Material.OBSIDIAN && m != Material.NETHER_WART_BLOCK && m != Material.WARPED_WART_BLOCK) {
								if (m.getBlastResistance() == -1) hasRoom = 0;
								//else cst += m.getBlastResistance();
							}
						}
					}
				}
			} else hasRoom = 0;

			//TODO change replication probability to 1/20 in release version
			if (hasRoom == 1 && (new Location(getWorld(Main.worldName), gnode.x, gnode.y, gnode.z)).distance(new Location(getWorld(Main.worldName), x, y, z)) > Main.modules[gnode.tno].currentSize && Main.modules[gnode.tno].currentSize >= 15 && Main.modules[gnode.tno].energy > 50) {
				r.nextInt(1);
				Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () ->
				{
					Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "makestub " + gnode.x + " " + gnode.y + " " + gnode.z);
					Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "grow " + Main.moduleNumber);
					Main.modules[gnode.tno].energy -= 50;
				}, timer);
				Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () ->
						Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "grow " + Main.moduleNumber), timer + 2400);
			}


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
