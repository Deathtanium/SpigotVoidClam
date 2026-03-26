package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Block building, shell/stub construction, resize/repair, and reach (pathfind trigger). */
public final class CommandToolbox {
    /**
     * Shared executor for pathfinding (reach + container scan) so it doesn't block main thread.
     * Replaced after each server session ends so a new pool exists if the JVM loads another world.
     */
    private static ExecutorService pathfinderExecutor = Executors.newFixedThreadPool(
        VoidClamConfig.effectiveAsyncThreadPoolSize(0));

    /** Each pathfinder worker thread may register a short description while its runnable runs (debug only). */
    private static final ConcurrentMap<Long, String> PATHFINDER_THREAD_TASK_LABELS = new ConcurrentHashMap<>();

    public static void pathfinderWorkerTaskBegin(String label) {
        PATHFINDER_THREAD_TASK_LABELS.put(Thread.currentThread().getId(), label);
    }

    public static void pathfinderWorkerTaskEnd() {
        PATHFINDER_THREAD_TASK_LABELS.remove(Thread.currentThread().getId());
    }

    public static List<String> pathfinderWorkerTaskLabelsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(PATHFINDER_THREAD_TASK_LABELS.values()));
    }

    /** Same pool used for async A* work and {@link BlockBfs.ExecutionMode#BACKGROUND} when {@code bfs_mode} is async. */
    public static Executor pathfindingExecutor() {
        return pathfinderExecutor;
    }

    /** OP debug: thread-pool stats for async pathfinding / background BFS (not per-clam). */
    public static List<String> debugPathfinderExecutorLines() {
        List<String> lines = new ArrayList<>(2);
        ExecutorService ex = pathfinderExecutor;
        if (ex instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            lines.add("pathfinderExecutor: poolSize=" + tpe.getPoolSize()
                + " active=" + tpe.getActiveCount()
                + " queue=" + tpe.getQueue().size()
                + " completed=" + tpe.getCompletedTaskCount()
                + " largestPool=" + tpe.getLargestPoolSize()
                + " shutdown=" + tpe.isShutdown());
        } else {
            lines.add("pathfinderExecutor: (not ThreadPoolExecutor) isShutdown=" + ex.isShutdown());
        }
        lines.add("  workerTaskLabels=" + pathfinderWorkerTaskLabelsSnapshot());
        lines.add("  omniAsyncPulseRunning=" + TendrilPulseManager.isOmniAsyncPulseRunning());
        return lines;
    }

    public static void configurePathfinderExecutorSize(int poolSize) {
        int n = Math.max(1, poolSize);
        if (pathfinderExecutor instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            tpe.setMaximumPoolSize(n);
            tpe.setCorePoolSize(n);
            return;
        }
        pathfinderExecutor.shutdown();
        try {
            if (!pathfinderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pathfinderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pathfinderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pathfinderExecutor = Executors.newFixedThreadPool(n);
    }

    /**
     * Run pathfinding off-thread (used by clamReach and container BFS). Rejects without queuing while a coordinated kill barrier
     * is in effect. Otherwise skips the task body if shutdown, kill victim, or unloaded center chunk applies; when skipped, runs
     * {@code onAbortedBeforeRun} if non-null.
     *
     * @param pathfindingClamId kill barrier and unload checks use this id
     */
    public static void submitPathfinding(
        ServerWorld world,
        int clamCenterX,
        int clamCenterZ,
        @Nullable UUID pathfindingClamId,
        Runnable onAbortedBeforeRun,
        Runnable task
    ) {
        if (VoidClamMod.isAsyncPathfindingKillBarrierInEffect() || VoidClamMod.isAsyncPathfindingShutdownRequested()) {
            if (onAbortedBeforeRun != null) {
                onAbortedBeforeRun.run();
            }
            return;
        }
        Module pauseModule = pathfindingClamId != null ? VoidClamMod.getModuleById(pathfindingClamId) : null;
        if (!VoidClamMod.isPathfindingAllowedYet(world, pauseModule)) {
            if (onAbortedBeforeRun != null) {
                onAbortedBeforeRun.run();
            }
            return;
        }
        if (VoidClamConfig.get().astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ, pathfindingClamId)) {
                if (onAbortedBeforeRun != null) {
                    onAbortedBeforeRun.run();
                }
                return;
            }
            task.run();
            return;
        }
        pathfinderExecutor.execute(() -> {
            String label = pathfindingClamId != null
                ? "submitPathfinding clamReach clamId=" + pathfindingClamId
                : "submitPathfinding (no clamId)";
            pathfinderWorkerTaskBegin(label);
            try {
                if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ, pathfindingClamId)) {
                    if (onAbortedBeforeRun != null) {
                        onAbortedBeforeRun.run();
                    }
                    return;
                }
                task.run();
            } finally {
                pathfinderWorkerTaskEnd();
            }
        });
    }

    /**
     * Waits for queued/running pathfinding tasks after {@link VoidClamMod#onAsyncPathfindingSessionStop()} sets the shutdown flag,
     * then replaces the executor. Called from the server lifecycle only.
     */
    static void shutdownPathfinderExecutorForSessionEnd() {
        pathfinderExecutor.shutdown();
        try {
            if (!pathfinderExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                pathfinderExecutor.shutdownNow();
                if (!pathfinderExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (InterruptedException e) {
            pathfinderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pathfinderExecutor = Executors.newFixedThreadPool(VoidClamConfig.get().effectiveAsyncThreadPoolSize());
    }

    /**
     * During {@link VoidClamMod#clamKillBlocking}: drain workers (victim slot aborts cooperatively), then replace the pool.
     * Must not run on the server thread if workers may {@code server.execute} — use a helper thread.
     */
    static void shutdownPathfinderExecutorAfterKillDrain() {
        shutdownPathfinderExecutorForSessionEnd();
    }

    public static void buildStub(ServerWorld world, int x, int y, int z) {
        for (int ix = x - 1; ix <= x + 1; ix++) {
            for (int iy = y - 2; iy <= y + 2; iy++) {
                for (int iz = z - 1; iz <= z + 1; iz++) {
                    boolean black = !(((iy != y + 2 && iy != y - 2) || iz != z || ix != x)
                        && ((iy != y - 1 && iy != y + 1) || ((iz != z || (ix != x + 1 && ix != x - 1))
                        && (ix != x || (iz != z + 1 && iz != z - 1)))));
                    boolean red = (ix == x && iz == z && iy < y + 2 && iy > y - 2);
                    final int ux = ix, uy = iy, uz = iz;
                    if (red || black) {
                        long delay = Math.abs(iy - y) * 20L;
                        VoidClamMod.scheduleDelayed(world, delay, () -> {
                            if (ux == x && uy == y && uz == z) return;
                            world.setBlockState(new BlockPos(ux, uy, uz), Blocks.NETHER_WART_BLOCK.getDefaultState());
                            VoidClamSfx.playBlockSound(world, null, ux + 0.5, uy + 0.5, uz + 0.5,
                                SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
                        });
                    }
                    if (black) {
                        long delay = Math.abs(iy - y) * 30L;
                        VoidClamMod.scheduleDelayed(world, delay, () -> {
                            if (ux == x && uy == y && uz == z) return;
                            world.setBlockState(new BlockPos(ux, uy, uz), Blocks.OBSIDIAN.getDefaultState());
                            VoidClamSfx.playBlockSound(world, null, ux + 0.5, uy + 0.5, uz + 0.5,
                                SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
                        });
                    }
                }
            }
        }
    }

    /** True if (px, py, pz) is inside the clam octahedron interior (relative to module center at 0,0,0). */
    public static boolean isInsideOctahedronInterior(double px, double py, double pz, int tsize) {
        if (py < -tsize / 2 + 1 || py > tsize - 2) return false;
        double horiz = Math.abs(px) + Math.abs(pz);
        if (py >= 0) return horiz <= (tsize - 2) - py;
        return horiz <= (tsize - 2) + py;
    }

    /**
     * Replaces obsidian with {@code mat} where the block lies inside {@link #isInsideOctahedronInterior}
     * for shell size {@code octahedronShellSize} (same metric as clam volume / player-interior checks).
     */
    public static void replaceObsidianInsideOctahedronInterior(
        ServerWorld world,
        int cx, int cy, int cz,
        int octahedronShellSize,
        net.minecraft.block.Block mat
    ) {
        int t = Math.max(1, octahedronShellSize);
        int yMin = cy + (-t / 2 + 1);
        int yMax = cy + (t - 2);
        int horizBound = Math.max(0, t - 2);
        BlockState replaceState = mat.getDefaultState();
        for (int iy = yMin; iy <= yMax; iy++) {
            for (int ix = cx - horizBound; ix <= cx + horizBound; ix++) {
                for (int iz = cz - horizBound; iz <= cz + horizBound; iz++) {
                    if (!isInsideOctahedronInterior(ix - cx, iy - cy, iz - cz, t)) continue;
                    BlockPos pos = new BlockPos(ix, iy, iz);
                    if (world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                        world.setBlockState(pos, replaceState);
                    }
                }
            }
        }
    }

    /** True if the player's bounding box intersects the module's octahedron interior. */
    public static boolean isPlayerInsideOctahedron(ServerPlayerEntity player, Module m) {
        Box box = player.getBoundingBox();
        int mx = m.x, my = m.y, mz = m.z;
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    if (isInsideOctahedronInterior(x - mx, y - my, z - mz, m.currentSize)) return true;
                }
            }
        }
        return false;
    }

    public static void buildShell(ServerWorld world, int x, int y, int z, int tsize, net.minecraft.block.Block mat) {
        net.minecraft.block.BlockState state = mat.getDefaultState();
        for (int iy = y + tsize - 1; iy >= y + 1; iy--) {
            int k = Math.abs(iy - y);
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z + tsize - 1 - k - Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z + tsize - 1 - k - Math.abs(x - j);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
        }
        for (int iy = y - tsize / 2; iy <= y - 1; iy++) {
            int k = Math.abs(iy - y);
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z + tsize - 1 - k - Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z + tsize - 1 - k - Math.abs(x - j);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
        }
    }

    public static void clamReSize(ServerWorld world, UUID clamId, int tsize) {
        Module m = VoidClamMod.getModuleById(clamId);
        if (m == null) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        VoidClamMod.prepareClamForResizeShell(m);
        net.minecraft.block.Block mat = Blocks.NETHER_WART_BLOCK;
        int csize = m.currentSize;
        int x = m.x, y = m.y, z = m.z;
        int timer = 0;

        for (int i = 1; i <= tsize; i += 1) {
            final int iFinal = i;
            VoidClamMod.scheduleDelayed(world, timer * 10L, () -> {
                buildShell(world, x, y, z, iFinal, Blocks.NETHER_WART_BLOCK);
                VoidClamSfx.playBlockSound(world, null, x + 0.5, y + 0.5, z + 0.5,
                    SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
            });
            timer++;
        }

        // Obsidian from the prior shell: convert to wart inside the octahedron for the target shell, or (on repair) one size larger than csize.
        int octObsidianReplace = Math.max(tsize, csize + 1);
        replaceObsidianInsideOctahedronInterior(world, x, y, z, octObsidianReplace, mat);

        for (int i = y + csize; i <= y + tsize - 1; i++) {
            if (i != y)
                world.setBlockState(new BlockPos(x, i, z), mat.getDefaultState());
        }
        for (int i = y - csize; i >= y - tsize + 1; i--) {
            if (i != y)
                world.setBlockState(new BlockPos(x, i, z), mat.getDefaultState());
        }

        long obsidianAtTick = world.getTime() + (long) timer * 20L;
        VoidClamMod.scheduleDelayed(world, timer * 20L, () -> buildShell(world, x, y, z, tsize, Blocks.OBSIDIAN));
        m.pathfindingResumeWorldTime = obsidianAtTick + VoidClamMod.POST_RESIZE_OBSIDIAN_PATHFINDING_DELAY_TICKS;

        m.currentSize = tsize;
        VoidClamMod.placeHeartBlockForModule(world, new BlockPos(x, y, z), m);
        VoidClamMod.maybeSaveLegacyModulesSiva(world.getServer());
        VoidClamMod.startLightCacheRebuild(m);

        int ts = tsize - 2;
        for (int ix = x - ts + 1; ix <= x; ix++) {
            int iz = z - ts + 1 + Math.abs(ix - x);
            if (ix != x || iz != z)
                world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
        for (int ix = x - ts + 1; ix <= x; ix++) {
            int iz = z + ts - 1 - Math.abs(ix - x);
            if (ix != x || iz != z)
                world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
        for (int ix = x + ts - 1; ix >= x; ix--) {
            int iz = z - ts + 1 + Math.abs(ix - x);
            if (ix != x || iz != z)
                world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
        for (int ix = x + ts - 1; ix >= x; ix--) {
            int iz = z + ts - 1 - Math.abs(x - ix);
            if (ix != x || iz != z)
                world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
    }

    /** Start light/ore search for module. Scans box off-thread, pathfinds to closest target. */
    public static void clamReach(ServerWorld world, UUID clamId) {
        Module m = VoidClamMod.getModuleById(clamId);
        if (m == null || m.status != 1) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        m.ensureClamId();
        if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, m.x, m.z, m.clamId)) return;
        if (!VoidClamMod.isPathfindingAllowedYet(world, m)) return;
        if (m.busyFlagMainCycle != 0) return;
        m.busyFlagMainCycle = 1;

        submitPathfinding(world, m.x, m.z, m.clamId, () -> VoidClamMod.releasePathfindingMainCycle(m), () -> {
            try {
                if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, m.x, m.z, m.clamId)) {
                    VoidClamMod.releasePathfindingMainCycle(m);
                    return;
                }
                if (!VoidClamMod.isPathfindingAllowedYet(world, m)) {
                    VoidClamMod.releasePathfindingMainCycle(m);
                    return;
                }
                int x = m.x, y = m.y, z = m.z, cSize = m.currentSize;
                BlockPos modPos = new BlockPos(x, y, z);
                BlockPos closestLight = null;
                double closestLightDist = Double.MAX_VALUE;
                BlockPos closestOre = null;
                double closestOreDist = Double.MAX_VALUE;

                if (m.seekLights || m.seekOres) {
                    if (m.seekLights) {
                        for (long packed : m.lightsCache) {
                            if (m.lightsBlackList.contains(packed)) {
                                continue;
                            }
                            BlockPos pos = BlockPos.fromLong(packed);
                            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                            net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
                            if (!VoidClamMod.isLight(block)) continue;
                            double dist = modPos.getSquaredDistance(pos);
                            if (dist < closestLightDist) {
                                closestLightDist = dist;
                                closestLight = pos.toImmutable();
                            }
                        }
                    }
                    if (m.seekOres) {
                        int scanStep = 0;
                        outerScan:
                        for (int iy = y - 4 * cSize; iy <= y + 4 * cSize; iy++) {
                            for (int ix = x - 4 * cSize; ix <= x + 4 * cSize; ix++) {
                                for (int iz = z - 4 * cSize; iz <= z + 4 * cSize; iz++) {
                                    if ((scanStep++ & 0xFFF) == 0 && VoidClamMod.shouldAbortAsyncPathfindingWork(world, x, z, m.clamId)) {
                                        break outerScan;
                                    }
                                    BlockPos pos = new BlockPos(ix, iy, iz);
                                    net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
                                    double dist = modPos.getSquaredDistance(pos);
                                    if (VoidClamMod.isOre(block) && !m.oresBlackList.contains(pos) && dist < closestOreDist) {
                                        closestOreDist = dist;
                                        closestOre = pos;
                                    }
                                }
                            }
                        }
                    }
                    if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, x, z, m.clamId)) {
                        VoidClamMod.releasePathfindingMainCycle(m);
                        return;
                    }
                }

                BlockPos closest = null;
                if (closestLight != null && (closestOre == null || closestLightDist <= closestOreDist)) {
                    closest = closestLight;
                } else if (closestOre != null) {
                    closest = closestOre;
                    m.oresBlackList.add(closest.toImmutable());
                }

                if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, x, z, m.clamId)) {
                    if (closest != null && closestOre != null && closest.equals(closestOre)) {
                        m.oresBlackList.remove(closest.toImmutable());
                    }
                    VoidClamMod.releasePathfindingMainCycle(m);
                    return;
                }
                if (closest != null) {
                    if (closestLight != null && closest.equals(closestLight)) {
                        long goalPacked = closest.asLong();
                        m.lightsBlackList.add(goalPacked);
                        m.lightPathGoalPacked = goalPacked;
                    } else {
                        m.lightPathGoalPacked = null;
                    }
                    Pathfinder.calculatePath(world, m.clamId, x, y, z, closest.getX(), closest.getY(), closest.getZ());
                } else {
                    VoidClamMod.releasePathfindingMainCycle(m);
                }
            } catch (Throwable t) {
                VoidClamMod.releasePathfindingMainCycle(m);
                throw t;
            }
        });
    }
}
