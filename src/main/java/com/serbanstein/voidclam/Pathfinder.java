package com.serbanstein.voidclam;

import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Pathfinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("voidclam/Pathfinder");
    /** Main-thread A* jobs when {@link VoidClamConfig.AstarMode#SYNC_BATCHED} is active. */
    private static final ConcurrentLinkedQueue<AStarJob> syncAStarJobs = new ConcurrentLinkedQueue<>();
    private static final Deque<AStarJob> syncAStarFairness = new ArrayDeque<>();

    public static void clearSyncPathJobsForSessionEnd() {
        syncAStarJobs.clear();
        syncAStarFairness.clear();
    }

    /** OP debug: sync-batched A* rows for {@code clamId} (empty list if none). */
    public static List<String> debugSyncAStarJobsForClam(UUID clamId) {
        List<String> out = new ArrayList<>();
        if (clamId == null) {
            return out;
        }
        int fairnessMatches = 0;
        for (AStarJob j : syncAStarFairness) {
            if (clamId.equals(j.clamId)) {
                fairnessMatches++;
            }
        }
        List<String> jobLines = new ArrayList<>();
        for (AStarJob j : syncAStarJobs) {
            if (!clamId.equals(j.clamId)) {
                continue;
            }
            String prepassState = "null";
            if (j.prepassBfs != null) {
                prepassState = j.prepassBfs.isFinished() ? "finished" : "running";
            }
            int openSz = j.open != null ? j.open.size() : -1;
            int closedSz = j.closed != null ? j.closed.size() : -1;
            jobLines.add(String.format(
                "  job phase=%s %d,%d,%d -> %d,%d,%d prepass=%s open=%d closed=%d totalExp=%d astarIter=%s",
                j.phase,
                j.sx, j.sy, j.sz,
                j.gx, j.gy, j.gz,
                prepassState,
                openSz,
                closedSz,
                j.totalSyncExpansions,
                j.astarIterations));
        }
        out.add(String.format(
            "sync A*: astar_mode=%s queuedJobs=%d fairnessDequeEntriesForClam=%d",
            VoidClamConfig.get().astarModeEnum(),
            jobLines.size(),
            fairnessMatches));
        if (jobLines.isEmpty()) {
            out.add("  (no entries in syncAStarJobs for this clam)");
        } else {
            out.addAll(jobLines);
        }
        return out;
    }

    /** Remove queued sync A* jobs for one clam (e.g. before {@link CommandToolbox#clamReSize}). */
    public static void clearSyncAStarJobsForClam(@org.jetbrains.annotations.Nullable java.util.UUID clamId) {
        if (clamId == null) {
            return;
        }
        syncAStarJobs.removeIf(job -> clamId.equals(job.clamId));
        syncAStarFairness.removeIf(job -> clamId.equals(job.clamId));
    }

    /**
     * Enqueue a path job for sync batched mode (server thread). Prepass and A* steps run across ticks via {@link #tickSyncAStarJobs}.
     */
    public static void enqueueSyncAStarJob(ServerWorld world, UUID clamId, int sx, int sy, int sz, int gx, int gy, int gz) {
        Module m = VoidClamMod.getModuleById(clamId);
        if (m != null) {
            m.ensureClamId();
        }
        if (clamId == null) return;
        syncAStarJobs.add(new AStarJob(world, clamId, sx, sy, sz, gx, gy, gz));
    }

    /**
     * Spread {@code globalStepBudget} expansions across queued sync jobs (round-robin).
     */
    public static void tickSyncAStarJobs(int globalStepBudget) {
        if (globalStepBudget <= 0 || syncAStarJobs.isEmpty()) return;
        if (syncAStarFairness.isEmpty()) {
            syncAStarFairness.addAll(syncAStarJobs);
        }
        // Generous cap so many “paused” jobs (resize cooldown) in the round-robin do not burn the budget before an eligible job runs.
        int safety = Math.max(4096, syncAStarFairness.size() * 4 + syncAStarJobs.size() * 4 + 64);
        while (globalStepBudget > 0 && safety-- > 0) {
            AStarJob job = syncAStarFairness.pollFirst();
            if (job == null) {
                if (syncAStarJobs.isEmpty()) break;
                syncAStarFairness.addAll(syncAStarJobs);
                continue;
            }
            if (!syncAStarJobs.contains(job)) {
                continue;
            }
            Module pauseMod = VoidClamMod.getModuleById(job.clamId);
            if (!VoidClamMod.isPathfindingAllowedYet(job.worldRef, pauseMod)) {
                syncAStarFairness.addLast(job);
                continue;
            }
            int used = job.step(job.worldRef, globalStepBudget);
            globalStepBudget -= used;
            if (job.isFinished()) {
                syncAStarJobs.remove(job);
            } else {
                syncAStarFairness.addLast(job);
            }
        }
    }

    private enum AStarPhase {
        PREPASS,
        ASTAR,
        DONE
    }

    private static final class AStarJob {
        final ServerWorld worldRef;
        final UUID clamId;
        final int sx, sy, sz, gx, gy, gz;
        AStarPhase phase = AStarPhase.PREPASS;
        BlockBfs prepassBfs;
        List<Node> open;
        List<Node> closed;
        long astarIterations;
        /** Prepass {@link BlockBfs} node steps plus A* {@link #expandOneAStarIteration} calls for this job (cross-tick). */
        long totalSyncExpansions;
        /** Refreshed at the start of each {@link #step}; prepass policy reads this field each expansion (not a stale closure). */
        PathfindChunkCache activePathChunkCache;

        AStarJob(ServerWorld world, UUID clamId, int sx, int sy, int sz, int gx, int gy, int gz) {
            this.worldRef = world;
            this.clamId = clamId;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
        }

        boolean isFinished() {
            return phase == AStarPhase.DONE;
        }

        /** @return prepass or A* expansions consumed */
        int step(ServerWorld world, int budget) {
            Module modForFlag = VoidClamMod.getModuleById(clamId);
            if (modForFlag == null) {
                finishFail(null);
                return 0;
            }
            modForFlag.ensureClamId();
            if (world != worldRef) {
                finishFail(modForFlag);
                return 0;
            }
            long cap = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
            if (totalSyncExpansions >= cap) {
                finishFail(modForFlag);
                return 0;
            }
            activePathChunkCache = PathfindChunkCache.buildColumnSnapshot(world, modForFlag);
            if (phase == AStarPhase.PREPASS) {
                return stepPrepass(world, modForFlag, budget);
            }
            if (phase == AStarPhase.ASTAR) {
                return stepAstar(world, modForFlag, budget);
            }
            return 0;
        }

        private int stepPrepass(ServerWorld world, Module modForFlag, int budget) {
            if (prepassBfs == null) {
                if (sx == gx && sy == gy && sz == gz) {
                    beginAstarPhase();
                    return 1;
                }
                long goalLong = BlockPos.asLong(gx, gy, gz);
                long startLong = BlockPos.asLong(sx, sy, sz);
                BlockBfs.EdgePolicy prepassPolicy = (w, fromLong, toLong, fromDist) -> {
                    BlockPos nextPos = BlockPos.fromLong(toLong);
                    return inPathfindSearchBounds(modForFlag, nextPos.getX(), nextPos.getY(), nextPos.getZ())
                        && !isPathfindCellImpassable(w, activePathChunkCache, nextPos);
                };
                prepassBfs = BlockBfs.start(
                    world,
                    startLong,
                    prepassPolicy,
                    Integer.MAX_VALUE,
                    BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED,
                    null,
                    null,
                    asyncPathfindingAbortChecker(world, modForFlag.x, modForFlag.z, clamId),
                    goalLong
                );
            }
            int used = 0;
            long expandCap = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
            while (used < budget && !prepassBfs.isFinished()) {
                if (totalSyncExpansions >= expandCap) {
                    finishFail(modForFlag);
                    return used;
                }
                prepassBfs.step(1);
                totalSyncExpansions++;
                used++;
            }
            if (!prepassBfs.isFinished()) {
                return used;
            }
            boolean reachable = prepassBfs.isEarlyGoalNeighborHit();
            prepassBfs = null;
            if (!reachable) {
                VoidClamMod.removeLightGoalFromCacheIfPrepassUnreachable(world, clamId, gx, gy, gz);
                finishFail(modForFlag);
                return used;
            }
            beginAstarPhase();
            return used;
        }

        private void beginAstarPhase() {
            open = new ArrayList<>();
            closed = new ArrayList<>();
            Node firstNode = new Node(sx, sy, sz, null, clamId);
            firstNode.g = 0;
            firstNode.h = manhattanH(sx, sy, sz, gx, gy, gz);
            firstNode.f = firstNode.g + firstNode.h;
            open.add(firstNode);
            astarIterations = 0;
            phase = AStarPhase.ASTAR;
        }

        private int stepAstar(ServerWorld world, Module modForFlag, int budget) {
            int used = 0;
            long expandCap = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
            while (used < budget) {
                if (totalSyncExpansions >= expandCap) {
                    finishFail(modForFlag);
                    return used;
                }
                AStarExpandResult r = expandOneAStarIteration(
                    world, clamId, gx, gy, gz, modForFlag, open, closed, astarIterations, activePathChunkCache);
                astarIterations++;
                totalSyncExpansions++;
                if (r == AStarExpandResult.ABORT) {
                    finishFail(modForFlag);
                    return used + 1;
                }
                if (r == AStarExpandResult.SUCCESS) {
                    phase = AStarPhase.DONE;
                    return used + 1;
                }
                if (r == AStarExpandResult.NO_PATH) {
                    finishFail(modForFlag);
                    return used + 1;
                }
                used++;
            }
            return used;
        }

        private void finishFail(Module modForFlag) {
            phase = AStarPhase.DONE;
            if (modForFlag != null) {
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
            }
        }
    }

    private enum AStarExpandResult {
        CONTINUE,
        SUCCESS,
        NO_PATH,
        ABORT
    }

    /**
     * One A* iteration: pop best open node, expand neighbors, maybe enqueue goal to {@link VoidClamMod#enqueueTarget}.
     * {@code astarIterations} is the count before this iteration (for cooperative abort every 1024 steps).
     */
    private static AStarExpandResult expandOneAStarIteration(
        ServerWorld world,
        UUID pathClamId,
        int gx, int gy, int gz,
        Module modForFlag,
        List<Node> open,
        List<Node> closed,
        long astarIterationsBeforeStep,
        PathfindChunkCache pathChunkCache
    ) {
        UUID effectiveClamId = pathClamId != null ? pathClamId : modForFlag.clamId;
        if ((astarIterationsBeforeStep & 0x3FF) == 0 && VoidClamMod.shouldAbortAsyncPathfindingWork(world, modForFlag.x, modForFlag.z, effectiveClamId)) {
            return AStarExpandResult.ABORT;
        }
        if (open.isEmpty()) {
            return AStarExpandResult.NO_PATH;
        }
        Node nextCheapestNode = leastF(open);
        open.remove(nextCheapestNode);

        for (Cursor c : xc) {
            int nx = nextCheapestNode.x + c.x;
            int ny = nextCheapestNode.y + c.y;
            int nz = nextCheapestNode.z + c.z;

            BlockPos nextPos = new BlockPos(nx, ny, nz);
            BlockState bl = pathChunkCache.getBlockState(nextPos);
            double cst;
            if (VoidClamCoreBlocks.isWartOrCore(bl)) {
                cst = 0;
            } else if (bl.getBlock() instanceof BlockEntityProvider) {
                cst = 2500;
            } else {
                float hard = getHardness(world, nextPos, bl);
                // Negative hardness: bedrock, barrier, etc. Impervious to break; do not path or apply as diggable.
                if (hard > 5 || hard < 0) {
                    cst = 2500;
                } else if (bl.isOf(Blocks.WATER) || (isAirLike(bl, world, pathChunkCache, nextPos) && isSolid(world, pathChunkCache, nextPos.down()))) {
                    cst = 1;
                } else if (isAirLike(bl, world, pathChunkCache, nextPos)) {
                    int b = countAdjacentNotWaterAirWart(world, pathChunkCache, nextPos);
                    cst = 6 - b;
                } else {
                    cst = 10 + getBlastResistance(bl);
                }
            }

            if (cst == 2500) {
                continue;
            }

            Module pathMod = modForFlag;
            // Prepass can mark the goal reachable via a 6-neighbor adjacent to the goal even when the goal cell lies
            // just outside the pathfinding AABB (common for a torch one block past the Y cap). A* must allow that final
            // step; otherwise we exhaust the open set after a long search (async "stuck") or return NO_PATH.
            boolean isGoalCell = nx == gx && ny == gy && nz == gz;
            if (!isGoalCell && !isWithinPathfindingRange(nx, ny, nz, pathMod.x, pathMod.y, pathMod.z, pathMod.currentSize)) {
                continue;
            }

            double tentativeG = nextCheapestNode.g + cst;
            Node probe = new Node(nx, ny, nz, nextCheapestNode, effectiveClamId);
            Node inOpen = nodeExists(open, probe);
            Node inClosed = nodeExists(closed, probe);
            if (inOpen != null && tentativeG >= inOpen.g) {
                continue;
            }
            if (inClosed != null && tentativeG >= inClosed.g) {
                continue;
            }

            if (inOpen != null) {
                open.remove(inOpen);
            }
            if (inClosed != null) {
                closed.remove(inClosed);
            }

            Node nextNode = new Node(nx, ny, nz, nextCheapestNode, effectiveClamId);
            nextNode.g = tentativeG;
            nextNode.h = manhattanH(nx, ny, nz, gx, gy, gz);
            nextNode.f = nextNode.g + nextNode.h;

            if (nx == gx && ny == gy && nz == gz) {
                if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, modForFlag.x, modForFlag.z, effectiveClamId)) {
                    return AStarExpandResult.ABORT;
                }
                if (modForFlag.busyFlagMainCycle == 0) {
                    return AStarExpandResult.ABORT;
                }
                VoidClamMod.enqueueTarget(nextNode);
                return AStarExpandResult.SUCCESS;
            }

            open.add(nextNode);
        }
        closed.add(nextCheapestNode);
        return AStarExpandResult.CONTINUE;
    }

    private static BlockBfs.AbortChecker asyncPathfindingAbortChecker(
        ServerWorld world,
        int clamCenterX,
        int clamCenterZ,
        @org.jetbrains.annotations.Nullable UUID pathfindingClamId
    ) {
        return (w, posLong, distanceFromStart) ->
            VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ, pathfindingClamId);
    }

    static final List<Cursor> xc = new ArrayList<>();
    static final List<Cursor> yc = new ArrayList<>();
    private static final Map<net.minecraft.block.Block, List<ItemStack>> FORTUNE3_DROPS = new HashMap<>();

    private static void putFortune3(net.minecraft.block.Block block, net.minecraft.item.Item item, int count) {
        FORTUNE3_DROPS.put(block, Collections.singletonList(new ItemStack(item, count)));
    }

    static List<ItemStack> getFortune3Drops(net.minecraft.block.Block block) {
        List<ItemStack> list = FORTUNE3_DROPS.get(block);
        return list != null ? list.stream().map(ItemStack::copy).toList() : new ArrayList<>();
    }

    static {
        xc.add(new Cursor(1, 0, 0));
        xc.add(new Cursor(-1, 0, 0));
        xc.add(new Cursor(0, 1, 0));
        xc.add(new Cursor(0, -1, 0));
        xc.add(new Cursor(0, 0, 1));
        xc.add(new Cursor(0, 0, -1));
        putFortune3(Blocks.COAL_ORE, Items.COAL, 4);
        putFortune3(Blocks.DEEPSLATE_COAL_ORE, Items.COAL, 4);
        putFortune3(Blocks.IRON_ORE, Items.IRON_INGOT, 4);
        putFortune3(Blocks.DEEPSLATE_IRON_ORE, Items.IRON_INGOT, 4);
        putFortune3(Blocks.GOLD_ORE, Items.GOLD_INGOT, 4);
        putFortune3(Blocks.DEEPSLATE_GOLD_ORE, Items.GOLD_INGOT, 4);
        putFortune3(Blocks.COPPER_ORE, Items.COPPER_INGOT, 4);
        putFortune3(Blocks.DEEPSLATE_COPPER_ORE, Items.COPPER_INGOT, 4);
        putFortune3(Blocks.NETHER_GOLD_ORE, Items.GOLD_NUGGET, 24);
        putFortune3(Blocks.DIAMOND_ORE, Items.DIAMOND, 1);
        putFortune3(Blocks.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND, 1);
        putFortune3(Blocks.LAPIS_ORE, Items.LAPIS_LAZULI, 25);
        putFortune3(Blocks.DEEPSLATE_LAPIS_ORE, Items.LAPIS_LAZULI, 25);
        putFortune3(Blocks.REDSTONE_ORE, Items.REDSTONE, 36);
        putFortune3(Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE, 36);
        putFortune3(Blocks.EMERALD_ORE, Items.EMERALD, 25);
        putFortune3(Blocks.DEEPSLATE_EMERALD_ORE, Items.EMERALD, 25);
        putFortune3(Blocks.NETHER_QUARTZ_ORE, Items.QUARTZ, 4);
        yc.add(new Cursor(1, 0, 0));
        yc.add(new Cursor(-1, 0, 0));
        yc.add(new Cursor(0, 1, 0));
        yc.add(new Cursor(0, -1, 0));
        yc.add(new Cursor(0, 0, 1));
        yc.add(new Cursor(0, 0, -1));
    }

    public static Node leastF(List<Node> list) {
        if (list.isEmpty()) {
            return null;
        }
        Node mini = list.getFirst();
        double minf = mini.f;
        for (int i = 1; i < list.size(); i++) {
            Node n = list.get(i);
            if (n.f < minf) {
                minf = n.f;
                mini = n;
            }
        }
        return mini;
    }

    public static Node nodeExists(List<Node> list, Node firstNode) {
        if (list.isEmpty()) return null;
        for (Node n : list) {
            if (n.x == firstNode.x && n.y == firstNode.y && n.z == firstNode.z)
                return n;
        }
        return null;
    }

    /**
     * Half-extents for A* expansion, reachability prepass, and container BFS, in block units from module center.
     * Must match {@link #calculatePath} bounds: ±4×{@code cSize} on X, ±5×{@code cSize} on Y and Z.
     */
    static final int PATHFINDING_RANGE_XZ_HALF = 4;
    static final int PATHFINDING_RANGE_Y_HALF = 5;
    static final int PATHFINDING_RANGE_Z_HALF = 5;

    private static boolean isWithinPathfindingRange(int x, int y, int z, int cx, int cy, int cz, int cSize) {
        return Math.abs(x - cx) <= PATHFINDING_RANGE_XZ_HALF * cSize
            && Math.abs(y - cy) <= PATHFINDING_RANGE_Y_HALF * cSize
            && Math.abs(z - cz) <= PATHFINDING_RANGE_Z_HALF * cSize;
    }

    /**
     * True if this cell cannot be entered in A* (same condition as {@code cst == 2500} in {@link #calculatePath}).
     */
    private static boolean isPathfindCellImpassable(ServerWorld world, PathfindChunkCache pathChunkCache, BlockPos pos) {
        BlockState bl = pathChunkCache.getBlockState(pos);
        if (VoidClamCoreBlocks.isWartOrCore(bl)) {
            return false;
        }
        if (bl.getBlock() instanceof BlockEntityProvider) {
            return true;
        }
        float h = getHardness(world, pos, bl);
        return h > 5 || h < 0;
    }

    private static boolean inPathfindSearchBounds(Module mod, int x, int y, int z) {
        return isWithinPathfindingRange(x, y, z, mod.x, mod.y, mod.z, mod.currentSize);
    }

    /**
     * 6-neighbor BFS from start within the same axis bounds as A*. Edges match A* impassability (cells with cost 2500 are walls).
     * Ignores movement costs; only detects hard disconnects so unreachable goals skip the expensive A* search.
     * <p>
     * Runs to completion on the <strong>invoking</strong> thread: for async A* that is the same pathfinder worker that
     * then runs {@link #calculatePath}'s main loop, so prepass and A* share one thread with an early return if unreachable.
     * {@code bfs_mode} does not apply here — only {@link BlockBfs.ExecutionMode#MAIN_THREAD_BATCHED} is used so prepass is
     * never nested as {@link BlockBfs.ExecutionMode#BACKGROUND} on the pathfinder pool (deadlock risk).
     */
    private static boolean isGoalReachableByPrepass(
        ServerWorld world,
        int sx, int sy, int sz,
        int gx, int gy, int gz,
        Module mod,
        PathfindChunkCache pathChunkCache
    ) {
        if (sx == gx && sy == gy && sz == gz) {
            return true;
        }
        if (mod == null) {
            return false;
        }
        long goalLong = BlockPos.asLong(gx, gy, gz);
        long startLong = BlockPos.asLong(sx, sy, sz);
        BlockBfs.EdgePolicy prepassPolicy = (w, fromLong, toLong, fromDist) -> {
            BlockPos nextPos = BlockPos.fromLong(toLong);
            return inPathfindSearchBounds(mod, nextPos.getX(), nextPos.getY(), nextPos.getZ())
                && !isPathfindCellImpassable(w, pathChunkCache, nextPos);
        };
        BlockBfs bfs = BlockBfs.start(
            world,
            startLong,
            prepassPolicy,
            Integer.MAX_VALUE,
            BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED,
            null,
            null,
            asyncPathfindingAbortChecker(world, mod.x, mod.z, mod.clamId),
            goalLong
        );
        bfs.runToCompletionOnCurrentThread();
        return bfs.isEarlyGoalNeighborHit();
    }

    /** Cheaper than Euclidean: no sqrt, O(1). Not admissible when edge costs can be 0 (e.g. wart). */
    private static double manhattanH(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(y - gy) + Math.abs(z - gz);
    }

    /** SLF4J debug: enable {@code voidclam/Pathfinder} or parent logger at DEBUG (e.g. in log4j2.xml). */
    private static void logPathfindingStartDebug(UUID clamId, int sx, int sy, int sz, int gx, int gy, int gz) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        VoidClamConfig cfg = VoidClamConfig.get();
        String from = "(" + sx + "," + sy + "," + sz + ")";
        String goal = "(" + gx + "," + gy + "," + gz + ")";
        if (cfg.astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            int raw = cfg.astar_sync_global_max_steps_per_tick;
            int perTick = cfg.effectiveSyncMaxStepsPerTick();
            if (raw == 0) {
                int n = Math.max(1, Runtime.getRuntime().availableProcessors());
                LOGGER.debug(
                    "[VoidClam] pathfinding start sync_batched clamId={} {} -> {} expansionsPerTick={} (astar_sync_global_max_steps_per_tick=0 guessedBase={} cpus={})",
                    clamId, from, goal, perTick, n * 128, n
                );
            } else {
                LOGGER.debug(
                    "[VoidClam] pathfinding start sync_batched clamId={} {} -> {} expansionsPerTick={} (astar_sync_global_max_steps_per_tick={})",
                    clamId, from, goal, perTick, raw
                );
            }
        } else {
            LOGGER.debug(
                "[VoidClam] pathfinding start async clamId={} {} -> {} threadPoolSize={}",
                clamId, from, goal, cfg.effectiveAsyncThreadPoolSize()
            );
        }
    }

    /**
     * {@link VoidClamConfig.AstarMode#SYNC_BATCHED}: enqueues a server-tick job whose prepass and A* steps share the main thread.
     * {@link VoidClamConfig.AstarMode#ASYNC}: pathfinder worker runs prepass BFS then the A* loop on that same thread; unreachable
     * prepass clears {@code busyFlagMainCycle} and returns before building the open list. {@code bfs_mode} does not change prepass.
     * <p>
     * Async: pass a snapshot from {@link PathfindChunkCache#buildColumnSnapshot} (server thread) or {@code null} for live world reads only.
     */
    public static boolean calculatePath(ServerWorld world, UUID clamId, int sx, int sy, int sz, int gx, int gy, int gz) {
        return calculatePath(world, clamId, sx, sy, sz, gx, gy, gz, null);
    }

    public static boolean calculatePath(
        ServerWorld world,
        UUID clamId,
        int sx, int sy, int sz,
        int gx, int gy, int gz,
        @org.jetbrains.annotations.Nullable PathfindChunkCache prebuiltColumnSnapshot
    ) {
        if (!world.isChunkLoaded(sx >> 4, sz >> 4)) {
            Module early = VoidClamMod.getModuleById(clamId);
            if (early != null) {
                VoidClamMod.releasePathfindingMainCycle(early);
            }
            return false;
        }
        Module modForFlag = VoidClamMod.getModuleById(clamId);
        if (modForFlag == null) return false;
        if (modForFlag.status != 1) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return false;
        }
        modForFlag.ensureClamId();
        if (!VoidClamMod.isPathfindingAllowedYet(world, modForFlag)) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return false;
        }
        logPathfindingStartDebug(clamId, sx, sy, sz, gx, gy, gz);
        if (VoidClamConfig.get().astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            enqueueSyncAStarJob(world, clamId, sx, sy, sz, gx, gy, gz);
            return true;
        }
        PathfindChunkCache asyncPathCache = prebuiltColumnSnapshot != null
            ? prebuiltColumnSnapshot
            : PathfindChunkCache.liveWorldOnly(world);
        if (!isGoalReachableByPrepass(world, sx, sy, sz, gx, gy, gz, modForFlag, asyncPathCache)) {
            VoidClamMod.removeLightGoalFromCacheIfPrepassUnreachable(world, clamId, gx, gy, gz);
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return false;
        }
        List<Node> open = new ArrayList<>();
        List<Node> closed = new ArrayList<>();
        UUID pathCid = modForFlag.clamId;
        Node firstNode = new Node(sx, sy, sz, null, pathCid);
        firstNode.g = 0;
        firstNode.h = manhattanH(sx, sy, sz, gx, gy, gz);
        firstNode.f = firstNode.g + firstNode.h;
        open.add(firstNode);

        long astarIterations = 0;
        long maxAstarExpansions = (long) VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
        while (!open.isEmpty()) {
            if (astarIterations >= maxAstarExpansions) {
                LOGGER.warn(
                    "[voidclam/Pathfinder] async A* exceeded expansion cap {} clamId={} goal=({},{},{}) open={} closed={}",
                    maxAstarExpansions, clamId, gx, gy, gz,
                    open.size(), closed.size());
                VoidClamMod.removeLightFromClamCacheAfterFailedPath(clamId, new BlockPos(gx, gy, gz));
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
                return false;
            }
            AStarExpandResult r = expandOneAStarIteration(
                world, pathCid, gx, gy, gz, modForFlag, open, closed, astarIterations, asyncPathCache);
            astarIterations++;
            if (r == AStarExpandResult.ABORT) {
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
                return false;
            }
            if (r == AStarExpandResult.SUCCESS) {
                return true;
            }
            if (r == AStarExpandResult.NO_PATH) {
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
                return false;
            }
        }
        VoidClamMod.releasePathfindingMainCycle(modForFlag);
        return false;
    }

    private static float getHardness(World world, BlockPos pos, BlockState state) {
        try {
            return state.getHardness(world, pos);
        } catch (Exception e) {
            return 0;
        }
    }

    private static float getBlastResistance(BlockState state) {
        try {
            return state.getBlock().getBlastResistance();
        } catch (Exception e) {
            return 0;
        }
    }

    /** True if block at pos is "solid" for tendril stickiness (not air/fluid/soft/wart). */
    private static boolean isSolid(World world, PathfindChunkCache pathChunkCache, BlockPos pos) {
        BlockState state = pathChunkCache.getBlockState(pos);
        if (state.isOf(Blocks.NETHER_WART_BLOCK)) return false;
        if (VoidClamMod.isBaseCost(state.getBlock())) return false;
        return getHardness(world, pos, state) > 0.2f;
    }

    /** True if block is traversable without breaking (air, baseCost, or soft hardness). */
    private static boolean isAirLike(BlockState state, World world, PathfindChunkCache pathChunkCache, BlockPos pos) {
        return VoidClamMod.isBaseCost(state.getBlock()) || getHardness(world, pos, state) <= 0.2f;
    }

    /** True if block is water, air, or nether wart (for adjacent count B). */
    private static boolean isWaterAirOrWart(BlockState state) {
        return state.isOf(Blocks.WATER) || state.isAir()
            || VoidClamCoreBlocks.isWartOrCore(state);
    }

    /** Number of adjacent blocks (6-neighborhood) that are not water/air/nether wart. */
    private static int countAdjacentNotWaterAirWart(World world, PathfindChunkCache pathChunkCache, BlockPos pos) {
        int b = 0;
        for (Cursor c : xc) {
            if (!isWaterAirOrWart(pathChunkCache.getBlockState(pos.getX() + c.x, pos.getY() + c.y, pos.getZ() + c.z))) {
                b++;
            }
        }
        return b;
    }

    // --- Container logic: off-thread BFS on live world (same rules as former snapshot), within pathfinding AABB ---
    private static boolean isContainerBlock(net.minecraft.block.Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
    }

    /**
     * BFS from {@code startLong} (clam center) over the live world. Same traversal rules as before: expand from root once;
     * otherwise only through nether wart. Bounded by the same AABB as {@link #calculatePath}.
     * Appends container positions to {@code containersOut} via {@link BlockBfs} (use a synchronized list if
     * {@link VoidClamConfig#bfsBlockBfsExecutionMode()} is {@link BlockBfs.ExecutionMode#BACKGROUND}).
     * When finished, queues {@code onCompleteOnServerThread} on the server thread.
     */
    private static void runContainerBfsOnWorld(
        ServerWorld world,
        int cx,
        int cy,
        int cz,
        int cSize,
        long startLong,
        List<Long> containersOut,
        @org.jetbrains.annotations.Nullable UUID pathfindingClamId,
        Runnable onCompleteOnServerThread
    ) {
        BlockBfs.EdgePolicy containerPolicy = new BlockBfs.EdgePolicy() {
            @Override
            public boolean expandFrom(ServerWorld w, long curLong, int distanceFromStart) {
                if (curLong == startLong) return true;
                BlockState st = w.getBlockState(BlockPos.fromLong(curLong));
                return VoidClamCoreBlocks.isWartOrCore(st);
            }

            @Override
            public boolean canTraverseTo(ServerWorld w, long fromLong, long toLong, int fromDistance) {
                BlockPos p = BlockPos.fromLong(toLong);
                return isWithinPathfindingRange(p.getX(), p.getY(), p.getZ(), cx, cy, cz, cSize);
            }
        };
        BlockBfs.ExecutionMode mode = VoidClamConfig.get().bfsBlockBfsExecutionMode();
        Executor bgExec = mode == BlockBfs.ExecutionMode.BACKGROUND ? CommandToolbox.pathfindingExecutor() : null;
        Runnable bgDone = mode == BlockBfs.ExecutionMode.BACKGROUND
            ? () -> world.getServer().execute(onCompleteOnServerThread)
            : null;
        BlockBfs bfs = BlockBfs.start(
            world,
            startLong,
            containerPolicy,
            Integer.MAX_VALUE,
            mode,
            bgExec,
            bgDone,
            asyncPathfindingAbortChecker(world, cx, cz, pathfindingClamId),
            BlockBfs.NO_EARLY_GOAL,
            (w, posLong, d) -> {
                BlockState state = w.getBlockState(BlockPos.fromLong(posLong));
                if (isContainerBlock(state.getBlock())) {
                    containersOut.add(posLong);
                }
            }
        );
        if (mode == BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED) {
            bfs.runToCompletionOnCurrentThread();
            world.getServer().execute(onCompleteOnServerThread);
        }
    }

    private static void replaceWithWartAndPulse(ServerWorld world, BlockPos breakPos) {
        int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, breakPos);
        world.setBlockState(breakPos, Blocks.NETHER_WART_BLOCK.getDefaultState());
        VoidClamSfx.playBlockSound(world, breakPos, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
        TendrilPulseManager.startPulse(world, breakPos, packedBrightness, () -> {});
    }

    /** Main thread: try insert into containers; if none, create barrel at breakPos. Replaces breakPos with wart when stored in existing container. */
    private static void applyContainerResult(ServerWorld world, List<Long> containerPositions, BlockPos breakPos, ItemStack toStore) {
        for (long l : containerPositions) {
            if (toStore.isEmpty()) break;
            tryInsertInto(world, BlockPos.fromLong(l), toStore);
        }
        if (!toStore.isEmpty()) {
            createBarrelAndInsert(world, breakPos, toStore);
        } else {
            replaceWithWartAndPulse(world, breakPos);
        }
    }

    /** Main thread: insert multiple stacks into containers; remainder goes to barrel. Replaces breakPos with wart when all stored. */
    private static void applyContainerResult(ServerWorld world, List<Long> containerPositions, BlockPos breakPos, List<ItemStack> toStoreList) {
        for (long l : containerPositions) {
            boolean anyLeft = false;
            for (ItemStack stack : toStoreList) {
                if (stack.isEmpty()) continue;
                tryInsertInto(world, BlockPos.fromLong(l), stack);
                anyLeft = anyLeft || !stack.isEmpty();
            }
            if (!anyLeft) break;
        }
        List<ItemStack> remainder = new ArrayList<>();
        for (ItemStack stack : toStoreList) {
            if (!stack.isEmpty()) remainder.add(stack);
        }
        if (remainder.isEmpty()) {
            replaceWithWartAndPulse(world, breakPos);
        } else {
            createBarrelAndInsert(world, breakPos, remainder);
        }
    }

    private static boolean tryInsertInto(ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return true;
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inv)) return false;
        int size = inv.size();
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack inSlot = inv.getStack(i);
            if (inSlot.isEmpty()) {
                int toPut = Math.min(stack.getCount(), inv.getMaxCountPerStack());
                ItemStack put = stack.copy();
                put.setCount(toPut);
                inv.setStack(i, put);
                stack.decrement(toPut);
            } else if (ItemStack.areItemsEqual(inSlot, stack)) {
                int max = Math.min(inv.getMaxCountPerStack(), inSlot.getMaxCount());
                int canAdd = max - inSlot.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    inSlot.increment(toAdd);
                    stack.decrement(toAdd);
                }
            }
        }
        if (be != null) be.markDirty();
        return stack.isEmpty();
    }

    private static void createBarrelAndInsert(ServerWorld world, BlockPos pos, ItemStack stack) {
        BlockState barrelState = Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, Direction.NORTH);
        world.setBlockState(pos, barrelState);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof BarrelBlockEntity) {
            tryInsertInto(world, pos, stack);
        } else {
            net.minecraft.block.Block.dropStack(world, pos, stack);
        }
    }

    private static void createBarrelAndInsert(ServerWorld world, BlockPos pos, List<ItemStack> stacks) {
        BlockState barrelState = Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, Direction.NORTH);
        world.setBlockState(pos, barrelState);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof BarrelBlockEntity) {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) tryInsertInto(world, pos, stack);
            }
        } else {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) net.minecraft.block.Block.dropStack(world, pos, stack);
            }
        }
    }

    public static void buildPath(ServerWorld world, Node gnode) {
        if (gnode.clamId == null) return;
        Module mod = VoidClamMod.getModuleById(gnode.clamId);
        if (mod == null) {
            return;
        }
        mod.ensureClamId();
        final java.util.UUID pathClamId = gnode.clamId;
        final int pathOriginX = mod.x;
        final int pathOriginY = mod.y;
        final int pathOriginZ = mod.z;
        final Module modForFlag = mod;
        if (modForFlag.busyFlagMainCycle == 0) {
            return;
        }
        if (gnode.f >= 2500) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        if (!world.isChunkLoaded(mod.x >> 4, mod.z >> 4)) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        // Skip if this goal was enqueued before seek flags were turned off
        BlockPos goalPos = new BlockPos(gnode.x, gnode.y, gnode.z);
        net.minecraft.block.Block goalBlock = world.getBlockState(goalPos).getBlock();
        if (VoidClamMod.isOre(goalBlock) && !mod.seekOres) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        if (VoidClamMod.isLight(goalBlock) && !mod.seekLights) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        int pathSteps = 0;
        for (Node c = gnode; c.parent != null; c = c.parent) {
            pathSteps++;
        }
        modForFlag.pathApplyPendingSteps = pathSteps;

        Node firstNode = gnode;
        Node copy = gnode;
        long timer = 2;
        while (copy.parent != null) {
            timer += 2;
            copy = copy.parent;
        }

        VoidClamMod.scheduleDelayed(world, timer, () -> {
            if (!VoidClamMod.moduleMatchesClamAt(pathClamId, pathOriginX, pathOriginY, pathOriginZ)) {
                return;
            }
            VoidClamMod.removeOresBlackList(pathClamId, goalPos);
        });

        int[] stamina = new int[]{modForFlag.currentSize};
        int[] blocked = new int[1];
        int[] pathStopped = new int[1]; // set when block-to-break: path stops, no energy, resume next attempt
        int[] pathStoppedAwaitingContainer = new int[1]; // 1 while off-thread container BFS + apply not finished; keeps busy, suppresses stale path steps

        while (firstNode.parent != null && blocked[0] == 0) {
            final Node refNode = firstNode;
            final long runAt = timer;
            final int cSize = modForFlag.currentSize;
            VoidClamMod.scheduleDelayed(world, runAt, () -> {
                if (!VoidClamMod.moduleMatchesClamAt(pathClamId, pathOriginX, pathOriginY, pathOriginZ)) {
                    VoidClamMod.releasePathfindingMainCycle(modForFlag);
                    return;
                }
                if (pathStopped[0] != 0 && pathStoppedAwaitingContainer[0] != 0) {
                    return;
                }
                if (blocked[0] != 0 || pathStopped[0] != 0) {
                    VoidClamMod.completeOnePathApplyStep(modForFlag);
                    return;
                }
                BlockPos pos = new BlockPos(refNode.x, refNode.y, refNode.z);
                BlockState mat = world.getBlockState(pos);
                if (mat.isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
                    int packedH = TendrilPulseManager.getPackedBrightnessAt(world, pos);
                    TendrilPulseManager.startPulse(world, pos, mat, packedH, () -> {}, TendrilPulseManager.INITIAL_SCALE_OMNI);
                    VoidClamMod.completeOnePathApplyStep(modForFlag);
                    return;
                }
                int cst;
                float breakHard = getHardness(world, pos, mat);
                if (mat.isOf(Blocks.NETHER_WART_BLOCK)) cst = 0;
                else if (mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA)) cst = 1;
                else if (breakHard < 0) cst = 1_000_000; // indestructible: do not use negative-hardness stamina math
                else cst = (int) Math.floor(breakHard) * 2;
                if (refNode == gnode) cst = 0;
                if (refNode == gnode && breakHard < 0
                    && !mat.isAir() && !mat.isOf(Blocks.WATER) && !mat.isOf(Blocks.LAVA)
                    && !VoidClamCoreBlocks.isWartOrCore(mat)) {
                    cst = 1_000_000;
                }

                // Ore at goal: fortune-3 drops, store in containers, replace with wart
                if (refNode == gnode && VoidClamMod.isOre(mat.getBlock())) {
                    List<ItemStack> drops = getFortune3Drops(mat.getBlock());
                    if (!drops.isEmpty()) {
                        BlockPos breakPos = pos.toImmutable();
                        long clamCenterLong = BlockPos.asLong(mod.x, mod.y, mod.z);
                        int mx = mod.x, my = mod.y, mz = mod.z;
                        CommandToolbox.submitPathfinding(
                            world,
                            mx,
                            mz,
                            pathClamId,
                            () -> VoidClamMod.completeOnePathApplyStep(modForFlag),
                            () -> {
                                List<Long> containers = Collections.synchronizedList(new ArrayList<>());
                                runContainerBfsOnWorld(
                                    world, mx, my, mz, cSize, clamCenterLong, containers, pathClamId,
                                    () -> {
                                        if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, mod.x, mod.z, pathClamId)) {
                                            VoidClamMod.completeOnePathApplyStep(modForFlag);
                                            return;
                                        }
                                        applyContainerResult(world, containers, breakPos, drops);
                                        VoidClamMod.completeOnePathApplyStep(modForFlag);
                                    });
                            }
                        );
                    } else {
                        replaceWithWartAndPulse(world, pos);
                        VoidClamMod.completeOnePathApplyStep(modForFlag);
                    }
                    return;
                }

                if (stamina[0] - cst < 0) {
                    blocked[0] = 1;
                    if (!(mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA))) {
                        BlockState goalState = world.getBlockState(goalPos);
                        if (VoidClamMod.isLight(goalState.getBlock())) {
                            VoidClamMod.removeLightFromClamCacheAfterFailedPath(pathClamId, goalPos);
                        }
                        if (VoidClamMod.isOre(goalState.getBlock())) {
                            VoidClamMod.addOresBlackList(pathClamId, goalPos);
                        }
                    }
                    VoidClamMod.addEnergy(pathClamId, -1);
                } else {
                    stamina[0] -= cst;
                }

                boolean isReplacingBlock = !(refNode == gnode || mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA) || VoidClamCoreBlocks.isWartOrCore(mat));
                if (isReplacingBlock && mat.getBlock().asItem() != Items.AIR) {
                    pathStopped[0] = 1; // path stops; clam does not get energy; resume next attempt
                    pathStoppedAwaitingContainer[0] = 1;
                    ItemStack toStore = new ItemStack(mat.getBlock().asItem(), 1);
                    BlockPos breakPos = pos.toImmutable();
                    long clamCenterLong = BlockPos.asLong(mod.x, mod.y, mod.z);
                    int mx = mod.x, my = mod.y, mz = mod.z;
                    CommandToolbox.submitPathfinding(
                        world,
                        mx,
                        mz,
                        pathClamId,
                        () -> {
                            pathStoppedAwaitingContainer[0] = 0;
                            VoidClamMod.completeOnePathApplyStep(modForFlag);
                        },
                        () -> {
                            List<Long> containers = Collections.synchronizedList(new ArrayList<>());
                            runContainerBfsOnWorld(
                                world, mx, my, mz, cSize, clamCenterLong, containers, pathClamId,
                                () -> {
                                    if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, mod.x, mod.z, pathClamId)) {
                                        pathStoppedAwaitingContainer[0] = 0;
                                        VoidClamMod.completeOnePathApplyStep(modForFlag);
                                        return;
                                    }
                                    applyContainerResult(world, containers, breakPos, toStore);
                                    pathStoppedAwaitingContainer[0] = 0;
                                    VoidClamMod.completeOnePathApplyStep(modForFlag);
                                });
                        }
                    );
                    return;
                }

                int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, pos);
                world.setBlockState(pos, Blocks.NETHER_WART_BLOCK.getDefaultState());
                VoidClamSfx.playBlockSound(world, pos, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
                if (refNode == gnode && VoidClamMod.isLight(mat.getBlock()))
                    VoidClamMod.addEnergy(pathClamId, 1); // energy only when light source is eaten
                if (!(refNode == gnode || mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA) || VoidClamCoreBlocks.isWartOrCore(mat))) {
                    if (mat.getBlock().asItem() != Items.AIR)
                        net.minecraft.block.Block.dropStack(world, pos, new ItemStack(mat.getBlock().asItem(), 1));
                }
                TendrilPulseManager.startPulse(world, pos, packedBrightness, () -> {});
                VoidClamMod.completeOnePathApplyStep(modForFlag);
            });
            timer -= 2;
            firstNode = firstNode.parent;
        }
    }
}
