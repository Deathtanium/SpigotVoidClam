/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_1263
 *  net.minecraft.class_1792
 *  net.minecraft.class_1799
 *  net.minecraft.class_1802
 *  net.minecraft.class_1922
 *  net.minecraft.class_1935
 *  net.minecraft.class_1937
 *  net.minecraft.class_2246
 *  net.minecraft.class_2248
 *  net.minecraft.class_2338
 *  net.minecraft.class_2343
 *  net.minecraft.class_2350
 *  net.minecraft.class_2382
 *  net.minecraft.class_2586
 *  net.minecraft.class_2680
 *  net.minecraft.class_2769
 *  net.minecraft.class_3218
 *  net.minecraft.class_3417
 *  net.minecraft.class_3419
 *  net.minecraft.class_3708
 *  net.minecraft.class_3719
 */
package com.serbanstein.voidclam;

import com.serbanstein.voidclam.Cursor;
import com.serbanstein.voidclam.Module;
import com.serbanstein.voidclam.Node;
import com.serbanstein.voidclam.TendrilPulseManager;
import com.serbanstein.voidclam.VoidClamMod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.class_1263;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_1922;
import net.minecraft.class_1935;
import net.minecraft.class_1937;
import net.minecraft.class_2246;
import net.minecraft.class_2248;
import net.minecraft.class_2338;
import net.minecraft.class_2343;
import net.minecraft.class_2350;
import net.minecraft.class_2382;
import net.minecraft.class_2586;
import net.minecraft.class_2680;
import net.minecraft.class_2769;
import net.minecraft.class_3218;
import net.minecraft.class_3417;
import net.minecraft.class_3419;
import net.minecraft.class_3708;
import net.minecraft.class_3719;

public final class Pathfinder {
    static final List<Cursor> xc = new ArrayList<Cursor>();
    static final List<Cursor> yc = new ArrayList<Cursor>();
    private static final Map<class_2248, List<class_1799>> FORTUNE3_DROPS;

    public static Node leastF(List<Node> list) {
        double minf = 100000.0;
        Node mini = null;
        for (Node n : list) {
            if (!(n.f < minf)) continue;
            minf = n.f;
            mini = n;
        }
        return mini;
    }

    public static Node nodeExists(List<Node> list, Node firstNode) {
        if (list.isEmpty()) {
            return null;
        }
        for (Node n : list) {
            if (n.x != firstNode.x || n.y != firstNode.y || n.z != firstNode.z) continue;
            return n;
        }
        return null;
    }

    public static boolean calculatePath(class_3218 world, int tno, int sx, int sy, int sz, int gx, int gy, int gz) {
        if (!world.method_8393(sx >> 4, sz >> 4)) {
            return false;
        }
        ArrayList<Node> open = new ArrayList<Node>();
        ArrayList<Node> closed = new ArrayList<Node>();
        Node firstNode = new Node(sx, sy, sz, null, tno);
        firstNode.g = 0.0;
        firstNode.f = firstNode.h = Math.pow(firstNode.x - gx, 2.0) + Math.pow(firstNode.y - gy, 2.0) + Math.pow(firstNode.z - gz, 2.0);
        open.add(firstNode);
        Module[] modules = VoidClamMod.getModules();
        int moduleNumber = VoidClamMod.getModuleNumber();
        while (!open.isEmpty()) {
            Node nextCheapestNode = Pathfinder.leastF(open);
            open.remove(nextCheapestNode);
            for (Cursor c : xc) {
                double cst;
                Node nextNode = new Node(nextCheapestNode.x + c.x, nextCheapestNode.y + c.y, nextCheapestNode.z + c.z, nextCheapestNode, tno);
                if (nextNode.x == gx && nextNode.y == gy && nextNode.z == gz) {
                    VoidClamMod.enqueueTarget(nextNode);
                    return true;
                }
                class_2338 nextPos = new class_2338(nextNode.x, nextNode.y, nextNode.z);
                class_2680 bl = world.method_8320(nextPos);
                if (bl.method_27852(class_2246.field_10541) || bl.method_27852(class_2246.field_22115)) {
                    cst = 0.0;
                } else if (bl.method_26204() instanceof class_2343) {
                    cst = 2500.0;
                } else if (VoidClamMod.isUnbreakableByClam(bl.method_26204())) {
                    cst = 2500.0;
                } else if (Pathfinder.getHardness((class_1937)world, nextPos, bl) > 5.0f) {
                    cst = 2500.0;
                } else if (bl.method_27852(class_2246.field_10382) || Pathfinder.isAirLike(bl, (class_1937)world, nextPos) && Pathfinder.isSolid((class_1937)world, nextPos.method_10074())) {
                    cst = 1.0;
                } else if (Pathfinder.isAirLike(bl, (class_1937)world, nextPos)) {
                    int b = Pathfinder.countAdjacentNotWaterAirWart((class_1937)world, nextPos);
                    cst = 6 - b;
                } else {
                    cst = 10.0f + Pathfinder.getBlastResistance(bl);
                }
                nextNode.g = cst;
                nextNode.h = Math.abs(nextNode.x - gx) + Math.abs(nextNode.y - gy) + Math.abs(nextNode.z - gz);
                nextNode.f = nextNode.g + nextNode.h;
                Node tempNode1 = Pathfinder.nodeExists(open, nextNode);
                Node tempNode2 = Pathfinder.nodeExists(closed, nextNode);
                if (tno > moduleNumber || modules[tno] == null || Math.abs(nextNode.x - modules[tno].x) > 4 * modules[tno].currentSize || Math.abs(nextNode.y - modules[tno].y) > 5 * modules[tno].currentSize || Math.abs(nextNode.z - modules[tno].z) > 5 * modules[tno].currentSize || tempNode1 != null && !(tempNode1.f > nextNode.f) || tempNode2 != null && !(tempNode2.f > nextNode.f) || cst == 2500.0) continue;
                open.add(nextNode);
            }
            closed.add(nextCheapestNode);
        }
        return false;
    }

