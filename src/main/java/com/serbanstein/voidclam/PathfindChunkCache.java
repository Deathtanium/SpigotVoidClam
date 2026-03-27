package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional snapshot of {@link WorldChunk} columns overlapping the pathfinding AABB for one module.
 * <p>
 * {@linkplain Pathfinder#calculatePath Async A*} runs on {@link com.serbanstein.voidclam.CommandToolbox#pathfindingExecutor()}
 * worker threads; {@link WorldChunk} block-state reads are not safe there while the server thread mutates chunks.
 * For async jobs, pass {@code snapshotColumns == false} so {@link #getBlockState} uses {@link ServerWorld#getBlockState}
 * only (same as pre-cache behavior). Sync-batched {@link Pathfinder.AStarJob} steps on the server thread and may use
 * {@code snapshotColumns == true} to avoid repeated {@link ServerWorld#getChunk} work.
 */
public final class PathfindChunkCache {
    private final ServerWorld world;
    private final Map<Long, WorldChunk> columns;

    public PathfindChunkCache(ServerWorld world, Module mod, boolean snapshotColumns) {
        this.world = world;
        if (!snapshotColumns) {
            this.columns = Map.of();
            return;
        }
        int c = mod.currentSize;
        int cx = mod.x;
        int cy = mod.y;
        int cz = mod.z;
        int minBx = cx - Pathfinder.PATHFINDING_RANGE_XZ_HALF * c;
        int maxBx = cx + Pathfinder.PATHFINDING_RANGE_XZ_HALF * c;
        int minBz = cz - Pathfinder.PATHFINDING_RANGE_Z_HALF * c;
        int maxBz = cz + Pathfinder.PATHFINDING_RANGE_Z_HALF * c;
        int minCx = minBx >> 4;
        int maxCx = maxBx >> 4;
        int minCz = minBz >> 4;
        int maxCz = maxBz >> 4;
        int est = Math.max(16, (maxCx - minCx + 1) * (maxCz - minCz + 1));
        Map<Long, WorldChunk> m = new HashMap<>(Math.min(1 << 20, est * 4 / 3 + 1));
        for (int icx = minCx; icx <= maxCx; icx++) {
            for (int icz = minCz; icz <= maxCz; icz++) {
                if (!world.isChunkLoaded(icx, icz)) {
                    continue;
                }
                Chunk ch = world.getChunk(icx, icz);
                if (ch instanceof WorldChunk wc) {
                    m.put(columnKey(icx, icz), wc);
                }
            }
        }
        this.columns = m;
    }

    private static long columnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockState(int x, int y, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        WorldChunk wc = columns.get(columnKey(chunkX, chunkZ));
        if (wc == null) {
            return world.getBlockState(new BlockPos(x, y, z));
        }
        return wc.getBlockState(new BlockPos(x, y, z));
    }
}
