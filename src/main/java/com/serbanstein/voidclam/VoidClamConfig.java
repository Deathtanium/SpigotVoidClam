package com.serbanstein.voidclam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Server config at {@code config/voidclam.json}. Created with defaults if missing.
 */
public final class VoidClamConfig {
    public enum NaturalSpawnMethod {
        DEFAULT,
        DUNGEON
    }

    public enum AstarMode {
        SYNC_BATCHED,
        ASYNC
    }

    /**
     * How BFS-heavy work is scheduled: incremental on the server thread vs a background executor.
     * Applies to omnidirectional pulse graph and storage container discovery only. Path reachability prepass
     * always runs on the same thread as the A* work unit (server tick job or pathfinder worker) and is not
     * affected by this setting, so async A* still does prepass then A* back-to-back on one worker with early exit.
     */
    public enum BfsMode {
        SYNC_BATCHED,
        ASYNC
    }

    /** Try to spawn voidclams in freshly generated chunks. */
    public boolean clam_spawn_natural = false;
    /** Only if {@link #clam_spawn_natural}: {@code default} or {@code dungeon}. */
    public String clam_spawn_natural_method = "default";
    /** If method is dungeon: chance per mob spawner block to replace with a voidclam nest. */
    public double clam_spawn_natural_dungeon_rate = 0.15;
    /** Approximate chance per newly generated overworld chunk for default natural spawn (ignored for dungeon method). */
    public double clam_spawn_natural_default_chunk_chance = 0.003;

    public boolean clam_light_flag_default = false;
    public boolean clam_ores_flag_default = false;
    public boolean clam_protect_itself_default = true;

    public String astar_mode = "async";
    /** {@code sync_batched} or {@code async} — see {@link BfsMode}. */
    public String bfs_mode = "sync_batched";
    /**
     * Sync A* + prepass budget source: when &gt; 0, effective steps per tick = this value divided by 4.
     * When {@code 0}, estimates a base budget from host CPU max clock (see {@link #syncBudgetResolvedCpuMhz()}) and uses
     * one quarter of that, then one fifth (calibrated so ~4.0 GHz reported → ~205 steps/tick).
     */
    public int astar_sync_global_max_steps_per_tick = 0;
    /** Max parallel async pathfinding threads. 0 = estimate from available processors (minimum 2). */
    public int astar_async_global_max_threads = 0;
    /**
     * Prepass BFS cell visits + A* expansions allowed for one sync-batched job before it aborts and releases the clam.
     * Stops “infinite” searches when the budget per tick is tiny or there is no path. 0 = default {@code 400_000}.
     * {@code -1} = no cap (debug only; can hang or OOM on huge searches). Values &lt; {@code -1} are cleared to null.
     * Use {@link Long} so Gson reliably reads {@code -1} from JSON (a primitive {@code int} field can misbehave with some configs).
     */
    public Long astar_sync_max_total_expansions_per_job;

    /**
     * When {@code true}, A* / prepass use a per-job {@link PathfindChunkCache} snapshot.
     * When {@code false}, every cell uses live {@link ServerWorld#getBlockState} (debug / legacy).
     * {@code null} after load = default {@code true} (Gson omits key otherwise {@code boolean} would read as false).
     */
    public Boolean pathfind_chunk_cache;

    /**
     * Light + ore seek caches: when {@code true} (default), {@link Module#lightsCache}/{@link Module#oresCache} are
     * maintained (tick rebuild + block deltas) and {@link CommandToolbox#clamReach} reads them; cache and blacklist
     * positions stay in server memory only (see {@link VoidClamMod#tickSeekEphemeralExpiry}). When {@code false}
     * (“live”), caches are not maintained and clamReach rescans the full seek box each run; threading follows
     * {@link #astar_mode} like A*.
     * {@code null} after load ⇒ {@code true}, or legacy migration from {@link #light_block_cache}/{@link #ore_block_cache}.
     */
    public Boolean seek_target_cache;

    /**
     * Legacy keys read only when {@link #seek_target_cache} is null after JSON load; both explicitly {@code false}
     * migrates to {@code seek_target_cache false}. Prefer {@link #seek_target_cache} in new configs.
     */
    public Boolean light_block_cache;
    public Boolean ore_block_cache;

