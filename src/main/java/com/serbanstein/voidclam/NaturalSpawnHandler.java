package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.WorldChunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Natural voidclam spawning for freshly generated chunks and optional dungeon spawner conversion.
 */
public final class NaturalSpawnHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("voidclam/natural_spawn");
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
        long key = chunkKey(world, cp.x, cp.z);
        if (spawnedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;
        Random rand = Random.create(cp.x * 31L ^ cp.z);
        double roll = rand.nextDouble();
        double chance = cfg.clam_spawn_natural_default_chunk_chance;
        boolean pass = roll < chance;
        if (cfg.clam_spawn_natural_debug_log) {
            LOGGER.info(
                "[natural_spawn] default chunk roll world={} chunk=({}, {}) roll={} threshold={} pass={}",
                world.getRegistryKey().getValue(),
                cp.x,
                cp.z,
                roll,
                chance,
                pass);
        }
        if (!pass) return;
        // Avoid heightmap / setBlockState / makeStub while still inside chunk generation (re-entrant load deadlock).
        long randSeed = cp.x * 31L ^ cp.z;
        VoidClamMod.scheduleDelayed(world, 1L, () -> {
            Random deferred = Random.create(randSeed);
            deferred.nextDouble();
            trySpawnAtChunkCenter(world, cp, deferred);
        });
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

    /** Inclusive natural spawn size for default method (see {@link VoidClamMod#makeStubWithTargetSize}). */
    private static final int NATURAL_SPAWN_SIZE_MIN = 5;
    private static final int NATURAL_SPAWN_SIZE_MAX = 11;

    private static void trySpawnAtChunkCenter(ServerWorld world, ChunkPos cp, Random rand) {
        int centerX = cp.getCenterX() + rand.nextBetween(-2, 2);
        int centerZ = cp.getCenterZ() + rand.nextBetween(-2, 2);
        if (!world.isChunkLoaded(centerX >> 4, centerZ >> 4)) return;

        VoidClamConfig cfg = VoidClamConfig.get();
        int sea = world.getSeaLevel();
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, centerX, centerZ);
        if (surfaceY <= world.getBottomY()) return;

        int maxT = Math.min(NATURAL_SPAWN_SIZE_MAX, cfg.clam_size_max);
        if (maxT < NATURAL_SPAWN_SIZE_MIN) return;
        int t = rand.nextBetween(NATURAL_SPAWN_SIZE_MIN, maxT);

        int dyBottom = VoidClamMod.obsidianShellBottomDy(t);
        int sphereR = (int) Math.ceil(CommandToolbox.clamOctahedronCircumsphereRadius(t)) + cfg.clam_spawn_natural_sphere_padding;

        int minBelowSea = cfg.clam_spawn_natural_min_blocks_below_sea;
        int minBelowSurface = cfg.clam_spawn_natural_min_blocks_below_surface;

        int capSeaSphere = sea - minBelowSea - dyBottom - 2 * sphereR;
        int capSurfSphere = surfaceY - minBelowSurface - dyBottom - 2 * sphereR;
        int capSeaShell = sea - minBelowSea - (t - 1);
        int capSurfShell = surfaceY - minBelowSurface - (t - 1);
        int maxHeartY = Math.min(Math.min(capSeaSphere, capSurfSphere), Math.min(capSeaShell, capSurfShell));
        int minHeartY = world.getBottomY() + 1 - dyBottom;

        if (maxHeartY < minHeartY) return;

        int heartY = rand.nextBetween(minHeartY, maxHeartY);
        int sphereCy = heartY + dyBottom + sphereR;

        if (!allChunksIntersectingSphereLoaded(world, centerX, sphereCy, centerZ, sphereR)) return;

        clearSphere(world, centerX, sphereCy, centerZ, sphereR);
        VoidClamMod.makeStubWithTargetSize(world, centerX, heartY, centerZ, t);
    }

    private static boolean allChunksIntersectingSphereLoaded(ServerWorld world, int cx, int cy, int cz, int radius) {
        int minX = cx - radius;
        int maxX = cx + radius;
        int minZ = cz - radius;
        int maxZ = cz + radius;
        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;
        for (int icx = minCx; icx <= maxCx; icx++) {
            for (int icz = minCz; icz <= maxCz; icz++) {
                if (!world.isChunkLoaded(icx, icz)) {
                    return false;
                }
            }
        }
        return true;
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
