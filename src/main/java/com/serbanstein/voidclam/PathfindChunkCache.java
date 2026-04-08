package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot of {@link WorldChunk} columns overlapping the pathfinding AABB for one clam, built once per
 * {@link Pathfinder.AStarJob#step} / async {@link Pathfinder#calculatePath} prepass so A* avoids repeated
 * {@link ServerWorld#getChunk} work when {@link VoidClamConfig#pathfind_chunk_cache} is {@code true}.
 * Missing snapshot columns use bedrock placeholders so async workers never need a live world read for those cells.
 * When the config flag is {@code false}, no snapshot is built and {@link #getBlockState} always uses {@link ServerWorld#getBlockState}
 * (legacy / debug path).
 */
public final class PathfindChunkCache {
    private final ServerWorld world;
    private final boolean useColumnSnapshot;
    private final Map<Long, WorldChunk> columns;

    public PathfindChunkCache(ServerWorld world, Clam mod) {
        this.world = world;
        this.useColumnSnapshot = VoidClamConfig.get().pathfindChunkCacheEnabled();
        if (!useColumnSnapshot) {
            this.columns = Collections.emptyMap();
            return;
        }
        int c = Math.max(1, mod.currentSize);
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
        this.columns = Collections.unmodifiableMap(m);
    }

    private static long columnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (!useColumnSnapshot) {
            return world.getBlockState(new BlockPos(x, y, z));
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        WorldChunk wc = columns.get(columnKey(chunkX, chunkZ));
        if (wc == null) {
            return Blocks.BEDROCK.getDefaultState();
        }
        return wc.getBlockState(new BlockPos(x, y, z));
    }
}
