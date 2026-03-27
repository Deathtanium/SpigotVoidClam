package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Column snapshot for pathfinding: one {@link WorldChunk} reference per chunk column in the pathfinding AABB.
 * <p>
 * <strong>Threading:</strong> {@link #buildColumnSnapshot} must run on the server thread (it calls
 * {@link ServerWorld#getChunk}). The resulting instance may then be used from a pathfinder worker to read
 * {@link #getBlockState} without repeated chunk-map lookups. If the world changes before the path is applied,
 * {@link Pathfinder#buildPath} re-reads the live world for each step (small footprint).
 */
public final class PathfindChunkCache {
    private final ServerWorld world;
    private final Map<Long, WorldChunk> columns;

    private PathfindChunkCache(ServerWorld world, Map<Long, WorldChunk> columns) {
        this.world = world;
        this.columns = columns;
    }

    /**
     * Server thread only: resolves every loaded column in the module's pathfinding AABB once.
     */
    public static PathfindChunkCache buildColumnSnapshot(ServerWorld world, Module mod) {
        int c = mod.currentSize;
        int cx = mod.x;
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
        return new PathfindChunkCache(world, m);
    }

    /** No column snapshot; every {@link #getBlockState} uses {@link ServerWorld#getBlockState}. */
    public static PathfindChunkCache liveWorldOnly(ServerWorld world) {
        return new PathfindChunkCache(world, Map.of());
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
