package org.serbanstein.voidclam;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;


import java.util.ArrayList;
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
    }
    
    public static PathfinderNode leastF(List<PathfinderNode> list){
        return list.stream().min((a,b)->Double.compare(a.f,b.f)).orElse(null);
    }

    public static List<Location> getNeighbors(Location loc) {
        List<Location> neighbors = new ArrayList<>();
        for (PathfinderVector cursor : vec) {
            neighbors.add(new Location(loc.getWorld(), loc.getX() + cursor.x, loc.getY() + cursor.y, loc.getZ() + cursor.z));
        }
        return neighbors;
    }

    public static PathfinderNode calculatePath(
            int clamID,
            Location start,
            Location end,
            World world) {
        List<PathfinderNode> open = new ArrayList<>();
        List<PathfinderNode> closed = new ArrayList<>();

        PathfinderNode endNode = new PathfinderNode(end.getBlockX(),end.getBlockY(),end.getBlockZ(),null,clamID);

        PathfinderNode current = new PathfinderNode(start.getBlockX(),start.getBlockY(),start.getBlockZ(),null,clamID);
        open.add(current);
        while(!open.isEmpty()){
            current = leastF(open);
            open.remove(current);
            closed.add(current);
            if(current.equals(endNode)){
                return current;
            }
            for(PathfinderVector vector: vec){
                PathfinderNode neighbor = new PathfinderNode(
                        current.x + vector.x,
                        current.y + vector.y,
                        current.z + vector.z,
                        current,
                        clamID
                );
                if(closed.contains(neighbor)){
                    continue;
                }
                float cost = world.getBlockAt(neighbor.x,neighbor.y,neighbor.z).getType().getHardness()+0.5f;
                if(cost>50f){
                    closed.add(neighbor);
                    continue;
                }
                //if nether wart block
                if (world.getBlockAt(neighbor.x,neighbor.y,neighbor.z).getType() == Material.NETHER_WART_BLOCK){
                    cost = 0.0f;
                }
                //if liquid
                if(liquids.contains(world.getBlockAt(neighbor.x,neighbor.y,neighbor.z).getType())){
                    cost = 0.2f;
                }
                if(getNeighbors(new Location(world,neighbor.x,neighbor.y,neighbor.z)).stream().anyMatch(loc->loc.getBlock().getType().isSolid())){
                    cost *= 0.2f;
                }
                if(!open.contains(neighbor)){
                    open.add(neighbor);
                }
                if(current.g + cost < neighbor.g){
                    neighbor.parent = current;
                    neighbor.g = current.g + cost;
                    neighbor.h = neighbor.distanceTo(endNode);
                    neighbor.f = neighbor.g + neighbor.h;
                }
            }
        }
        return null;
    }

    //function that uses A* but only pathfinds through nether wart blocks, populates a candidateList with all the non-wart neighbors of the nether wart blocks and returns the neighbor with least F
    public static PathfinderNode singleBlockPathfind(int clamID,
                                                     Location start,
                                                     Location end,
                                                     World world) {
        List<PathfinderNode> open = new ArrayList<>();
        List<PathfinderNode> closed = new ArrayList<>();
        List<PathfinderNode> candidateList = new ArrayList<>();

        PathfinderNode endNode = new PathfinderNode(end.getBlockX(),end.getBlockY(),end.getBlockZ(),null,clamID);

        PathfinderNode current = new PathfinderNode(start.getBlockX(),start.getBlockY(),start.getBlockZ(),null,clamID);
        open.add(current);
        while(!open.isEmpty()){
            current = leastF(open);
            open.remove(current);
            closed.add(current);
            if(current.equals(endNode)){
                return current;
            }
            for(PathfinderVector vector: vec){
                PathfinderNode neighbor = new PathfinderNode(
                        current.x + vector.x,
                        current.y + vector.y,
                        current.z + vector.z,
                        current,
                        clamID
                );
                if(closed.contains(neighbor)){
                    continue;
                }
                if(world.getBlockAt(neighbor.x,neighbor.y,neighbor.z).getType() == Material.NETHER_WART_BLOCK){
                    if(current.g + 0.0f < neighbor.g){
                        neighbor.parent = current;
                        neighbor.g = current.g + 0.0f;
                        neighbor.h = neighbor.distanceTo(endNode);
                        neighbor.f = neighbor.g + neighbor.h;
                    }
                    if(!open.contains(neighbor)){
                        open.add(neighbor);
                    }
                }
                else{
                    candidateList.add(neighbor);
                }
            }
        }
        return leastF(candidateList);
    }
}
