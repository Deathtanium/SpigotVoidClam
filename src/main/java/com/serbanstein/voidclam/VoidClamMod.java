package com.serbanstein.voidclam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Central state and helpers for the VoidClam mod. Preserves original logic, locks (busy flags),
 * and CSV save format (modules.siva in world folder).
 */
public final class VoidClamMod {
    private static final int MAX_MODULES = 1001;

    private static Module[] modules = new Module[MAX_MODULES];
    private static int moduleNumber = 0;
    /** Queue of found path end nodes to build on main thread. Thread-safe. */
    private static final Queue<Node> targets = new ConcurrentLinkedQueue<>();
    /** Blocks that count as "food" (light sources) for SIVA. */
    private static final Set<Block> lights = new HashSet<>();
    private static final Set<Block> baseCost = new HashSet<>();

    static {
        lights.add(Blocks.BEACON);
        lights.add(Blocks.GLOWSTONE);
        lights.add(Blocks.JACK_O_LANTERN);
        lights.add(Blocks.SEA_LANTERN);
        lights.add(Blocks.LANTERN);
        lights.add(Blocks.END_ROD);
        lights.add(Blocks.TORCH);
        lights.add(Blocks.SEA_PICKLE);
        lights.add(Blocks.WALL_TORCH);
        lights.add(Blocks.SHROOMLIGHT);
        lights.add(Blocks.LAVA);
        baseCost.add(Blocks.AIR);
        baseCost.add(Blocks.WATER);
        baseCost.add(Blocks.LAVA);
        baseCost.add(Blocks.SNOW);
        baseCost.add(Blocks.SNOW_BLOCK);
    }

    public static Module[] getModules() { return modules; }
    public static int getModuleNumber() { return moduleNumber; }
    public static boolean isLight(Block block) { return lights.contains(block); }
    public static boolean isBaseCost(Block block) { return baseCost.contains(block); }

    /** True if module tno exists and its center chunk is loaded (so clam work is safe). */
    public static boolean isModuleInLoadedChunk(ServerWorld world, int tno) {
        if (tno < 1 || tno > moduleNumber || modules[tno] == null) return false;
        Module m = modules[tno];
        return world.isChunkLoaded(m.x >> 4, m.z >> 4);
    }

    public static void enqueueTarget(Node node) {
        targets.offer(node);
    }

