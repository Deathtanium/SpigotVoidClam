package com.serbanstein.voidclam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * When {@code 0}, estimates {@code max(1, processors) * 128} and uses one quarter of that (not tied to omni BFS).
     */
    public int astar_sync_global_max_steps_per_tick = 0;
    /** Max parallel async pathfinding threads. 0 = estimate from available processors (minimum 2). */
    public int astar_async_global_max_threads = 0;
    /**
     * Prepass BFS cell visits + A* expansions allowed for one sync-batched job before it aborts and releases the clam.
     * Stops “infinite” searches when the budget per tick is tiny or there is no path. 0 = default {@code 400_000}.
     */
    public int astar_sync_max_total_expansions_per_job = 0;

    public int clam_size_max = 15;
    /**
     * Natural grow attempts only when {@code energy > this * currentSize}. Each successful light feed adds 1 energy.
     */
    public int clam_grow_energymultiplier = 4;

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
        if (sfx_volume_multiplier < 0) sfx_volume_multiplier = 0;
        if (astar_sync_global_max_steps_per_tick < 0) astar_sync_global_max_steps_per_tick = 0;
        if (astar_async_global_max_threads < 0) astar_async_global_max_threads = 0;
        if (astar_sync_max_total_expansions_per_job < 0) astar_sync_max_total_expansions_per_job = 0;
        if (bfs_mode != null && bfs_mode.equalsIgnoreCase("async")) {
            bfs_mode = "async";
        } else {
            bfs_mode = "sync_batched";
        }
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
     * Quarter of {@code astar_sync_global_max_steps_per_tick} when set; when {@code 0}, quarter of {@code processors * 128}.
     * Floored at {@link #ASTAR_SYNC_MIN_STEPS_PER_TICK} so very small configured values do not stall jobs for an excessive number of ticks.
     */
    public static final int ASTAR_SYNC_MIN_STEPS_PER_TICK = 48;

    public int effectiveSyncMaxStepsPerTick() {
        int base;
        if (astar_sync_global_max_steps_per_tick > 0) {
            base = astar_sync_global_max_steps_per_tick;
        } else {
            int n = Math.max(1, Runtime.getRuntime().availableProcessors());
            base = n * 128;
        }
        return Math.max(ASTAR_SYNC_MIN_STEPS_PER_TICK, Math.max(1, base / 4));
    }

    /** Max prepass visits + A* expansions for one {@link Pathfinder} sync job; then {@link VoidClamMod#releasePathfindingMainCycle}. */
    public int effectiveSyncMaxTotalExpansionsPerJob() {
        int v = astar_sync_max_total_expansions_per_job;
        if (v <= 0) {
            v = 400_000;
        }
        return Math.min(5_000_000, Math.max(5_000, v));
    }

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
