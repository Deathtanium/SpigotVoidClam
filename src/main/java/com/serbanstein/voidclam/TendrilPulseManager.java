package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages pulsing tendril display entities: spawns a red nether wart BlockDisplay
 * slightly larger than a block, animates it shrinking to 1:1 over several ticks,
 * then places the real block and removes the display.
 */
public final class TendrilPulseManager {
    private static final float INITIAL_SCALE = 1.2f;
    private static final float TARGET_SCALE = 1.0f;
    /** Omni pulse: barely noticeable — 1 pixel bigger than block (16px → 17/16). */
    public static final float INITIAL_SCALE_OMNI = 1f + 1f / 16f;
    private static final int PULSE_DURATION_TICKS = 8;

    /**
     * Omnidirectional pulse: caps for merged BFS. Previously {@code Integer.MAX_VALUE}, which let async mode
     * {@linkplain CommandToolbox#pathfindingExecutor() monopolize a pool thread} until the entire wart graph was visited.
     */
    private static final int MAX_OMNI_BFS_PER_MODULE = 350_000;
    private static final int MAX_OMNI_TOTAL_BLOCKS = 800_000;
    /** Async batching: expansions per inner {@link BlockBfs.MergedOmniBfsJob#step} to avoid one giant step. */
    private static final int OMNI_ASYNC_STEP_EXPANSIONS = 65_536;
    private static final int OMNI_TICKS_PER_STEP = 2;
    /** Max BFS node expansions per tick so omni pulse doesn't block main thread (sync batched mode uses 25% of this). */
    private static final int OMNI_BFS_BATCH_PER_TICK = 300;

    /** Expansions advanced per server tick in sync omni BFS — default sync A* budget matches this (see {@link VoidClamConfig#effectiveSyncMaxStepsPerTick}). */
    public static int omniBfsExpansionsPerServerTick() {
        return Math.max(1, OMNI_BFS_BATCH_PER_TICK / 4);
    }

    /** Entity tag persisted by vanilla in NBT (Tags); used to identify our tendril block displays after restart. */
    public static final String VOIDCLAM_DISPLAY_TAG = "voidclam_tendril_display";

    private static final String DEBUG_PREFIX = "[VoidClam TendrilPulse] ";
    private static final boolean DEBUG = false;

    private static final List<PulseEntry> entries = new CopyOnWriteArrayList<>();

    // Resolve by generic type so field names work across mappings (Yarn vs obfuscated)
    private static TrackedData<Vector3f> scaleData;
    private static TrackedData<BlockState> blockStateData;
    private static TrackedData<Integer> brightnessData;
    private static String brightnessDataFieldName;

    static {
        try {
            scaleData = findTrackedDataByType(DisplayEntity.class, Vector3f.class, "scale");
            blockStateData = findTrackedDataByType(DisplayEntity.BlockDisplayEntity.class, BlockState.class);
            try {
                brightnessData = findTrackedDataByType(DisplayEntity.class, Integer.class, "brightness");
                brightnessDataFieldName = findTrackedDataFieldName(DisplayEntity.class, brightnessData);
            } catch (Exception e) {
                brightnessData = null;
                brightnessDataFieldName = null;
            }
            if (DEBUG) {
                System.out.println(DEBUG_PREFIX + "init: brightnessData=" + (brightnessData != null)
                    + ", fieldName=" + brightnessDataFieldName);
            }
        } catch (Exception e) {
            throw new RuntimeException("TendrilPulseManager: failed to resolve DisplayEntity tracked data", e);
        }
    }

