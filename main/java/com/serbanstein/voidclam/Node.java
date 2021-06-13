package com.serbanstein.voidclam;

public class Node {
    int x;
    int y;
    int z;
    public Node(int x, int y, int z, Node parent, int tno) {
        this.x = x; this.y = y; this.z = z;
        this.f = this.g = this.h = 0.0D;
        this.parent = parent;
    }
    double f;
    double g;
    double h;
    int tno;
    Node parent;
}