    private static float getHardness(class_1937 world, class_2338 pos, class_2680 state) {
        try {
            return state.method_26214((class_1922)world, pos);
        }
        catch (Exception e) {
            return 0.0f;
        }
    }

    private static float getBlastResistance(class_2680 state) {
        try {
            return state.method_26204().method_9520();
        }
        catch (Exception e) {
            return 0.0f;
        }
    }

    private static boolean isSolid(class_1937 world, class_2338 pos) {
        class_2680 state = world.method_8320(pos);
        if (state.method_27852(class_2246.field_10541) || state.method_27852(class_2246.field_22115)) {
            return false;
        }
        if (VoidClamMod.isBaseCost(state.method_26204())) {
            return false;
        }
        return Pathfinder.getHardness(world, pos, state) > 0.2f;
    }

    private static boolean isAirLike(class_2680 state, class_1937 world, class_2338 pos) {
        return VoidClamMod.isBaseCost(state.method_26204()) || Pathfinder.getHardness(world, pos, state) <= 0.2f;
    }

    private static boolean isWaterAirOrWart(class_2680 state) {
        return state.method_27852(class_2246.field_10382) || state.method_26215() || state.method_27852(class_2246.field_10541) || state.method_27852(class_2246.field_22115);
    }

    private static int countAdjacentNotWaterAirWart(class_1937 world, class_2338 pos) {
        int b = 0;
        for (Cursor c : xc) {
            if (Pathfinder.isWaterAirOrWart(world.method_8320(pos.method_10069(c.x, c.y, c.z)))) continue;
            ++b;
        }
        return b;
    }

    private static boolean isContainerBlock(class_2248 block) {
        return block == class_2246.field_10034 || block == class_2246.field_10380 || block == class_2246.field_16328;
    }

    private static void putFortune3(Map<class_2248, List<class_1799>> map, class_2248 block, class_1792 item, int count) {
        map.put(block, Collections.singletonList(new class_1799((class_1935)item, count)));
    }

    static List<class_1799> getFortune3Drops(class_2248 block) {
        List<class_1799> list = FORTUNE3_DROPS.get(block);
        return list != null ? list.stream().map(class_1799::method_7972).collect(Collectors.toList()) : new ArrayList<class_1799>();
    }

    private static Set<class_2338> findWartConnectedToClam(class_3218 world, class_2338 clamCenter, int cSize) {
        int cx = clamCenter.method_10263();
        int cy = clamCenter.method_10264();
        int cz = clamCenter.method_10260();
        int r = 4 * cSize;
        HashSet<class_2338> connected = new HashSet<class_2338>();
        ArrayDeque<class_2338> queue = new ArrayDeque<class_2338>();
        queue.add(clamCenter);
        connected.add(clamCenter.method_10062());
        while (!queue.isEmpty()) {
            class_2338 p = (class_2338)queue.poll();
            for (Cursor c : xc) {
                class_2248 b;
                class_2338 n = p.method_10069(c.x, c.y, c.z);
                if (n.method_10263() < cx - r || n.method_10263() > cx + r || n.method_10264() < cy - r || n.method_10264() > cy + r || n.method_10260() < cz - r || n.method_10260() > cz + r || connected.contains(n) || (b = world.method_8320(n).method_26204()) != class_2246.field_10541 && b != class_2246.field_22115) continue;
                connected.add(n.method_10062());
                queue.add(n);
            }
        }
        return connected;
    }

