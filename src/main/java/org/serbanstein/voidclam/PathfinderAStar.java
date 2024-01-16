package org.serbanstein.voidclam;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PathfinderAStar {
    static List<PathfinderVector> vec = new ArrayList<>();
    static List<Material> liquids = new ArrayList<>();

    public static void initializeLists(){
        vec.add(new PathfinderVector( 1, 0, 0));
        vec.add(new PathfinderVector(-1, 0, 0));
        vec.add(new PathfinderVector( 0, 1, 0));
        vec.add(new PathfinderVector( 0,-1, 0));
        vec.add(new PathfinderVector( 0, 0, 1));
        vec.add(new PathfinderVector( 0, 0,-1));
        liquids.add(Material.WATER);
        liquids.add(Material.LAVA);
    }
    
    public static PathfinderNode leastF(List<PathfinderNode> list){
        return list.stream().min((a,b)->Double.compare(a.f,b.f)).orElse(null);
    }

    public static List<PathfinderNode> getNeighbors(PathfinderNode currentNode, int clamID) {
        List<PathfinderNode> neighbors = new ArrayList<>();
        for (PathfinderVector cursor : vec) {
            neighbors.add(new PathfinderNode(
                    currentNode.getWorld(),
                    currentNode.getBlockX() + cursor.x,
                    currentNode.getBlockY() + cursor.y,
                    currentNode.getBlockZ() + cursor.z,
                    currentNode,
                    clamID
            ));
        }
        return neighbors;
    }

    //function that uses A* but only pathfinds through nether wart blocks, populates a candidateList with all the non-wart neighbors of the nether wart blocks and returns the neighbor with least F
    public static PathfinderNode singleBlockPathfind(int clamID,
                                                     Location start,
                                                     Location end,
                                                     World world) {
        List<PathfinderNode> open = new ArrayList<>();
        List<PathfinderNode> closed = new ArrayList<>();
        List<PathfinderNode> candidateList = new ArrayList<>();

        PathfinderNode endNode = new PathfinderNode(end, null, clamID);
        PathfinderNode current = new PathfinderNode(start, null, clamID);

        open.add(current);
        while(!open.isEmpty()){
            current = leastF(open);
            open.remove(current);
            closed.add(current);
            if(current.equals(endNode)){
                return current;
            }
            List<PathfinderNode> neighbors = getNeighbors(current, clamID);
            System.out.println("neighbors size: " + neighbors.size());
            for(PathfinderNode neighbor : neighbors){
                if (endNode.equals(neighbor)) {
                    return neighbor;
                }
                if(closed.contains(neighbor)){
                    continue;
                }
                float cost = world.getBlockAt(neighbor).getType().getHardness()+0.5f;
                if(cost>50f){
                    closed.add(neighbor);
                    continue;
                }
                //if liquid
                if(liquids.contains(world.getBlockAt(neighbor).getType())){
                    cost = 0.2f;
                }
                if(getNeighbors(neighbor,clamID).stream().anyMatch(loc->(loc.getBlock().getType().isSolid() && loc.getBlock().getType() != Material.NETHER_WART_BLOCK))){
                    cost *= 0.2f;
                }
                if(world.getBlockAt(neighbor).getType() == Material.NETHER_WART_BLOCK){
                    cost = 0;
                }
                if(current.g + 0.0f < neighbor.g){
                    neighbor.parent = current;
                    neighbor.g = current.g + 0.0f;
                    neighbor.h = neighbor.distanceTo(endNode);
                    neighbor.f = neighbor.g + neighbor.h;
                }
                if(cost==0) {
                    open.add(neighbor);
                }else{
                    System.out.println("neighbor.f: " + neighbor.f);
                    candidateList.add(neighbor);
                    closed.add(neighbor);
                }
            }
        }
        System.out.println("candidateList size: " + candidateList.size());
        return leastF(candidateList);
    }
}
