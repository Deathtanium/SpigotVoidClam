package com.serbanstein.voidclam;

/** 6-direction offset for A* neighbours (no diagonals). */
public class Cursor {
    public final int x;
    public final int y;
    public final int z;

    public Cursor(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