    private static List<Long> findContainersInBox(class_3218 world, class_2338 clamCenter, int cSize, class_2338 breakPos) {
        Set<class_2338> wartConnected = Pathfinder.findWartConnectedToClam(world, clamCenter, cSize);
        int cx = clamCenter.method_10263();
        int cy = clamCenter.method_10264();
        int cz = clamCenter.method_10260();
        int r = 4 * cSize;
        ArrayList<Long> containers = new ArrayList<Long>();
        for (int ix = cx - r; ix <= cx + r; ++ix) {
            for (int iy = cy - r; iy <= cy + r; ++iy) {
                for (int iz = cz - r; iz <= cz + r; ++iz) {
                    class_2338 pos = new class_2338(ix, iy, iz);
                    if (!Pathfinder.isContainerBlock(world.method_8320(pos).method_26204())) continue;
                    boolean adjacentToWart = false;
                    for (Cursor c : xc) {
                        if (!wartConnected.contains(pos.method_10069(c.x, c.y, c.z))) continue;
                        adjacentToWart = true;
                        break;
                    }
                    if (!adjacentToWart) continue;
                    containers.add(pos.method_10063());
                }
            }
        }
        containers.sort(Comparator.comparingDouble(l -> class_2338.method_10092((long)l).method_10262((class_2382)breakPos)));
        return containers;
    }

    private static void applyContainerResult(class_3218 world, List<Long> containerPositions, class_2338 breakPos, class_1799 toStore, boolean spawnDisplay, boolean replaceBlockWithWart) {
        for (long l : containerPositions) {
            if (toStore.method_7960()) break;
            Pathfinder.tryInsertInto(world, class_2338.method_10092((long)l), toStore);
        }
        if (!toStore.method_7960()) {
            Pathfinder.createBarrelAndInsert(world, breakPos, toStore);
        } else if (replaceBlockWithWart) {
            Pathfinder.replaceWithWartAndPulse(world, breakPos, spawnDisplay);
        }
    }

    private static void applyContainerResult(class_3218 world, List<Long> containerPositions, class_2338 breakPos, List<class_1799> toStoreList, boolean spawnDisplay, boolean replaceBlockWithWart) {
        for (long l : containerPositions) {
            boolean anyLeft = false;
            for (class_1799 stack : toStoreList) {
                if (stack.method_7960()) continue;
                Pathfinder.tryInsertInto(world, class_2338.method_10092((long)l), stack);
                anyLeft = anyLeft || !stack.method_7960();
            }
            if (anyLeft) continue;
            break;
        }
        ArrayList<class_1799> remainder = new ArrayList<class_1799>();
        for (class_1799 stack : toStoreList) {
            if (stack.method_7960()) continue;
            remainder.add(stack);
        }
        if (remainder.isEmpty()) {
            if (replaceBlockWithWart) {
                Pathfinder.replaceWithWartAndPulse(world, breakPos, spawnDisplay);
            }
        } else {
            Pathfinder.createBarrelAndInsert(world, breakPos, remainder);
        }
    }