    /** Find which static TrackedData field holds the given data ref (for debug). */
    private static String findTrackedDataFieldName(Class<?> clazz, TrackedData<?> target) {
        if (target == null) return null;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != TrackedData.class) continue;
                f.setAccessible(true);
                try {
                    if (f.get(null) == target) return c.getSimpleName() + "." + f.getName();
                } catch (Exception ignored) { }
            }
        }
        return "?";
    }

    /** Ticks per full day (24000). Day flat 1000–11000 (max); night flat 13000–23000 (min); smooth transitions at dawn/dusk. */
    private static final long DAY_LENGTH = 24000L;
    private static final long DAY_FLAT_START = 1000L;
    private static final long DAY_FLAT_END = 11000L;
    private static final long NIGHT_FLAT_START = 13000L;
    private static final long NIGHT_FLAT_END = 23000L;
    private static final long DUSK_DURATION = NIGHT_FLAT_START - DAY_FLAT_END;   // 2000
    private static final long DAWN_DURATION = DAY_FLAT_START + (DAY_LENGTH - NIGHT_FLAT_END); // 2000
    /** Minimum sky multiplier at night (not 0) so night isn't pitch black and block light still shows. */
    private static final float NIGHT_MIN_MULT = 0.25f;

    /** Sky brightness multiplier: 1 during day flat, NIGHT_MIN_MULT during night flat, linear blend at dawn/dusk. */
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

    /** Packed brightness: max of pos and adjacent block/sky light. Sky scaled by time-of-day; high nibble uses
     * max(scaled sky, block) so block light is always reflected. Format (sky << 4) | block. */
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

    /** Incremental omni-pulse BFS job: runs over multiple ticks so main thread doesn't block. */
    private static volatile BlockBfs.MergedOmniBfsJob omniPulseJob = null;
    /** When {@link VoidClamConfig.BfsMode#ASYNC}: full omni graph runs on {@link CommandToolbox#pathfindingExecutor()}. */
    private static volatile boolean omniAsyncRunning = false;

    /** OP/debug: true while async omnidirectional BFS is running on the pathfinder pool. */
    public static boolean isOmniAsyncPulseRunning() {
        return omniAsyncRunning;
    }

    /**
     * Run an omnidirectional pulse: starts an incremental BFS job if none is running.
     * BFS runs over multiple ticks (see {@link #tickOmniPulseJob}); when done, pulses are scheduled.
     */
    public static void runOmnidirectionalPulse(ServerWorld world) {
        if (!VoidClamConfig.get().vfx_enabled) return;
        if (omniPulseJob != null || omniAsyncRunning) return;
        List<BlockBfs.MergedOmniBfsJob.SingleSource> bfsList = new ArrayList<>();
        for (Module m : VoidClamMod.getAllModules()) {
            if (m == null || m.status != 1) continue;
            BlockPos center = new BlockPos(m.x, m.y, m.z);
            if (!world.isChunkLoaded(center)) continue;
            BlockState startState = world.getBlockState(center);
            if (!VoidClamCoreBlocks.isWartOrCore(startState))
                continue;
            bfsList.add(new BlockBfs.MergedOmniBfsJob.SingleSource(center.asLong(), MAX_OMNI_BFS_PER_MODULE));
        }
        if (bfsList.isEmpty()) return;
        if (VoidClamConfig.get().bfsModeEnum() == VoidClamConfig.BfsMode.ASYNC) {
            MinecraftServer server = world.getServer();
            omniAsyncRunning = true;
            CommandToolbox.pathfindingExecutor().execute(() -> {
                CommandToolbox.pathfinderWorkerTaskBegin("omniPulse MergedOmniBfsJob sources=" + bfsList.size());
                try {
                    BlockBfs.MergedOmniBfsJob job = new BlockBfs.MergedOmniBfsJob(world, bfsList);
                    while (!job.isDone()) {
                        job.step(OMNI_ASYNC_STEP_EXPANSIONS, MAX_OMNI_TOTAL_BLOCKS);
                    }
                    Map<BlockPos, Integer> result = job.getMergedResult();
                    server.execute(() -> {
                        omniAsyncRunning = false;
                        scheduleOmniPulsesFromMergedResult(world, result);
                    });
                } catch (Throwable t) {
                    server.execute(() -> omniAsyncRunning = false);
                    throw t;
                } finally {
                    CommandToolbox.pathfinderWorkerTaskEnd();
                }
            });
            return;
        }
        omniPulseJob = new BlockBfs.MergedOmniBfsJob(world, bfsList);
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

    /**
     * Call every server tick (overworld). Advances the omni-pulse BFS by a batch; when done, schedules all pulses and clears the job.
     */
    public static void tickOmniPulseJob(ServerWorld world) {
        BlockBfs.MergedOmniBfsJob job = omniPulseJob;
        if (job == null) return;
        int batch = omniBfsExpansionsPerServerTick();
        job.step(batch, MAX_OMNI_TOTAL_BLOCKS);
        if (!job.isDone()) return;
        Map<BlockPos, Integer> result = job.getMergedResult();
        omniPulseJob = null;
        scheduleOmniPulsesFromMergedResult(world, result);
    }

    @SuppressWarnings("unchecked")
    private static void debugLogAllIntegerTrackedData(DisplayEntity display) {
        if (!DEBUG) return;
        try {
            StringBuilder sb = new StringBuilder(DEBUG_PREFIX + "all Integer TrackedData: ");
            for (Class<?> c = DisplayEntity.class; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != TrackedData.class) continue;
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    Type type = f.getGenericType();
                    if (!(type instanceof ParameterizedType pt)) continue;
                    Type arg = pt.getActualTypeArguments().length == 1 ? pt.getActualTypeArguments()[0] : null;
                    if (arg != null && (arg.equals(Integer.class) || (arg instanceof Class<?> cls && cls == Integer.class))) {
                        try {
                            int v = display.getDataTracker().get((TrackedData<Integer>) val);
                            sb.append(c.getSimpleName()).append('.').append(f.getName()).append('=').append(v).append(' ');
                        } catch (Exception e) {
                            sb.append(c.getSimpleName()).append('.').append(f.getName()).append("=err ");
                        }
                    }
                }
            }
            System.out.println(sb);
        } catch (Exception e) {
            System.out.println(DEBUG_PREFIX + "debugLogAllIntegerTrackedData: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> TrackedData<T> findTrackedDataByType(Class<?> clazz, Class<T> valueType) throws Exception {
        return findTrackedDataByType(clazz, valueType, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> TrackedData<T> findTrackedDataByType(Class<?> clazz, Class<T> valueType, String nameHint) throws Exception {
        List<TrackedData<T>> candidates = new ArrayList<>();
        List<TrackedData<T>> rawCandidates = new ArrayList<>();
        String valueTypeName = valueType.getName();
        String valueTypeSimpleName = valueType.getSimpleName();
        // For DisplayEntity brightness: only search DisplayEntity (not Entity superclass), else we get wrong field (e.g. class_1297.field_27858). BRIGHTNESS is on DisplayEntity (class_8113.field_42369).
        boolean brightnessOnlyDisplayEntity = (clazz == DisplayEntity.class && "brightness".equals(nameHint));
        for (Class<?> c = clazz; c != null; c = brightnessOnlyDisplayEntity ? null : c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != TrackedData.class) continue;
                f.setAccessible(true);
                TrackedData<T> data = (TrackedData<T>) f.get(null);
                Type type = f.getGenericType();
                if (type instanceof ParameterizedType pt && pt.getRawType() == TrackedData.class) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length != 1) continue;
                    Type arg = args[0];
                    boolean typeMatches = arg.equals(valueType)
                        || (arg instanceof Class<?> cls && (cls == valueType || valueType.isAssignableFrom(cls) || cls.isAssignableFrom(valueType)
                            || valueTypeName.equals(cls.getName()) || cls.getName().equals(valueTypeName)))
                        || arg.getTypeName().contains(valueTypeSimpleName);
                    if (!typeMatches) continue;
                    if (nameHint != null && !brightnessOnlyDisplayEntity && f.getName().toLowerCase(java.util.Locale.ROOT).contains(nameHint))
                        return data;
                    candidates.add(data);
                } else if (c == DisplayEntity.class || c == DisplayEntity.BlockDisplayEntity.class) {
                    rawCandidates.add(data);
                }
            }
        }
        // Brightness: DisplayEntity has 5 Integer TrackedData; BRIGHTNESS is the 4th (index 3) in declaration order (field_42369).
        if (brightnessOnlyDisplayEntity && valueType == Integer.class && candidates.size() >= 4) {
            return candidates.get(3);
        }
        if (nameHint != null && candidates.size() > 1)
            return candidates.get(candidates.size() - 1);
        if (!candidates.isEmpty())
            return candidates.get(0);
        if (valueType == Vector3f.class && clazz == DisplayEntity.class && rawCandidates.size() >= 2)
            return rawCandidates.get(1);
        if (!rawCandidates.isEmpty())
            return rawCandidates.get(0);
        throw new NoSuchFieldException("TrackedData<" + valueType.getSimpleName() + "> in " + clazz.getSimpleName());
    }

    private static final double PLAYER_RANGE_SQ = 32.0 * 32.0;

    /**
     * Starts a pulsing tendril at the given position. Caller must sample brightness with
     * {@link #getPackedBrightnessAt(ServerWorld, BlockPos)} before placing the solid block and pass it here.
     */
    public static void startPulse(ServerWorld world, BlockPos pos, int packedBrightness, Runnable onComplete) {
        startPulse(world, pos, Blocks.NETHER_WART_BLOCK.getDefaultState(), packedBrightness, onComplete, INITIAL_SCALE);
    }

    /** Pulse using {@code displayState} on the BlockDisplay (e.g. heart block so omni pulses match local block). */
    public static void startPulse(ServerWorld world, BlockPos pos, BlockState displayState, int packedBrightness, Runnable onComplete, float initialScale) {
        if (!VoidClamConfig.get().vfx_enabled) {
            onComplete.run();
            return;
        }
        boolean playerInRange = false;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (pos.getSquaredDistance(player.getBlockPos()) <= PLAYER_RANGE_SQ) {
                playerInRange = true;
                break;
            }
        }
        if (!playerInRange) {
            onComplete.run();
            return;
        }
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
        display.setPosition(pos.getX(), pos.getY(), pos.getZ());
        display.getDataTracker().set(blockStateData, displayState);
        display.getDataTracker().set(scaleData, new Vector3f(initialScale, initialScale, initialScale));
        if (brightnessData != null) {
            display.getDataTracker().set(brightnessData, packedBrightness);
        }
        display.setNoGravity(true);
        display.setInvulnerable(true);
        display.addCommandTag(VOIDCLAM_DISPLAY_TAG);
        if (!world.spawnEntity(display)) return;
        long startTick = world.getTime();
        entries.add(new PulseEntry(world, pos, display, startTick, onComplete, initialScale));
    }

    /**
     * Starts a pulsing tendril with a custom initial scale (e.g. {@link #INITIAL_SCALE_OMNI} for a subtle omni pulse).
     * Does not spawn a display if no player is within 32 blocks of the block position.
     */
    public static void startPulse(ServerWorld world, BlockPos pos, int packedBrightness, Runnable onComplete, float initialScale) {
        startPulse(world, pos, Blocks.NETHER_WART_BLOCK.getDefaultState(), packedBrightness, onComplete, initialScale);
    }

    /** Call every server tick for each world that may have pulses. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        List<PulseEntry> toRemove = new ArrayList<>();
        for (PulseEntry e : entries) {
            if (e.world != world) continue;
            if (!e.display.isAlive()) {
                toRemove.add(e);
                continue;
            }
            long elapsed = now - e.startTick;
            float progress = Math.min(1f, (float) elapsed / PULSE_DURATION_TICKS);
            float scale = e.initialScale + (TARGET_SCALE - e.initialScale) * progress;
            e.display.getDataTracker().set(scaleData, new Vector3f(scale, scale, scale));

            if (progress >= 1f) {
                try {
                    e.onComplete.run();
                } finally {
                    e.display.discard();
                }
                toRemove.add(e);
            }
        }
        Set<PulseEntry> toRemoveSet = Collections.newSetFromMap(new IdentityHashMap<>());
        toRemoveSet.addAll(toRemove);
        entries.removeIf(toRemoveSet::contains);
    }

    private static class PulseEntry {
        final ServerWorld world;
        final BlockPos blockPos;
        final DisplayEntity.BlockDisplayEntity display;
        final long startTick;
        final Runnable onComplete;
        final float initialScale;

        PulseEntry(ServerWorld world, BlockPos blockPos, DisplayEntity.BlockDisplayEntity display,
                   long startTick, Runnable onComplete, float initialScale) {
            this.world = world;
            this.blockPos = blockPos;
            this.display = display;
            this.startTick = startTick;
            this.onComplete = onComplete;
            this.initialScale = initialScale;
        }
    }

    /** Full-world box for entity queries. */
    private static Box fullWorldBox(ServerWorld world) {
        int minY = world.getDimension().minY();
        int maxY = minY + world.getDimension().height();
        return new Box(-3e7, minY, -3e7, 3e7, maxY, 3e7);
    }

    /** Removes all block display entities showing nether wart block (any source). Returns count removed. */
    public static int cleanupAllNetherWartDisplays(ServerWorld world) {
        Box box = fullWorldBox(world);
        List<DisplayEntity.BlockDisplayEntity> toDiscard = world.getEntitiesByClass(
            DisplayEntity.BlockDisplayEntity.class, box, e ->
                e.getDataTracker().get(blockStateData).isOf(Blocks.NETHER_WART_BLOCK));
        for (DisplayEntity.BlockDisplayEntity e : toDiscard) e.discard();
        return toDiscard.size();
    }

    private static final boolean CLEANUP_DEBUG = Boolean.getBoolean("voidclam.debug.cleanup");

    /** Removes only block display entities spawned by this mod (identified by entity tag, which vanilla persists). */
    public static void cleanupStrayDisplays(ServerWorld world) {
        Box box = fullWorldBox(world);
        List<DisplayEntity.BlockDisplayEntity> all = world.getEntitiesByClass(
            DisplayEntity.BlockDisplayEntity.class, box, entity -> true);
        int withTag = 0;
        int netherWartCount = 0;
        for (DisplayEntity.BlockDisplayEntity e : all) {
            if (e.getDataTracker().get(blockStateData).isOf(Blocks.NETHER_WART_BLOCK)) netherWartCount++;
            if (e.getCommandTags().contains(VOIDCLAM_DISPLAY_TAG)) {
                withTag++;
                e.discard();
            }
        }
        if (CLEANUP_DEBUG || (netherWartCount > 0 && withTag == 0)) {
            System.out.println("[VoidClam] cleanupStrayDisplays: world=" + world.getRegistryKey().getValue()
                + " block_displays=" + all.size() + " nether_wart=" + netherWartCount
                + " with_tag=" + withTag + " discarded=" + withTag);
        }
    }
}
