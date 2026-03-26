package com.serbanstein.voidclam;

import java.util.UUID;

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
    /** Stable clam identity (matches {@link Module#clamId}); path build and kill barrier use this only. */
    public final UUID clamId;
    public Node parent;

    public Node(int x, int y, int z, Node parent, UUID clamId) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.f = this.g = this.h = 0.0;
        this.parent = parent;
        this.clamId = clamId;
    }
}