    private static void replaceWithWartAndPulse(class_3218 world, class_2338 breakPos, boolean spawnDisplay) {
        int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, breakPos);
        world.method_8501(breakPos, class_2246.field_10541.method_9564());
        world.method_8396(null, breakPos, class_3417.field_14817, class_3419.field_15245, 1.0f, 0.01f);
        if (spawnDisplay) {
            TendrilPulseManager.startPulse(world, breakPos, packedBrightness, () -> {});
        }
    }

    private static boolean tryInsertInto(class_3218 world, class_2338 pos, class_1799 stack) {
        if (stack.method_7960()) {
            return true;
        }
        class_2586 be = world.method_8321(pos);
        if (!(be instanceof class_1263)) {
            return false;
        }
        class_1263 inv = (class_1263)be;
        int size = inv.method_5439();
        for (int i = 0; i < size && !stack.method_7960(); ++i) {
            int max;
            int canAdd;
            class_1799 inSlot = inv.method_5438(i);
            if (inSlot.method_7960()) {
                int toPut = Math.min(stack.method_7947(), inv.method_5444());
                class_1799 put = stack.method_7972();
                put.method_7939(toPut);
                inv.method_5447(i, put);
                stack.method_7934(toPut);
                continue;
            }
            if (!class_1799.method_7984((class_1799)inSlot, (class_1799)stack) || (canAdd = (max = Math.min(inv.method_5444(), inSlot.method_7914())) - inSlot.method_7947()) <= 0) continue;
            int toAdd = Math.min(canAdd, stack.method_7947());
            inSlot.method_7933(toAdd);
            stack.method_7934(toAdd);
        }
        if (be != null) {
            be.method_5431();
        }
        return stack.method_7960();
    }

    private static void createBarrelAndInsert(class_3218 world, class_2338 pos, class_1799 stack) {
        class_2680 barrelState = (class_2680)class_2246.field_16328.method_9564().method_11657((class_2769)class_3708.field_16320, (Comparable)class_2350.field_11043);
        world.method_8501(pos, barrelState);
        class_2586 be = world.method_8321(pos);
        if (be instanceof class_3719) {
            Pathfinder.tryInsertInto(world, pos, stack);
        } else {
            class_2248.method_9577((class_1937)world, (class_2338)pos, (class_1799)stack);
        }
    }

    private static void createBarrelAndInsert(class_3218 world, class_2338 pos, List<class_1799> stacks) {
        class_2680 barrelState = (class_2680)class_2246.field_16328.method_9564().method_11657((class_2769)class_3708.field_16320, (Comparable)class_2350.field_11043);
        world.method_8501(pos, barrelState);
        class_2586 be = world.method_8321(pos);
        if (be instanceof class_3719) {
            for (class_1799 stack : stacks) {
                if (stack.method_7960()) continue;
                Pathfinder.tryInsertInto(world, pos, stack);
            }
        } else {
            for (class_1799 stack : stacks) {
                if (stack.method_7960()) continue;
                class_2248.method_9577((class_1937)world, (class_2338)pos, (class_1799)stack);
            }
        }
    }

    public static void buildPath(class_3218 world, Node gnode) {
        if (gnode.f >= 2500.0) {
            return;
        }
        Module[] modules = VoidClamMod.getModules();
        Module mod = modules[gnode.tno];
        if (mod == null || !world.method_8393(mod.x >> 4, mod.z >> 4)) {
            return;
        }
        Node firstNode = gnode;
        Node copy = gnode;
        long timer = 2L;
        while (copy.parent != null) {
            timer += 2L;
            copy = copy.parent;
        }
        class_2338 goalPos = new class_2338(gnode.x, gnode.y, gnode.z);
        VoidClamMod.scheduleDelayed(world, timer, () -> {
            VoidClamMod.removeLightsBlackList(gnode.tno, goalPos);
            VoidClamMod.removeOresBlackList(gnode.tno, goalPos);
        });
        int[] stamina = new int[]{modules[gnode.tno].currentSize};
        int[] blocked = new int[1];
        int[] pathStopped = new int[1];
        while (firstNode.parent != null && blocked[0] == 0) {
            Node refNode = firstNode;
            long runAt = timer;
            int cSize = modules[gnode.tno].currentSize;
            VoidClamMod.scheduleDelayed(world, runAt, () -> {
                boolean isReplacingBlock;
                if (blocked[0] != 0 || pathStopped[0] != 0) {
                    return;
                }
                class_2338 pos = new class_2338(refNode.x, refNode.y, refNode.z);
                class_2680 mat = world.method_8320(pos);
                class_2338 clamCenter = new class_2338(mod.x, mod.y, mod.z);
                boolean spawnDisplay = VoidClamMod.hasPlayerWithinRange(world, clamCenter, 4 * cSize);
                int cst = mat.method_27852(class_2246.field_10541) ? 0 : (mat.method_26215() || mat.method_27852(class_2246.field_10382) || mat.method_27852(class_2246.field_10164) ? 1 : (int)Math.floor(Pathfinder.getHardness((class_1937)world, pos, mat)) * 2);
                if (refNode == gnode) {
                    cst = 0;
                }
                if (stamina[0] - cst < 0) {
                    blocked[0] = 1;
                    if (!(mat.method_26215() || mat.method_27852(class_2246.field_10382) || mat.method_27852(class_2246.field_10164))) {
                        VoidClamMod.addLightsBlackList(gnode.tno, goalPos);
                        VoidClamMod.addOresBlackList(gnode.tno, goalPos);
                    }
                    VoidClamMod.addEnergy(gnode.tno, -1);
                } else {
                    stamina[0] = stamina[0] - cst;
                }
                if (refNode == gnode && VoidClamMod.isOre(mat.method_26204())) {
                    pathStopped[0] = 1;
                    List<class_1799> drops = Pathfinder.getFortune3Drops(mat.method_26204());
                    if (drops.isEmpty()) {
                        Pathfinder.replaceWithWartAndPulse(world, pos, spawnDisplay);
                    } else {
                        class_2338 breakPos = pos.method_10062();
                        List<Long> containers = Pathfinder.findContainersInBox(world, clamCenter, cSize, breakPos);
                        Pathfinder.applyContainerResult(world, containers, breakPos, drops, spawnDisplay, true);
                    }
                    return;
                }
                boolean bl = isReplacingBlock = refNode != gnode && !mat.method_26215() && !mat.method_27852(class_2246.field_10382) && !mat.method_27852(class_2246.field_10164) && !mat.method_27852(class_2246.field_10541);
                if (isReplacingBlock && mat.method_26204().method_8389() != class_1802.field_8162) {
                    pathStopped[0] = 1;
                    class_1799 toStore = new class_1799((class_1935)mat.method_26204().method_8389(), 1);
                    class_2338 breakPos = pos.method_10062();
                    List<Long> containers = Pathfinder.findContainersInBox(world, clamCenter, cSize, breakPos);
                    Pathfinder.applyContainerResult(world, containers, breakPos, toStore, spawnDisplay, true);
                    return;
                }
                int packedBrightness = TendrilPulseManager.getPackedBrightnessAt(world, pos);
                world.method_8501(pos, class_2246.field_10541.method_9564());
                world.method_8396(null, pos, class_3417.field_14817, class_3419.field_15245, 1.0f, 0.01f);
                if (refNode == gnode && VoidClamMod.isLight(mat.method_26204())) {
                    VoidClamMod.addEnergy(gnode.tno, 1);
                    if (mat.method_26204() == class_2246.field_10327) {
                        class_1799 netherStar = new class_1799((class_1935)class_1802.field_8137, 1);
                        List<Long> containers = Pathfinder.findContainersInBox(world, clamCenter, cSize, pos.method_10062());
                        for (long l : containers) {
                            if (netherStar.method_7960()) break;
                            Pathfinder.tryInsertInto(world, class_2338.method_10092((long)l), netherStar);
                        }
                        if (!netherStar.method_7960()) {
                            Pathfinder.createBarrelAndInsert(world, pos.method_10062(), netherStar);
                        }
                    }
                }
                if (!(refNode == gnode || mat.method_26215() || mat.method_27852(class_2246.field_10382) || mat.method_27852(class_2246.field_10164) || mat.method_27852(class_2246.field_10541) || mat.method_26204().method_8389() == class_1802.field_8162)) {
                    class_2248.method_9577((class_1937)world, (class_2338)pos, (class_1799)new class_1799((class_1935)mat.method_26204().method_8389(), 1));
                }
                if (spawnDisplay) {
                    TendrilPulseManager.startPulse(world, pos, packedBrightness, () -> {});
                }
            });
            timer -= 2L;
            firstNode = firstNode.parent;
        }
    }

    static {
        xc.add(new Cursor(1, 0, 0));
        xc.add(new Cursor(-1, 0, 0));
        xc.add(new Cursor(0, 1, 0));
        xc.add(new Cursor(0, -1, 0));
        xc.add(new Cursor(0, 0, 1));
        xc.add(new Cursor(0, 0, -1));
        yc.add(new Cursor(1, 0, 0));
        yc.add(new Cursor(-1, 0, 0));
        yc.add(new Cursor(0, 1, 0));
        yc.add(new Cursor(0, -1, 0));
        yc.add(new Cursor(0, 0, 1));
        yc.add(new Cursor(0, 0, -1));
        FORTUNE3_DROPS = new HashMap<class_2248, List<class_1799>>();
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10418, class_1802.field_8713, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29219, class_1802.field_8713, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10442, class_1802.field_8477, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29029, class_1802.field_8477, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10013, class_1802.field_8687, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29220, class_1802.field_8687, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10571, class_1802.field_33402, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29026, class_1802.field_33402, 4);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_23077, class_1802.field_8397, 24);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10212, class_1802.field_33400, 1);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29027, class_1802.field_33400, 1);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_27120, class_1802.field_33401, 25);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29221, class_1802.field_33401, 25);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10090, class_1802.field_8759, 36);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29028, class_1802.field_8759, 36);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10080, class_1802.field_8725, 25);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_29030, class_1802.field_8725, 25);
        Pathfinder.putFortune3(FORTUNE3_DROPS, class_2246.field_10213, class_1802.field_8155, 4);
    }
}

