/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_2338
 */
package com.serbanstein.voidclam;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.class_2338;

public class Module {
    public int type;
    public int x;
    public int y;
    public int z;
    public int currentSize;
    public int status;
    public int energy;
    public int age;
    public boolean seekLights;
    public boolean seekOres;
    public final Set<class_2338> lightsBlackList = new HashSet<class_2338>();
    public final Set<class_2338> oresBlackList = new HashSet<class_2338>();
    public short busyFlagPlaceEvent;
    public short busyFlagMainCycle;
    public short busyFlagGrowing;
    public int mobEffect;
}

