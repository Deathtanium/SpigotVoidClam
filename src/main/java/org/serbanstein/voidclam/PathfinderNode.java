package org.serbanstein.voidclam;

public class PathfinderNode {
    int x;
    int y;
    int z;
    public PathfinderNode(int x, int y, int z, PathfinderNode parent, int clamID) {
        this.x = x; this.y = y; this.z = z;
        this.f = this.g = this.h = 0.0D;
        this.parent = parent;
    }
    double f;
    double g;
    double h;
    int clamID;
    PathfinderNode parent;
}