package com.serbanstein.voidclam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Central state and helpers: module array, path-result queue, grow-pending coordination,
 * CSV save format ({@code modules.siva} in world save root).
 */
public final class VoidClamMod {
    private static final int MAX_MODULES = 1001;

    private static Module[] modules = new Module[MAX_MODULES];
    private static int moduleNumber = 0;
    /** Queue of found path end nodes to build on main thread. Thread-safe. */
    private static final Queue<Node> targets = new ConcurrentLinkedQueue<>();
    /** When non-null, grow is pending: seeks are false, waiting for paths to finish before running grow. */
    private static ServerWorld growPendingWorld = null;
    /** If > 0, when grow runs do clamReSize(world, growCommandTno, growCommandTargetSize); else run full auto grow routine. */
    private static int growCommandTno = 0;
    private static int growCommandTargetSize = 0;
    private static final boolean[] savedSeekLights = new boolean[MAX_MODULES];
    private static final boolean[] savedSeekOres = new boolean[MAX_MODULES];
    private static final int DEFENSE_MIN_SIZE = 11;
    private static final int DEFENSE_EFFECT_TICKS = 6 * 20; // 6 seconds
    private static final float DEFENSE_HORN_PITCH = 0.5f;
    /** Blocks that count as "food" (light sources) for SIVA. */
    private static final Set<Block> lights = new HashSet<>();
    /** Blocks that count as ores (fortune-3 style drops when eaten). */
    private static final Set<Block> ores = new HashSet<>();
    private static final Set<Block> baseCost = new HashSet<>();

    static {
        ores.add(Blocks.COAL_ORE);
        ores.add(Blocks.DEEPSLATE_COAL_ORE);
        ores.add(Blocks.IRON_ORE);
        ores.add(Blocks.DEEPSLATE_IRON_ORE);
        ores.add(Blocks.GOLD_ORE);
        ores.add(Blocks.DEEPSLATE_GOLD_ORE);
        ores.add(Blocks.COPPER_ORE);
        ores.add(Blocks.DEEPSLATE_COPPER_ORE);
        ores.add(Blocks.NETHER_GOLD_ORE);
        ores.add(Blocks.DIAMOND_ORE);
        ores.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        ores.add(Blocks.LAPIS_ORE);
        ores.add(Blocks.DEEPSLATE_LAPIS_ORE);
        ores.add(Blocks.REDSTONE_ORE);
        ores.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        ores.add(Blocks.EMERALD_ORE);
        ores.add(Blocks.DEEPSLATE_EMERALD_ORE);
        ores.add(Blocks.NETHER_QUARTZ_ORE);
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
    public static boolean isOre(Block block) { return ores.contains(block); }
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

    /** True if no pathfinding results are waiting to be built (targets queue empty). */
    public static boolean isTargetsQueueEmpty() {
        return targets.isEmpty();
    }

    public static void removeLightsBlackList(int tno, BlockPos pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].lightsBlackList.remove(pos);
    }

