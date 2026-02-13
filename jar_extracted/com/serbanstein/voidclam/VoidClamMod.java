/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_1293
 *  net.minecraft.class_1294
 *  net.minecraft.class_1309
 *  net.minecraft.class_1588
 *  net.minecraft.class_2246
 *  net.minecraft.class_2248
 *  net.minecraft.class_2338
 *  net.minecraft.class_2350
 *  net.minecraft.class_238
 *  net.minecraft.class_2382
 *  net.minecraft.class_2394
 *  net.minecraft.class_2398
 *  net.minecraft.class_243
 *  net.minecraft.class_2596
 *  net.minecraft.class_2675
 *  net.minecraft.class_2680
 *  net.minecraft.class_3218
 *  net.minecraft.class_3222
 *  net.minecraft.class_3414
 *  net.minecraft.class_3417
 *  net.minecraft.class_3419
 *  net.minecraft.class_5218
 *  net.minecraft.class_6880$class_6883
 *  net.minecraft.class_7444
 *  net.minecraft.class_7445
 *  net.minecraft.class_7924
 *  net.minecraft.server.MinecraftServer
 */
package com.serbanstein.voidclam;

import com.serbanstein.voidclam.CommandToolbox;
import com.serbanstein.voidclam.Module;
import com.serbanstein.voidclam.Node;
import com.serbanstein.voidclam.Pathfinder;
import com.serbanstein.voidclam.VoidClamModScheduler;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Queue;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.class_1293;
import net.minecraft.class_1294;
import net.minecraft.class_1309;
import net.minecraft.class_1588;
import net.minecraft.class_2246;
import net.minecraft.class_2248;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_238;
import net.minecraft.class_2382;
import net.minecraft.class_2394;
import net.minecraft.class_2398;
import net.minecraft.class_243;
import net.minecraft.class_2596;
import net.minecraft.class_2675;
import net.minecraft.class_2680;
import net.minecraft.class_3218;
import net.minecraft.class_3222;
import net.minecraft.class_3414;
import net.minecraft.class_3417;
import net.minecraft.class_3419;
import net.minecraft.class_5218;
import net.minecraft.class_6880;
import net.minecraft.class_7444;
import net.minecraft.class_7445;
import net.minecraft.class_7924;
import net.minecraft.server.MinecraftServer;

public final class VoidClamMod {
    private static final int MAX_MODULES = 1001;
    private static Module[] modules = new Module[1001];
    private static int moduleNumber = 0;
    private static final Queue<Node> targets = new ConcurrentLinkedQueue<Node>();
    private static final Set<class_2248> lights = new HashSet<class_2248>();
    private static final Set<class_2248> ores = new HashSet<class_2248>();
    private static final Set<class_2248> baseCost = new HashSet<class_2248>();
    private static final Set<class_2248> unbreakableByClam = new HashSet<class_2248>();
    private static final int DEFENSE_MIN_SIZE = 11;
    private static final int DEFENSE_HUNGER_SEC = 10;
    private static final int DEFENSE_FATIGUE_AMP = 1;
    private static final float DEFENSE_HORN_PITCH = 0.5f;
    private static final int MOB_EFFECT_DURATION = 40;
    private static final double FLAME_ABOVE_HEAD = 0.25;

    public static Module[] getModules() {
        return modules;
    }

    public static int getModuleNumber() {
        return moduleNumber;
    }

    public static boolean isLight(class_2248 block) {
        return lights.contains(block);
    }

    public static boolean isOre(class_2248 block) {
        return ores.contains(block);
    }

    public static boolean isBaseCost(class_2248 block) {
        return baseCost.contains(block);
    }

    public static boolean isUnbreakableByClam(class_2248 block) {
        return unbreakableByClam.contains(block);
    }

    public static boolean hasPlayerWithinRange(class_3218 world, class_2338 center, int range) {
        double maxDistSq = (double)range * (double)range;
        for (class_3222 player : world.method_18456()) {
            double distSq;
            if (player.method_7325() || !((distSq = player.method_5649((double)center.method_10263() + 0.5, (double)center.method_10264() + 0.5, (double)center.method_10260() + 0.5)) <= maxDistSq)) continue;
            return true;
        }
        return false;
    }

