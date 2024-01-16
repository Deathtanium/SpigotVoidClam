package org.serbanstein.voidclam;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        liquids.add(Material.AIR);
    }
    
    public static PathfinderNode leastF(List<PathfinderNode> list){
        return list.stream().min(Comparator.comparingDouble(a -> a.f)).orElse(null);
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
            for(PathfinderNode neighbor : neighbors){
                if (endNode.equals(neighbor)) {
                    return neighbor;
                }
                if(closed.contains(neighbor)){
                    continue;
                }
                float cost = world.getBlockAt(neighbor).getType().getHardness()+0.5f;
                cost*=cost;
                if(cost>2500f){
                    closed.add(neighbor);
                    continue;
                }
                //if liquid
                if(liquids.contains(world.getBlockAt(neighbor).getType())){
                    cost = 2f;
                }
                if(getNeighbors(neighbor,clamID).stream().anyMatch(loc->(loc.getBlock().getType().isSolid() && loc.getBlock().getType() != Material.NETHER_WART_BLOCK))){
                    cost *= 0.2f;
                }
                if(world.getBlockAt(neighbor).getType() == Material.NETHER_WART_BLOCK){
                    cost = 0;
                }
                neighbor.parent = current;
                neighbor.g = current.g + cost;
                neighbor.h = neighbor.distance(endNode);
                neighbor.f = neighbor.g + neighbor.h;
                if(cost==0) {
                    open.add(neighbor);
                }else{
                    candidateList.add(neighbor);
                }
            }
        }
        return leastF(candidateList);
    }
}
