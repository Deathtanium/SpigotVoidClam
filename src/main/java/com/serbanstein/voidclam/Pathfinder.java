package com.serbanstein.voidclam;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;

import static org.bukkit.Bukkit.getPlayer;
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
					} else if (Main.baseCost.contains(bl) || bl.getHardness()<=0.2) {
						cst = 10;
						for(Cursor cc : yc){
							//would rather stick to walls than suspending tendrils mid-air
							Material mat = Objects.requireNonNull(Bukkit.getServer().getWorld(Main.worldName)).getBlockAt(nextNode.x+cc.x,nextNode.y+cc.y,nextNode.z+cc.z).getType();
							if(!(mat.getHardness()<=0.2 || Main.baseCost.contains(mat) || mat==Material.NETHER_WART_BLOCK)){
								cst = 0.1;
							}
						}
					} else {
						if(bl.getHardness() > 5) cst = 2500;
						else {
							cst = 10+bl.getHardness()*10;
						}
					}
					final double finalcst = cst;
					/*
					//this below is to avoid pets, animals, armor stands, item frames bla bla
					Collection<Entity> coll = Objects.requireNonNull(getWorld(Main.worldName)).getNearbyEntities(new Location(Bukkit.getWorld(Main.worldName),nextNode.x,nextNode.y,nextNode.z),2,2,2);
					if(!coll.isEmpty()){
						cst = 2500;
					}*/
					//this shit does not work and is a performance nightmare

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
			long timer = 2;
			while (copy.parent != null) {
				timer += 2;
				copy = copy.parent;
			}
			Random r = new Random();

			Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () -> Main.modules[gnode.tno].lightsBlackList.remove(new Location(getWorld(Main.worldName), gnode.x, gnode.y, gnode.z)), timer);

			int []stamina = new int[1];
			stamina[0]=Main.modules[gnode.tno].currentSize;
			int []blocked = new int[1];
			while (firstNode.parent != null && blocked[0]==0) {
				final Node refnode2 = firstNode;
				Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(Main.getPlugin(Main.class), () -> {
					if(blocked[0]==0) {
						Material mat = Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(refnode2.x, refnode2.y, refnode2.z).getType();
						int cst = 0;
						if(mat == Material.NETHER_WART_BLOCK) cst =0;
						else if(mat == Material.AIR || mat == Material.WATER || mat == Material.LAVA) cst = 1;
						else cst = (int)Math.floor(mat.getHardness())*2;
						if(refnode2==gnode) cst=0;
						/*if (!(refnode2==gnode || mat == Material.AIR || mat == Material.WATER || mat == Material.LAVA || mat == Material.NETHER_WART_BLOCK)) {
							blocked[0]=1;
							Main.modules[refnode2.tno+1].lightsBlackList.add(new Location(getWorld(Main.worldName),gnode.x,gnode.y,gnode.z));
							Main.modules[refnode2.tno+1].energy--;
						}else*/
						if(stamina[0]-cst<0){
							blocked[0]=1;
							if(!(mat == Material.AIR || mat == Material.WATER || mat == Material.LAVA)) Main.modules[gnode.tno].lightsBlackList.add(new Location(getWorld(Main.worldName),gnode.x,gnode.y,gnode.z));
							Main.modules[gnode.tno].energy--;
						}else{
							stamina[0]-=cst;
						}
						if(refnode2 == gnode && !Main.lights.contains(mat))
							Main.modules[gnode.tno].energy--;
						Objects.requireNonNull(getWorld(Main.worldName)).getBlockAt(refnode2.x, refnode2.y, refnode2.z).setType(Material.NETHER_WART_BLOCK);
						Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(getWorld(Main.worldName), refnode2.x, refnode2.y, refnode2.z), Sound.valueOf("BLOCK_CHORUS_FLOWER_GROW"), 1, 0.01f);
						if (!(refnode2 == gnode || mat == Material.AIR || mat == Material.WATER || mat == Material.LAVA || mat == Material.NETHER_WART_BLOCK))
							Objects.requireNonNull(getWorld(Main.worldName)).dropItemNaturally(new Location(getWorld(Main.worldName),refnode2.x, refnode2.y,refnode2.z),new ItemStack(mat,1));
					}
				}, (timer));
				timer -= 2;
				firstNode = firstNode.parent;
			}
		}
	}
}
