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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public final class Pathfinder {
    private static BlockBfs.AbortChecker asyncPathfindingAbortChecker(
        ServerWorld world,
        int clamCenterX,
        int clamCenterZ,
        int pathfindingModuleSlot
    ) {
        return (w, posLong, distanceFromStart) ->
            VoidClamMod.shouldAbortAsyncPathfindingWork(world, clamCenterX, clamCenterZ, pathfindingModuleSlot);
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
        double minf = 100_000;
        Node mini = null;
        for (Node n : list) {
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
    private static final int PATHFINDING_RANGE_XZ_HALF = 4;
    private static final int PATHFINDING_RANGE_Y_HALF = 5;
    private static final int PATHFINDING_RANGE_Z_HALF = 5;

    private static boolean isWithinPathfindingRange(int x, int y, int z, int cx, int cy, int cz, int cSize) {
        return Math.abs(x - cx) <= PATHFINDING_RANGE_XZ_HALF * cSize
            && Math.abs(y - cy) <= PATHFINDING_RANGE_Y_HALF * cSize
            && Math.abs(z - cz) <= PATHFINDING_RANGE_Z_HALF * cSize;
    }

    /**
     * True if this cell cannot be entered in A* (same condition as {@code cst == 2500} in {@link #calculatePath}).
     */
    private static boolean isPathfindCellImpassable(ServerWorld world, BlockPos pos) {
        BlockState bl = world.getBlockState(pos);
        if (bl.isOf(Blocks.NETHER_WART_BLOCK)) {
            return false;
        }
        if (bl.getBlock() instanceof BlockEntityProvider) {
            return true;
        }
        return getHardness(world, pos, bl) > 5;
    }

    private static boolean inPathfindSearchBounds(Module mod, int x, int y, int z) {
        return isWithinPathfindingRange(x, y, z, mod.x, mod.y, mod.z, mod.currentSize);
    }

    /**
     * 6-neighbor BFS from start within the same axis bounds as A*. Edges match A* impassability (cells with cost 2500 are walls).
     * Ignores movement costs; only detects hard disconnects so unreachable goals skip the expensive A* search.
     * <p>
     * Today {@link #calculatePath} runs from a worker thread ({@link CommandToolbox#submitPathfinding}). If this prepass is ever
     * invoked from the server main thread and proves too heavy for TPS, move it to the same executor pattern as A*.
     */
    private static boolean isGoalReachableByPrepass(
        ServerWorld world,
        int tno,
        int sx, int sy, int sz,
        int gx, int gy, int gz,
        Module mod,
        Module[] modules,
        int moduleNumber
    ) {
        if (sx == gx && sy == gy && sz == gz) {
            return true;
        }
        if (tno > moduleNumber || modules[tno] == null) {
            return false;
        }
        long goalLong = BlockPos.asLong(gx, gy, gz);
        long startLong = BlockPos.asLong(sx, sy, sz);
        BlockBfs.EdgePolicy prepassPolicy = (w, fromLong, toLong, fromDist) -> {
            BlockPos nextPos = BlockPos.fromLong(toLong);
            return inPathfindSearchBounds(mod, nextPos.getX(), nextPos.getY(), nextPos.getZ())
                && !isPathfindCellImpassable(w, nextPos);
        };
        BlockBfs bfs = BlockBfs.start(
            world,
            startLong,
            prepassPolicy,
            Integer.MAX_VALUE,
            BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED,
            null,
            null,
            asyncPathfindingAbortChecker(world, mod.x, mod.z, tno),
            goalLong
        );
        bfs.runToCompletionOnCurrentThread();
        return bfs.isEarlyGoalNeighborHit();
    }

    /** Cheaper than Euclidean: no sqrt, O(1). Not admissible when edge costs can be 0 (e.g. wart). */
    private static double manhattanH(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(y - gy) + Math.abs(z - gz);
    }

    public static boolean calculatePath(ServerWorld world, int tno, int sx, int sy, int sz, int gx, int gy, int gz) {
        if (!world.isChunkLoaded(sx >> 4, sz >> 4)) return false;
        Module[] modules = VoidClamMod.getModules();
        Module modForFlag = modules[tno];
        if (modForFlag == null) return false;
        int moduleNumber = VoidClamMod.getModuleNumber();
        if (!isGoalReachableByPrepass(world, tno, sx, sy, sz, gx, gy, gz, modForFlag, modules, moduleNumber)) {
            modForFlag.busyFlagMainCycle = 0;
            return false;
        }
        List<Node> open = new ArrayList<>();
        List<Node> closed = new ArrayList<>();

        Node firstNode = new Node(sx, sy, sz, null, tno);
        firstNode.g = 0;
        firstNode.h = manhattanH(sx, sy, sz, gx, gy, gz);
        firstNode.f = firstNode.g + firstNode.h;
        open.add(firstNode);

        long astarIterations = 0;
        while (!open.isEmpty()) {
            if ((astarIterations++ & 0x3FF) == 0 && VoidClamMod.shouldAbortAsyncPathfindingWork(world, modForFlag.x, modForFlag.z, tno)) {
                modForFlag.busyFlagMainCycle = 0;
                return false;
            }
            Node nextCheapestNode = leastF(open);
            open.remove(nextCheapestNode);

            for (Cursor c : xc) {
                int nx = nextCheapestNode.x + c.x;
                int ny = nextCheapestNode.y + c.y;
                int nz = nextCheapestNode.z + c.z;

                BlockPos nextPos = new BlockPos(nx, ny, nz);
                BlockState bl = world.getBlockState(nextPos);
                double cst;
                if (bl.isOf(Blocks.NETHER_WART_BLOCK)) {
                    cst = 0;
                } else if (bl.getBlock() instanceof BlockEntityProvider) {
                    cst = 2500; // tile entities are insurpassible
                } else if (getHardness(world, nextPos, bl) > 5) {
                    cst = 2500;
                } else if (bl.isOf(Blocks.WATER) || (isAirLike(bl, world, nextPos) && isSolid(world, nextPos.down()))) {
                    cst = 1;
                } else if (isAirLike(bl, world, nextPos)) {
                    int b = countAdjacentNotWaterAirWart(world, nextPos);
                    cst = 6 - b;
                } else {
                    cst = 10 + getBlastResistance(bl);
                }

                if (cst == 2500) {
                    continue;
                }

                Module pathMod = modules[tno];
                if (tno > moduleNumber || pathMod == null
                    || !isWithinPathfindingRange(nx, ny, nz, pathMod.x, pathMod.y, pathMod.z, pathMod.currentSize)) {
                    continue;
                }

                double tentativeG = nextCheapestNode.g + cst;
                Node probe = new Node(nx, ny, nz, nextCheapestNode, tno);
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

                Node nextNode = new Node(nx, ny, nz, nextCheapestNode, tno);
                nextNode.g = tentativeG;
                nextNode.h = manhattanH(nx, ny, nz, gx, gy, gz);
                nextNode.f = nextNode.g + nextNode.h;

                if (nx == gx && ny == gy && nz == gz) {
                    if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, modForFlag.x, modForFlag.z, tno)) {
                        modForFlag.busyFlagMainCycle = 0;
                        return false;
                    }
                    VoidClamMod.enqueueTarget(nextNode);
                    //we do not reset the busy flag here, because if CommandToolbox.clamReach was called, the reset is handled there
                    return true;
                }

                open.add(nextNode);
            }
            closed.add(nextCheapestNode);
        }

        modForFlag.busyFlagMainCycle = 0;
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
    private static boolean isSolid(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.NETHER_WART_BLOCK)) return false;
        if (VoidClamMod.isBaseCost(state.getBlock())) return false;
        return getHardness(world, pos, state) > 0.2f;
    }

    /** True if block is traversable without breaking (air, baseCost, or soft hardness). */
    private static boolean isAirLike(BlockState state, World world, BlockPos pos) {
        return VoidClamMod.isBaseCost(state.getBlock()) || getHardness(world, pos, state) <= 0.2f;
    }

    /** True if block is water, air, or nether wart (for adjacent count B). */
    private static boolean isWaterAirOrWart(BlockState state) {
        return state.isOf(Blocks.WATER) || state.isAir()
            || state.isOf(Blocks.NETHER_WART_BLOCK);
    }

    /** Number of adjacent blocks (6-neighborhood) that are not water/air/nether wart. */
    private static int countAdjacentNotWaterAirWart(World world, BlockPos pos) {
        int b = 0;
        for (Cursor c : xc) {
            if (!isWaterAirOrWart(world.getBlockState(pos.add(c.x, c.y, c.z)))) b++;
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
     * Appends container positions to {@code containersOut} via {@link BlockBfs}.
     *
     * @param executionMode {@link BlockBfs.ExecutionMode#MAIN_THREAD_BATCHED} drains on the current thread (e.g. inside
     *                      {@link CommandToolbox#submitPathfinding}); {@link BlockBfs.ExecutionMode#BACKGROUND} runs on {@code executor}
     *                      and invokes {@code onComplete} on that executor thread when done.
     */
    private static void runContainerBfsOnWorld(
        ServerWorld world,
        int cx,
        int cy,
        int cz,
        int cSize,
        long startLong,
        List<Long> containersOut,
        BlockBfs.ExecutionMode executionMode,
        Executor executor,
        Runnable onComplete,
        int pathfindingModuleSlot
    ) {
        BlockBfs.EdgePolicy containerPolicy = new BlockBfs.EdgePolicy() {
            @Override
            public boolean expandFrom(ServerWorld w, long curLong, int distanceFromStart) {
                if (curLong == startLong) return true;
                BlockState st = w.getBlockState(BlockPos.fromLong(curLong));
                return st.isOf(Blocks.NETHER_WART_BLOCK);
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
            executionMode,
            executor,
            onComplete,
            asyncPathfindingAbortChecker(world, cx, cz, pathfindingModuleSlot),
            BlockBfs.NO_EARLY_GOAL,
            (w, posLong, d) -> {
                BlockState state = w.getBlockState(BlockPos.fromLong(posLong));
                if (isContainerBlock(state.getBlock())) {
                    containersOut.add(posLong);
                }
            }
        );
        if (executionMode == BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED) {
            bfs.runToCompletionOnCurrentThread();
        }
    }

    private static void replaceWithWartAndPulse(ServerWorld world, BlockPos breakPos) {
        int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, breakPos);
        world.setBlockState(breakPos, Blocks.NETHER_WART_BLOCK.getDefaultState());
        world.playSound(null, breakPos, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
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
        Module[] modules = VoidClamMod.getModules();
        Module mod = modules[gnode.tno];
        if (mod == null) {
            return;
        }
        final int pathTno = gnode.tno;
        final int pathOriginX = mod.x;
        final int pathOriginY = mod.y;
        final int pathOriginZ = mod.z;
        final Module modForFlag = mod;
        if (gnode.f >= 2500) {
            modForFlag.busyFlagMainCycle = 0;
            return;
        }
        if (!world.isChunkLoaded(mod.x >> 4, mod.z >> 4)) {
            modForFlag.busyFlagMainCycle = 0;
            return;
        }
        // Skip if this goal was enqueued before seek flags were turned off
        BlockPos goalPos = new BlockPos(gnode.x, gnode.y, gnode.z);
        net.minecraft.block.Block goalBlock = world.getBlockState(goalPos).getBlock();
        if (VoidClamMod.isOre(goalBlock) && !mod.seekOres) {
            modForFlag.busyFlagMainCycle = 0;
            return;
        }
        if (VoidClamMod.isLight(goalBlock) && !mod.seekLights) {
            modForFlag.busyFlagMainCycle = 0;
            return;
        }
        Node firstNode = gnode;
        Node copy = gnode;
        long timer = 2;
        while (copy.parent != null) {
            timer += 2;
            copy = copy.parent;
        }

        VoidClamMod.scheduleDelayed(world, timer, () -> {
            if (!VoidClamMod.moduleAtSlotMatchesPosition(pathTno, pathOriginX, pathOriginY, pathOriginZ)) {
                return;
            }
            VoidClamMod.removeLightsBlackList(pathTno, goalPos);
            VoidClamMod.removeOresBlackList(pathTno, goalPos);
        });

        int[] stamina = new int[]{modules[pathTno].currentSize};
        int[] blocked = new int[1];
        int[] pathStopped = new int[1]; // set when block-to-break: path stops, no energy, resume next attempt
        int[] pathStoppedAwaitingContainer = new int[1]; // 1 while off-thread container BFS + apply not finished; keeps busy, suppresses stale path steps

        while (firstNode.parent != null && blocked[0] == 0) {
            final Node refNode = firstNode;
            final long runAt = timer;
            final int cSize = modules[pathTno].currentSize;
            VoidClamMod.scheduleDelayed(world, runAt, () -> {
                if (!VoidClamMod.moduleAtSlotMatchesPosition(pathTno, pathOriginX, pathOriginY, pathOriginZ)) {
                    return;
                }
                if (pathStopped[0] != 0 && pathStoppedAwaitingContainer[0] != 0) {
                    return;
                }
                if (blocked[0] != 0 || pathStopped[0] != 0) {
                    modForFlag.busyFlagMainCycle = 0;
                    return;
                }
                BlockPos pos = new BlockPos(refNode.x, refNode.y, refNode.z);
                BlockState mat = world.getBlockState(pos);
                int cst;
                if (mat.isOf(Blocks.NETHER_WART_BLOCK)) cst = 0;
                else if (mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA)) cst = 1;
                else cst = (int) Math.floor(getHardness(world, pos, mat)) * 2;
                if (refNode == gnode) cst = 0;

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
                            pathTno,
                            () -> modForFlag.busyFlagMainCycle = 0,
                            () -> {
                                List<Long> containers = new ArrayList<>();
                                runContainerBfsOnWorld(
                                    world, mx, my, mz, cSize, clamCenterLong, containers,
                                    BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED, null, null, pathTno);
                                world.getServer().execute(() -> {
                                    if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, mod.x, mod.z, pathTno)) {
                                        modForFlag.busyFlagMainCycle = 0;
                                        return;
                                    }
                                    applyContainerResult(world, containers, breakPos, drops);
                                    modForFlag.busyFlagMainCycle = 0;
                                });
                            }
                        );
                    } else {
                        replaceWithWartAndPulse(world, pos);
                        modForFlag.busyFlagMainCycle = 0;
                    }
                    return;
                }

                if (stamina[0] - cst < 0) {
                    blocked[0] = 1;
                    if (!(mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA))) {
                        VoidClamMod.addLightsBlackList(pathTno, goalPos);
                        VoidClamMod.addOresBlackList(pathTno, goalPos);
                    }
                    VoidClamMod.addEnergy(pathTno, -1);
                } else {
                    stamina[0] -= cst;
                }

                boolean isReplacingBlock = !(refNode == gnode || mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA) || mat.isOf(Blocks.NETHER_WART_BLOCK));
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
                        pathTno,
                        () -> {
                            pathStoppedAwaitingContainer[0] = 0;
                            modForFlag.busyFlagMainCycle = 0;
                        },
                        () -> {
                            List<Long> containers = new ArrayList<>();
                            runContainerBfsOnWorld(
                                world, mx, my, mz, cSize, clamCenterLong, containers,
                                BlockBfs.ExecutionMode.MAIN_THREAD_BATCHED, null, null, pathTno);
                            world.getServer().execute(() -> {
                                if (VoidClamMod.shouldAbortAsyncPathfindingWork(world, mod.x, mod.z, pathTno)) {
                                    pathStoppedAwaitingContainer[0] = 0;
                                    modForFlag.busyFlagMainCycle = 0;
                                    return;
                                }
                                applyContainerResult(world, containers, breakPos, toStore);
                                pathStoppedAwaitingContainer[0] = 0;
                                modForFlag.busyFlagMainCycle = 0;
                            });
                        }
                    );
                    return;
                }

                int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, pos);
                world.setBlockState(pos, Blocks.NETHER_WART_BLOCK.getDefaultState());
                world.playSound(null, pos, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
                if (refNode == gnode && VoidClamMod.isLight(mat.getBlock()))
                    VoidClamMod.addEnergy(pathTno, 1); // energy only when light source is eaten
                if (!(refNode == gnode || mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA) || mat.isOf(Blocks.NETHER_WART_BLOCK))) {
                    if (mat.getBlock().asItem() != Items.AIR)
                        net.minecraft.block.Block.dropStack(world, pos, new ItemStack(mat.getBlock().asItem(), 1));
                }
                TendrilPulseManager.startPulse(world, pos, packedBrightness, () -> {});
                if (refNode == gnode)
                    modForFlag.busyFlagMainCycle = 0; // light or empty goal: path done
            });
            timer -= 2;
            firstNode = firstNode.parent;
        }
    }
}
