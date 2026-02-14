package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.data.TrackedData;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
    private static final float INITIAL_SCALE_OMNI = 1f + 1f / 16f;
    private static final int PULSE_DURATION_TICKS = 8;

    /** Omnidirectional pulse: max BFS blocks per module and total; delay = distance * TICKS_PER_STEP (like path building). */
    private static final int MAX_OMNI_BFS_PER_MODULE = Integer.MAX_VALUE;
    private static final int MAX_OMNI_TOTAL_BLOCKS = Integer.MAX_VALUE;
    private static final int OMNI_TICKS_PER_STEP = 2;
    /** Max BFS node expansions per tick so omni pulse doesn't block main thread. */
    private static final int OMNI_BFS_BATCH_PER_TICK = 300;

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
    private static volatile OmniPulseJob omniPulseJob = null;

    /**
     * Run an omnidirectional pulse: starts an incremental BFS job if none is running.
     * BFS runs over multiple ticks (see {@link #tickOmniPulseJob}); when done, pulses are scheduled.
     */
    public static void runOmnidirectionalPulse(ServerWorld world) {
        if (omniPulseJob != null) return;
        Module[] modules = VoidClamMod.getModules();
        int moduleNumber = VoidClamMod.getModuleNumber();
        List<OmniPulseJob.SingleModuleBfs> bfsList = new ArrayList<>();
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m == null) continue;
            BlockPos center = new BlockPos(m.x, m.y, m.z);
            if (!world.isChunkLoaded(center)) continue;
            BlockState startState = world.getBlockState(center);
            if (startState.getBlock() != Blocks.NETHER_WART_BLOCK && startState.getBlock() != Blocks.WARPED_WART_BLOCK)
                continue;
            OmniPulseJob.SingleModuleBfs bfs = new OmniPulseJob.SingleModuleBfs(center, MAX_OMNI_BFS_PER_MODULE);
            bfsList.add(bfs);
        }
        if (bfsList.isEmpty()) return;
        omniPulseJob = new OmniPulseJob(world, bfsList);
    }

    /**
     * Call every server tick (overworld). Advances the omni-pulse BFS by a batch; when done, schedules all pulses and clears the job.
     */
    public static void tickOmniPulseJob(ServerWorld world) {
        OmniPulseJob job = omniPulseJob;
        if (job == null || job.world != world) return;
        int processed = job.tick(world, OMNI_BFS_BATCH_PER_TICK, MAX_OMNI_TOTAL_BLOCKS);
        if (!job.done) return;
        Map<BlockPos, Integer> result = job.mergedResult;
        omniPulseJob = null;
        for (Map.Entry<BlockPos, Integer> e : result.entrySet()) {
            BlockPos pos = e.getKey();
            int distance = e.getValue();
            int delay = distance * OMNI_TICKS_PER_STEP;
            VoidClamMod.scheduleDelayed(world, delay, () -> {
                if (!world.isChunkLoaded(pos)) return;
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() != Blocks.NETHER_WART_BLOCK && state.getBlock() != Blocks.WARPED_WART_BLOCK)
                    return;
                int packed = getPackedBrightnessAt(world, pos);
                startPulse(world, pos, packed, () -> {}, INITIAL_SCALE_OMNI);
            });
        }
    }

    /** Per-module BFS state for incremental omni pulse. */
    private static final class OmniPulseJob {
        final ServerWorld world;
        final Map<BlockPos, Integer> mergedResult = new HashMap<>();
        final List<SingleModuleBfs> bfsList;
        boolean done = false;

        OmniPulseJob(ServerWorld world, List<SingleModuleBfs> bfsList) {
            this.world = world;
            this.bfsList = bfsList;
            for (SingleModuleBfs bfs : bfsList) {
                BlockPos start = bfs.seed;
                bfs.queue.add(start);
                bfs.dist.put(start, 0);
                bfs.resultCount = 1;
                mergedResult.put(start, 0);
            }
        }

        /** Process up to maxNodes expansions across all module BFSes. Returns number processed. Sets done when all exhausted or total limit hit. */
        int tick(ServerWorld world, int maxNodes, int totalLimit) {
            if (mergedResult.size() >= totalLimit) {
                done = true;
                return 0;
            }
            int remaining = maxNodes;
            for (SingleModuleBfs bfs : bfsList) {
                if (remaining <= 0 || mergedResult.size() >= totalLimit) break;
                if (bfs.queue.isEmpty() || bfs.resultCount >= bfs.limit) continue;
                while (remaining > 0 && !bfs.queue.isEmpty() && bfs.resultCount < bfs.limit && mergedResult.size() < totalLimit) {
                    BlockPos pos = bfs.queue.poll();
                    int d = bfs.dist.get(pos);
                    for (Direction dir : Direction.values()) {
                        BlockPos next = pos.offset(dir).toImmutable();
                        if (bfs.dist.containsKey(next)) continue;
                        if (!world.isChunkLoaded(next)) continue;
                        BlockState state = world.getBlockState(next);
                        if (state.getBlock() != Blocks.NETHER_WART_BLOCK && state.getBlock() != Blocks.WARPED_WART_BLOCK)
                            continue;
                        int nextDist = d + 1;
                        if (mergedResult.containsKey(next)) {
                            mergedResult.merge(next, nextDist, Math::min);
                            continue;
                        }
                        bfs.dist.put(next, nextDist);
                        bfs.resultCount++;
                        mergedResult.put(next, nextDist);
                        bfs.queue.add(next);
                        remaining--;
                        if (mergedResult.size() >= totalLimit) break;
                    }
                }
            }
            boolean allDone = mergedResult.size() >= totalLimit || bfsList.stream().allMatch(bfs ->
                bfs.queue.isEmpty() || bfs.resultCount >= bfs.limit);
            if (allDone) done = true;
            return maxNodes - remaining;
        }

        static final class SingleModuleBfs {
            final BlockPos seed;
            final Queue<BlockPos> queue = new ArrayDeque<>();
            final Map<BlockPos, Integer> dist = new HashMap<>();
            final int limit;
            int resultCount = 0;

            SingleModuleBfs(BlockPos seed, int limit) {
                this.seed = seed.toImmutable();
                this.limit = limit;
            }
        }
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

    /**
     * Starts a pulsing tendril at the given position. Caller must sample brightness with
     * {@link #getPackedBrightnessAt(ServerWorld, BlockPos)} before placing the solid block and pass it here.
     */
    public static void startPulse(ServerWorld world, BlockPos pos, int packedBrightness, Runnable onComplete) {
        startPulse(world, pos, packedBrightness, onComplete, INITIAL_SCALE);
    }

    private static final double PLAYER_RANGE_SQ = 32.0 * 32.0;

    /**
     * Starts a pulsing tendril with a custom initial scale (e.g. {@link #INITIAL_SCALE_OMNI} for a subtle omni pulse).
     * Does not spawn a display if no player is within 32 blocks of the block position.
     */
    public static void startPulse(ServerWorld world, BlockPos pos, int packedBrightness, Runnable onComplete, float initialScale) {
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
        display.getDataTracker().set(blockStateData, Blocks.NETHER_WART_BLOCK.getDefaultState());
        display.getDataTracker().set(scaleData, new Vector3f(initialScale, initialScale, initialScale));
        if (brightnessData != null) {
            display.getDataTracker().set(brightnessData, packedBrightness);
            if (DEBUG) {
                int v = display.getDataTracker().get(brightnessData);
                System.out.println(DEBUG_PREFIX + "before spawn: set brightnessData(" + brightnessDataFieldName + ")=" + packedBrightness + ", get=" + v);
            }
        } else if (DEBUG) {
            System.out.println(DEBUG_PREFIX + "brightnessData is null");
        }
        display.setNoGravity(true);
        display.setInvulnerable(true);
        display.addCommandTag(VOIDCLAM_DISPLAY_TAG);

        if (!world.spawnEntity(display)) return;

        if (DEBUG && brightnessData != null) {
            int afterSpawn = display.getDataTracker().get(brightnessData);
            System.out.println(DEBUG_PREFIX + "after spawn: brightnessData get=" + afterSpawn);
            debugLogAllIntegerTrackedData(display);
        }

        long startTick = world.getTime();
        entries.add(new PulseEntry(world, pos, display, startTick, onComplete, initialScale));
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
        entries.removeAll(toRemove);
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
