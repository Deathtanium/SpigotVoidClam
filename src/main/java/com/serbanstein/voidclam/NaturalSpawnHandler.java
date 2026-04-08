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
        VoidClamConfig.NaturalSpawnWorldSettings worldCfg = cfg.naturalSpawnWorldSettings(world.getRegistryKey());
        if (worldCfg == null || !worldCfg.enabled) return;
        ChunkPos cp = chunk.getPos();
        if (worldCfg.methodEnum() == VoidClamConfig.NaturalSpawnMethod.DUNGEON) {
            scanChunkForSpawners(world, chunk, worldCfg);
            return;
        }
        long key = chunkKey(world, cp.x, cp.z);
        if (spawnedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;
        long rollSeed = naturalSpawnSeed(world, cp.x, cp.z, 0x9E3779B97F4A7C15L);
        Random rand = Random.create(rollSeed);
        double roll = rand.nextDouble();
        double chance = worldCfg.default_chunk_chance;
        boolean pass = roll < chance;
        if (worldCfg.debug_log) {
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
        long randSeed = naturalSpawnSeed(world, cp.x, cp.z, 0xD1B54A32D192ED03L);
        VoidClamMod.scheduleDelayed(world, 1L, () -> {
            Random deferred = Random.create(randSeed);
            trySpawnAtChunkCenter(world, cp, deferred, worldCfg);
        });
    }

    private static long chunkKey(ServerWorld world, int cx, int cz) {
        return ((long) world.getRegistryKey().hashCode() << 32) ^ (ChunkPos.toLong(cx, cz) & 0xffffffffL);
    }

    private static void scanChunkForSpawners(ServerWorld world, WorldChunk chunk, VoidClamConfig.NaturalSpawnWorldSettings worldCfg) {
        Random rand = Random.create(naturalSpawnSeed(world, chunk.getPos().x, chunk.getPos().z, 0x94D049BB133111EBL));
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
                    if (rand.nextDouble() >= worldCfg.dungeon_rate) continue;
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
    private static final int NATURAL_SPAWN_SIZE_MAX = 9;

    private static void trySpawnAtChunkCenter(
        ServerWorld world,
        ChunkPos cp,
        Random rand,
        VoidClamConfig.NaturalSpawnWorldSettings worldCfg
    ) {
        VoidClamConfig cfg = VoidClamConfig.get();
        int centerX = cp.getCenterX() + rand.nextBetween(-2, 2);
        int centerZ = cp.getCenterZ() + rand.nextBetween(-2, 2);
        if (!world.isChunkLoaded(centerX >> 4, centerZ >> 4)) return;

        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, centerX, centerZ);
        if (surfaceY <= world.getBottomY()) return;

        int maxT = Math.min(NATURAL_SPAWN_SIZE_MAX, cfg.clam_size_max);
        if (maxT < NATURAL_SPAWN_SIZE_MIN) return;
        int t = rand.nextBetween(NATURAL_SPAWN_SIZE_MIN, maxT);

        int dyBottom = VoidClamMod.obsidianShellBottomDy(t);
        int sphereR = (int) Math.ceil(CommandToolbox.clamOctahedronCircumsphereRadius(t)) + worldCfg.sphere_padding;
        int minShellBottomY = world.getBottomY() + 16;
        int maxShellBottomY = surfaceY;
        if (maxShellBottomY < minShellBottomY) return;
        int shellBottomY = rand.nextBetween(minShellBottomY, maxShellBottomY);
        int heartY = shellBottomY - dyBottom;
        int sphereCy = shellBottomY + sphereR;

        if (!allChunksIntersectingSphereLoaded(world, centerX, sphereCy, centerZ, sphereR)) return;

        clearSphere(world, centerX, sphereCy, centerZ, sphereR);
        VoidClamMod.makeNaturalSpawnClam(world, centerX, heartY, centerZ, t);
    }

    private static long naturalSpawnSeed(ServerWorld world, int cx, int cz, long salt) {
        long worldSeed = world.getSeed();
        long chunk = ChunkPos.toLong(cx, cz);
        long dimSalt = world.getRegistryKey().getValue().toString().hashCode();
        long mixed = worldSeed ^ Long.rotateLeft(chunk, 21) ^ Long.rotateLeft(dimSalt, 9) ^ salt;
        return splitmix64(mixed);
    }

    private static long splitmix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
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
