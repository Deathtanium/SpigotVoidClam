package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Ordered prepass route (heart → goal-adjacent cell) with states captured at prepass time for mid-A* staleness checks. */
public final class PrepassBfsSnapshot {
    private final long[] cellLongs;
    private final BlockState[] cellStatesAtPrepass;
    private final int goalX;
    private final int goalY;
    private final int goalZ;
    private final BlockState goalStateAtPrepass;

    PrepassBfsSnapshot(
        long[] cellLongs,
        BlockState[] cellStatesAtPrepass,
        int goalX,
        int goalY,
        int goalZ,
        BlockState goalStateAtPrepass
    ) {
        this.cellLongs = cellLongs;
        this.cellStatesAtPrepass = cellStatesAtPrepass;
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.goalStateAtPrepass = goalStateAtPrepass;
    }

    public int goalX() {
        return goalX;
    }

    public int goalY() {
        return goalY;
    }

    public int goalZ() {
        return goalZ;
    }

    /** True if live world differs from prepass snapshot along the route or at the goal. */
    public boolean hasLiveWorldDiscrepancy(ServerWorld world) {
        for (int i = 0; i < cellLongs.length; i++) {
            BlockPos p = BlockPos.fromLong(cellLongs[i]);
            if (!world.getBlockState(p).equals(cellStatesAtPrepass[i])) {
                return true;
            }
        }
        BlockPos goalPos = new BlockPos(goalX, goalY, goalZ);
        return !world.getBlockState(goalPos).equals(goalStateAtPrepass);
    }
}
