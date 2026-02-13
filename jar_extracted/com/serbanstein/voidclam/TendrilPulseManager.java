/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_1297
 *  net.minecraft.class_1299
 *  net.minecraft.class_1937
 *  net.minecraft.class_1944
 *  net.minecraft.class_2246
 *  net.minecraft.class_2338
 *  net.minecraft.class_2350
 *  net.minecraft.class_238
 *  net.minecraft.class_2680
 *  net.minecraft.class_2940
 *  net.minecraft.class_3218
 *  net.minecraft.class_5575
 *  net.minecraft.class_8113
 *  net.minecraft.class_8113$class_8115
 *  org.joml.Vector3f
 */
package com.serbanstein.voidclam;

import com.serbanstein.voidclam.Module;
import com.serbanstein.voidclam.VoidClamMod;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.class_1297;
import net.minecraft.class_1299;
import net.minecraft.class_1937;
import net.minecraft.class_1944;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_238;
import net.minecraft.class_2680;
import net.minecraft.class_2940;
import net.minecraft.class_3218;
import net.minecraft.class_5575;
import net.minecraft.class_8113;
import org.joml.Vector3f;

public final class TendrilPulseManager {
    public static final String VOIDCLAM_DISPLAY_TAG = "voidclam_tendril_display";
    private static final float INITIAL_SCALE = 1.2f;
    private static final float TARGET_SCALE = 1.0f;
    private static final float INITIAL_SCALE_OMNI = 1.0625f;
    private static final int PULSE_DURATION_TICKS = 8;
    private static final int MAX_ACTIVE_BLOCK_DISPLAYS = 48000;
    private static final int MAX_OMNI_BFS_PER_MODULE = 4800;
    private static final int MAX_OMNI_TOTAL_BLOCKS = 24000;
    private static final int OMNI_TICKS_PER_STEP = 2;
    private static final int OMNI_BFS_BATCH_PER_TICK = 300;
    private static final String DEBUG_PREFIX = "[VoidClam TendrilPulse] ";
    private static final boolean DEBUG = false;
    private static final List<PulseEntry> entries = new CopyOnWriteArrayList<PulseEntry>();
    private static class_2940<Vector3f> scaleData;
    private static class_2940<class_2680> blockStateData;
    private static class_2940<Integer> brightnessData;
    private static String brightnessDataFieldName;
    private static final long DAY_LENGTH = 24000L;
    private static final long DAY_FLAT_START = 1000L;
    private static final long DAY_FLAT_END = 11000L;
    private static final long NIGHT_FLAT_START = 13000L;
    private static final long NIGHT_FLAT_END = 23000L;
    private static final long DUSK_DURATION = 2000L;
    private static final long DAWN_DURATION = 2000L;
    private static final float NIGHT_MIN_MULT = 0.25f;
    private static volatile OmniPulseJob omniPulseJob;