    public static void addLightsBlackList(int tno, BlockPos pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].lightsBlackList.add(pos.toImmutable());
    }

    public static void removeOresBlackList(int tno, BlockPos pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].oresBlackList.remove(pos);
    }

    public static void addOresBlackList(int tno, BlockPos pos) {
        if (tno >= 1 && tno <= moduleNumber && modules[tno] != null)
            modules[tno].oresBlackList.add(pos.toImmutable());
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

    /** Load modules from world save folder (CSV {@code modules.siva}). */
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
                m.seekLights = parts.length > 8 ? Boolean.parseBoolean(parts[8]) : false;
                m.seekOres = parts.length > 9 ? Boolean.parseBoolean(parts[9]) : false;
                modules[moduleNumber] = m;
            }
        } catch (IOException e) {
            // no-op
        }
    }

    /** Save modules; CSV format and {@code modules.siva} / {@code modules.siva.old} rotation. */
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
                            + m.currentSize + "," + m.status + "," + m.energy + "," + m.age
                            + "," + m.seekLights + "," + m.seekOres);
                    }
                }
            }
        } catch (IOException e) {
            // no-op
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

    /** Remove module at index (shift array down). */
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

    /**
     * Request a safe repair for one module (e.g. from /voidclam repair). Same flow as grow but target size = current size.
     */
    public static void requestRepairCommand(ServerWorld world, int tno) {
        if (tno < 1 || tno > moduleNumber || modules[tno] == null) return;
        requestGrowCommand(world, tno, modules[tno].currentSize);
    }

    /**
     * Request a safe grow for one module (e.g. from /voidclam grow). Uses same flow as auto grow:
     * seeks off → wait for paths to finish → run clamReSize(world, tno, targetSize) → restore seeks.
     * If a grow is already pending for this world, this request becomes the action run when ready.
     */
    public static void requestGrowCommand(ServerWorld world, int tno, int targetSize) {
        if (tno < 1 || tno > moduleNumber || modules[tno] == null) return;
        if (growPendingWorld == null) {
            for (int i = 1; i <= moduleNumber; i++) {
                Module m = modules[i];
                if (m != null) {
                    savedSeekLights[i] = m.seekLights;
                    savedSeekOres[i] = m.seekOres;
                    m.seekLights = false;
                    m.seekOres = false;
                }
            }
            growPendingWorld = world;
        }
        if (growPendingWorld.getRegistryKey().equals(world.getRegistryKey())) {
            growCommandTno = tno;
            growCommandTargetSize = targetSize;
        }
    }

    /** Called every tick. If a grow/repair is pending for this world and pathfinding is idle, runs it and restores seeks. */
    public static void tickGrowPendingCheck(ServerWorld world) {
        if (growPendingWorld == null) return;
        if (!growPendingWorld.getRegistryKey().equals(world.getRegistryKey())) return;
        Module[] modules = getModules();
        int cmdTno = growCommandTno;
        boolean idle;
        if (cmdTno > 0) {
            idle = (modules[cmdTno] == null || modules[cmdTno].busyFlagMainCycle == 0);
        } else {
            idle = true;
            for (int i = 1; i <= moduleNumber; i++) {
                Module m = modules[i];
                if (m != null && m.busyFlagMainCycle != 0) {
                    idle = false;
                    break;
                }
            }
        }
        if (!idle || !isTargetsQueueEmpty() || VoidClamModScheduler.hasPendingTasks(world)) return;
        int cmdSize = growCommandTargetSize;
        growPendingWorld = null;
        growCommandTno = 0;
        growCommandTargetSize = 0;
        if (cmdTno > 0) {
            CommandToolbox.clamReSize(world, cmdTno, cmdSize);
        } else {
            runGrowRoutine(world, modules);
        }
        for (int i = 1; i <= moduleNumber; i++) {
            if (modules[i] != null) {
                modules[i].seekLights = savedSeekLights[i];
                modules[i].seekOres = savedSeekOres[i];
            }
        }
    }

    /** Auto-repair/grow: every 5 min. Starts the safe flow (seeks off → wait for paths → run grow); completion is checked every tick in tickGrowPendingCheck. */
    public static void tickAutoRepairAndGrow(ServerWorld world) {
        if (growPendingWorld != null) return;
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m != null) {
                savedSeekLights[i] = m.seekLights;
                savedSeekOres[i] = m.seekOres;
                m.seekLights = false;
                m.seekOres = false;
            }
        }
        growPendingWorld = world;
        growCommandTno = 0;
        growCommandTargetSize = 0;
    }

    private static void runGrowRoutine(ServerWorld world, Module[] modules) {
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m == null) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            int x = m.x, y = m.y, z = m.z, csize = m.currentSize;
            CommandToolbox.clamReSize(world, i, m.currentSize); // repair
            m.lightsBlackList.clear();
            m.oresBlackList.clear();
            if (m.energy <= 4 * m.currentSize || m.currentSize >= 15) continue;
            double cst = 0;
            int hasRoom = 1;
            for (int ix = x - csize + 2; ix <= x + csize - 2; ix++) {
                for (int iz = z - csize + 2; iz <= z + csize - 2; iz++) {
                    for (int iy = y - 2; iy <= y + csize / 2 + 2; iy++) {
                        BlockState state = world.getBlockState(new BlockPos(ix, iy, iz));
                        Block b = state.getBlock();
                        if (b != Blocks.AIR && b != Blocks.WATER && b != Blocks.LAVA && b != Blocks.OBSIDIAN
                            && b != Blocks.NETHER_WART_BLOCK) {
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

    /** Kill modules whose core block is not nether wart or obsidian. Iterate backwards so kill shift doesn't skip. Skip unloaded chunks. */
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

    /** Defense: every 5s, players inside clam octahedron (except 'serbanstein') get encased in nether wart, hunger 0, mining fatigue I for 6s, Dream horn sound. */
    public static void tickDefense(ServerWorld world) {
        Module[] modules = getModules();
        for (int i = 1; i <= moduleNumber; i++) {
            Module m = modules[i];
            if (m == null || m.currentSize < DEFENSE_MIN_SIZE || !world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            float volume = Math.min(3f, (float) m.currentSize / 4f);
            // Dream goat horn: minecraft:item.goat_horn.sound.dream_goat_horn (static registry)
            SoundEvent dreamHornSound = net.minecraft.registry.Registries.SOUND_EVENT.get(Identifier.of("minecraft", "item.goat_horn.sound.dream_goat_horn"));
            final SoundEvent soundRef = dreamHornSound;
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.isSpectator()) continue;
                if ("serbantein".equalsIgnoreCase(player.getName().getString())) continue;
                if (!CommandToolbox.isPlayerInsideOctahedron(player, m)) continue;
                BlockPos playerBlock = player.getBlockPos();
                for (Direction d : Direction.values()) {
                    BlockPos adj = playerBlock.offset(d);
                    BlockState state = world.getBlockState(adj);
                    if (state.isReplaceable() || state.isAir())
                        world.setBlockState(adj, Blocks.NETHER_WART_BLOCK.getDefaultState());
                }
                SoundEvent hornSound = soundRef != null ? soundRef : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value();
                world.playSound(null, playerBlock.getX() + 0.5, playerBlock.getY() + 0.5, playerBlock.getZ() + 0.5,
                    hornSound, SoundCategory.HOSTILE, volume, DEFENSE_HORN_PITCH);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, DEFENSE_EFFECT_TICKS, 0));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, DEFENSE_EFFECT_TICKS, 0));
            }
        }
    }

    /** Heartbeat sound for loaded modules (every 4s). */
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

}
