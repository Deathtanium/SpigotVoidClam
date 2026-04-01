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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A*, prepass BFS, path application, and container routing for clams.
 * Stamina costs for dig steps use explicit air / water / lava checks; traversal costs elsewhere use
 * {@link VoidClamMod#isBaseCost} and {@link #isAirLike} where fluid and snow-like blocks matter.
 */
public final class Pathfinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("voidclam/Pathfinder");

    /** Mutable flags shared across delayed path-apply runnables for one {@link #buildPath} invocation. */
    private static final class PathApplySliceState {
        int stamina;
        boolean blocked;
        boolean pathStopped;
        boolean pathStoppedAwaitingContainer;

        PathApplySliceState(int initialStamina) {
            this.stamina = initialStamina;
        }
    }

    /** {@link BlockBfs} uses {@code int} visit caps; unbounded job config maps to {@link Integer#MAX_VALUE}. */
    private static int blockBfsMaxVisited(long expansionsJobCap) {
        if (expansionsJobCap >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) expansionsJobCap;
    }

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
            int openSz = j.astarFrontier != null ? j.astarFrontier.openSize() : -1;
            int closedSz = j.astarFrontier != null ? j.astarFrontier.closedSize() : -1;
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

    /** True if sync-batched mode still has a queued or in-progress job for this clam. */
    public static boolean hasSyncAStarWorkForClam(@org.jetbrains.annotations.Nullable UUID clamId) {
        if (clamId == null) {
            return false;
        }
        for (AStarJob j : syncAStarJobs) {
            if (clamId.equals(j.clamId)) {
                return true;
            }
        }
        for (AStarJob j : syncAStarFairness) {
            if (clamId.equals(j.clamId)) {
                return true;
            }
        }
        return false;
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
        Clam m = VoidClamMod.getClamById(clamId);
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
            Clam pauseMod = VoidClamMod.getClamById(job.clamId);
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
        AStarFrontier astarFrontier;
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
            Clam modForFlag = VoidClamMod.getClamById(clamId);
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
            activePathChunkCache = new PathfindChunkCache(world, modForFlag);
            if (phase == AStarPhase.PREPASS) {
                return stepPrepass(world, modForFlag, budget);
            }
            if (phase == AStarPhase.ASTAR) {
                return stepAstar(world, modForFlag, budget);
            }
            return 0;
        }

        private int stepPrepass(ServerWorld world, Clam modForFlag, int budget) {
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
                long jobExpCap = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
                prepassBfs = BlockBfs.start(
                    world,
                    startLong,
                    prepassPolicy,
                    blockBfsMaxVisited(jobExpCap),
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
                VoidClamMod.removeOreGoalFromCacheIfPrepassUnreachable(world, clamId, gx, gy, gz);
                finishFail(modForFlag);
                return used;
            }
            beginAstarPhase();
            return used;
        }

        private void beginAstarPhase() {
            astarFrontier = new AStarFrontier();
            Node firstNode = new Node(sx, sy, sz, null, clamId);
            firstNode.g = 0;
            firstNode.h = manhattanH(sx, sy, sz, gx, gy, gz);
            firstNode.f = firstNode.g + firstNode.h;
            astarFrontier.offerStart(firstNode);
            astarIterations = 0;
            phase = AStarPhase.ASTAR;
        }

        private int stepAstar(ServerWorld world, Clam modForFlag, int budget) {
            int used = 0;
            long expandCap = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
            while (used < budget) {
                if (totalSyncExpansions >= expandCap) {
                    finishFail(modForFlag);
                    return used;
                }
                AStarExpandResult r = expandOneAStarIteration(
                    world, clamId, gx, gy, gz, modForFlag, astarFrontier, astarIterations, activePathChunkCache);
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

        private void finishFail(Clam modForFlag) {
            phase = AStarPhase.DONE;
            if (modForFlag != null) {
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
            }
        }
    }

    /**
     * A* open/closed: one {@link Node} per cell in {@code openByPos}, backed by an explicit indexed binary min-heap
     * ({@code openHeap} + {@code openHeapIndex}). Gives O(log n) pop and priority fix on improve-key, without lazy
     * {@link java.util.PriorityQueue} duplicates or whole-heap {@code addAll} rebuilds.
     */
    private static final class AStarFrontier {
        private final ArrayList<Node> openHeap = new ArrayList<>();
        private final Map<Long, Integer> openHeapIndex = new HashMap<>();
        private final Map<Long, Node> openByPos = new HashMap<>();
        private final Map<Long, Node> closedByPos = new HashMap<>();

        boolean isOpenEmpty() {
            return openByPos.isEmpty();
        }

        int openSize() {
            return openByPos.size();
        }

        int closedSize() {
            return closedByPos.size();
        }

        void offerStart(Node n) {
            putOpen(BlockPos.asLong(n.x, n.y, n.z), n);
        }

        void putOpen(long posKey, Node n) {
            Node old = openByPos.put(posKey, n);
            if (old == null) {
                int i = openHeap.size();
                openHeap.add(n);
                openHeapIndex.put(posKey, i);
                siftUp(i);
            } else {
                int i = openHeapIndex.get(posKey);
                openHeap.set(i, n);
                siftUp(i);
                siftDown(openHeapIndex.get(posKey));
            }
        }

        Node getOpen(long posKey) {
            return openByPos.get(posKey);
        }

        Node getClosed(long posKey) {
            return closedByPos.get(posKey);
        }

        void removeClosed(long posKey) {
            closedByPos.remove(posKey);
        }

        void addClosed(Node expanded) {
            closedByPos.put(BlockPos.asLong(expanded.x, expanded.y, expanded.z), expanded);
        }

        /** Min-heap ordering: smaller f, then y, x, z. */
        private static int compareOpenOrder(Node a, Node b) {
            int c = Double.compare(a.f, b.f);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.y, b.y);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.x, b.x);
            if (c != 0) {
                return c;
            }
            return Integer.compare(a.z, b.z);
        }

        private static boolean openPrecedes(Node a, Node b) {
            return compareOpenOrder(a, b) < 0;
        }

        private void swapHeap(int i, int j) {
            if (i == j) {
                return;
            }
            Node ni = openHeap.get(i);
            Node nj = openHeap.get(j);
            openHeap.set(i, nj);
            openHeap.set(j, ni);
            openHeapIndex.put(BlockPos.asLong(nj.x, nj.y, nj.z), i);
            openHeapIndex.put(BlockPos.asLong(ni.x, ni.y, ni.z), j);
        }

        private void siftUp(int i) {
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (!openPrecedes(openHeap.get(i), openHeap.get(p))) {
                    break;
                }
                swapHeap(i, p);
                i = p;
            }
        }

        private void siftDown(int i) {
            int size = openHeap.size();
            while (true) {
                int l = i * 2 + 1;
                if (l >= size) {
                    break;
                }
                int r = l + 1;
                int smallest = l;
                if (r < size && openPrecedes(openHeap.get(r), openHeap.get(l))) {
                    smallest = r;
                }
                if (!openPrecedes(openHeap.get(smallest), openHeap.get(i))) {
                    break;
                }
                swapHeap(i, smallest);
                i = smallest;
            }
        }

        /**
         * Removes and returns the open node with smallest f. {@code null} if frontier empty.
         */
        Node pollNextToExpand() {
            if (openByPos.isEmpty()) {
                return null;
            }
            Node best = openHeap.get(0);
            long k = BlockPos.asLong(best.x, best.y, best.z);
            openByPos.remove(k);
            int last = openHeap.size() - 1;
            if (last == 0) {
                openHeap.remove(last);
                openHeapIndex.remove(k);
                return best;
            }
            swapHeap(0, last);
            openHeap.remove(last);
            // Remove after the swap: swapHeap assigns the popped node's index to `last`; clearing here avoids a stale
            // index equal to the old size (out of bounds once the list shrinks).
            openHeapIndex.remove(k);
            siftDown(0);
            return best;
        }
    }

    private enum AStarExpandResult {
        CONTINUE,
        SUCCESS,
        NO_PATH,
        ABORT
    }

    /**
     * Movement/break cost for one A* neighbor. When {@code blockEntityAsWall} is true, {@link BlockEntityProvider} cells are
     * impassable ({@code 2500}); when false (this cell is the path goal), they use the same dig/air/water costs as other blocks.
     */
    private static double aStarNeighborCost(
        ServerWorld world,
        PathfindChunkCache pathChunkCache,
        BlockPos nextPos,
        BlockState bl,
        boolean blockEntityAsWall
    ) {
        if (VoidClamCoreBlocks.isWartOrCore(bl)) {
            return 0;
        }
        if (blockEntityAsWall && bl.getBlock() instanceof BlockEntityProvider) {
            return 2500;
        }
        if (!skipHardnessWallForTraversal(bl)) {
            float hard = getHardness(world, nextPos, bl);
            if (hard > 5 || hard < 0) {
                return 2500;
            }
        }
        if (bl.isOf(Blocks.WATER) || (isAirLike(bl, world, pathChunkCache, nextPos) && isSolid(world, pathChunkCache, nextPos.down()))) {
            return 1;
        }
        if (isAirLike(bl, world, pathChunkCache, nextPos)) {
            int b = countAdjacentNotWaterAirWart(world, pathChunkCache, nextPos);
            return 6 - b;
        }
        return 10 + getBlastResistance(bl);
    }

    /**
     * One A* iteration: pop best open node, expand neighbors, maybe enqueue goal to {@link VoidClamMod#enqueueTarget}.
     * {@code astarIterations} is the count before this iteration (for cooperative abort every 1024 steps).
     */
    private static AStarExpandResult expandOneAStarIteration(
        ServerWorld world,
        UUID pathClamId,
        int gx, int gy, int gz,
        Clam modForFlag,
        AStarFrontier frontier,
        long astarIterationsBeforeStep,
        PathfindChunkCache pathChunkCache
    ) {
        UUID effectiveClamId = pathClamId != null ? pathClamId : modForFlag.clamId;
        if ((astarIterationsBeforeStep & 0x3FF) == 0 && VoidClamMod.shouldAbortAsyncPathfindingWork(world, modForFlag.x, modForFlag.z, effectiveClamId)) {
            return AStarExpandResult.ABORT;
        }
        if (frontier.isOpenEmpty()) {
            return AStarExpandResult.NO_PATH;
        }
        Node nextCheapestNode = frontier.pollNextToExpand();
        if (nextCheapestNode == null) {
            return AStarExpandResult.NO_PATH;
        }

        for (Cursor c : xc) {
            int nx = nextCheapestNode.x + c.x;
            int ny = nextCheapestNode.y + c.y;
            int nz = nextCheapestNode.z + c.z;

            BlockPos nextPos = new BlockPos(nx, ny, nz);
            boolean isGoalCell = nx == gx && ny == gy && nz == gz;
            BlockState bl = pathChunkCache.getBlockState(nextPos);
            // Goal first: light targets include beacons (BlockEntityProvider); those are walls for traversal but valid dig targets.
            double cst = aStarNeighborCost(world, pathChunkCache, nextPos, bl, !isGoalCell);
            if (cst == 2500) {
                continue;
            }

            Clam pathMod = modForFlag;
            // Prepass can mark the goal reachable via a 6-neighbor adjacent to the goal even when the goal cell lies
            // just outside the pathfinding AABB (common for a torch one block past the Y cap). A* must allow that final
            // step; otherwise we exhaust the open set after a long search (async "stuck") or return NO_PATH.
            if (!isGoalCell && !isWithinPathfindingRange(nx, ny, nz, pathMod.x, pathMod.y, pathMod.z, pathMod.currentSize)) {
                continue;
            }

            long nk = BlockPos.asLong(nx, ny, nz);
            double tentativeG = nextCheapestNode.g + cst;
            Node inOpen = frontier.getOpen(nk);
            Node inClosed = frontier.getClosed(nk);
            if (inOpen != null && tentativeG >= inOpen.g) {
                continue;
            }
            if (inClosed != null && tentativeG >= inClosed.g) {
                continue;
            }

            if (inClosed != null) {
                frontier.removeClosed(nk);
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

            frontier.putOpen(nk, nextNode);
        }
        frontier.addClosed(nextCheapestNode);
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

    /**
     * Half-extents for A* expansion, reachability prepass, and container BFS, in block units from clam center.
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
     * Pathfinding treats extreme {@link BlockState#getHardness} as a solid wall, but some blocks report misleading values
     * (fluids, {@link VoidClamMod#isBaseCost base-cost} blocks like snow). Non-empty {@link BlockState#getFluidState}
     * covers waterlogging, powder-snow fluid, and typical modded fluids without listing each block.
     */
    private static boolean skipHardnessWallForTraversal(BlockState bl) {
        return VoidClamMod.isBaseCost(bl.getBlock()) || !bl.getFluidState().isEmpty();
    }

    /**
     * True if this cell cannot be entered in A* (same condition as {@code cst == 2500} in {@link #calculatePath}, for
     * neighbors where the goal exception for {@link BlockEntityProvider} does not apply).
     */
    private static boolean isPathfindCellImpassable(ServerWorld world, BlockState bl, BlockPos pos) {
        if (VoidClamCoreBlocks.isWartOrCore(bl)) {
            return false;
        }
        if (bl.getBlock() instanceof BlockEntityProvider) {
            return true;
        }
        if (skipHardnessWallForTraversal(bl)) {
            return false;
        }
        float h = getHardness(world, pos, bl);
        return h > 5 || h < 0;
    }

    private static boolean isPathfindCellImpassable(ServerWorld world, PathfindChunkCache pathChunkCache, BlockPos pos) {
        return isPathfindCellImpassable(world, pathChunkCache.getBlockState(pos), pos);
    }

    /**
     * Walk parent chain from goal toward start; every node except the goal must be path-enterable (matches A* interior rules).
     * The goal cell may be a beacon etc. and is not checked here.
     */
    private static boolean pathInteriorHasImpassibleStep(ServerWorld world, Node gnode) {
        for (Node c = gnode.parent; c != null; c = c.parent) {
            BlockPos p = new BlockPos(c.x, c.y, c.z);
            BlockState st = world.getBlockState(p);
            if (isPathfindCellImpassable(world, st, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inPathfindSearchBounds(Clam mod, int x, int y, int z) {
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
        Clam mod,
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
        long jobExpCap = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
        int prepassCap = blockBfsMaxVisited(jobExpCap);
        BlockBfs bfs = BlockBfs.start(
            world,
            startLong,
            prepassPolicy,
            prepassCap,
            BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED,
            null,
            null,
            asyncPathfindingAbortChecker(world, mod.x, mod.z, mod.clamId),
            goalLong
        );
        bfs.runToCompletionOnCurrentThread();
        boolean hit = bfs.isEarlyGoalNeighborHit();
        return hit;
    }

    /** Cheaper than Euclidean: no sqrt, O(1). Not admissible when edge costs can be 0 (e.g. wart). */
    private static double manhattanH(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(y - gy) + Math.abs(z - gz);
    }

    /**
     * {@link VoidClamConfig.AstarMode#SYNC_BATCHED}: enqueues a server-tick job whose prepass and A* steps share the main thread.
     * {@link VoidClamConfig.AstarMode#ASYNC}: pathfinder worker runs prepass BFS then the A* loop on that same thread; unreachable
     * prepass clears {@code busyFlagMainCycle} and returns before building the open list. {@code bfs_mode} does not change prepass.
     */
    public static boolean calculatePath(ServerWorld world, UUID clamId, int sx, int sy, int sz, int gx, int gy, int gz) {
        if (!world.isChunkLoaded(sx >> 4, sz >> 4)) {
            Clam early = VoidClamMod.getClamById(clamId);
            if (early != null) {
                VoidClamMod.releasePathfindingMainCycle(early);
            }
            return false;
        }
        Clam modForFlag = VoidClamMod.getClamById(clamId);
        if (modForFlag == null) return false;
        if (!VoidClamMod.isSearingHeartThermallyActive(world, modForFlag)) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return false;
        }
        modForFlag.ensureClamId();
        if (!VoidClamMod.isPathfindingAllowedYet(world, modForFlag)) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return false;
        }
        if (VoidClamConfig.get().astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            enqueueSyncAStarJob(world, clamId, sx, sy, sz, gx, gy, gz);
            return true;
        }
        PathfindChunkCache asyncPathCache = new PathfindChunkCache(world, modForFlag);
        if (!isGoalReachableByPrepass(world, sx, sy, sz, gx, gy, gz, modForFlag, asyncPathCache)) {
            VoidClamMod.removeLightGoalFromCacheIfPrepassUnreachable(world, clamId, gx, gy, gz);
            VoidClamMod.removeOreGoalFromCacheIfPrepassUnreachable(world, clamId, gx, gy, gz);
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return false;
        }
        AStarFrontier af = new AStarFrontier();
        UUID pathCid = modForFlag.clamId;
        Node firstNode = new Node(sx, sy, sz, null, pathCid);
        firstNode.g = 0;
        firstNode.h = manhattanH(sx, sy, sz, gx, gy, gz);
        firstNode.f = firstNode.g + firstNode.h;
        af.offerStart(firstNode);

        long astarIterations = 0;
        long maxAstarExpansions = VoidClamConfig.get().effectiveSyncMaxTotalExpansionsPerJob();
        while (!af.isOpenEmpty()) {
            if (astarIterations >= maxAstarExpansions) {
                LOGGER.warn(
                    "[voidclam/Pathfinder] async A* exceeded expansion cap {} clamId={} goal=({},{},{}) open={} closed={}",
                    maxAstarExpansions, clamId, gx, gy, gz,
                    af.openSize(), af.closedSize());
                VoidClamMod.removeSeekGoalFromCachesAfterFailedPath(world, clamId, gx, gy, gz);
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
                return false;
            }
            AStarExpandResult r = expandOneAStarIteration(
                world, pathCid, gx, gy, gz, modForFlag, af, astarIterations, asyncPathCache);
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

    /**
     * @return number of edges from {@code gnode} toward the start (legacy {@code pathSteps}), or {@code -1} if the
     * parent chain cycles (releases pathfinding main cycle).
     */
    private static int countPathStepsSanityOrRelease(Node gnode, Clam modForFlag) {
        HashSet<Long> seen = new HashSet<>();
        int steps = 0;
        for (Node c = gnode; c.parent != null; c = c.parent) {
            long pk = BlockPos.asLong(c.x, c.y, c.z);
            if (!seen.add(pk)) {
                VoidClamMod.releasePathfindingMainCycle(modForFlag);
                return -1;
            }
            steps++;
        }
        return steps;
    }

    private static float getHardness(World world, BlockPos pos, BlockState state) {
        return state.getHardness(world, pos);
    }

    private static float getBlastResistance(BlockState state) {
        return state.getBlock().getBlastResistance();
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

    /**
     * Counts chest, trapped chest, and barrel blocks reachable from the clam heart through nether wart / core
     * within the same axis bounds as pathfinding container BFS ({@link #runContainerBfsOnWorld}).
     * Runs synchronously on the calling thread (intended: server thread).
     */
    public static int countConnectedStorageBlocks(ServerWorld world, Clam mod) {
        if (mod == null || world == null) {
            return 0;
        }
        mod.ensureClamId();
        if (!world.getRegistryKey().equals(mod.dimensionWorldKey())) {
            return 0;
        }
        if (!world.isChunkLoaded(mod.x >> 4, mod.z >> 4)) {
            return 0;
        }
        int cSize = Math.max(1, mod.currentSize);
        int cx = mod.x, cy = mod.y, cz = mod.z;
        long startLong = BlockPos.asLong(cx, cy, cz);
        List<Long> containersOut = new ArrayList<>();
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
        BlockBfs bfs = BlockBfs.start(
            world,
            startLong,
            containerPolicy,
            Integer.MAX_VALUE,
            BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED,
            null,
            null,
            asyncPathfindingAbortChecker(world, cx, cz, mod.clamId),
            BlockBfs.NO_EARLY_GOAL,
            (w, posLong, d) -> {
                BlockState state = w.getBlockState(BlockPos.fromLong(posLong));
                if (isContainerBlock(state.getBlock())) {
                    containersOut.add(posLong);
                }
            }
        );
        bfs.runToCompletionOnCurrentThread();
        return containersOut.size();
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
        if (be == null) {
            return false;
        }
        if (!(be instanceof Inventory inv)) {
            return false;
        }
        int size = inv.size();
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack inSlot = inv.getStack(i);
            if (inSlot.isEmpty()) {
                int toPut = Math.min(stack.getCount(), inv.getMaxCountPerStack());
                if (toPut <= 0) {
                    continue;
                }
                // split: move a portion off the stack without ItemStack.copy() (avoids deep component cloning / Guava SO on nested items).
                ItemStack put = stack.split(toPut);
                inv.setStack(i, put);
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
        be.markDirty();
        return stack.isEmpty();
    }

    private static void createBarrelAndInsert(ServerWorld world, BlockPos pos, ItemStack stack) {
        createBarrelAndInsert(world, pos, Collections.singletonList(stack));
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

    /**
     * Off-thread container discovery from the clam heart, then main-thread {@link #applyContainerResult}
     * and {@code onAfterStore} (typically energy + {@link VoidClamMod#completeOnePathApplyStep}).
     */
    private static void enqueueGoalLootContainerRouting(
        ServerWorld world,
        Clam mod,
        Clam modForFlag,
        int cSize,
        UUID pathClamId,
        BlockPos breakPos,
        List<ItemStack> loot,
        Runnable onAfterStore
    ) {
        int mx = mod.x, my = mod.y, mz = mod.z;
        long clamCenterLong = BlockPos.asLong(mx, my, mz);
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
                        applyContainerResult(world, containers, breakPos, loot);
                        onAfterStore.run();
                    });
            }
        );
    }

    public static void buildPath(ServerWorld world, Node gnode) {
        if (gnode.clamId == null) return;
        Clam mod = VoidClamMod.getClamById(gnode.clamId);
        if (mod == null) {
            return;
        }
        mod.ensureClamId();
        final java.util.UUID pathClamId = gnode.clamId;
        final int pathOriginX = mod.x;
        final int pathOriginY = mod.y;
        final int pathOriginZ = mod.z;
        final Clam modForFlag = mod;
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
        if (VoidClamMod.isOre(goalBlock) && !mod.seekOres && !mod.orePathForMaterialHunger) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        if (VoidClamMod.isLight(goalBlock) && !mod.seekLights) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        int pathSteps = countPathStepsSanityOrRelease(gnode, modForFlag);
        if (pathSteps < 0) {
            return;
        }
        if (pathInteriorHasImpassibleStep(world, gnode)) {
            VoidClamMod.releasePathfindingMainCycle(modForFlag);
            return;
        }
        modForFlag.pathApplyPendingSteps = pathSteps;

        Node firstNode = gnode;
        long timer = 2 + 2L * pathSteps;

        VoidClamMod.scheduleDelayed(world, timer, () -> {
            if (!VoidClamMod.clamMatchesAt(pathClamId, pathOriginX, pathOriginY, pathOriginZ)) {
                return;
            }
            VoidClamMod.removeOresBlackList(pathClamId, goalPos);
        });

        PathApplySliceState sliceState = new PathApplySliceState(modForFlag.currentSize);

        while (firstNode.parent != null && !sliceState.blocked) {
            final Node refNode = firstNode;
            final long runAt = timer;
            final int cSize = modForFlag.currentSize;
            VoidClamMod.scheduleDelayed(world, runAt, () -> {
                if (!VoidClamMod.clamMatchesAt(pathClamId, pathOriginX, pathOriginY, pathOriginZ)) {
                    VoidClamMod.releasePathfindingMainCycle(modForFlag);
                    return;
                }
                if (sliceState.pathStopped && sliceState.pathStoppedAwaitingContainer) {
                    return;
                }
                if (sliceState.blocked || sliceState.pathStopped) {
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
                if (refNode != gnode && isPathfindCellImpassable(world, mat, pos)) {
                    sliceState.blocked = true;
                    VoidClamMod.releasePathfindingMainCycle(modForFlag);
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

                // Beacon at goal: preserve the nether star and route it through container BFS (barrel fallback if needed).
                if (refNode == gnode && mat.isOf(Blocks.BEACON)) {
                    ItemStack starDrop = new ItemStack(Items.NETHER_STAR, 1);
                    BlockPos breakPos = pos.toImmutable();
                    enqueueGoalLootContainerRouting(
                        world, mod, modForFlag, cSize, pathClamId, breakPos,
                        Collections.singletonList(starDrop),
                        () -> {
                            VoidClamMod.addEnergy(pathClamId, VoidClamMod.lightEnergyForBlock(Blocks.BEACON));
                            VoidClamMod.completeOnePathApplyStep(modForFlag);
                        });
                    return;
                }

                // Ore at goal: fortune-3 drops, store in containers, replace with wart
                if (refNode == gnode && VoidClamMod.isOre(mat.getBlock()) && modForFlag.orePathForMaterialHunger) {
                    replaceWithWartAndPulse(world, pos);
                    VoidClamMod.addMaterial(pathClamId, 1);
                    VoidClamMod.completeOnePathApplyStep(modForFlag);
                    return;
                }

                // Ore at goal: fortune-3 drops, store in containers, replace with wart
                if (refNode == gnode && VoidClamMod.isOre(mat.getBlock())) {
                    List<ItemStack> drops = getFortune3Drops(mat.getBlock());
                    if (!drops.isEmpty()) {
                        BlockPos breakPos = pos.toImmutable();
                        enqueueGoalLootContainerRouting(
                            world, mod, modForFlag, cSize, pathClamId, breakPos, drops,
                            () -> VoidClamMod.completeOnePathApplyStep(modForFlag));
                    } else {
                        replaceWithWartAndPulse(world, pos);
                        VoidClamMod.completeOnePathApplyStep(modForFlag);
                    }
                    return;
                }

                if (sliceState.stamina - cst < 0) {
                    sliceState.blocked = true;
                    if (!(mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA))) {
                        BlockState goalState = world.getBlockState(goalPos);
                        if (VoidClamMod.isLight(goalState.getBlock())) {
                            VoidClamMod.removeLightFromClamCacheAfterFailedPath(pathClamId, goalPos);
                        }
                        if (VoidClamMod.isOre(goalState.getBlock())) {
                            VoidClamMod.removeOreFromClamCacheAfterFailedPath(pathClamId, goalPos);
                        }
                    }
                    VoidClamMod.addEnergy(pathClamId, -1);
                    VoidClamMod.completeOnePathApplyStep(modForFlag);
                    return;
                }
                sliceState.stamina -= cst;

                boolean isReplacingBlock = !(refNode == gnode || mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA) || VoidClamCoreBlocks.isWartOrCore(mat));
                if (isReplacingBlock && mat.getBlock().asItem() != Items.AIR) {
                    sliceState.pathStopped = true;
                    sliceState.pathStoppedAwaitingContainer = true;
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
                            sliceState.pathStoppedAwaitingContainer = false;
                            VoidClamMod.completeOnePathApplyStep(modForFlag);
                        },
                        () -> {
                            List<Long> containers = Collections.synchronizedList(new ArrayList<>());
                            runContainerBfsOnWorld(
                                world, mx, my, mz, cSize, clamCenterLong, containers, pathClamId,
                                () -> {
                                    if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, mod.x, mod.z, pathClamId)) {
                                        sliceState.pathStoppedAwaitingContainer = false;
                                        VoidClamMod.completeOnePathApplyStep(modForFlag);
                                        return;
                                    }
                                    applyContainerResult(world, containers, breakPos, toStore);
                                    sliceState.pathStoppedAwaitingContainer = false;
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
                    VoidClamMod.addEnergy(pathClamId, VoidClamMod.lightEnergyForBlock(mat.getBlock())); // energy only when light source is eaten
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