    private static String findTrackedDataFieldName(Class<?> clazz, class_2940<?> target) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != class_2940.class) continue;
                f.setAccessible(true);
                try {
                    if (f.get(null) != target) continue;
                    return c.getSimpleName() + "." + f.getName();
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
        return "?";
    }

    private static float skyMultiplierForTimeOfDay(long timeOfDay) {
        long t = timeOfDay % 24000L;
        if (t >= 1000L && t <= 11000L) {
            return 1.0f;
        }
        if (t >= 13000L && t <= 23000L) {
            return 0.25f;
        }
        if (t > 11000L && t < 13000L) {
            return 1.0f - 0.75f * (float)(t - 11000L) / 2000.0f;
        }
        if (t > 23000L) {
            return 0.25f + 0.75f * (float)(t - 23000L) / 2000.0f;
        }
        return 0.25f + 0.75f * (float)(t + 1000L) / 2000.0f;
    }

    public static int getPackedBrightnessAt(class_3218 world, class_2338 pos) {
        int maxBlock = Math.min(15, world.method_8314(class_1944.field_9282, pos));
        int maxSky = Math.min(15, world.method_8314(class_1944.field_9284, pos));
        for (class_2350 d : class_2350.values()) {
            class_2338 adj = pos.method_10093(d);
            maxBlock = Math.max(maxBlock, Math.min(15, world.method_8314(class_1944.field_9282, adj)));
            maxSky = Math.max(maxSky, Math.min(15, world.method_8314(class_1944.field_9284, adj)));
        }
        float mult = TendrilPulseManager.skyMultiplierForTimeOfDay(world.method_8532());
        int scaledSky = Math.min(15, Math.round((float)maxSky * mult));
        int highNibble = Math.max(scaledSky, maxBlock);
        return highNibble << 4 | maxBlock;
    }

    public static void runOmnidirectionalPulse(class_3218 world) {
        if (omniPulseJob != null) {
            return;
        }
        Module[] modules = VoidClamMod.getModules();
        int moduleNumber = VoidClamMod.getModuleNumber();
        ArrayList<OmniPulseJob.SingleModuleBfs> bfsList = new ArrayList<OmniPulseJob.SingleModuleBfs>();
        for (int i = 1; i <= moduleNumber; ++i) {
            class_2680 startState;
            class_2338 center;
            Module m = modules[i];
            if (m == null || !world.method_22340(center = new class_2338(m.x, m.y, m.z)) || !VoidClamMod.hasPlayerWithinRange(world, center, 4 * m.currentSize) || (startState = world.method_8320(center)).method_26204() != class_2246.field_10541 && startState.method_26204() != class_2246.field_22115) continue;
            OmniPulseJob.SingleModuleBfs bfs = new OmniPulseJob.SingleModuleBfs(center, 4800);
            bfsList.add(bfs);
        }
        if (bfsList.isEmpty()) {
            return;
        }
        omniPulseJob = new OmniPulseJob(world, bfsList);
    }

    public static void tickOmniPulseJob(class_3218 world) {
        OmniPulseJob job = omniPulseJob;
        if (job == null || job.world != world) {
            return;
        }
        int processed = job.tick(world, 300, 24000);
        if (!job.done) {
            return;
        }
        Map<class_2338, Integer> result = job.mergedResult;
        omniPulseJob = null;
        for (Map.Entry<class_2338, Integer> e : result.entrySet()) {
            class_2338 pos = e.getKey();
            int distance = e.getValue();
            int delay = distance * 2;
            VoidClamMod.scheduleDelayed(world, delay, () -> {
                if (!world.method_22340(pos)) {
                    return;
                }
                class_2680 state = world.method_8320(pos);
                if (state.method_26204() != class_2246.field_10541 && state.method_26204() != class_2246.field_22115) {
                    return;
                }
                int packed = TendrilPulseManager.getPackedBrightnessAt(world, pos);
                TendrilPulseManager.startPulse(world, pos, packed, () -> {}, 1.0625f);
            });
        }
    }

    private static void debugLogAllIntegerTrackedData(class_8113 display) {
    }

    private static <T> class_2940<T> findTrackedDataByType(Class<?> clazz, Class<T> valueType) throws Exception {
        return TendrilPulseManager.findTrackedDataByType(clazz, valueType, null);
    }

    private static <T> class_2940<T> findTrackedDataByType(Class<?> clazz, Class<T> valueType, String nameHint) throws Exception {
        ArrayList<class_2940> candidates = new ArrayList<class_2940>();
        ArrayList<class_2940> rawCandidates = new ArrayList<class_2940>();
        String valueTypeName = valueType.getName();
        String valueTypeSimpleName = valueType.getSimpleName();
        boolean brightnessOnlyDisplayEntity = clazz == class_8113.class && "brightness".equals(nameHint);
        Class<?> c = clazz;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                ParameterizedType pt;
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != class_2940.class) continue;
                f.setAccessible(true);
                class_2940 data = (class_2940)f.get(null);
                Type type = f.getGenericType();
                if (type instanceof ParameterizedType && (pt = (ParameterizedType)type).getRawType() == class_2940.class) {
                    Class cls;
                    boolean typeMatches;
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length != 1) continue;
                    Type arg = args[0];
                    boolean bl = typeMatches = arg.equals(valueType) || arg instanceof Class && ((cls = (Class)arg) == valueType || valueType.isAssignableFrom(cls) || cls.isAssignableFrom(valueType) || valueTypeName.equals(cls.getName()) || cls.getName().equals(valueTypeName)) || arg.getTypeName().contains(valueTypeSimpleName);
                    if (!typeMatches) continue;
                    if (nameHint != null && !brightnessOnlyDisplayEntity && f.getName().toLowerCase(Locale.ROOT).contains(nameHint)) {
                        return data;
                    }
                    candidates.add(data);
                    continue;
                }
                if (c != class_8113.class && c != class_8113.class_8115.class) continue;
                rawCandidates.add(data);
            }
            c = brightnessOnlyDisplayEntity ? null : c.getSuperclass();
        }
        if (brightnessOnlyDisplayEntity && valueType == Integer.class && candidates.size() >= 4) {
            return (class_2940)candidates.get(3);
        }
        if (nameHint != null && candidates.size() > 1) {
            return (class_2940)candidates.get(candidates.size() - 1);
        }
        if (!candidates.isEmpty()) {
            return (class_2940)candidates.get(0);
        }
        if (valueType == Vector3f.class && clazz == class_8113.class && rawCandidates.size() >= 2) {
            return (class_2940)rawCandidates.get(1);
        }
        if (!rawCandidates.isEmpty()) {
            return (class_2940)rawCandidates.get(0);
        }
        throw new NoSuchFieldException("TrackedData<" + valueType.getSimpleName() + "> in " + clazz.getSimpleName());
    }

    public static void startPulse(class_3218 world, class_2338 pos, int packedBrightness, Runnable onComplete) {
        TendrilPulseManager.startPulse(world, pos, packedBrightness, onComplete, 1.2f);
    }

    public static void startPulse(class_3218 world, class_2338 pos, int packedBrightness, Runnable onComplete, float initialScale) {
        if (entries.size() >= 48000) {
            return;
        }
        class_8113.class_8115 display = new class_8113.class_8115(class_1299.field_42460, (class_1937)world);
        display.method_5814((double)pos.method_10263(), (double)pos.method_10264(), (double)pos.method_10260());
        display.method_5841().method_12778(blockStateData, (Object)class_2246.field_10541.method_9564());
        display.method_5841().method_12778(scaleData, (Object)new Vector3f(initialScale, initialScale, initialScale));
        if (brightnessData != null) {
            display.method_5841().method_12778(brightnessData, (Object)packedBrightness);
        }
        display.method_5875(true);
        display.method_5684(true);
        display.method_5780(VOIDCLAM_DISPLAY_TAG);
        if (!world.method_8649((class_1297)display)) {
            return;
        }
        long startTick = world.method_75260();
        entries.add(new PulseEntry(world, pos, display, startTick, onComplete, initialScale));
    }

    public static int cleanupAllNetherWartDisplays(class_3218 world) {
        class_238 worldBox = new class_238(-3.0E7, (double)world.method_31607(), -3.0E7, 3.0E7, (double)world.method_31600(), 3.0E7);
        ArrayList<class_8113.class_8115> toDiscard = new ArrayList<class_8113.class_8115>();
        for (class_8113.class_8115 entity : world.method_18023((class_5575)class_1299.field_42460, worldBox, e -> ((class_2680)e.method_5841().method_12789(blockStateData)).method_27852(class_2246.field_10541))) {
            toDiscard.add(entity);
        }
        for (class_8113.class_8115 e2 : toDiscard) {
            e2.method_31472();
        }
        return toDiscard.size();
    }

    public static void cleanupStrayDisplays(class_3218 world) {
        class_238 worldBox = new class_238(-3.0E7, (double)world.method_31607(), -3.0E7, 3.0E7, (double)world.method_31600(), 3.0E7);
        for (class_8113.class_8115 entity : world.method_18023((class_5575)class_1299.field_42460, worldBox, e -> e.method_5752().contains(VOIDCLAM_DISPLAY_TAG))) {
            entity.method_31472();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void tick(class_3218 world) {
        long now = world.method_75260();
        ArrayList<PulseEntry> toRemove = new ArrayList<PulseEntry>();
        for (PulseEntry e : entries) {
            if (e.world != world) continue;
            if (!e.display.method_5805()) {
                toRemove.add(e);
                continue;
            }
            long elapsed = now - e.startTick;
            float progress = Math.min(1.0f, (float)elapsed / 8.0f);
            float scale = e.initialScale + (1.0f - e.initialScale) * progress;
            e.display.method_5841().method_12778(scaleData, (Object)new Vector3f(scale, scale, scale));
            if (!(progress >= 1.0f)) continue;
            try {
                e.onComplete.run();
            }
            finally {
                e.display.method_31472();
            }
            toRemove.add(e);
        }
        entries.removeAll(toRemove);
    }

    static {
        try {
            scaleData = TendrilPulseManager.findTrackedDataByType(class_8113.class, Vector3f.class, "scale");
            blockStateData = TendrilPulseManager.findTrackedDataByType(class_8113.class_8115.class, class_2680.class);
            try {
                brightnessData = TendrilPulseManager.findTrackedDataByType(class_8113.class, Integer.class, "brightness");
                brightnessDataFieldName = TendrilPulseManager.findTrackedDataFieldName(class_8113.class, brightnessData);
            }
            catch (Exception e) {
                brightnessData = null;
                brightnessDataFieldName = null;
            }
        }
        catch (Exception e) {
            throw new RuntimeException("TendrilPulseManager: failed to resolve DisplayEntity tracked data", e);
        }
        omniPulseJob = null;
    }

    private static final class OmniPulseJob {
        final class_3218 world;
        final Map<class_2338, Integer> mergedResult = new HashMap<class_2338, Integer>();
        final List<SingleModuleBfs> bfsList;
        boolean done = false;

        OmniPulseJob(class_3218 world, List<SingleModuleBfs> bfsList) {
            this.world = world;
            this.bfsList = bfsList;
            for (SingleModuleBfs bfs : bfsList) {
                class_2338 start = bfs.seed;
                bfs.queue.add(start);
                bfs.dist.put(start, 0);
                bfs.resultCount = 1;
                this.mergedResult.put(start, 0);
            }
        }

        int tick(class_3218 world, int maxNodes, int totalLimit) {
            boolean allDone;
            if (this.mergedResult.size() >= totalLimit) {
                this.done = true;
                return 0;
            }
            int remaining = maxNodes;
            for (SingleModuleBfs bfs2 : this.bfsList) {
                if (remaining <= 0 || this.mergedResult.size() >= totalLimit) break;
                if (bfs2.queue.isEmpty() || bfs2.resultCount >= bfs2.limit) continue;
                block1: while (remaining > 0 && !bfs2.queue.isEmpty() && bfs2.resultCount < bfs2.limit && this.mergedResult.size() < totalLimit) {
                    class_2338 pos = bfs2.queue.poll();
                    int d = bfs2.dist.get(pos);
                    for (class_2350 dir : class_2350.values()) {
                        class_2680 state;
                        class_2338 next = pos.method_10093(dir).method_10062();
                        if (bfs2.dist.containsKey(next) || !world.method_22340(next) || (state = world.method_8320(next)).method_26204() != class_2246.field_10541 && state.method_26204() != class_2246.field_22115) continue;
                        int nextDist = d + 1;
                        if (this.mergedResult.containsKey(next)) {
                            this.mergedResult.merge(next, nextDist, Math::min);
                            continue;
                        }
                        bfs2.dist.put(next, nextDist);
                        ++bfs2.resultCount;
                        this.mergedResult.put(next, nextDist);
                        bfs2.queue.add(next);
                        --remaining;
                        if (this.mergedResult.size() >= totalLimit) continue block1;
                    }
                }
            }
            boolean bl = allDone = this.mergedResult.size() >= totalLimit || this.bfsList.stream().allMatch(bfs -> bfs.queue.isEmpty() || bfs.resultCount >= bfs.limit);
            if (allDone) {
                this.done = true;
            }
            return maxNodes - remaining;
        }

        static final class SingleModuleBfs {
            final class_2338 seed;
            final Queue<class_2338> queue = new ArrayDeque<class_2338>();
            final Map<class_2338, Integer> dist = new HashMap<class_2338, Integer>();
            final int limit;
            int resultCount = 0;

            SingleModuleBfs(class_2338 seed, int limit) {
                this.seed = seed.method_10062();
                this.limit = limit;
            }
        }
    }

    private static class PulseEntry {
        final class_3218 world;
        final class_2338 blockPos;
        final class_8113.class_8115 display;
        final long startTick;
        final Runnable onComplete;
        final float initialScale;

        PulseEntry(class_3218 world, class_2338 blockPos, class_8113.class_8115 display, long startTick, Runnable onComplete, float initialScale) {
            this.world = world;
            this.blockPos = blockPos;
            this.display = display;
            this.startTick = startTick;
            this.onComplete = onComplete;
            this.initialScale = initialScale;
        }
    }
}

