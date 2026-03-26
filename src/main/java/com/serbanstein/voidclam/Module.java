package com.serbanstein.voidclam;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * Packed {@link BlockPos#asLong()} positions of light blocks in seek range (same box as {@code clamReach}).
     * Incrementally updated on block changes; fully rebuilt in batches during the clam repair cycle.
     */
    public final Set<Long> lightsCache = ConcurrentHashMap.newKeySet();
    /** Counts down during a full cache rescan after repair; 0 when idle. */
    public int lightCacheRebuildTicksRemaining;
    /** Linear index into the scan volume during rebuild. */
    public long lightCacheRebuildCursor;
    /**
     * Packed light block this clam is currently routing toward (set in {@code clamReach}, cleared when pathfinding releases busy).
     * Entries are also in {@link #lightsBlackList} so scans skip this goal until the path/build cycle ends or repair clears all.
     */
    public volatile Long lightPathGoalPacked;
    /**
     * Packed positions of lights reserved for an in-flight path (concurrency lock): skip in {@code clamReach} until
     * {@link VoidClamMod#releasePathfindingMainCycle} or full clear on repair.
     */
    public final Set<Long> lightsBlackList = ConcurrentHashMap.newKeySet();
    /** Positions of ores we've failed or are ignoring for this cycle */
    public final Set<BlockPos> oresBlackList = new HashSet<>();
    public short busyFlagPlaceEvent;
    public short busyFlagMainCycle;
    /**
     * Scheduled {@link Pathfinder#buildPath} slice count still to finish; when it hits 0 after {@link VoidClamMod#completeOnePathApplyStep},
     * main-cycle busy is released. Reset by {@link VoidClamMod#releasePathfindingMainCycle}.
     */
    public int pathApplyPendingSteps;
    /**
     * First overworld {@link net.minecraft.server.world.ServerWorld#getTime()} tick at which this clam may run
     * {@link com.serbanstein.voidclam.CommandToolbox#clamReach} / pathfind again after {@code clamReSize} (obsidian shell + grace).
     * {@code 0} means no resize cooldown.
     */
    public long pathfindingResumeWorldTime;
    /**
     * Overworld {@link net.minecraft.server.world.ServerWorld#getTime()} when this clam should run its next auto repair/grow
     * (staggered per core position). Only meaningful while the clam is registered.
     */
    public long nextAutoGrowRepairWorldTime;
    /** {@code false} until {@link com.serbanstein.voidclam.CommandToolbox#buildStub} has run (searing-heart placements defer until first fuel). */
    public boolean stubBuilt = true;

    public void ensureClamId() {
        if (clamId == null) {
            clamId = UUID.randomUUID();
        }
    }
}
