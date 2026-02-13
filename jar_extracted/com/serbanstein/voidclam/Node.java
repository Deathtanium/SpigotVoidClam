/*
 * Decompiled with CFR 0.152.
 */
package com.serbanstein.voidclam;

public class Node {
    public final int x;
    public final int y;
    public final int z;
    public double f;
    public double g;
    public double h;
    public int tno;
    public Node parent;

    public Node(int x, int y, int z, Node parent, int tno) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.h = 0.0;
        this.g = 0.0;
        this.f = 0.0;
        this.parent = parent;
        this.tno = tno;
    }
}