    public static void removeLightsBlackList(int tno, BlockPos pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].lightsBlackList.remove(pos);
    }

    public static void addLightsBlackList(int tno, BlockPos pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].lightsBlackList.add(pos.toImmutable());
    }

    public static void addEnergy(int tno, int delta) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].energy = Math.max(0, modules[tno].energy + delta);
    }

    /** Schedule runnable on main thread after delayTicks (call from main thread). */
    public static void scheduleDelayed(ServerWorld world, long delayTicks, Runnable run) {
        VoidClamModScheduler.schedule(world, delayTicks, run);
    }

    /** Called every tick on server thread: drain path queue and run buildPath. */
    public static void tickTargets(ServerWorld world) {
        Node n;
        while ((n = targets.poll()) != null)
            Pathfinder.buildPath(world, n);
    }

    /** Load modules from world save folder (same format as original: CSV in world/modules.siva). */
    public static void load(MinecraftServer server) {
        Path savePath = getModulesPath(server);
        modules = new Module[MAX_MODULES];
        moduleNumber = 0;
        if (!Files.exists(savePath)) return;
        try (Scanner s = new Scanner(Files.newInputStream(savePath))) {
            while (s.hasNextLine()) {
                String line = s.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 8) continue;
                moduleNumber++;
                if (moduleNumber >= MAX_MODULES) break;
                Module m = new Module();
                m.type = Integer.parseInt(parts[0]);
                m.x = Integer.parseInt(parts[1]);
                m.y = Integer.parseInt(parts[2]);
                m.z = Integer.parseInt(parts[3]);
                m.currentSize = Integer.parseInt(parts[4]);
                m.status = Integer.parseInt(parts[5]);
                m.energy = Integer.parseInt(parts[6]);
                m.age = Integer.parseInt(parts[7]);
                modules[moduleNumber] = m;
            }
        } catch (IOException e) {
            // no-op like original
        }
    }

    /** Save modules to world folder. Preserves original CSV format and modules.siva / modules.siva.old rotation. */
    public static void save(MinecraftServer server) {
        Path path = getModulesPath(server);
        Path oldPath = path.getParent().resolve("modules.siva.old");
        try {
            Files.deleteIfExists(oldPath);
            if (Files.exists(path))
                Files.move(path, oldPath);
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {
                for (int i = 1; i <= moduleNumber; i++) {
                    Module m = modules[i];
                    if (m != null) {
                        out.println(m.type + "," + m.x + "," + m.y + "," + m.z + ","
                            + m.currentSize + "," + m.status + "," + m.energy + "," + m.age);
                    }
                }
            }
        } catch (IOException e) {
            // no-op like original
        }
    }

    private static Path getModulesPath(MinecraftServer server) {
        return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("modules.siva");
    }

    /** Create a new stub module at (x,y,z). Increments moduleNumber and saves. */
    public static int makeStub(ServerWorld world, int x, int y, int z) {
        moduleNumber++;
        if (moduleNumber >= MAX_MODULES) {
            moduleNumber--;
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
        modules[moduleNumber] = m;
        CommandToolbox.buildStub(world, x, y, z);
        save(world.getServer());
        return moduleNumber;
    }

    /** Remove module at index (shift array down like original). */
    public static void clamKill(int tno) {
        if (tno < 1 || tno > moduleNumber) return;
        for (int i = tno; i < moduleNumber; i++) {
            Module swap = modules[i];
            modules[i] = modules[i + 1];
            modules[i + 1] = swap;
        }
        modules[moduleNumber] = null;
        moduleNumber--;
    }

    /** Auto-repair/grow: every 5 min, grow modules that have enough energy and room (original logic + blast resistance). */
    public static void tickAutoRepairAndGrow(ServerWorld world) {
        Module[] modules = getModules();
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m == null) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            int x = m.x, y = m.y, z = m.z, csize = m.currentSize;
            CommandToolbox.clamReSize(world, i, m.currentSize); // repair
            m.lightsBlackList.clear();
            if (m.energy <= 4 * m.currentSize || m.currentSize >= 15) continue;
            double cst = 0;
            int hasRoom = 1;
            for (int ix = x - csize + 2; ix <= x + csize - 2; ix++) {
                for (int iz = z - csize + 2; iz <= z + csize - 2; iz++) {
                    for (int iy = y - 2; iy <= y + csize / 2 + 2; iy++) {
                        BlockState state = world.getBlockState(new BlockPos(ix, iy, iz));
                        Block b = state.getBlock();
                        if (b != Blocks.AIR && b != Blocks.WATER && b != Blocks.LAVA && b != Blocks.OBSIDIAN
                            && b != Blocks.NETHER_WART_BLOCK && b != Blocks.WARPED_WART_BLOCK) {
                            float br = b.getBlastResistance();
                            if (br < 0) hasRoom = 0;
                            else cst += br;
                        }
                    }
                }
            }
            if (cst > 10 * csize) hasRoom = 0;
            if (hasRoom == 1) {
                m.energy = 0;
                CommandToolbox.clamReSize(world, i, m.currentSize + 2);
                m.currentSize += 2;
            }
        }
        save(world.getServer());
    }

    /** Kill modules whose core block is not nether wart or obsidian (original integrity check). Iterate backwards so kill shift doesn't skip. Skip unloaded chunks. */
    public static void tickCoreCheck(ServerWorld world) {
        Module[] modules = getModules();
        for (int i = moduleNumber; i >= 1; i--) {
            Module m = modules[i];
            if (m == null) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            Block block = world.getBlockState(new BlockPos(m.x, m.y, m.z)).getBlock();
            if (block != Blocks.NETHER_WART_BLOCK && block != Blocks.OBSIDIAN)
                clamKill(i);
        }
    }

    /** Heartbeat sound for loaded modules (original every 4s). */
    public static void tickHeartbeat(ServerWorld world) {
        Module[] modules = getModules();
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m == null || !world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            float volume = (float) m.currentSize / 4;
            world.playSound(null, m.x + 0.5, m.y + 0.5, m.z + 0.5,
                net.minecraft.sound.SoundEvents.BLOCK_CONDUIT_AMBIENT, net.minecraft.sound.SoundCategory.BLOCKS, volume, 0.7f);
        }
    }

    /** Called when a light block is placed; notifies nearby modules to pathfind (async). Only considers modules in loaded chunks. */
    public static void onLightPlaced(ServerWorld world, BlockPos pos, Block block) {
        if (!isLight(block)) return;
        Vec3d eventPos = Vec3d.ofCenter(pos);
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m == null || m.status != 1 || m.busyFlagPlaceEvent != 0) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            Vec3d modPos = new Vec3d(m.x + 0.5, m.y + 0.5, m.z + 0.5);
            if (eventPos.squaredDistanceTo(modPos) > (4.0 * m.currentSize) * (4.0 * m.currentSize)) continue;
            if (m.lightsBlackList.contains(pos)) continue;
            final int ii = i;
            m.busyFlagPlaceEvent = 1;
            m.lightsBlackList.add(pos.toImmutable());
            CommandToolbox.submitPathfinding(() -> {
                try {
                    Pathfinder.calculatePath(world, ii, m.x, m.y, m.z, pos.getX(), pos.getY(), pos.getZ());
                    // energy granted only when light is eaten in buildPath
                } finally {
                    m.busyFlagPlaceEvent = 0;
                }
            });
            break; // one module per place, like original
        }
    }
}
