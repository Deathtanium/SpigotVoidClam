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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Natural voidclam spawning for freshly generated chunks and optional dungeon spawner conversion.
 */
public final class NaturalSpawnHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("voidclam/natural_spawn");
    private static final ConcurrentHashMap<Long, Boolean> spawnedChunks = new ConcurrentHashMap<>();

    /** Total trySpawn runs (first + deferred) while waiting for the carve sphere's chunks to load. */
    private static final int NATURAL_SPAWN_CHUNK_LOAD_ATTEMPTS = 64;
    /** World-tick delay between attempts when carving would otherwise touch unloaded chunks. */
    private static final int NATURAL_SPAWN_CHUNK_LOAD_RETRY_DELAY_TICKS = 10;

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
        if (!pass) return;
        if (worldCfg.debug_log) {
            LOGGER.info(
                ("[natural_spawn] chunk roll PASS world={} chunk=({}, {}) roll={} threshold={} — scheduling trySpawn in 1 tick"),
                world.getRegistryKey().getValue(),
                cp.x,
                cp.z,
                roll,
                chance);
        }
        // Avoid heightmap / setBlockState / makeStub while still inside chunk generation (re-entrant load deadlock).
        long placementRandSeed = naturalSpawnSeed(world, cp.x, cp.z, 0xD1B54A32D192ED03L);
        VoidClamMod.scheduleDelayed(world, 1L, () ->
            trySpawnAtChunkCenter(world, cp, worldCfg, placementRandSeed, 0));
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
        VoidClamConfig.NaturalSpawnWorldSettings worldCfg,
        long placementRandSeed,
        int loadAttemptIndex
    ) {
        Random rand = Random.create(placementRandSeed);
        final boolean dbg = worldCfg.debug_log;
        if (dbg) {
            LOGGER.info(
                "[natural_spawn] trySpawn begin world={} source_chunk=({}, {}) worldTime={} loadAttempt={}/{}",
                world.getRegistryKey().getValue(),
                cp.x,
                cp.z,
                world.getTime(),
                loadAttemptIndex + 1,
                NATURAL_SPAWN_CHUNK_LOAD_ATTEMPTS);
        }
        VoidClamConfig cfg = VoidClamConfig.get();
        int centerX = cp.getCenterX() + rand.nextBetween(-2, 2);
        int centerZ = cp.getCenterZ() + rand.nextBetween(-2, 2);
        if (!world.isChunkLoaded(centerX >> 4, centerZ >> 4)) {
            deferTrySpawnForChunkLoad(
                world,
                cp,
                worldCfg,
                placementRandSeed,
                loadAttemptIndex,
                dbg,
                "center_column_chunk_unloaded");
            return;
        }

        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, centerX, centerZ);
        if (surfaceY <= world.getBottomY()) {
            if (dbg) {
                LOGGER.info(
                    "[natural_spawn] SKIP reason=no_surface_heightmap world={} source_chunk=({}, {}) centerXZ=({}, {}) surfaceY={} bottomY={}",
                    world.getRegistryKey().getValue(),
                    cp.x,
                    cp.z,
                    centerX,
                    centerZ,
                    surfaceY,
                    world.getBottomY());
            }
            return;
        }

        int maxT = Math.min(NATURAL_SPAWN_SIZE_MAX, cfg.clam_size_max);
        if (maxT < NATURAL_SPAWN_SIZE_MIN) {
            if (dbg) {
                LOGGER.info(
                    "[natural_spawn] SKIP reason=clam_size_max_too_small_for_natural world={} source_chunk=({}, {}) clam_size_max={} need_min={}",
                    world.getRegistryKey().getValue(),
                    cp.x,
                    cp.z,
                    cfg.clam_size_max,
                    NATURAL_SPAWN_SIZE_MIN);
            }
            return;
        }
        int t = rand.nextBetween(NATURAL_SPAWN_SIZE_MIN, maxT);

        int dyBottom = VoidClamMod.obsidianShellBottomDy(t);
        int sphereR = (int) Math.ceil(CommandToolbox.clamOctahedronCircumsphereRadius(t)) + worldCfg.sphere_padding;
        int minShellBottomY = world.getBottomY() + 16;
        int maxShellBottomY = surfaceY;
        if (maxShellBottomY < minShellBottomY) {
            if (dbg) {
                LOGGER.info(
                    "[natural_spawn] SKIP reason=no_room_for_shell_between_bottom_and_surface world={} source_chunk=({}, {}) minShellBottomY={} maxShellBottomY={} surfaceY={}",
                    world.getRegistryKey().getValue(),
                    cp.x,
                    cp.z,
                    minShellBottomY,
                    maxShellBottomY,
                    surfaceY);
            }
            return;
        }
        int shellBottomY = rand.nextBetween(minShellBottomY, maxShellBottomY);
        int heartY = shellBottomY - dyBottom;
        int sphereCy = shellBottomY + sphereR;

        long unloadedPacked = firstUnloadedChunkInSphere(world, centerX, sphereCy, centerZ, sphereR);
        if (unloadedPacked != Long.MIN_VALUE) {
            if (dbg) {
                int ucx = (int) (unloadedPacked >> 32);
                int ucz = (int) unloadedPacked;
                LOGGER.info(
                    "[natural_spawn] sphere_intersects_unloaded_chunk world={} source_chunk=({}, {}) sphereCenter=({}, {}, {}) sphereR={} first_unloaded_chunk=({}, {}) — will retry if attempts remain (min_blocks_below_* unused in this path)",
                    world.getRegistryKey().getValue(),
                    cp.x,
                    cp.z,
                    centerX,
                    sphereCy,
                    centerZ,
                    sphereR,
                    ucx,
                    ucz);
            }
            deferTrySpawnForChunkLoad(
                world,
                cp,
                worldCfg,
                placementRandSeed,
                loadAttemptIndex,
                dbg,
                "sphere_intersects_unloaded_chunk");
            return;
        }

        clearSphere(world, centerX, sphereCy, centerZ, sphereR);
        UUID clamId = VoidClamMod.makeNaturalSpawnClam(world, centerX, heartY, centerZ, t);
        if (clamId == null) {
            if (dbg) {
                LOGGER.info(
                    "[natural_spawn] SKIP reason=makeNaturalSpawnClam_failed world={} source_chunk=({}, {}) heart=({}, {}, {}) targetSize={}",
                    world.getRegistryKey().getValue(),
                    cp.x,
                    cp.z,
                    centerX,
                    heartY,
                    centerZ,
                    t);
            }
            return;
        }
        if (dbg) {
            LOGGER.info(
                "[natural_spawn] SUCCESS world={} source_chunk=({}, {}) clamId={} heart=({}, {}, {}) targetSize={} sphereCenter=({}, {}, {}) sphereR={} shellBottomY={}",
                world.getRegistryKey().getValue(),
                cp.x,
                cp.z,
                clamId,
                centerX,
                heartY,
                centerZ,
                t,
                centerX,
                sphereCy,
                centerZ,
                sphereR,
                shellBottomY);
        }
    }

    /**
     * Neighbors are often still absent one tick after {@code CHUNK_GENERATE}; reschedule with the same
     * {@code placementRandSeed} so XZ, size, and depth stay deterministic.
     */
    private static void deferTrySpawnForChunkLoad(
        ServerWorld world,
        ChunkPos cp,
        VoidClamConfig.NaturalSpawnWorldSettings worldCfg,
        long placementRandSeed,
        int loadAttemptIndex,
        boolean dbg,
        String reason
    ) {
        int nextAttempt = loadAttemptIndex + 1;
        if (nextAttempt >= NATURAL_SPAWN_CHUNK_LOAD_ATTEMPTS) {
            if (dbg) {
                LOGGER.info(
                    "[natural_spawn] SKIP reason={} exhausted_chunk_load_retries world={} source_chunk=({}, {}) attempts={}",
                    reason,
                    world.getRegistryKey().getValue(),
                    cp.x,
                    cp.z,
                    NATURAL_SPAWN_CHUNK_LOAD_ATTEMPTS);
            }
            return;
        }
        if (dbg) {
            LOGGER.info(
                "[natural_spawn] DEFER reason={} world={} source_chunk=({}, {}) next_in_ticks={} next_attempt_index={}/{}",
                reason,
                world.getRegistryKey().getValue(),
                cp.x,
                cp.z,
                NATURAL_SPAWN_CHUNK_LOAD_RETRY_DELAY_TICKS,
                nextAttempt + 1,
                NATURAL_SPAWN_CHUNK_LOAD_ATTEMPTS);
        }
        VoidClamMod.scheduleDelayed(world, NATURAL_SPAWN_CHUNK_LOAD_RETRY_DELAY_TICKS, () ->
            trySpawnAtChunkCenter(world, cp, worldCfg, placementRandSeed, nextAttempt));
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

    /**
     * @return {@link Long#MIN_VALUE} if every chunk column intersecting the sphere's XZ footprint is loaded;
     *         otherwise packed chunk coords {@code (chunkX << 32) | (chunkZ & 0xffffffffL)} for the first missing column.
     */
    private static long firstUnloadedChunkInSphere(ServerWorld world, int cx, int cy, int cz, int radius) {
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
                    return ((long) icx << 32) | (icz & 0xffffffffL);
                }
            }
        }
        return Long.MIN_VALUE;
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
