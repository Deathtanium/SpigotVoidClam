package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Centralized 6-neighbor BFS over block positions (long-packed {@link BlockPos} keys).
 * {@link ExecutionMode#MAIN_THREAD_BATCHED}: {@link #step} for explicit batches, or {@link #runToCompletionOnCurrentThread}
 * (on the server thread, shares {@link Pathfinder}'s per-tick step budget with sync A*). {@link ExecutionMode#BACKGROUND} runs
 * on an executor via {@link #start}'s {@code backgroundExecutor}.
 */
public final class BlockBfs {

    /** Sentinel: do not stop when a neighbor equals this packed position. */
    public static final long NO_EARLY_GOAL = Long.MIN_VALUE;

    public enum ExecutionMode {
        /** Run up to {@code batchSize} node expansions per {@link #step(int)} on the caller thread. */
        MAIN_THREAD_BATCHED,
        /** Run the full search on {@link #start}'s {@code backgroundExecutor}. */
        BACKGROUND
    }

    /**
     * Controls expansion. {@link #expandFrom} gates whether neighbors are considered;
     * {@link #canTraverseTo} filters each directed edge {@code from -> to}.
     */
    public interface EdgePolicy {
        /** If false, visitors still run for this node but no neighbors are enqueued. */
        default boolean expandFrom(ServerWorld world, long curLong, int distanceFromStart) {
            return true;
        }

        boolean canTraverseTo(ServerWorld world, long fromLong, long toLong, int fromDistance);
    }

    private final ServerWorld world;
    private final EdgePolicy edgePolicy;
    private final int maxVisited;
    private final ExecutionMode mode;
    private final Executor backgroundExecutor;
    private final Runnable onBackgroundComplete;

    private final Map<Long, Integer> dist = new HashMap<>();
    private final Queue<Long> queue = new ArrayDeque<>();
    private final List<BfsVisitor> visitors = new ArrayList<>();
    private final AbortChecker abortChecker;
    /** If not {@link #NO_EARLY_GOAL}, stop as soon as this packed position is seen as a neighbor (not necessarily enqueued). */
    private final long earlyGoalLong;
    private boolean earlyGoalNeighborHit;

    private boolean finished;
    /** Distance of the most recently dequeued node in this run, or -1 before the first {@link #step} poll. */
    private int lastPolledDistance = -1;

    /** If non-null and returns true after a node is visited, the search stops immediately (queue cleared). */
    @FunctionalInterface
    public interface AbortChecker {
        boolean shouldAbort(ServerWorld world, long posLong, int distanceFromStart);
    }

    private BlockBfs(
        ServerWorld world,
        EdgePolicy edgePolicy,
        int maxVisited,
        ExecutionMode mode,
        Executor backgroundExecutor,
        Runnable onBackgroundComplete,
        AbortChecker abortChecker,
        long earlyGoalLong
    ) {
        this.world = world;
        this.edgePolicy = edgePolicy;
        this.maxVisited = maxVisited;
        this.mode = mode;
        this.backgroundExecutor = backgroundExecutor;
        this.onBackgroundComplete = onBackgroundComplete;
        this.abortChecker = abortChecker;
        this.earlyGoalLong = earlyGoalLong;
    }

    /**
     * Starts BFS from {@code startLong}. Each dequeued node is passed to {@code visitors} with its distance from the start.
     * When {@code mode == BACKGROUND}, the full traversal runs on {@code backgroundExecutor}; then {@code onBackgroundComplete}
     * runs on that same thread (use {@code world.getServer().execute(...)} to resume on the server thread).
     */
    public static BlockBfs start(
        ServerWorld world,
        long startLong,
        EdgePolicy edgePolicy,
        int maxVisited,
        ExecutionMode mode,
        Executor backgroundExecutor,
        Runnable onBackgroundComplete,
        AbortChecker abortChecker,
        long earlyGoalLong,
        BfsVisitor... visitors
    ) {
        BlockBfs bfs = new BlockBfs(world, edgePolicy, maxVisited, mode, backgroundExecutor, onBackgroundComplete, abortChecker, earlyGoalLong);
        for (BfsVisitor v : visitors) {
            if (v != null) bfs.visitors.add(v);
        }
        bfs.dist.put(startLong, 0);
        bfs.queue.add(startLong);
        if (mode == ExecutionMode.BACKGROUND) {
            if (backgroundExecutor == null) {
                throw new IllegalArgumentException("BACKGROUND mode requires a non-null executor");
            }
            backgroundExecutor.execute(bfs::runToCompletion);
        }
        return bfs;
    }

    /**
     * Drain the queue on the caller thread. Only for {@link ExecutionMode#MAIN_THREAD_BATCHED}.
     * On the server thread, each BFS node dequeue consumes one unit from {@link Pathfinder}'s per-tick step pool
     * (same budget as {@link Pathfinder#tickSyncMainThreadPathWork}); when the pool is empty, the search resumes next tick via
     * {@link VoidClamMod#scheduleDelayed} and this method returns early with {@link #isFinished()} still false.
     * Do not read {@link #distances()} or assume completion until finished unless you control that continuation.
     * For a single call that must fully drain on the server thread, use {@link #runToCompletionIgnoringSyncMainThreadStepBudget()}.
     */
    public void runToCompletionOnCurrentThread() {
        if (mode == ExecutionMode.BACKGROUND) {
            throw new IllegalStateException("use executor completion callback for BACKGROUND mode");
        }
        MinecraftServer server = world.getServer();
        while (!finished) {
            if (server.isOnThread()) {
                if (Pathfinder.consumeSyncMainThreadStepsForBlockBfs(1) < 1) {
                    VoidClamMod.scheduleDelayed(world, 1, this::runToCompletionOnCurrentThread);
                    return;
                }
            }
            if (step(1)) {
                return;
            }
        }
    }

    /**
     * Drain immediately without participating in the per-tick main-thread step pool. Use when the caller must observe a
     * fully drained queue in one return (e.g. commands, {@link Pathfinder#buildVolatileReachabilityMap}).
     */
    public void runToCompletionIgnoringSyncMainThreadStepBudget() {
        if (mode == ExecutionMode.BACKGROUND) {
            throw new IllegalStateException("use executor completion callback for BACKGROUND mode");
        }
        step(Integer.MAX_VALUE);
    }

    /**
     * Dequeue and expand at most {@code batchSize} nodes. Each step: dequeue one position, notify visitors, enqueue valid neighbors.
     *
     * @return true when the BFS has finished (queue empty or visit cap reached)
     */
    public boolean step(int batchSize) {
        if (mode != ExecutionMode.BACKGROUND && finished) {
            return true;
        }
        if (mode == ExecutionMode.BACKGROUND) {
            return finished;
        }
        int budget = batchSize;
        while (budget > 0 && !queue.isEmpty() && dist.size() < maxVisited) {
            long cur = queue.poll();
            Integer dObj = dist.get(cur);
            int d = dObj != null ? dObj : 0;
            lastPolledDistance = d;
            for (BfsVisitor v : visitors) {
                v.visit(world, cur, d);
            }
            if (abortChecker != null && abortChecker.shouldAbort(world, cur, d)) {
                queue.clear();
                finished = true;
                return true;
            }
            if (edgePolicy.expandFrom(world, cur, d)) {
                expandNeighbors(cur, d);
            }
            budget--;
        }
        if (queue.isEmpty() || dist.size() >= maxVisited) {
            finished = true;
        }
        return finished;
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Clears the frontier and marks the search finished without visiting more cells. Distances and visitor state for
     * already-dequeued cells are unchanged (partial flood is valid for reach ranking when neighbors of all candidates are settled).
     */
    public void stopEarlyAsFinished() {
        if (mode == ExecutionMode.BACKGROUND) {
            throw new IllegalStateException("stopEarlyAsFinished is for MAIN_THREAD_BATCHED only");
        }
        queue.clear();
        finished = true;
    }

    /** Distinct positions assigned a distance (includes start). */
    public int visitedCount() {
        return dist.size();
    }

    public Map<Long, Integer> distances() {
        return dist;
    }

    /**
     * Distance assigned to the most recently dequeued node. Under FIFO 6-neighbor BFS, dequeue distances are
     * non-decreasing, so comparing this to the depth of the first “goal witness” dequeue tells when that layer is done.
     */
    public int getLastPolledDistance() {
        return lastPolledDistance;
    }

    /**
     * True if {@code earlyGoalLong != NO_EARLY_GOAL} and expansion touched that position as a neighbor
     * (matches pathfinding prepass: reachable without requiring the goal cell to be passable).
     */
    public boolean isEarlyGoalNeighborHit() {
        return earlyGoalNeighborHit;
    }

    private void runToCompletion() {
        try {
            while (!queue.isEmpty() && dist.size() < maxVisited) {
                long cur = queue.poll();
                Integer dObj = dist.get(cur);
                int d = dObj != null ? dObj : 0;
                lastPolledDistance = d;
                for (BfsVisitor v : visitors) {
                    v.visit(world, cur, d);
                }
                if (abortChecker != null && abortChecker.shouldAbort(world, cur, d)) {
                    queue.clear();
                    break;
                }
                if (edgePolicy.expandFrom(world, cur, d)) {
                    expandNeighbors(cur, d);
                }
            }
        } finally {
            finished = true;
            if (onBackgroundComplete != null) {
                onBackgroundComplete.run();
            }
        }
    }

    private void expandNeighbors(long fromLong, int fromDist) {
        BlockPos from = BlockPos.fromLong(fromLong);
        for (Cursor c : Pathfinder.xc) {
            if (dist.size() >= maxVisited) return;
            int nx = from.getX() + c.x;
            int ny = from.getY() + c.y;
            int nz = from.getZ() + c.z;
            long toLong = BlockPos.asLong(nx, ny, nz);
            if (earlyGoalLong != NO_EARLY_GOAL && toLong == earlyGoalLong) {
                earlyGoalNeighborHit = true;
                queue.clear();
                finished = true;
                return;
            }
            if (dist.containsKey(toLong)) continue;
            if (!edgePolicy.canTraverseTo(world, fromLong, toLong, fromDist)) continue;
            dist.put(toLong, fromDist + 1);
            queue.add(toLong);
        }
    }

    @FunctionalInterface
    public interface BfsVisitor {
        void visit(ServerWorld world, long posLong, int distanceFromStart);
    }

    /**
     * Multiple simultaneous BFS sources (e.g. one per clam), one merged distance map (shortest wins),
     * per-source visit caps, and global merged size cap. Same edge rule as omni pulse: only nether wart, chunk loaded.
     */
    public static final class MergedOmniBfsJob {
        private final ServerWorld world;
        private final List<SingleSource> sources;
        private final Map<BlockPos, Integer> mergedResult = new HashMap<>();
        private boolean done;

        public MergedOmniBfsJob(ServerWorld world, List<SingleSource> sources) {
            this.world = world;
            this.sources = sources;
            for (SingleSource s : sources) {
                BlockPos start = BlockPos.fromLong(s.seedLong).toImmutable();
                s.queue.add(start);
                s.dist.put(start, 0);
                s.resultCount = 1;
                mergedResult.put(start, 0);
            }
        }

        /**
         * Expand up to {@code maxNodes} neighbor-additions across all sources (same accounting as legacy omni job).
         *
         * @return number of expansions consumed (budget minus leftover)
         */
        public int step(int maxNodes, int totalMergedLimit) {
            if (mergedResult.size() >= totalMergedLimit) {
                done = true;
                return 0;
            }
            int remaining = maxNodes;
            for (SingleSource s : sources) {
                if (remaining <= 0 || mergedResult.size() >= totalMergedLimit) break;
                if (s.queue.isEmpty() || s.resultCount >= s.perClamLimit) continue;
                while (remaining > 0 && !s.queue.isEmpty() && s.resultCount < s.perClamLimit && mergedResult.size() < totalMergedLimit) {
                    BlockPos pos = s.queue.poll();
                    int d = s.dist.get(pos);
                    for (Direction dir : Direction.values()) {
                        BlockPos next = pos.offset(dir).toImmutable();
                        if (s.dist.containsKey(next)) continue;
                        if (!world.isChunkLoaded(next)) continue;
                        BlockState state = world.getBlockState(next);
                        if (!VoidClamCoreBlocks.isWartOrCore(state))
                            continue;
                        int nextDist = d + 1;
                        if (mergedResult.containsKey(next)) {
                            mergedResult.merge(next, nextDist, Math::min);
                            continue;
                        }
                        s.dist.put(next, nextDist);
                        s.resultCount++;
                        mergedResult.put(next, nextDist);
                        s.queue.add(next);
                        remaining--;
                        if (mergedResult.size() >= totalMergedLimit) break;
                    }
                }
            }
            boolean allDone = mergedResult.size() >= totalMergedLimit || sources.stream().allMatch(s ->
                s.queue.isEmpty() || s.resultCount >= s.perClamLimit);
            if (allDone) done = true;
            return maxNodes - remaining;
        }

        public boolean isDone() {
            return done;
        }

        public Map<BlockPos, Integer> getMergedResult() {
            return mergedResult;
        }

        public static final class SingleSource {
            final long seedLong;
            final int perClamLimit;
            final Queue<BlockPos> queue = new ArrayDeque<>();
            final Map<BlockPos, Integer> dist = new HashMap<>();
            int resultCount;

            public SingleSource(long seedLong, int perClamLimit) {
                this.seedLong = seedLong;
                this.perClamLimit = perClamLimit;
            }
        }
    }
}