    public int clam_size_max = 15;
    /**
     * Natural grow attempts only when {@code energy > this * currentSize}. Each successful light feed adds 1 energy.
     */
    public int clam_grow_energymultiplier = 4;
    /** Auto repair/grow cadence in seconds (world-time based; per-clam phase offset still applies). */
    public int clam_repair_grow_cycle_interval_seconds = 5 * 60;
    /** How often awake clams attempt target selection/pathfinding in seconds (phase-staggered per clam). */
    public int clam_seek_attempt_interval_seconds = 1;

    public boolean vfx_enabled = true;
    public double sfx_volume_multiplier = 1.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile VoidClamConfig instance = defaultInstance();

    private static VoidClamConfig defaultInstance() {
        return new VoidClamConfig();
    }

    public static VoidClamConfig get() {
        return instance;
    }

    public static void loadFromDisk() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("voidclam.json");
        VoidClamConfig cfg = defaultInstance();
        if (Files.isRegularFile(path)) {
            try (BufferedReader r = Files.newBufferedReader(path)) {
                VoidClamConfig fromFile = GSON.fromJson(r, VoidClamConfig.class);
                if (fromFile != null) {
                    cfg = fromFile;
                }
            } catch (IOException ignored) {
                // keep defaults
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(path)) {
                    GSON.toJson(cfg, w);
                }
            } catch (IOException ignored) {
                // still use defaults in memory
            }
        }
        cfg.normalize();
        instance = cfg;
        CommandToolbox.configurePathfinderExecutorSize(cfg.effectiveAsyncThreadPoolSize());
    }

    private void normalize() {
        if (clam_spawn_natural_dungeon_rate < 0) clam_spawn_natural_dungeon_rate = 0;
        if (clam_spawn_natural_dungeon_rate > 1) clam_spawn_natural_dungeon_rate = 1;
        if (clam_spawn_natural_default_chunk_chance < 0) clam_spawn_natural_default_chunk_chance = 0;
        if (clam_spawn_natural_default_chunk_chance > 1) clam_spawn_natural_default_chunk_chance = 1;
        if (clam_size_max < 1) clam_size_max = 1;
        if (clam_grow_energymultiplier < 1) clam_grow_energymultiplier = 1;
        if (clam_repair_grow_cycle_interval_seconds < 1) clam_repair_grow_cycle_interval_seconds = 1;
        if (clam_seek_attempt_interval_seconds < 1) clam_seek_attempt_interval_seconds = 1;
        if (sfx_volume_multiplier < 0) sfx_volume_multiplier = 0;
        if (astar_sync_global_max_steps_per_tick < 0) astar_sync_global_max_steps_per_tick = 0;
        if (astar_async_global_max_threads < 0) astar_async_global_max_threads = 0;
        if (astar_sync_max_total_expansions_per_job != null && astar_sync_max_total_expansions_per_job < -1L) {
            astar_sync_max_total_expansions_per_job = null;
        }
        if (bfs_mode != null && bfs_mode.equalsIgnoreCase("async")) {
            bfs_mode = "async";
        } else {
            bfs_mode = "sync_batched";
        }
        if (seek_target_cache == null) {
            if (Boolean.FALSE.equals(light_block_cache) && Boolean.FALSE.equals(ore_block_cache)) {
                seek_target_cache = false;
            } else {
                seek_target_cache = true;
            }
        }
        // Keep legacy keys migration-only so config converges to one cache flag.
        light_block_cache = null;
        ore_block_cache = null;
    }

    public NaturalSpawnMethod naturalSpawnMethodEnum() {
        if (clam_spawn_natural_method != null && clam_spawn_natural_method.equalsIgnoreCase("dungeon")) {
            return NaturalSpawnMethod.DUNGEON;
        }
        return NaturalSpawnMethod.DEFAULT;
    }

    public AstarMode astarModeEnum() {
        if (astar_mode != null && astar_mode.equalsIgnoreCase("sync_batched")) {
            return AstarMode.SYNC_BATCHED;
        }
        return AstarMode.ASYNC;
    }

    public BfsMode bfsModeEnum() {
        if (bfs_mode != null && bfs_mode.equalsIgnoreCase("async")) {
            return BfsMode.ASYNC;
        }
        return BfsMode.SYNC_BATCHED;
    }

    public BlockBfs.ExecutionMode bfsBlockBfsExecutionMode() {
        return bfsModeEnum() == BfsMode.ASYNC
            ? BlockBfs.ExecutionMode.BACKGROUND
            : BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED;
    }

    /**
     * Global A* + prepass BFS expansion budget per server tick for {@link AstarMode#SYNC_BATCHED}.
     * Quarter of {@code astar_sync_global_max_steps_per_tick} when set; when {@code 0}, quarter of a MHz-derived base
     * (see {@link #syncBudgetBaseBeforeQuarter()}); when that value is MHz-auto, it is also divided by 5 after the quartering.
     * Floored at {@link #ASTAR_SYNC_MIN_STEPS_PER_TICK} so very small configured values do not stall jobs for an excessive number of ticks.
     */
    public static final int ASTAR_SYNC_MIN_STEPS_PER_TICK = 48;

    /**
     * Host CPU MHz at which the auto base equals {@link #AUTO_SYNC_STEPS_BASE_AT_CALIBRATION_MHZ} (4096), so effective
     * steps/tick after ÷4 and ÷5 is ~205.
     */
    private static final double AUTO_SYNC_STEPS_CPU_MHZ_CALIBRATION = 4000.0;

    private static final int AUTO_SYNC_STEPS_BASE_AT_CALIBRATION_MHZ = 4096;

    private static final int AUTO_SYNC_STEPS_BASE_MIN = 256;

    /** Resolved once at class init: detected max MHz or {@link #AUTO_SYNC_STEPS_CPU_MHZ_CALIBRATION} if unknown. */
    private static final double RESOLVED_SYNC_BUDGET_CPU_MHZ = resolveSyncBudgetCpuMhz();

    private static double resolveSyncBudgetCpuMhz() {
        Double mhz = tryDetectHostCpuMaxMhz();
        if (mhz != null && mhz > 0.0 && Double.isFinite(mhz)) {
            return mhz;
        }
        return AUTO_SYNC_STEPS_CPU_MHZ_CALIBRATION;
    }

    /**
     * Best-effort host CPU max frequency in MHz (Linux sysfs / {@code /proc/cpuinfo}, Windows {@code wmic}).
     * {@code null} when unavailable.
     */
    private static Double tryDetectHostCpuMaxMhz() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            Double d = tryReadLinuxCpufreqKhzToMhz("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
            if (d != null) {
                return d;
            }
            d = tryReadLinuxCpufreqKhzToMhz("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq");
            if (d != null) {
                return d;
            }
            d = tryReadLinuxProcCpuinfoMaxMhz();
            if (d != null) {
                return d;
            }
        } else if (os.contains("win")) {
            Double d = tryReadWindowsWmicMaxClockMhz();
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static Double tryReadLinuxCpufreqKhzToMhz(String sysfsPath) {
        try {
            String s = Files.readString(Path.of(sysfsPath)).trim();
            long khz = Long.parseLong(s);
            if (khz <= 0L) {
                return null;
            }
            return khz / 1000.0;
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Double tryReadLinuxProcCpuinfoMaxMhz() {
        try {
            double max = -1.0;
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                line = line.trim();
                if (line.startsWith("cpu MHz")) {
                    int c = line.indexOf(':');
                    if (c < 0) {
                        continue;
                    }
                    double v = Double.parseDouble(line.substring(c + 1).trim());
                    max = Math.max(max, v);
                }
            }
            return max > 0.0 ? max : null;
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Double tryReadWindowsWmicMaxClockMhz() {
        Process proc = null;
        try {
            proc = new ProcessBuilder("wmic", "cpu", "get", "MaxClockSpeed", "/value")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("MaxClockSpeed=")) {
                        double v = Double.parseDouble(line.substring("MaxClockSpeed=".length()).trim());
                        return v > 0.0 ? v : null;
                    }
                }
            }
            proc.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // missing WMIC or parse failure
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
        return null;
    }

    private static int autoSyncStepsBaseFromDetectedMhz(double mhz) {
        int base = (int) Math.round(
            AUTO_SYNC_STEPS_BASE_AT_CALIBRATION_MHZ * (mhz / AUTO_SYNC_STEPS_CPU_MHZ_CALIBRATION));
        return Math.max(AUTO_SYNC_STEPS_BASE_MIN, base);
    }

    /** MHz used for the auto sync-step budget when {@link #astar_sync_global_max_steps_per_tick} is {@code 0}. */
    public static double syncBudgetResolvedCpuMhz() {
        return RESOLVED_SYNC_BUDGET_CPU_MHZ;
    }

    /**
     * Expansion budget numerator before the legacy ÷4 applied in {@link #effectiveSyncMaxStepsPerTick()}:
     * {@link #astar_sync_global_max_steps_per_tick} when set, otherwise the MHz-derived auto base.
     */
    public int syncBudgetBaseBeforeQuarter() {
        if (astar_sync_global_max_steps_per_tick > 0) {
            return astar_sync_global_max_steps_per_tick;
        }
        return autoSyncStepsBaseFromDetectedMhz(RESOLVED_SYNC_BUDGET_CPU_MHZ);
    }

    public int effectiveSyncMaxStepsPerTick() {
        int base = syncBudgetBaseBeforeQuarter();
        int quartered = Math.max(1, base / 4);
        if (astar_sync_global_max_steps_per_tick <= 0) {
            quartered = Math.max(1, quartered / 5);
        }
        return Math.max(ASTAR_SYNC_MIN_STEPS_PER_TICK, quartered);
    }

    /**
     * Max prepass visits + A* expansions for one {@link Pathfinder} job; then {@link VoidClamMod#releasePathfindingMainCycle}.
     * {@code -1} in config yields {@link Long#MAX_VALUE} (uncapped, debug).
     */
    public long effectiveSyncMaxTotalExpansionsPerJob() {
        Long box = astar_sync_max_total_expansions_per_job;
        if (Objects.equals(box, -1L)) {
            return Long.MAX_VALUE;
        }
        long raw = box == null ? 0L : box;
        if (raw <= 0L) {
            raw = 400_000L;
        }
        return Math.min(5_000_000L, Math.max(5_000L, raw));
    }

    /**
     * {@code true} when enabled or key absent in JSON; only {@code false} when {@link #pathfind_chunk_cache} is explicitly false.
     */
    public boolean pathfindChunkCacheEnabled() {
        return !Boolean.FALSE.equals(pathfind_chunk_cache);
    }

    /** {@code true} when {@link #seek_target_cache} is not explicitly {@code false} (default cached mode). */
    public boolean seekTargetCacheEnabled() {
        return !Boolean.FALSE.equals(seek_target_cache);
    }

    /** Same as {@link #seekTargetCacheEnabled()} — light and ore share one switch. */
    public boolean lightBlockCacheEnabled() {
        return seekTargetCacheEnabled();
    }

    /** Same as {@link #seekTargetCacheEnabled()}. */
    public boolean oreBlockCacheEnabled() {
        return seekTargetCacheEnabled();
    }

    public int autoGrowRepairIntervalTicks() {
        return Math.max(20, clam_repair_grow_cycle_interval_seconds * 20);
    }

    public int seekAttemptIntervalTicks() {
        return Math.max(20, clam_seek_attempt_interval_seconds * 20);
    }

    /**
     * When {@code true}, {@link Pathfinder} logs {@code [voidclam/Pathfinder][trace]} INFO lines to the server log (console)
     * for BFS prepass progress (sync-batched slices and async full runs), A* iterations (open/closed sizes, running vs done),
     * detected {@link Node#parent} cycles during path apply, and {@code tryInsertInto} (container storage after mining).
     * Default off.
     */
    public boolean pathfinding_trace = false;

    /**
     * When {@code true}, logs {@code [voidclam/crash-crumbs]} INFO lines before sync A* slices and container/inventory work,
     * and on {@link StackOverflowError} in those voidclam hotspots logs extra context then rethrows (best-effort—deep SO may skip).
     * Enable while hunting server tick {@code Sets$1$1} / Guava iterator overflows. Default off.
     */
    public boolean tick_crash_crumbs = false;

    public int effectiveAsyncThreadPoolSize() {
        return effectiveAsyncThreadPoolSize(astar_async_global_max_threads);
    }

    static int effectiveAsyncThreadPoolSize(int configured) {
        if (configured > 0) {
            return configured;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }
}