    public static boolean isModuleInLoadedChunk(class_3218 world, int tno) {
        if (tno < 1 || tno > moduleNumber || modules[tno] == null) {
            return false;
        }
        Module m = modules[tno];
        return world.method_8393(m.x >> 4, m.z >> 4);
    }

    public static void enqueueTarget(Node node) {
        targets.offer(node);
    }

    public static void removeLightsBlackList(int tno, class_2338 pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null) {
            VoidClamMod.modules[tno].lightsBlackList.remove(pos);
        }
    }

    public static void addLightsBlackList(int tno, class_2338 pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null) {
            VoidClamMod.modules[tno].lightsBlackList.add(pos.method_10062());
        }
    }

    public static void removeOresBlackList(int tno, class_2338 pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null) {
            VoidClamMod.modules[tno].oresBlackList.remove(pos);
        }
    }

    public static void addOresBlackList(int tno, class_2338 pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null) {
            VoidClamMod.modules[tno].oresBlackList.add(pos.method_10062());
        }
    }

    public static void addEnergy(int tno, int delta) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null) {
            VoidClamMod.modules[tno].energy = Math.max(0, VoidClamMod.modules[tno].energy + delta);
        }
    }

    public static void scheduleDelayed(class_3218 world, long delayTicks, Runnable run) {
        VoidClamModScheduler.schedule(world, delayTicks, run);
    }

    public static void tickTargets(class_3218 world) {
        Node n;
        while ((n = targets.poll()) != null) {
            Pathfinder.buildPath(world, n);
        }
    }

    public static void load(MinecraftServer server) {
        Path savePath = VoidClamMod.getModulesPath(server);
        modules = new Module[1001];
        moduleNumber = 0;
        if (!Files.exists(savePath, new LinkOption[0])) {
            return;
        }
        try (Scanner s = new Scanner(Files.newInputStream(savePath, new OpenOption[0]));){
            while (s.hasNextLine()) {
                String[] parts;
                String line = s.nextLine().trim();
                if (line.isEmpty() || (parts = line.split(",", -1)).length < 8) continue;
                if (++moduleNumber >= 1001) {
                    break;
                }
                Module m = new Module();
                m.type = Integer.parseInt(parts[0]);
                m.x = Integer.parseInt(parts[1]);
                m.y = Integer.parseInt(parts[2]);
                m.z = Integer.parseInt(parts[3]);
                m.currentSize = Integer.parseInt(parts[4]);
                m.status = Integer.parseInt(parts[5]);
                m.energy = Integer.parseInt(parts[6]);
                m.age = Integer.parseInt(parts[7]);
                m.seekLights = parts.length > 8 && Boolean.parseBoolean(parts[8]);
                m.seekOres = parts.length > 9 && Boolean.parseBoolean(parts[9]);
                m.mobEffect = parts.length > 10 ? Math.max(0, Math.min(2, Integer.parseInt(parts[10]))) : 0;
                VoidClamMod.modules[VoidClamMod.moduleNumber] = m;
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    public static void save(MinecraftServer server) {
        Path path = VoidClamMod.getModulesPath(server);
        Path oldPath = path.getParent().resolve("modules.siva.old");
        try {
            Files.deleteIfExists(oldPath);
            if (Files.exists(path, new LinkOption[0])) {
                Files.move(path, oldPath, new CopyOption[0]);
            }
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, new OpenOption[0]));){
                for (int i = 1; i <= moduleNumber; ++i) {
                    Module m = modules[i];
                    if (m == null) continue;
                    out.println(m.type + "," + m.x + "," + m.y + "," + m.z + "," + m.currentSize + "," + m.status + "," + m.energy + "," + m.age + "," + m.seekLights + "," + m.seekOres + "," + m.mobEffect);
                }
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private static Path getModulesPath(MinecraftServer server) {
        return server.method_27050(class_5218.field_24188).resolve("modules.siva");
    }

    public static int makeStub(class_3218 world, int x, int y, int z) {
        if (++moduleNumber >= 1001) {
            --moduleNumber;
            return -1;
        }
        Module m = new Module();
        m.type = 1;
        m.x = x;
        m.y = y;
        m.z = z;
        m.currentSize = 1;
        m.status = 1;
        m.energy = 0;
        m.age = 0;
        VoidClamMod.modules[VoidClamMod.moduleNumber] = m;
        CommandToolbox.buildStub(world, x, y, z);
        VoidClamMod.save(world.method_8503());
        return moduleNumber;
    }

    public static void clamKill(int tno) {
        if (tno < 1 || tno > moduleNumber) {
            return;
        }
        for (int i = tno; i < moduleNumber; ++i) {
            Module swap = modules[i];
            VoidClamMod.modules[i] = modules[i + 1];
            VoidClamMod.modules[i + 1] = swap;
        }
        VoidClamMod.modules[VoidClamMod.moduleNumber] = null;
        --moduleNumber;
    }

    public static void tickAutoRepairAndGrow(class_3218 world) {
        Module[] modules = VoidClamMod.getModules();
        for (int i = 1; i <= moduleNumber; ++i) {
            Module m = modules[i];
            if (m == null || !world.method_8393(m.x >> 4, m.z >> 4)) continue;
            int x = m.x;
            int y = m.y;
            int z = m.z;
            int csize = m.currentSize;
            CommandToolbox.clamReSize(world, i, m.currentSize);
            m.lightsBlackList.clear();
            m.oresBlackList.clear();
            if (m.energy <= 4 * m.currentSize || m.currentSize >= 15) continue;
            double cst = 0.0;
            boolean hasRoom = true;
            for (int ix = x - csize + 2; ix <= x + csize - 2; ++ix) {
                for (int iz = z - csize + 2; iz <= z + csize - 2; ++iz) {
                    for (int iy = y - 2; iy <= y + csize / 2 + 2; ++iy) {
                        class_2680 state = world.method_8320(new class_2338(ix, iy, iz));
                        class_2248 b = state.method_26204();
                        if (b == class_2246.field_10124 || b == class_2246.field_10382 || b == class_2246.field_10164 || b == class_2246.field_10540 || b == class_2246.field_10541 || b == class_2246.field_22115) continue;
                        float br = b.method_9520();
                        if (br < 0.0f) {
                            hasRoom = false;
                            continue;
                        }
                        cst += (double)br;
                    }
                }
            }
            if (cst > (double)(10 * csize)) {
                hasRoom = false;
            }
            if (!hasRoom) continue;
            m.energy = 0;
            CommandToolbox.clamReSize(world, i, m.currentSize + 2);
            m.currentSize += 2;
        }
        VoidClamMod.save(world.method_8503());
    }

    public static void tickCoreCheck(class_3218 world) {
        Module[] modules = VoidClamMod.getModules();
        for (int i = moduleNumber; i >= 1; --i) {
            class_2248 block;
            Module m = modules[i];
            if (m == null || !world.method_8393(m.x >> 4, m.z >> 4) || (block = world.method_8320(new class_2338(m.x, m.y, m.z)).method_26204()) == class_2246.field_10541 || block == class_2246.field_10540) continue;
            VoidClamMod.clamKill(i);
        }
    }

    public static void tickDefense(class_3218 world) {
        Module[] modules = VoidClamMod.getModules();
        for (int i = 1; i <= moduleNumber; ++i) {
            Module m = modules[i];
            if (m == null || m.currentSize < 11 || !world.method_8393(m.x >> 4, m.z >> 4)) continue;
            float volume = (float)m.currentSize / 4.0f;
            class_3414 dreamHorn = null;
            try {
                class_6880.class_6883 entry = world.method_30349().method_30530(class_7924.field_41275).method_46747(class_7445.field_39132);
                dreamHorn = (class_3414)((class_7444)entry.comp_349()).comp_772().comp_349();
            }
            catch (Exception exception) {
                // empty catch block
            }
            if (dreamHorn == null) {
                dreamHorn = (class_3414)class_3417.field_14624.comp_349();
            }
            for (class_3222 player : world.method_18456()) {
                if (!CommandToolbox.isPlayerTooCloseToModule(player, m) || "serbanstein".equalsIgnoreCase(player.method_5477().getString())) continue;
                class_2338 playerBlock = player.method_24515();
                for (class_2350 d : class_2350.values()) {
                    class_2338 adj = playerBlock.method_10093(d);
                    if (!world.method_8320(adj).method_45474() && !world.method_8320(adj).method_26215()) continue;
                    world.method_8501(adj, class_2246.field_10541.method_9564());
                }
                world.method_43128(null, (double)playerBlock.method_10263() + 0.5, (double)playerBlock.method_10264() + 0.5, (double)playerBlock.method_10260() + 0.5, dreamHorn, class_3419.field_15251, volume, 0.5f);
                player.method_6092(new class_1293(class_1294.field_5903, 200, 0));
                player.method_6092(new class_1293(class_1294.field_5901, 200, 1));
            }
        }
    }

    public static boolean isHostileSpawnBlocked(class_3218 world, class_2338 pos) {
        Module[] modules = VoidClamMod.getModules();
        int px = pos.method_10263();
        int py = pos.method_10264();
        int pz = pos.method_10260();
        for (int i = 1; i <= moduleNumber; ++i) {
            int r;
            Module m = modules[i];
            if (m == null || m.mobEffect != 1 || !world.method_8393(m.x >> 4, m.z >> 4) || px < m.x - (r = 4 * m.currentSize) || px > m.x + r || py < m.y - r || py > m.y + r || pz < m.z - r || pz > m.z + r) continue;
            return true;
        }
        return false;
    }

    public static void tickMobEffect(class_3218 world) {
        Module[] modules = VoidClamMod.getModules();
        for (int i = 1; i <= moduleNumber; ++i) {
            Module m = modules[i];
            if (m == null || !world.method_8393(m.x >> 4, m.z >> 4)) continue;
            int r = 4 * m.currentSize;
            class_238 box = new class_238((double)(m.x - r), (double)(m.y - r), (double)(m.z - r), (double)(m.x + r + 1), (double)(m.y + r + 1), (double)(m.z + r + 1));
            if (m.mobEffect == 2) {
                for (class_1309 living : world.method_8390(class_1309.class, box, e -> true)) {
                    class_3222 player;
                    boolean isSerbanstein;
                    boolean isHostile = living instanceof class_1588;
                    boolean bl = isSerbanstein = living instanceof class_3222 && "serbanstein".equalsIgnoreCase((player = (class_3222)living).method_5477().getString());
                    if (!isHostile && !isSerbanstein) continue;
                    double headY = living.method_23318() + (double)living.method_17682();
                    double px = living.method_23317();
                    double py = headY + 0.25;
                    double pz = living.method_23321();
                    class_2675 packet = new class_2675((class_2394)class_2398.field_11240, false, false, px, py, pz, 0.0f, 0.0f, 0.0f, 0.0f, 1);
                    double maxDistSq = 9216.0;
                    for (class_3222 player2 : world.method_18456()) {
                        if (!(player2.method_5649(px, py, pz) <= maxDistSq)) continue;
                        player2.field_13987.method_14364((class_2596)packet);
                    }
                    if (isSerbanstein && living instanceof class_3222) {
                        class_3222 player3 = (class_3222)living;
                        player3.method_6092(new class_1293(class_1294.field_5922, 40, 0, true, false, true));
                        continue;
                    }
                    if (!isHostile) continue;
                    living.method_6092(new class_1293(class_1294.field_5904, 40, 1, true, false, false));
                    living.method_6092(new class_1293(class_1294.field_5910, 40, 1, true, false, false));
                }
            }
            for (class_3222 player : world.method_18456()) {
                if (!box.method_1008(player.method_23317(), player.method_23318(), player.method_23321()) || !"serbanstein".equalsIgnoreCase(player.method_5477().getString())) continue;
                player.method_6092(new class_1293(class_1294.field_5922, 40, 0, true, false, true));
                double headY = player.method_23318() + (double)player.method_17682();
                double px = player.method_23317();
                double py = headY + 0.25;
                double pz = player.method_23321();
                class_2675 packet = new class_2675((class_2394)class_2398.field_11240, false, false, px, py, pz, 0.0f, 0.0f, 0.0f, 0.0f, 1);
                double maxDistSq = 9216.0;
                for (class_3222 other : world.method_18456()) {
                    if (!(other.method_5649(px, py, pz) <= maxDistSq)) continue;
                    other.field_13987.method_14364((class_2596)packet);
                }
            }
        }
    }

    public static void tickHeartbeat(class_3218 world) {
        Module[] modules = VoidClamMod.getModules();
        for (int i = 1; i <= moduleNumber; ++i) {
            Module m = modules[i];
            if (m == null || !world.method_8393(m.x >> 4, m.z >> 4)) continue;
            float volume = (float)m.currentSize / 4.0f;
            world.method_43128(null, (double)m.x + 0.5, (double)m.y + 0.5, (double)m.z + 0.5, class_3417.field_14632, class_3419.field_15245, volume, 0.7f);
        }
    }

    public static void onLightPlaced(class_3218 world, class_2338 pos, class_2248 block) {
        if (!VoidClamMod.isLight(block)) {
            return;
        }
        class_243 eventPos = class_243.method_24953((class_2382)pos);
        for (int i = 1; i <= moduleNumber; ++i) {
            class_243 modPos;
            Module m = modules[i];
            if (m == null || m.status != 1 || m.busyFlagPlaceEvent != 0 || m.busyFlagGrowing != 0 || !m.seekLights || !world.method_8393(m.x >> 4, m.z >> 4) || eventPos.method_1025(modPos = new class_243((double)m.x + 0.5, (double)m.y + 0.5, (double)m.z + 0.5)) > 4.0 * (double)m.currentSize * (4.0 * (double)m.currentSize) || m.lightsBlackList.contains(pos)) continue;
            int ii = i;
            m.busyFlagPlaceEvent = 1;
            m.lightsBlackList.add(pos.method_10062());
            CommandToolbox.submitPathfinding(() -> {
                try {
                    Pathfinder.calculatePath(world, ii, m.x, m.y, m.z, pos.method_10263(), pos.method_10264(), pos.method_10260());
                }
                finally {
                    m.busyFlagPlaceEvent = 0;
                }
            });
            break;
        }
    }

    static {
        unbreakableByClam.add(class_2246.field_22108);
        unbreakableByClam.add(class_2246.field_22109);
        unbreakableByClam.add(class_2246.field_27115);
        lights.add(class_2246.field_10327);
        lights.add(class_2246.field_10171);
        lights.add(class_2246.field_10009);
        lights.add(class_2246.field_10174);
        lights.add(class_2246.field_16541);
        lights.add(class_2246.field_10455);
        lights.add(class_2246.field_10336);
        lights.add(class_2246.field_10476);
        lights.add(class_2246.field_10099);
        lights.add(class_2246.field_22122);
        lights.add(class_2246.field_10164);
        ores.add(class_2246.field_10418);
        ores.add(class_2246.field_29219);
        ores.add(class_2246.field_10442);
        ores.add(class_2246.field_29029);
        ores.add(class_2246.field_10013);
        ores.add(class_2246.field_29220);
        ores.add(class_2246.field_10571);
        ores.add(class_2246.field_29026);
        ores.add(class_2246.field_23077);
        ores.add(class_2246.field_10212);
        ores.add(class_2246.field_29027);
        ores.add(class_2246.field_27120);
        ores.add(class_2246.field_29221);
        ores.add(class_2246.field_10090);
        ores.add(class_2246.field_29028);
        ores.add(class_2246.field_10080);
        ores.add(class_2246.field_29030);
        ores.add(class_2246.field_10213);
        ores.add(class_2246.field_23077);
        ores.add(class_2246.field_22109);
        baseCost.add(class_2246.field_10124);
        baseCost.add(class_2246.field_10382);
        baseCost.add(class_2246.field_10164);
        baseCost.add(class_2246.field_10477);
        baseCost.add(class_2246.field_10491);
    }
}

