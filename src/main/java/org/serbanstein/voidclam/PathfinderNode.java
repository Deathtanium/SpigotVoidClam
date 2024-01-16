package org.serbanstein.voidclam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class PathfinderNode extends Location {
    public PathfinderNode(World world, int x, int y, int z, PathfinderNode parent, int clamID) {
        super(world, x, y, z);
        this.f = this.g = this.h = 0.0D;
        this.parent = parent;
    }
    double f;
    double g;
    double h;
    int clamID;
    PathfinderNode parent;

    public PathfinderNode(Location end, PathfinderNode parent, int clamID) {
        super(end.getWorld(), end.getBlockX(), end.getBlockY(), end.getBlockZ());
        this.f = this.g = this.h = 0.0D;
        this.parent = parent;
        this.clamID = clamID;
    }

    //override equals method to compare nodes
    @Override
    public boolean equals(Object o){
        if(o == this){
            return true;
        }
        if(!(o instanceof PathfinderNode)){
            return false;
        }
        PathfinderNode node = (PathfinderNode) o;
        return node.getBlockY() == this.getBlockY() && node.getBlockX() == this.getBlockX() && node.getBlockZ() == this.getBlockZ();
    }

}