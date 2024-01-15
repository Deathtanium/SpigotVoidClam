package org.serbanstein.voidclam;

public class PathfinderNode {
    int x;
    int y;
    int z;
    String worldName;
    public PathfinderNode(int x, int y, int z, PathfinderNode parent, int clamID, String worldName) {
        this.x = x; this.y = y; this.z = z;
        this.f = this.g = this.h = 0.0D;
        this.parent = parent;
    }
    double f;
    double g;
    double h;
    int clamID;
    PathfinderNode parent;

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
        return node.x == this.x && node.y == this.y && node.z == this.z;
    }

    public float distanceTo(PathfinderNode node){
        return (float) Math.sqrt(Math.pow(this.x - node.x, 2) + Math.pow(this.y - node.y, 2) + Math.pow(this.z - node.z, 2));
    }
}