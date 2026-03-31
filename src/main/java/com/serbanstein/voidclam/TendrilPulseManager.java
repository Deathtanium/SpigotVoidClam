package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * Tendril pulse scheduling. Minecraft 1.16 has no block display entities; pulses run the completion
 * callback after {@link #PULSE_DURATION_TICKS} when VFX is enabled and a player could see the cell,
 * matching approximate 1.21 timing without the scale animation.
 */
public final class TendrilPulseManager {
    private static final float INITIAL_SCALE = 1.2f;
    public static final float INITIAL_SCALE_OMNI = 1f + 1f / 16f;
    private static final float TARGET_SCALE = 1.0f;
    private static final int PULSE_DURATION_TICKS = 8;

    private static final int MAX_OMNI_BFS_PER_CLAM = 350_000;
    private static final int MAX_OMNI_TOTAL_BLOCKS = 800_000;
    private static final int OMNI_ASYNC_STEP_EXPANSIONS = 65_536;
    private static final int OMNI_TICKS_PER_STEP = 2;
    private static final int OMNI_BFS_BATCH_PER_TICK = 300;

    public static int omniBfsExpansionsPerServerTick() {
        return Math.max(1, OMNI_BFS_BATCH_PER_TICK / 4);
    }

    public static final String VOIDCLAM_DISPLAY_TAG = "voidclam_tendril_display";

    private static volatile BlockBfs.MergedOmniBfsJob omniPulseJob = null;
    private static volatile @Nullable net.minecraft.util.registry.RegistryKey<World> omniPulseJobDimension = null;
    private static volatile boolean omniAsyncRunning = false;

    private static final long DAY_LENGTH = 24000L;
    private static final long DAY_FLAT_START = 1000L;
    private static final long DAY_FLAT_END = 11000L;
    private static final long NIGHT_FLAT_START = 13000L;
    private static final long NIGHT_FLAT_END = 23000L;
    private static final long DUSK_DURATION = NIGHT_FLAT_START - DAY_FLAT_END;
    private static final long DAWN_DURATION = DAY_FLAT_START + (DAY_LENGTH - NIGHT_FLAT_END);
    private static final float NIGHT_MIN_MULT = 0.25f;

    private static float skyMultiplierForTimeOfDay(long timeOfDay) {
        long t = timeOfDay % DAY_LENGTH;
        if (t >= DAY_FLAT_START && t <= DAY_FLAT_END) return 1f;
        if (t >= NIGHT_FLAT_START && t <= NIGHT_FLAT_END) return NIGHT_MIN_MULT;
        if (t > DAY_FLAT_END && t < NIGHT_FLAT_START) {
            return 1f - (1f - NIGHT_MIN_MULT) * (float) (t - DAY_FLAT_END) / DUSK_DURATION;
        }
        if (t > NIGHT_FLAT_END) {
            return NIGHT_MIN_MULT + (1f - NIGHT_MIN_MULT) * (float) (t - NIGHT_FLAT_END) / DAWN_DURATION;
        }
        return NIGHT_MIN_MULT + (1f - NIGHT_MIN_MULT) * (float) (t + DAY_FLAT_START) / DAWN_DURATION;
    }

    public static int getPackedBrightnessAt(ServerWorld world, BlockPos pos) {
        int maxBlock = Math.min(15, world.getLightLevel(LightType.BLOCK, pos));
        int maxSky = Math.min(15, world.getLightLevel(LightType.SKY, pos));
        for (Direction d : Direction.values()) {
            BlockPos adj = pos.offset(d);
            maxBlock = Math.max(maxBlock, Math.min(15, world.getLightLevel(LightType.BLOCK, adj)));
            maxSky = Math.max(maxSky, Math.min(15, world.getLightLevel(LightType.SKY, adj)));
        }
        float mult = skyMultiplierForTimeOfDay(world.getTimeOfDay());
        int scaledSky = Math.min(15, Math.round(maxSky * mult));
        int highNibble = Math.max(scaledSky, maxBlock);
        return (highNibble << 4) | maxBlock;
    }

    public static boolean isOmniAsyncPulseRunning() {
        return omniAsyncRunning;
    }

    public static void runOmnidirectionalPulse(ServerWorld world) {
        if (!VoidClamConfig.get().vfx_enabled) return;
        if (omniPulseJob != null || omniAsyncRunning) return;
        List<BlockBfs.MergedOmniBfsJob.SingleSource> bfsList = new ArrayList<>();
        for (Clam m : VoidClamMod.getAllClams()) {
            if (m == null || m.status != 1) continue;
            if (!m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
            BlockPos center = new BlockPos(m.x, m.y, m.z);
            if (!world.isChunkLoaded(center)) continue;
            BlockState startState = world.getBlockState(center);
            if (!VoidClamCoreBlocks.isWartOrCore(startState))
                continue;
            bfsList.add(new BlockBfs.MergedOmniBfsJob.SingleSource(center.asLong(), MAX_OMNI_BFS_PER_CLAM));
        }
        if (bfsList.isEmpty()) return;
        if (VoidClamConfig.get().bfsModeEnum() == VoidClamConfig.BfsMode.ASYNC) {
            MinecraftServer server = world.getServer();
            omniAsyncRunning = true;
            omniPulseJobDimension = world.getRegistryKey();
            CommandToolbox.pathfindingExecutor().execute(() -> {
                try {
                    BlockBfs.MergedOmniBfsJob job = new BlockBfs.MergedOmniBfsJob(world, bfsList);
                    while (!job.isDone()) {
                        job.step(OMNI_ASYNC_STEP_EXPANSIONS, MAX_OMNI_TOTAL_BLOCKS);
                    }
                    Map<BlockPos, Integer> result = job.getMergedResult();
                    server.execute(() -> {
                        omniAsyncRunning = false;
                        omniPulseJobDimension = null;
                        scheduleOmniPulsesFromMergedResult(world, result);
                    });
                } catch (Throwable t) {
                    server.execute(() -> {
                        omniAsyncRunning = false;
                        omniPulseJobDimension = null;
                    });
                    throw t;
                }
            });
            return;
        }
        omniPulseJob = new BlockBfs.MergedOmniBfsJob(world, bfsList);
        omniPulseJobDimension = world.getRegistryKey();
    }

    private static void scheduleOmniPulsesFromMergedResult(ServerWorld world, Map<BlockPos, Integer> result) {
        for (Map.Entry<BlockPos, Integer> e : result.entrySet()) {
            BlockPos pos = e.getKey();
            int distance = e.getValue();
            int delay = distance * OMNI_TICKS_PER_STEP;
            VoidClamMod.scheduleDelayed(world, delay, () -> {
                if (!world.isChunkLoaded(pos)) return;
                BlockState state = world.getBlockState(pos);
                if (!VoidClamCoreBlocks.isWartOrCore(state))
                    return;
                int packed = getPackedBrightnessAt(world, pos);
                startPulse(world, pos, state, packed, () -> {}, INITIAL_SCALE_OMNI);
            });
        }
    }

    public static void tickOmniPulseJob(ServerWorld world) {
        BlockBfs.MergedOmniBfsJob job = omniPulseJob;
        if (job == null || omniPulseJobDimension == null || !world.getRegistryKey().equals(omniPulseJobDimension)) {
            return;
        }
        int batch = omniBfsExpansionsPerServerTick();
        job.step(batch, MAX_OMNI_TOTAL_BLOCKS);
        if (!job.isDone()) return;
        Map<BlockPos, Integer> result = job.getMergedResult();
        omniPulseJob = null;
        omniPulseJobDimension = null;
        scheduleOmniPulsesFromMergedResult(world, result);
    }

    public static void startPulse(ServerWorld world, BlockPos pos, int packedBrightness, Runnable onComplete) {
        startPulse(world, pos, Blocks.NETHER_WART_BLOCK.getDefaultState(), packedBrightness, onComplete, INITIAL_SCALE);
    }

    public static void startPulse(ServerWorld world, BlockPos pos, BlockState displayState, int packedBrightness, Runnable onComplete, float initialScale) {
        if (!VoidClamConfig.get().vfx_enabled) {
            onComplete.run();
            return;
        }
        if (!anyPlayerCanSeePulse(world, pos)) {
            onComplete.run();
            return;
        }
        VoidClamMod.scheduleDelayed(world, PULSE_DURATION_TICKS, onComplete);
    }

    public static void startPulse(ServerWorld world, BlockPos pos, int packedBrightness, Runnable onComplete, float initialScale) {
        startPulse(world, pos, Blocks.NETHER_WART_BLOCK.getDefaultState(), packedBrightness, onComplete, initialScale);
    }

    public static void tick(ServerWorld world) {
        // No per-tick display animation on 1.16.
    }

    private static final double PLAYER_RANGE_SQ = 32.0 * 32.0;
    private static final double MAX_PLAYER_FOV_DEGREES = 130.0;
    private static final double COS_HALF_MAX_PLAYER_FOV = Math.cos(Math.toRadians(MAX_PLAYER_FOV_DEGREES / 2.0));

    private static boolean isVisualObstacle(BlockState state, ServerWorld world, BlockPos pos) {
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getCollisionShape(world, pos).isEmpty()) return false;
        if (!state.isOpaqueFullCube(world, pos)) return false;
        return true;
    }

    private static boolean isWithinFov(net.minecraft.server.network.ServerPlayerEntity player, BlockPos targetPos) {
        net.minecraft.util.math.Vec3d eye = player.getCameraPosVec(1.0f);
        net.minecraft.util.math.Vec3d toTarget = center(targetPos).subtract(eye);
        double lenSq = toTarget.lengthSquared();
        if (lenSq <= 1.0e-10) return true;
        net.minecraft.util.math.Vec3d look = player.getRotationVec(1.0f);
        double lookLenSq = look.lengthSquared();
        if (lookLenSq <= 1.0e-10) return true;
        double dot = look.normalize().dotProduct(toTarget.normalize());
        return dot >= COS_HALF_MAX_PLAYER_FOV;
    }

    private static boolean hasClearLineOfSight(ServerWorld world, net.minecraft.server.network.ServerPlayerEntity player, BlockPos targetPos) {
        net.minecraft.util.math.Vec3d eye = player.getCameraPosVec(1.0f);
        net.minecraft.util.math.Vec3d target = center(targetPos);
        net.minecraft.util.math.Vec3d delta = target.subtract(eye);
        double length = delta.length();
        if (length <= 1.0e-6) return true;
        int steps = Math.max(1, (int) Math.ceil(length * 4.0));
        int consecutiveObstacles = 0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / (double) steps;
            net.minecraft.util.math.Vec3d p = eye.add(delta.multiply(t));
            BlockPos bp = new BlockPos(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
            if (bp.equals(targetPos)) {
                continue;
            }
            if (!world.isChunkLoaded(bp.getX() >> 4, bp.getZ() >> 4)) {
                return false;
            }
            if (isVisualObstacle(world.getBlockState(bp), world, bp)) {
                consecutiveObstacles++;
                if (consecutiveObstacles >= 2) {
                    return false;
                }
            } else {
                consecutiveObstacles = 0;
            }
        }
        return true;
    }

    private static net.minecraft.util.math.Vec3d center(BlockPos targetPos) {
        return new net.minecraft.util.math.Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
    }

    private static boolean anyPlayerCanSeePulse(ServerWorld world, BlockPos pos) {
        for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
            if (pos.getSquaredDistance(player.getBlockPos()) > PLAYER_RANGE_SQ) {
                continue;
            }
            if (!isWithinFov(player, pos)) {
                continue;
            }
            if (hasClearLineOfSight(world, player, pos)) {
                return true;
            }
        }
        return false;
    }

    private static Box fullWorldBox(ServerWorld world) {
        int minY = 0;
        int maxY = world.getDimensionHeight();
        return new Box(-3e7, minY, -3e7, 3e7, maxY, 3e7);
    }

    public static int cleanupAllNetherWartDisplays(ServerWorld world) {
        return 0;
    }

    public static void cleanupStrayDisplays(ServerWorld world) {
    }
}
