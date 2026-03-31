package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Block building, shell/stub construction, resize/repair, and reach (pathfind trigger). */
public final class CommandToolbox {
    /**
     * Shared executor for pathfinding (reach + container scan) so it doesn't block main thread.
     * Replaced after each server session ends so a new pool exists if the JVM loads another world.
     */
    private static ExecutorService pathfindingExecutor = Executors.newFixedThreadPool(
        VoidClamConfig.effectiveAsyncThreadPoolSize(0));

    /** Same pool used for async A* work and {@link BlockBfs.ExecutionMode#BACKGROUND} when {@code bfs_mode} is async. */
    public static Executor pathfindingExecutor() {
        return pathfindingExecutor;
    }

    /** One-line status for {@code /voidclam status} (pool + omni pulse flag). */
    public static List<String> pathfindingExecutorStatusLines() {
        List<String> lines = new ArrayList<>(3);
        ExecutorService ex = pathfindingExecutor;
        if (ex instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) ex;
            lines.add("pathfindingExecutor: poolSize=" + tpe.getPoolSize()
                + " active=" + tpe.getActiveCount()
                + " queue=" + tpe.getQueue().size()
                + " completed=" + tpe.getCompletedTaskCount()
                + " largestPool=" + tpe.getLargestPoolSize()
                + " shutdown=" + tpe.isShutdown());
        } else {
            lines.add("pathfindingExecutor: (not ThreadPoolExecutor) isShutdown=" + ex.isShutdown());
        }
        lines.add("  omniAsyncPulseRunning=" + TendrilPulseManager.isOmniAsyncPulseRunning());
        return lines;
    }

    public static void configurePathfinderExecutorSize(int poolSize) {
        int n = Math.max(1, poolSize);
        if (pathfindingExecutor instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) pathfindingExecutor;
            tpe.setMaximumPoolSize(n);
            tpe.setCorePoolSize(n);
            return;
        }
        pathfindingExecutor.shutdown();
        try {
            if (!pathfindingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pathfindingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pathfindingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pathfindingExecutor = Executors.newFixedThreadPool(n);
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
        Clam pauseClam = pathfindingClamId != null ? VoidClamMod.getClamById(pathfindingClamId) : null;
        if (!VoidClamMod.isPathfindingAllowedYet(world, pauseClam)) {
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
        pathfindingExecutor.execute(() -> {
            if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ, pathfindingClamId)) {
                if (onAbortedBeforeRun != null) {
                    onAbortedBeforeRun.run();
                }
                return;
            }
            task.run();
        });
    }

    /**
     * Waits for queued/running pathfinding tasks after {@link VoidClamMod#onAsyncPathfindingSessionStop()} sets the shutdown flag,
     * then replaces the executor. Called from the server lifecycle only.
     */
    static void shutdownPathfinderExecutorForSessionEnd() {
        pathfindingExecutor.shutdown();
        try {
            if (!pathfindingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                pathfindingExecutor.shutdownNow();
                if (!pathfindingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (InterruptedException e) {
            pathfindingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pathfindingExecutor = Executors.newFixedThreadPool(VoidClamConfig.get().effectiveAsyncThreadPoolSize());
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

    /** True if (px, py, pz) is inside the clam octahedron interior (relative to clam center at 0,0,0). */
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

    /**
     * Euclidean radius of the largest sphere centered on the clam heart that fits in the octahedron interior
     * ({@link #isInsideOctahedronInterior}) — i.e. inside the obsidian shell volume.
     */
    public static double octahedronInteriorInscribedSphereRadius(int tsize) {
        if (tsize < 1) {
            return 0.0;
        }
        double s = tsize - 2;
        if (s <= 0.0) {
            return 0.0;
        }
        double rSlant = s / Math.sqrt(3.0);
        double pyHi = tsize - 2;
        double pyLo = -tsize / 2 + 1;
        double rTop = pyHi > 0.0 ? pyHi : Double.POSITIVE_INFINITY;
        double rBot = pyLo < 0.0 ? -pyLo : Double.POSITIVE_INFINITY;
        return Math.min(rSlant, Math.min(rTop, rBot));
    }

    /** Defense intrusion radius: inscribed sphere above, minus one block (Euclidean). */
    public static double clamDefenseIntrusionSphereRadius(int tsize) {
        return Math.max(0.0, octahedronInteriorInscribedSphereRadius(tsize) - 1.0);
    }

    /**
     * Smallest sphere centered on the heart that contains all expected obsidian shell block centers (~circumscribed to the shell).
     * Used for thermal melt / weather particle sampling around clams.
     */
    public static double clamOctahedronCircumsphereRadius(int tsize) {
        int t = Math.max(1, tsize);
        double maxR = 0.0;
        int yMin = -t / 2 + 1;
        int yMax = t - 1;
        int horiz = Math.max(0, t - 1);
        for (int dy = yMin; dy <= yMax; dy++) {
            for (int dx = -horiz; dx <= horiz; dx++) {
                for (int dz = -horiz; dz <= horiz; dz++) {
                    if (!VoidClamMod.isExpectedObsidianShellBlock(dx, dy, dz, t)) continue;
                    double r = Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.5 * Math.sqrt(3.0);
                    if (r > maxR) maxR = r;
                }
            }
        }
        return Math.max(1.0, maxR);
    }

    /**
     * True if any corner of the player's bounding box lies inside the defense sphere: inscribed in the shell
     * octahedron interior, then shrunk by one block, centered on the heart block.
     */
    public static boolean isPlayerInsideOctahedron(ServerPlayerEntity player, Clam m) {
        double r = clamDefenseIntrusionSphereRadius(m.currentSize);
        if (r <= 0.0) {
            return false;
        }
        double cx = m.x + 0.5, cy = m.y + 0.5, cz = m.z + 0.5;
        double r2 = r * r;
        Box box = player.getBoundingBox();
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @param shrinkEquatorialWartByOneRing when {@code mat} is wart: if true (grow), heart-plane flesh uses
     *     {@code |dx|+|dz| <= tsize-3} to sit inside the shell ring; if false (repair), uses full oct slice
     *     {@code <= tsize-2}. Ignored for obsidian (no iy==y placement).
     */
    public static void buildShell(
        ServerWorld world,
        int x, int y, int z,
        int tsize,
        net.minecraft.block.Block mat,
        boolean shrinkEquatorialWartByOneRing
    ) {
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
        // iy == y: wart only; grow uses one inset ring so the plane matches shell step-in from iy=±1.
        if (mat == Blocks.NETHER_WART_BLOCK) {
            int maxHoriz = shrinkEquatorialWartByOneRing ? tsize - 3 : tsize - 2;
            int h = Math.max(0, maxHoriz);
            for (int dx = -h; dx <= h; dx++) {
                for (int dz = -h; dz <= h; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > maxHoriz) {
                        continue;
                    }
                    if (!isInsideOctahedronInterior(dx, 0, dz, tsize)) {
                        continue;
                    }
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    world.setBlockState(new BlockPos(x + dx, y, z + dz), state);
                }
            }
        }
    }

    /** Grow / obsidian shell: wart uses inset equatorial ring; obsidian ignores inset. */
    public static void buildShell(ServerWorld world, int x, int y, int z, int tsize, net.minecraft.block.Block mat) {
        buildShell(world, x, y, z, tsize, mat, true);
    }

    public static void clamReSize(ServerWorld world, UUID clamId, int tsize) {
        Clam m = VoidClamMod.getClamById(clamId);
        if (m == null) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        VoidClamMod.prepareClamForResizeShell(m);
        int csize = m.currentSize;
        int x = m.x, y = m.y, z = m.z;

        // Repair cycle (same-size resize): preserve existing obsidian shell; missing cells get staggered wart, then
        // obsidian only where material was spent; remaining missing cells get wart only (no obsidian).
        if (tsize <= csize) {
            int size = Math.max(1, csize);
            int yMin = -size / 2 + 1;
            int yMax = size - 1;
            int horiz = Math.max(0, size - 1);
            int initialMaterial = m.material;
            int obsidianPresent = 0;
            int shellMissing = 0;
            List<BlockPos> missingShell = new ArrayList<>();
            int timer = 0;
            // Rebuild flesh without touching the outer shell: stop at size-1 so existing obsidian shell stays intact.
            for (int i = 1; i <= Math.max(1, size - 1); i++) {
                final int iFinal = i;
                VoidClamMod.scheduleResizeShellDelayed(world, timer * 10L, () -> {
                    buildShell(world, x, y, z, iFinal, Blocks.NETHER_WART_BLOCK, false);
                    VoidClamSfx.playBlockSound(world, null, x + 0.5, y + 0.5, z + 0.5,
                        SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
                });
                timer++;
            }
            int fleshStaggerSteps = timer;
            for (int dy = yMin; dy <= yMax; dy++) {
                for (int dx = -horiz; dx <= horiz; dx++) {
                    for (int dz = -horiz; dz <= horiz; dz++) {
                        if (!VoidClamMod.isExpectedObsidianShellBlock(dx, dy, dz, size)) continue;
                        BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                        BlockState state = world.getBlockState(p);
                        if (state.isOf(Blocks.OBSIDIAN)) {
                            obsidianPresent++;
                            continue;
                        }
                        shellMissing++;
                        missingShell.add(p.toImmutable());
                    }
                }
            }
            int nMissing = missingShell.size();
            int fillable = Math.min(Math.max(0, m.material), nMissing);
            long firstShellPatchDelay = (long) fleshStaggerSteps * 10L;
            for (int s = 0; s < nMissing; s++) {
                final BlockPos p = missingShell.get(s).toImmutable();
                long patchDelay = firstShellPatchDelay + (long) s * 10L;
                if (s < fillable) {
                    VoidClamMod.scheduleResizeShellDelayed(world, patchDelay, () -> {
                        if (world.getBlockState(p).isOf(Blocks.OBSIDIAN)) {
                            return;
                        }
                        if (m.material <= 0) {
                            return;
                        }
                        world.setBlockState(p, Blocks.NETHER_WART_BLOCK.getDefaultState());
                        m.material--;
                        VoidClamSfx.playBlockSound(world, p, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
                        VoidClamMod.scheduleResizeShellDelayed(world, 10L, () -> {
                            if (world.getBlockState(p).isOf(Blocks.NETHER_WART_BLOCK)) {
                                world.setBlockState(p, Blocks.OBSIDIAN.getDefaultState());
                                VoidClamSfx.playBlockSound(world, p, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.85f, 0.9f);
                            }
                        });
                    });
                } else {
                    VoidClamMod.scheduleResizeShellDelayed(world, patchDelay, () -> {
                        if (world.getBlockState(p).isOf(Blocks.OBSIDIAN)) {
                            return;
                        }
                        world.setBlockState(p, Blocks.NETHER_WART_BLOCK.getDefaultState());
                        VoidClamSfx.playBlockSound(world, p, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
                    });
                }
            }
            int shellObserved = obsidianPresent + shellMissing;
            // Keep an explicit shell inventory snapshot while repairing.
            m.prioritizeRepairOreSeek = m.seekLights && shellObserved > 0 && shellMissing > initialMaterial;
            long postTicks = VoidClamMod.POST_RESIZE_OBSIDIAN_PATHFINDING_DELAY_TICKS;
            long resumeAnimOffset;
            if (nMissing == 0) {
                resumeAnimOffset = (long) Math.max(0, fleshStaggerSteps - 1) * 10L;
            } else {
                long lastShellWartTick = firstShellPatchDelay + (long) (nMissing - 1) * 10L;
                long lastPaidObsidianTick = fillable > 0
                    ? firstShellPatchDelay + (long) (fillable - 1) * 10L + 10L
                    : Long.MIN_VALUE;
                resumeAnimOffset = Math.max(lastShellWartTick, lastPaidObsidianTick);
            }
            m.pathfindingResumeWorldTime = world.getTime() + resumeAnimOffset + postTicks;
            m.currentSize = csize;
            VoidClamMod.placeHeartBlockForClam(world, new BlockPos(x, y, z), m);
            VoidClamMod.startSeekCachesRebuild(m);
            return;
        }

        net.minecraft.block.Block mat = Blocks.NETHER_WART_BLOCK;
        int timer = 0;

        for (int i = 1; i <= tsize; i += 1) {
            final int iFinal = i;
            VoidClamMod.scheduleResizeShellDelayed(world, timer * 10L, () -> {
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
        VoidClamMod.scheduleResizeShellDelayed(world, timer * 20L, () -> buildShell(world, x, y, z, tsize, Blocks.OBSIDIAN));
        m.pathfindingResumeWorldTime = obsidianAtTick + VoidClamMod.POST_RESIZE_OBSIDIAN_PATHFINDING_DELAY_TICKS;

        m.currentSize = tsize;
        VoidClamMod.placeHeartBlockForClam(world, new BlockPos(x, y, z), m);
        VoidClamMod.startSeekCachesRebuild(m);
    }

    /** Start light/ore search for a clam. Scans box off-thread, pathfinds to closest target. */
    public static void clamReach(ServerWorld world, UUID clamId) {
        Clam m = VoidClamMod.getClamById(clamId);
        if (m == null || !VoidClamMod.isSearingHeartThermallyActive(world, m)) return;
        if (!world.getRegistryKey().equals(m.dimensionWorldKey())) return;
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
                int x = m.x, y = m.y, z = m.z, cSize = Math.max(1, m.currentSize);
                BlockPos modPos = new BlockPos(x, y, z);
                BlockPos closestLight = null;
                double closestLightDist = Double.MAX_VALUE;
                BlockPos closestOre = null;
                double closestOreDist = Double.MAX_VALUE;
                VoidClamConfig cfg = VoidClamConfig.get();
                boolean oreHunger = m.seekLights && m.material < cfg.clam_material_seek_threshold;
                boolean oreRepairPriority = m.seekLights && m.prioritizeRepairOreSeek;
                boolean materialOreFlow = oreHunger || oreRepairPriority;
                boolean shouldSeekOre = m.seekOres || materialOreFlow;

                if (m.seekLights || shouldSeekOre) {
                    if (m.seekLights) {
                        if (VoidClamConfig.get().lightBlockCacheEnabled()) {
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
                        } else {
                            int e = VoidClamMod.lightSeekHalfExtent(m.currentSize);
                            int scanStep = 0;
                            outerLights:
                            for (int iy = y - e; iy <= y + e; iy++) {
                                for (int ix = x - e; ix <= x + e; ix++) {
                                    for (int iz = z - e; iz <= z + e; iz++) {
                                        if ((scanStep++ & 0xFFF) == 0
                                            && VoidClamMod.shouldAbortAsyncPathfindingWork(world, x, z, m.clamId)) {
                                            break outerLights;
                                        }
                                        if (!world.isChunkLoaded(ix >> 4, iz >> 4)) {
                                            continue;
                                        }
                                        BlockPos pos = new BlockPos(ix, iy, iz);
                                        long packed = pos.asLong();
                                        if (m.lightsBlackList.contains(packed)) {
                                            continue;
                                        }
                                        net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
                                        if (!VoidClamMod.isLight(block)) continue;
                                        double dist = modPos.getSquaredDistance(pos);
                                        if (dist < closestLightDist) {
                                            closestLightDist = dist;
                                            closestLight = pos.toImmutable();
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (shouldSeekOre) {
                        boolean useOreCache = VoidClamConfig.get().oreBlockCacheEnabled()
                            && !(materialOreFlow && m.oresCache.isEmpty());
                        if (useOreCache) {
                            for (long packed : m.oresCache) {
                                if (m.oresBlackList.contains(packed)) {
                                    continue;
                                }
                                BlockPos pos = BlockPos.fromLong(packed);
                                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                                net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
                                if (!VoidClamMod.isOre(block)) continue;
                                double dist = modPos.getSquaredDistance(pos);
                                if (dist < closestOreDist) {
                                    closestOreDist = dist;
                                    closestOre = pos.toImmutable();
                                }
                            }
                        } else {
                            int e = VoidClamMod.lightSeekHalfExtent(m.currentSize);
                            int scanStep = 0;
                            outerScan:
                            for (int iy = y - e; iy <= y + e; iy++) {
                                for (int ix = x - e; ix <= x + e; ix++) {
                                    for (int iz = z - e; iz <= z + e; iz++) {
                                        if ((scanStep++ & 0xFFF) == 0
                                            && VoidClamMod.shouldAbortAsyncPathfindingWork(world, x, z, m.clamId)) {
                                            break outerScan;
                                        }
                                        if (!world.isChunkLoaded(ix >> 4, iz >> 4)) {
                                            continue;
                                        }
                                        BlockPos pos = new BlockPos(ix, iy, iz);
                                        long packed = pos.asLong();
                                        if (m.oresBlackList.contains(packed)) {
                                            continue;
                                        }
                                        net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
                                        double dist = modPos.getSquaredDistance(pos);
                                        if (VoidClamMod.isOre(block) && dist < closestOreDist) {
                                            closestOreDist = dist;
                                            closestOre = pos.toImmutable();
                                        }
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
                if (materialOreFlow && closestOre != null) {
                    closest = closestOre;
                } else if (materialOreFlow && closestLight != null) {
                    // Material refill mode: if no ore candidate is available, fall back to regular light seeking.
                    closest = closestLight;
                } else if (closestLight != null && (closestOre == null || closestLightDist <= closestOreDist)) {
                    closest = closestLight;
                } else if (closestOre != null) {
                    closest = closestOre;
                }

                if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, x, z, m.clamId)) {
                    VoidClamMod.releasePathfindingMainCycle(m);
                    return;
                }
                if (closest != null) {
                    if (closestLight != null && closest.equals(closestLight)) {
                        long goalPacked = closest.asLong();
                        m.lightsBlackList.add(goalPacked);
                        m.lightPathGoalPacked = goalPacked;
                        m.orePathGoalPacked = null;
                        m.orePathForMaterialHunger = false;
                    } else if (closestOre != null && closest.equals(closestOre)) {
                        long goalPacked = closest.asLong();
                        m.oresBlackList.add(goalPacked);
                        m.orePathGoalPacked = goalPacked;
                        m.lightPathGoalPacked = null;
                        m.orePathForMaterialHunger = materialOreFlow;
                    } else {
                        m.lightPathGoalPacked = null;
                        m.orePathGoalPacked = null;
                        m.orePathForMaterialHunger = false;
                    }
                    Pathfinder.calculatePath(world, m.clamId, x, y, z, closest.getX(), closest.getY(), closest.getZ());
                } else {
                    m.orePathForMaterialHunger = false;
                    VoidClamMod.releasePathfindingMainCycle(m);
                }
            } catch (Throwable t) {
                VoidClamMod.releasePathfindingMainCycle(m);
                throw t;
            }
        });
    }

    /**
     * Writes the searing heart block entity’s full NBT (including identifying fields, same as chunk disk form) to
     * {@code <runDir>/voidclam-nbt-dumps/<clamId>.nbt} using gzip-compressed NBT.
     */
    public static Path writeClamHeartNbtDumpFile(MinecraftServer server, Clam m) throws IOException {
        if (server == null || m == null) {
            throw new IOException("server or clam missing");
        }
        m.ensureClamId();
        ServerWorld world = VoidClamMod.getWorldForClam(server, m);
        if (world == null) {
            throw new IOException("dimension for this voidclam is not loaded");
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            throw new IOException("heart chunk is not loaded");
        }
        BlockPos heart = new BlockPos(m.x, m.y, m.z);
        if (!world.getBlockState(heart).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            throw new IOException(
                "no searing heart block at " + heart.getX() + " " + heart.getY() + " " + heart.getZ());
        }
        BlockEntity be = world.getBlockEntity(heart);
        if (!(be instanceof AbstractFurnaceBlockEntity)) {
            throw new IOException("heart block entity missing or not a furnace");
        }
        NbtCompound tag = new NbtCompound();
        be.writeNbt(tag);
        Path dir = server.getRunDirectory().toPath().resolve("voidclam-nbt-dumps");
        Files.createDirectories(dir);
        Path file = dir.resolve(m.clamId.toString() + ".nbt");
        NbtIo.writeCompressed(tag, file.toFile());
        return file;
    }
}
