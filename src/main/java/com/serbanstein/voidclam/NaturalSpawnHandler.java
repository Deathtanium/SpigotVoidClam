package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.WorldChunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Natural voidclam spawning for freshly generated chunks and optional dungeon spawner conversion.
 */
public final class NaturalSpawnHandler {
    private static final ConcurrentHashMap<Long, Boolean> spawnedChunks = new ConcurrentHashMap<>();

    public static void clearForSessionEnd() {
        spawnedChunks.clear();
    }

    public static void onChunkGenerated(ServerWorld world, WorldChunk chunk) {
        VoidClamConfig cfg = VoidClamConfig.get();
        if (!cfg.clam_spawn_natural) return;
        ChunkPos cp = chunk.getPos();
        if (cfg.naturalSpawnMethodEnum() == VoidClamConfig.NaturalSpawnMethod.DUNGEON) {
            scanChunkForSpawners(world, chunk);
            return;
        }
        if (!world.getRegistryKey().equals(ServerWorld.OVERWORLD)) return;
        long key = chunkKey(world, cp.x, cp.z);
        if (spawnedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;
        Random rand = Random.create(cp.x * 31L ^ cp.z);
        if (rand.nextDouble() >= cfg.clam_spawn_natural_default_chunk_chance) return;
        trySpawnAtChunkCenter(world, cp, rand);
    }

    private static long chunkKey(ServerWorld world, int cx, int cz) {
        return ((long) world.getRegistryKey().hashCode() << 32) ^ (ChunkPos.toLong(cx, cz) & 0xffffffffL);
    }

    private static void scanChunkForSpawners(ServerWorld world, WorldChunk chunk) {
        VoidClamConfig cfg = VoidClamConfig.get();
        Random rand = Random.create(world.getSeed() ^ ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z));
        ChunkPos cp = chunk.getPos();
        int baseX = cp.getStartX();
        int baseZ = cp.getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getBottomY(); y < world.getBottomY() + world.getDimension().height(); y++) {
                    m.set(baseX + x, y, baseZ + z);
                    BlockState st = chunk.getBlockState(m);
                    if (!st.isOf(Blocks.SPAWNER)) continue;
                    if (rand.nextDouble() >= cfg.clam_spawn_natural_dungeon_rate) continue;
                    if (!(chunk.getBlockEntity(m.toImmutable()) instanceof MobSpawnerBlockEntity)) continue;
                    world.setBlockState(m, Blocks.AIR.getDefaultState());
                    int cx = m.getX();
                    int cy = m.getY();
                    int cz = m.getZ();
                    if (VoidClamMod.makeStub(world, cx, cy, cz) == null) {
                        world.setBlockState(m, st);
                    }
                }
            }
        }
    }

    private static void trySpawnAtChunkCenter(ServerWorld world, ChunkPos cp, Random rand) {
        int centerX = cp.getCenterX() + rand.nextBetween(-2, 2);
        int centerZ = cp.getCenterZ() + rand.nextBetween(-2, 2);
        if (!world.isChunkLoaded(centerX >> 4, centerZ >> 4)) return;
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, centerX, centerZ);
        if (surfaceY <= world.getBottomY()) return;
        int clearanceR = 3 + rand.nextInt(3);
        int clearR = clearanceR + 1;
        int centerY = surfaceY - clearR - 2;
        int minY = world.getBottomY() + clearR + 1;
        if (centerY < minY) return;
        clearSphere(world, centerX, centerY, centerZ, clearR);
        VoidClamMod.makeStub(world, centerX, centerY, centerZ);
    }

    private static void clearSphere(ServerWorld world, int cx, int cy, int cz, int radius) {
        int rsq = radius * radius;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rsq) continue;
                    m.set(cx + dx, cy + dy, cz + dz);
                    if (world.isChunkLoaded(m))
                        world.setBlockState(m, Blocks.AIR.getDefaultState());
                }
            }
        }
    }
}
