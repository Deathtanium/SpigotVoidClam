package com.serbanstein.voidclam;

/**
 * A* pathfinding node.
 */
public class Node {
    public final int x;
    public final int y;
    public final int z;
    public double f;
    public double g;
    public double h;
    /** Module index this path belongs to */
    public int tno;
    public Node parent;

    public Node(int x, int y, int z, Node parent, int tno) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.f = this.g = this.h = 0.0;
        this.parent = parent;
        this.tno = tno;
    }
}
