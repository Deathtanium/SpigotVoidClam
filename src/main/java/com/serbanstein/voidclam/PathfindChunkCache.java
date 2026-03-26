package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot of {@link WorldChunk} columns overlapping the pathfinding AABB for one module, built once per
 * {@link Pathfinder.AStarJob#step} / async {@link Pathfinder#calculatePath} prepass so A* avoids repeated
 * {@link ServerWorld#getChunk} work. Positions in unloaded columns fall back to the live world.
 */
public final class PathfindChunkCache {
    private final ServerWorld world;
    private final Map<Long, WorldChunk> columns;

    public PathfindChunkCache(ServerWorld world, Module mod) {
        this.world = world;
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
