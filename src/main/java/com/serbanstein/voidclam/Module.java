package com.serbanstein.voidclam;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One VoidClam module (SIVA node). Types: 0 stub, 1 teen, 2 broadcast, 3 arming, 4 complex, -1 lightning rod.
 */
public class Module {
    /** Stable identity for pathfinding/kill matching; survives CSV slot shifts when persisted. */
    public UUID clamId;
    public int type;
    public int x;
    public int y;
    public int z;
    public int currentSize;
    /** 1 = awake, 0 = asleep */
    public int status;
    public int energy;
    /** Age since last phase change / startpassive */
    public int age;
    /** Whether this module seeks light sources */
    public boolean seekLights = false;
    /** Whether this module seeks ores */
    public boolean seekOres = false;
    /** Encase intruders and apply defense effects when large enough */
    public boolean protectItself = true;
    /** Positions of light sources we've failed or are ignoring for this cycle */
    public final Set<BlockPos> lightsBlackList = new HashSet<>();
    /** Positions of ores we've failed or are ignoring for this cycle */
    public final Set<BlockPos> oresBlackList = new HashSet<>();
    public short busyFlagPlaceEvent;
    public short busyFlagMainCycle;

    public void ensureClamId() {
        if (clamId == null) {
            clamId = UUID.randomUUID();
        }
    }
}
