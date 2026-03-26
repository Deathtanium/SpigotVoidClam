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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class Pathfinder {
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

    public static boolean calculatePath(ServerWorld world, int tno, int sx, int sy, int sz, int gx, int gy, int gz) {
        if (!world.isChunkLoaded(sx >> 4, sz >> 4)) return false;
        Module[] modules = VoidClamMod.getModules();
        Module modForFlag = modules[tno];
        if (modForFlag == null) return false;
        List<Node> open = new ArrayList<>();
        List<Node> closed = new ArrayList<>();

        Node firstNode = new Node(sx, sy, sz, null, tno);
        firstNode.g = 0;
        firstNode.h = Math.pow(firstNode.x - gx, 2) + Math.pow(firstNode.y - gy, 2) + Math.pow(firstNode.z - gz, 2);
        firstNode.f = firstNode.h;
        open.add(firstNode);

        int moduleNumber = VoidClamMod.getModuleNumber();

        while (!open.isEmpty()) {
            Node nextCheapestNode = leastF(open);
            open.remove(nextCheapestNode);

            for (Cursor c : xc) {
                Node nextNode = new Node(
                    nextCheapestNode.x + c.x,
                    nextCheapestNode.y + c.y,
                    nextCheapestNode.z + c.z,
                    nextCheapestNode,
                    tno
                );

                if (nextNode.x == gx && nextNode.y == gy && nextNode.z == gz) {
                    VoidClamMod.enqueueTarget(nextNode);
                    //we do not reset the busy flag here, because if CommandToolbox.clamReach was called, the reset is handled there
                    return true;
                }

                BlockPos nextPos = new BlockPos(nextNode.x, nextNode.y, nextNode.z);
                BlockState bl = world.getBlockState(nextPos);
                double cst;
                if (bl.isOf(Blocks.NETHER_WART_BLOCK) || bl.isOf(Blocks.WARPED_WART_BLOCK)) {
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

                nextNode.g = cst;
                nextNode.h = Math.abs(nextNode.x - gx) + Math.abs(nextNode.y - gy) + Math.abs(nextNode.z - gz);
                nextNode.f = nextNode.g + nextNode.h;

                Node tempNode1 = nodeExists(open, nextNode);
                Node tempNode2 = nodeExists(closed, nextNode);
                if (tno <= moduleNumber && modules[tno] != null &&
                    !(Math.abs(nextNode.x - modules[tno].x) > 4 * modules[tno].currentSize
                        || Math.abs(nextNode.y - modules[tno].y) > 5 * modules[tno].currentSize
                        || Math.abs(nextNode.z - modules[tno].z) > 5 * modules[tno].currentSize) &&
                    (tempNode1 == null || tempNode1.f > nextNode.f) &&
                    (tempNode2 == null || tempNode2.f > nextNode.f) &&
                    cst != 2500) {
                    open.add(nextNode);
                }
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
        if (state.isOf(Blocks.NETHER_WART_BLOCK) || state.isOf(Blocks.WARPED_WART_BLOCK)) return false;
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
            || state.isOf(Blocks.NETHER_WART_BLOCK) || state.isOf(Blocks.WARPED_WART_BLOCK);
    }

    /** Number of adjacent blocks (6-neighborhood) that are not water/air/nether wart. */
    private static int countAdjacentNotWaterAirWart(World world, BlockPos pos) {
        int b = 0;
        for (Cursor c : xc) {
            if (!isWaterAirOrWart(world.getBlockState(pos.add(c.x, c.y, c.z)))) b++;
        }
        return b;
    }

    // --- Container logic: off-thread BFS, max range = light search radius (capped by step limit) ---
    private static final int CONTAINER_SNAPSHOT_MAX_STEPS = 4096;
    private static final byte TYPE_OTHER = 0;
    private static final byte TYPE_NETHER_WART = 1;
    private static final byte TYPE_WARPED_WART = 2;
    private static final byte TYPE_CONTAINER = 3;
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private static boolean isContainerBlock(net.minecraft.block.Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
    }

    /**
     * Build snapshot on main thread: BFS from module center (clam core), capped by max steps. Returns (pos -> type).
     *
     * @param cSize {@link Module#currentSize} at scheduling time; reserved if snapshot bounds are tied to clam size later (unused today).
     */
    private static Map<Long, Byte> buildContainerSnapshot(ServerWorld world, BlockPos start, int cSize) {
        int maxSteps = CONTAINER_SNAPSHOT_MAX_STEPS; // light search radius volume is huge; cap to avoid main-thread hang
        Map<Long, Byte> map = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        long startLong = start.asLong();
        queue.add(startLong);
        seen.add(startLong);
        int steps = 0;
        while (!queue.isEmpty() && steps < maxSteps) {
            long cur = queue.poll();
            steps++;
            BlockPos pos = BlockPos.fromLong(cur);
            BlockState state = world.getBlockState(pos);
            byte type;
            if (state.isOf(Blocks.NETHER_WART_BLOCK)) type = TYPE_NETHER_WART;
            else if (state.isOf(Blocks.WARPED_WART_BLOCK)) type = TYPE_WARPED_WART;
            else if (isContainerBlock(state.getBlock())) type = TYPE_CONTAINER;
            else type = TYPE_OTHER;
            map.put(cur, type);
            // From BFS root (clam center) we can step to neighbors even if that block isn't wart; else only step from wart
            if (type != TYPE_NETHER_WART && type != TYPE_WARPED_WART && cur != startLong) continue;
            for (int i = 0; i < 6; i++) {
                long next = BlockPos.fromLong(cur).add(DX[i], DY[i], DZ[i]).asLong();
                if (seen.add(next)) queue.add(next);
            }
        }
        return map;
    }

    /** Runs off-thread: BFS on snapshot from start, returns container positions in BFS order. */
    private static List<Long> runContainerBfsOnSnapshot(Map<Long, Byte> snapshot, long startLong) {
        List<Long> containers = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(startLong);
        seen.add(startLong);
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            Byte type = snapshot.get(cur);
            if (type == null) continue;
            if (type == TYPE_CONTAINER) containers.add(cur);
            // From BFS root (clam center) we can step to neighbors; else only step from wart
            if (type != TYPE_NETHER_WART && type != TYPE_WARPED_WART && cur != startLong) continue;
            BlockPos pos = BlockPos.fromLong(cur);
            for (int i = 0; i < 6; i++) {
                long next = pos.add(DX[i], DY[i], DZ[i]).asLong();
                if (snapshot.containsKey(next) && seen.add(next)) queue.add(next);
            }
        }
        return containers;
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
            VoidClamMod.removeLightsBlackList(gnode.tno, goalPos);
            VoidClamMod.removeOresBlackList(gnode.tno, goalPos);
        });

        int[] stamina = new int[]{modules[gnode.tno].currentSize};
        int[] blocked = new int[1];
        int[] pathStopped = new int[1]; // set when block-to-break: path stops, no energy, resume next attempt

        while (firstNode.parent != null && blocked[0] == 0) {
            final Node refNode = firstNode;
            final long runAt = timer;
            final int cSize = modules[gnode.tno].currentSize;
            VoidClamMod.scheduleDelayed(world, runAt, () -> {
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
                        BlockPos clamCenter = new BlockPos(mod.x, mod.y, mod.z);
                        Map<Long, Byte> snapshot = buildContainerSnapshot(world, clamCenter, cSize);
                        long clamCenterLong = clamCenter.asLong();
                        CommandToolbox.submitPathfinding(() -> {
                            List<Long> containers = runContainerBfsOnSnapshot(snapshot, clamCenterLong);
                            world.getServer().execute(() -> applyContainerResult(world, containers, breakPos, drops));
                        });
                    } else {
                        replaceWithWartAndPulse(world, pos);
                    }
                    modForFlag.busyFlagMainCycle = 0;
                    return;
                }

                if (stamina[0] - cst < 0) {
                    blocked[0] = 1;
                    if (!(mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA))) {
                        VoidClamMod.addLightsBlackList(gnode.tno, goalPos);
                        VoidClamMod.addOresBlackList(gnode.tno, goalPos);
                    }
                    VoidClamMod.addEnergy(gnode.tno, -1);
                } else {
                    stamina[0] -= cst;
                }

                boolean isReplacingBlock = !(refNode == gnode || mat.isAir() || mat.isOf(Blocks.WATER) || mat.isOf(Blocks.LAVA) || mat.isOf(Blocks.NETHER_WART_BLOCK));
                if (isReplacingBlock && mat.getBlock().asItem() != Items.AIR) {
                    pathStopped[0] = 1; // path stops; clam does not get energy; resume next attempt
                    ItemStack toStore = new ItemStack(mat.getBlock().asItem(), 1);
                    BlockPos clamCenter = new BlockPos(mod.x, mod.y, mod.z);
                    Map<Long, Byte> snapshot = buildContainerSnapshot(world, clamCenter, cSize);
                    BlockPos breakPos = pos.toImmutable();
                    long clamCenterLong = clamCenter.asLong();
                    CommandToolbox.submitPathfinding(() -> {
                        List<Long> containers = runContainerBfsOnSnapshot(snapshot, clamCenterLong);
                        world.getServer().execute(() -> applyContainerResult(world, containers, breakPos, toStore));
                    });
                    modForFlag.busyFlagMainCycle = 0;
                    return;
                }

                int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, pos);
                world.setBlockState(pos, Blocks.NETHER_WART_BLOCK.getDefaultState());
                world.playSound(null, pos, SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 1f, 0.01f);
                if (refNode == gnode && VoidClamMod.isLight(mat.getBlock()))
                    VoidClamMod.addEnergy(gnode.tno, 1); // energy only when light source is eaten
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
