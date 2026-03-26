package com.serbanstein.voidclam;

import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.Identifier;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Central state: modules keyed by {@link Module#clamId}, path-result queue, grow-pending coordination.
 * Optional legacy CSV {@code modules.siva}: loaded at startup if present; written when the file already exists
 * (mirror) or via {@link #save} / {@link #importLegacyModulesSiva}.
 */
public final class VoidClamMod {
    private static final int MAX_MODULES = 1001;

    private static final Map<UUID, Module> modulesById = new ConcurrentHashMap<>();
    /** Captured in break {@code BEFORE} while the clam core block entity still exists (for item drop components). */
    private static final ThreadLocal<ComponentMap> breakingClamFurnaceComponents = new ThreadLocal<>();
    /** Queue of found path end nodes to build on main thread. Thread-safe. */
    private static final Queue<Node> targets = new ConcurrentLinkedQueue<>();
    /**
     * When true, off-thread pathfinding work ({@code CommandToolbox.submitPathfinding}) should exit promptly and must not enqueue
     * new main-thread effects. Set during {@link net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents#SERVER_STOPPING}
     * so the pathfinder pool can drain before save; cleared when a new server session starts.
     */
    private static volatile boolean asyncPathfindingShutdownRequested;
    /** While true, {@link CommandToolbox#submitPathfinding} rejects immediately (does not queue). Used during coordinated clam kill. */
    private static volatile boolean asyncPathfindingKillBarrierInEffect;
    /** During kill barrier: async pathfinding for this clam id aborts. */
    private static volatile @Nullable UUID asyncPathfindingKillVictimClamId;
    private static final Object asyncKillCoordinatorLock = new Object();
    private static final Queue<KillRequest> pendingClamKills = new ConcurrentLinkedQueue<>();
    private static MinecraftServer pendingKillDrainServer;

    private record KillRequest(UUID victimId, boolean saveAfter) {}
    /** When non-null, grow is pending: seeks are false, waiting for paths to finish before running grow. */
    private static ServerWorld growPendingWorld = null;
    /** When non-null, single-clam grow/repair pending for this id. */
    private static @Nullable UUID growCommandClamId = null;
    private static int growCommandTargetSize = 0;
    private static final Map<UUID, Boolean> growSavedSeekLights = new HashMap<>();
    private static final Map<UUID, Boolean> growSavedSeekOres = new HashMap<>();
    /** Blast furnace fuel slot (see {@code AbstractFurnaceBlockEntity.FUEL_SLOT_INDEX} in mappings). */
    private static final int CLAM_CORE_FUEL_SLOT = 1;
    /** Per-clam auto repair/grow cadence (overworld world time ticks), staggered by core position. */
    public static final int AUTO_GROW_REPAIR_INTERVAL_TICKS = 5 * 60 * 20;
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

    public static boolean isAsyncPathfindingShutdownRequested() {
        return asyncPathfindingShutdownRequested;
    }

    /**
     * Off-thread pathfinding should stop when the server is shutting down, the clam center chunk is unloaded, or this clam's UUID
     * is the coordinated-kill victim.
     *
     * @param pathfindingClamId stable id for this path job; kill barrier matches this UUID
     */
    public static boolean shouldAbortAsyncPathfindingWork(
        ServerWorld world,
        int clamCenterX,
        int clamCenterZ,
        @Nullable UUID pathfindingClamId
    ) {
        if (asyncPathfindingShutdownRequested) return true;
        UUID victimId = asyncPathfindingKillVictimClamId;
        if (victimId != null && pathfindingClamId != null && victimId.equals(pathfindingClamId)) {
            return true;
        }
        return !world.isChunkLoaded(clamCenterX >> 4, clamCenterZ >> 4);
    }

    public static boolean isAsyncPathfindingKillBarrierInEffect() {
        return asyncPathfindingKillBarrierInEffect;
    }

    /**
     * Remove/adjust queued path targets and grow-pending indices, then shift the module array.
     * Call only from the server thread after async pathfinding has drained.
     */
    private static void finishClamKillAfterAsyncSettled(UUID victimId) {
        if (victimId == null) return;
        purgeTargetsForVictimClamId(victimId);
        if (growPendingWorld != null && victimId.equals(growCommandClamId)) {
            growCommandClamId = null;
            growCommandTargetSize = 0;
        }
        growSavedSeekLights.remove(victimId);
        growSavedSeekOres.remove(victimId);
        modulesById.remove(victimId);
    }

    private static void purgeTargetsForVictimClamId(UUID victimClamId) {
        List<Node> kept = new ArrayList<>();
        Node n;
        while ((n = targets.poll()) != null) {
            if (victimClamId.equals(n.clamId)) {
                continue;
            }
            kept.add(n);
        }
        for (Node k : kept) {
            targets.offer(k);
        }
    }

    /**
     * Kill module at index: block all new async pathfinding, abort work for this slot, drain the pathfinder pool off-thread,
     * then on the server thread adjust targets and the module array and clear the barrier. Kills are serialized; additional
     * requests queue behind an in-progress drain. Saves after the shift when {@code saveAfter}.
     */
    public static void clamKillBlocking(MinecraftServer server, UUID victimId, boolean saveAfter) {
        if (victimId == null || !modulesById.containsKey(victimId)) return;
        synchronized (asyncKillCoordinatorLock) {
            pendingClamKills.add(new KillRequest(victimId, saveAfter));
            pendingKillDrainServer = server;
            tryStartNextClamKillDrainLocked();
        }
    }

    private static void tryStartNextClamKillDrainLocked() {
        if (asyncPathfindingKillBarrierInEffect) {
            return;
        }
        KillRequest next = pendingClamKills.poll();
        if (next == null) {
            return;
        }
        UUID victimId = next.victimId();
        Module victim = modulesById.get(victimId);
        if (victim == null) {
            tryStartNextClamKillDrainLocked();
            return;
        }
        victim.busyFlagMainCycle = 0;
        purgeTargetsForVictimClamId(victimId);
        asyncPathfindingKillVictimClamId = victimId;
        asyncPathfindingKillBarrierInEffect = true;
        MinecraftServer server = pendingKillDrainServer;
        if (server == null) {
            asyncPathfindingKillVictimClamId = null;
            asyncPathfindingKillBarrierInEffect = false;
            tryStartNextClamKillDrainLocked();
            return;
        }
        final UUID victimIdFinal = victimId;
        final boolean saveAfterThis = next.saveAfter;
        Thread drain = new Thread(() -> {
            try {
                CommandToolbox.shutdownPathfinderExecutorAfterKillDrain();
            } finally {
                server.execute(() -> {
                    try {
                        finishClamKillAfterAsyncSettled(victimIdFinal);
                        if (saveAfterThis) {
                            save(server);
                        }
                    } finally {
                        asyncPathfindingKillVictimClamId = null;
                        asyncPathfindingKillBarrierInEffect = false;
                        synchronized (asyncKillCoordinatorLock) {
                            tryStartNextClamKillDrainLocked();
                        }
                    }
                });
            }
        }, "voidclam-pathfinder-kill-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /** New server session: allow pathfinding tasks again (mod entry, before load). */
    public static void onAsyncPathfindingSessionStart() {
        asyncPathfindingShutdownRequested = false;
        asyncPathfindingKillBarrierInEffect = false;
        asyncPathfindingKillVictimClamId = null;
        pendingClamKills.clear();
        pendingKillDrainServer = null;
    }

    /** Server stopping: stop off-thread work and drain the pathfinder pool (mod entry, before save). */
    public static void onAsyncPathfindingSessionStop() {
        asyncPathfindingShutdownRequested = true;
        asyncPathfindingKillBarrierInEffect = false;
        asyncPathfindingKillVictimClamId = null;
        pendingClamKills.clear();
        pendingKillDrainServer = null;
        CommandToolbox.shutdownPathfinderExecutorForSessionEnd();
        Pathfinder.clearSyncPathJobsForSessionEnd();
        NaturalSpawnHandler.clearForSessionEnd();
        for (Module m : modulesById.values()) {
            if (m != null) {
                m.busyFlagMainCycle = 0;
            }
        }
    }

    public static Collection<Module> getAllModules() {
        return modulesById.values();
    }

    public static int getModuleCount() {
        return modulesById.size();
    }

    /** @deprecated use {@link #getModuleById} */
    @Deprecated
    public static Module[] getModules() {
        Collection<Module> c = modulesById.values();
        Module[] arr = c.toArray(new Module[0]);
        Arrays.sort(arr, Comparator.comparing(m -> m.clamId.toString()));
        return arr;
    }

    /** @deprecated use {@link #getModuleCount} */
    @Deprecated
    public static int getModuleNumber() {
        return getModuleCount();
    }

    public static @Nullable Module getModuleById(@Nullable UUID id) {
        return id == null ? null : modulesById.get(id);
    }

    public static @Nullable Module findModuleAt(BlockPos pos) {
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (Module m : modulesById.values()) {
            if (m != null && m.x == px && m.y == py && m.z == pz) return m;
        }
        return null;
    }

    private static boolean registerModule(Module m) {
        m.ensureClamId();
        if (modulesById.size() >= MAX_MODULES) return false;
        modulesById.put(m.clamId, m);
        return true;
    }

    /** Placement from searing heart item (same package mixin); {@code false} if at capacity. */
    static boolean registerModuleForSearingPlace(Module m) {
        return registerModule(m);
    }

    /** First auto-grow deadline for a clam that has not been scheduled yet (spread across one interval by position). */
    public static void ensureAutoGrowScheduled(ServerWorld world, Module m) {
        if (m.nextAutoGrowRepairWorldTime > 0) return;
        long t = world.getTime();
        int spread = Math.floorMod(m.x * 31 + m.y * 17 + m.z * 13, AUTO_GROW_REPAIR_INTERVAL_TICKS);
        m.nextAutoGrowRepairWorldTime = t + 1 + spread;
    }

    /** After CSV load: give every loaded module a first auto-grow fire time. */
    public static void seedAutoGrowScheduleForAllModules(ServerWorld world) {
        for (Module mm : modulesById.values()) {
            if (mm != null) ensureAutoGrowScheduled(world, mm);
        }
    }

    /**
     * Per-clam auto repair/grow. Returns false if another grow/repair is already pending globally.
     * Snapshots and clears seeks only for this clam.
     */
    public static boolean tryScheduleAutoGrowRepairForClam(ServerWorld world, UUID clamId) {
        if (growPendingWorld != null || asyncPathfindingKillBarrierInEffect) return false;
        Module mod = getModuleById(clamId);
        if (mod == null) return false;
        growSavedSeekLights.put(clamId, mod.seekLights);
        growSavedSeekOres.put(clamId, mod.seekOres);
        mod.seekLights = false;
        mod.seekOres = false;
        growPendingWorld = world;
        growCommandClamId = clamId;
        growCommandTargetSize = -1;
        return true;
    }

    public static void captureClamCoreComponentsBeforeBreak(net.minecraft.world.World world, BlockPos pos, BlockState state) {
        breakingClamFurnaceComponents.remove();
        if (world.isClient() || !(world instanceof ServerWorld)) return;
        if (!state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        if (findModuleAt(pos) == null) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be != null) {
            breakingClamFurnaceComponents.set(be.createComponentMap());
        }
    }

    public static void clearBreakingClamFurnaceComponentsCapture() {
        breakingClamFurnaceComponents.remove();
    }

    public static void applySearingHeartBlockLabel(ServerWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return;
        ComponentMap withName = ComponentMap.of(
            be.getComponents(),
            ComponentMap.builder()
                .add(DataComponentTypes.CUSTOM_NAME, SearingHeartItems.SEARING_NAME)
                .build()
        );
        be.setComponents(withName);
        be.markDirty();
    }

    /** Place or replace the block at {@code pos} with the vanilla clam core block (blast furnace). */
    public static void placeHeartBlockForModule(ServerWorld world, BlockPos pos, Module m) {
        boolean lit = m != null && m.status == 1;
        BlockState state = VoidClamCoreBlocks.CORE_BLOCK.getDefaultState().with(AbstractFurnaceBlock.LIT, lit);
        world.setBlockState(pos, state);
        applySearingHeartBlockLabel(world, pos);
    }

    public static void stripVanillaBlastFurnaceDropsNear(ServerWorld world, BlockPos pos) {
        Box box = Box.of(Vec3d.ofCenter(pos), 0.45, 0.45, 0.45);
        for (Entity entity : world.getOtherEntities(null, box, e -> e instanceof ItemEntity)) {
            ItemEntity itemEntity = (ItemEntity) entity;
            if (SearingHeartItems.isPlainBlastFurnaceDrop(itemEntity.getStack())) {
                itemEntity.discard();
            }
        }
    }

    /**
     * Clam core broken: replace the default blast furnace drop with a named stack carrying module + furnace data,
     * then remove the module from the save. Furnace components were stored in {@link #breakingClamFurnaceComponents}.
     */
    public static void onClamCoreBroken(ServerWorld world, @Nullable PlayerEntity player, BlockPos pos, BlockState state) {
        ComponentMap furnaceSnap = breakingClamFurnaceComponents.get();
        breakingClamFurnaceComponents.remove();
        Module m = findModuleAt(pos);
        if (m == null) return;
        stripVanillaBlastFurnaceDropsNear(world, pos);
        ItemStack drop = SearingHeartItems.createDropFromBreak(m, furnaceSnap);
        net.minecraft.block.Block.dropStack(world, pos, drop);
        clamKillBlocking(world.getServer(), m.clamId, true);
    }

    /**
     * After a searing heart item successfully places a blast furnace: new clam id at placement position,
     * inheriting module fields from the item.
     */
    public static void onSearingHeartItemPlaced(ServerWorld world, BlockPos pos, ItemStack templateFromBeforeConsume) {
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        Module snap = SearingHeartItems.readModuleTemplateFromStack(templateFromBeforeConsume);
        if (snap == null) return;
        if (findModuleAt(pos) != null) return;
        Module m = new Module();
        m.clamId = UUID.randomUUID();
        SearingHeartItems.applyTemplateOntoModule(snap, m);
        m.x = pos.getX();
        m.y = pos.getY();
        m.z = pos.getZ();
        if (!registerModuleForSearingPlace(m)) {
            world.breakBlock(pos, false);
            net.minecraft.block.Block.dropStack(world, pos, templateFromBeforeConsume.copy());
            return;
        }
        m.status = 0;
        m.stubBuilt = false;
        applySearingHeartBlockLabel(world, pos);
        maybeSaveLegacyModulesSiva(world.getServer());
    }

    /**
     * Server tick for each loaded module: reach, core check, heartbeat, defense (formerly heart block entity tick).
     * Runs on overworld; module coordinates are stored for the primary world.
     */
    public static void tickLoadedClamCores(ServerWorld world) {
        long t = world.getTime();
        for (Module m : modulesById.values()) {
            if (m == null || !world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            BlockPos pos = new BlockPos(m.x, m.y, m.z);
            tryConsumeFuelAndWakeClam(world, m);
            if (world.getRegistryKey().equals(ServerWorld.OVERWORLD)) {
                ensureAutoGrowScheduled(world, m);
                if (m.status == 1) {
                    long due = m.nextAutoGrowRepairWorldTime;
                    if (due > 0 && t >= due) {
                        if (tryScheduleAutoGrowRepairForClam(world, m.clamId)) {
                            m.nextAutoGrowRepairWorldTime = t + AUTO_GROW_REPAIR_INTERVAL_TICKS;
                        }
                    }
                }
            }
            int phase = Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, 20);
            UUID clamId = m.clamId;
            if ((t + phase) % 20 == 0) {
                tickCoreCheckAtHeart(world, pos, clamId);
            }
            if (m.status != 1) continue;
            if ((t + phase) % 20 == 0) {
                CommandToolbox.clamReach(world, clamId);
            }
            if ((t + phase + 11) % (4 * 20) == 0) {
                tickHeartbeatForModule(world, getModuleByClamId(clamId));
            }
            if ((t + phase + 7) % (5 * 20) == 0) {
                tickDefenseForModule(world, getModuleByClamId(clamId));
            }
        }
    }

    public static boolean isLight(Block block) { return lights.contains(block); }

    /** Fuel-slot items that can wake a dormant clam: edible light blocks (as items) or anything the fuel registry accepts. */
    public static boolean isClamWakeFuel(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof BlockItem bi && isLight(bi.getBlock())) {
            return true;
        }
        return world.getServer().getFuelRegistry().isFuel(stack);
    }

    /**
     * If the core furnace has a valid wake item in the fuel slot, consume one and mark the module awake ({@link Module#status} {@code 1}).
     */
    public static void tryConsumeFuelAndWakeClam(ServerWorld world, Module m) {
        if (m == null || m.status != 0) return;
        BlockPos pos = new BlockPos(m.x, m.y, m.z);
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return;
        ItemStack fuel = furnace.getStack(CLAM_CORE_FUEL_SLOT);
        if (fuel.isEmpty() || !isClamWakeFuel(world, fuel)) return;
        fuel.decrement(1);
        furnace.setStack(CLAM_CORE_FUEL_SLOT, fuel);
        furnace.markDirty();
        m.status = 1;
        if (!m.stubBuilt) {
            CommandToolbox.buildStub(world, m.x, m.y, m.z);
            m.stubBuilt = true;
        }
        ensureAutoGrowScheduled(world, m);
        maybeSaveLegacyModulesSiva(world.getServer());
    }
    public static boolean isOre(Block block) { return ores.contains(block); }
    public static boolean isBaseCost(Block block) { return baseCost.contains(block); }

    public static boolean isModuleInLoadedChunk(ServerWorld world, @Nullable UUID clamId) {
        Module m = getModuleById(clamId);
        return m != null && world.isChunkLoaded(m.x >> 4, m.z >> 4);
    }

    /** @deprecated use {@link #moduleMatchesClamAt(UUID, int, int, int)} */
    @Deprecated
    public static boolean moduleAtSlotMatchesPosition(int tno, int x, int y, int z) {
        return false;
    }

    /** @deprecated use {@link #getModuleById} */
    @Deprecated
    public static int getSlotByClamId(@Nullable UUID clamId) {
        return -1;
    }

    public static @Nullable Module getModuleByClamId(@Nullable UUID clamId) {
        return getModuleById(clamId);
    }

    public static boolean moduleMatchesClamAt(@Nullable UUID clamId, int x, int y, int z) {
        if (clamId == null) return false;
        Module m = getModuleById(clamId);
        return m != null && m.x == x && m.y == y && m.z == z;
    }

    public static void enqueueTarget(Node node) {
        targets.offer(node);
    }

    public static boolean isTargetsQueueEmpty() {
        return targets.isEmpty();
    }

    public static void removeLightsBlackList(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) m.lightsBlackList.remove(pos);
    }

    public static void addLightsBlackList(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) m.lightsBlackList.add(pos.toImmutable());
    }

    public static void removeOresBlackList(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) m.oresBlackList.remove(pos);
    }

    public static void addOresBlackList(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) m.oresBlackList.add(pos.toImmutable());
    }

    public static void addEnergy(UUID clamId, int delta) {
        Module m = getModuleById(clamId);
        if (m != null) m.energy = Math.max(0, m.energy + delta);
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

    /** One line of legacy {@code modules.siva} CSV, or null if empty/invalid. */
    public static @Nullable Module parseModuleFromSivaLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return null;
        String[] parts = t.split(",", -1);
        if (parts.length < 8) return null;
        try {
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
            m.protectItself = parts.length > 10 ? Boolean.parseBoolean(parts[10]) : true;
            if (parts.length > 11 && !parts[11].isEmpty()) {
                try {
                    m.clamId = UUID.fromString(parts[11]);
                } catch (IllegalArgumentException ignored) {
                    m.clamId = null;
                }
            }
            m.stubBuilt = parts.length > 12 ? Boolean.parseBoolean(parts[12]) : true;
            m.ensureClamId();
            return m;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * If {@code modules.siva} exists in the save root, replace {@link #modulesById} from it (legacy primary store).
     * If the file is absent, the registry stays empty until clams are created in-world.
     */
    public static void loadOptionalLegacyModulesSiva(MinecraftServer server) {
        Path savePath = getModulesPath(server);
        if (!Files.exists(savePath)) return;
        modulesById.clear();
        try (Scanner s = new Scanner(Files.newInputStream(savePath))) {
            while (s.hasNextLine()) {
                if (modulesById.size() >= MAX_MODULES) break;
                Module m = parseModuleFromSivaLine(s.nextLine());
                if (m != null) {
                    modulesById.put(m.clamId, m);
                }
            }
        } catch (IOException e) {
            // no-op
        }
    }

    /** Write {@code modules.siva} only when that file already exists (optional legacy mirror). */
    public static void maybeSaveLegacyModulesSiva(MinecraftServer server) {
        if (!Files.exists(getModulesPath(server))) return;
        save(server);
    }

    /**
     * Read {@code modules.siva} and register each row: places blast furnace core + stub in the overworld.
     *
     * @return short summary for command feedback
     */
    public static String importLegacyModulesSiva(MinecraftServer server) {
        Path savePath = getModulesPath(server);
        if (!Files.exists(savePath)) {
            return "No modules.siva in save root.";
        }
        ServerWorld world = server.getOverworld();
        if (world == null) {
            return "No overworld.";
        }
        int lines = 0;
        int imported = 0;
        int bad = 0;
        int dupId = 0;
        int occupied = 0;
        int cap = 0;
        int chunkFail = 0;
        try {
            List<String> allLines = Files.readAllLines(savePath);
            for (String raw : allLines) {
                lines++;
                Module m = parseModuleFromSivaLine(raw);
                if (m == null) {
                    bad++;
                    continue;
                }
                if (modulesById.containsKey(m.clamId)) {
                    dupId++;
                    continue;
                }
                BlockPos center = new BlockPos(m.x, m.y, m.z);
                Module at = findModuleAt(center);
                if (at != null) {
                    occupied++;
                    continue;
                }
                if (!registerModule(m)) {
                    cap++;
                    break;
                }
                int cx = m.x >> 4;
                int cz = m.z >> 4;
                try {
                    world.getChunk(cx, cz, ChunkStatus.FULL, true);
                } catch (Exception e) {
                    modulesById.remove(m.clamId);
                    chunkFail++;
                    continue;
                }
                placeHeartBlockForModule(world, center, m);
                CommandToolbox.buildStub(world, m.x, m.y, m.z);
                imported++;
            }
        } catch (IOException e) {
            return "Read failed: " + e.getMessage();
        }
        save(server);
        return "legacy import: +" + imported + " (lines " + lines + ", bad " + bad + ", dupId " + dupId + ", occupied " + occupied + ", cap " + cap + ", chunkFail " + chunkFail + ")";
    }

    /** After optional CSV load: overworld centers still wart/obsidian get a blast furnace core. */
    public static void migrateLoadedModulesToHeartBlocks(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) return;
        boolean any = false;
        for (Module m : modulesById.values()) {
            if (m == null) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            BlockPos p = new BlockPos(m.x, m.y, m.z);
            Block b = world.getBlockState(p).getBlock();
            if (b == VoidClamCoreBlocks.CORE_BLOCK) {
                continue;
            }
            if (b == Blocks.NETHER_WART_BLOCK || b == Blocks.OBSIDIAN) {
                placeHeartBlockForModule(world, p, m);
                any = true;
            }
        }
        if (any) {
            maybeSaveLegacyModulesSiva(server);
        }
    }

    /** Save all modules to CSV (sorted by UUID for stable diffs). */
    public static void save(MinecraftServer server) {
        Path path = getModulesPath(server);
        Path oldPath = path.getParent().resolve("modules.siva.old");
        try {
            Files.deleteIfExists(oldPath);
            if (Files.exists(path))
                Files.move(path, oldPath);
            List<Module> list = new ArrayList<>(modulesById.values());
            list.sort(Comparator.comparing(mm -> mm.clamId.toString()));
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {
                for (Module m : list) {
                    if (m == null) continue;
                    m.ensureClamId();
                    out.println(m.type + "," + m.x + "," + m.y + "," + m.z + ","
                        + m.currentSize + "," + m.status + "," + m.energy + "," + m.age
                        + "," + m.seekLights + "," + m.seekOres + "," + m.protectItself
                        + "," + m.clamId + "," + m.stubBuilt);
                }
            }
        } catch (IOException e) {
            // no-op
        }
    }

    private static Path getModulesPath(MinecraftServer server) {
        return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("modules.siva");
    }

    /** Create a new stub at (x,y,z). Returns clam UUID string for commands, or null on failure. */
    public static @Nullable UUID makeStub(ServerWorld world, int x, int y, int z) {
        Module m = new Module();
        m.clamId = UUID.randomUUID();
        m.type = 1;
        m.x = x;
        m.y = y;
        m.z = z;
        m.currentSize = 1;
        m.status = 0;
        m.energy = 0;
        m.age = 0;
        VoidClamConfig cfg = VoidClamConfig.get();
        m.seekLights = cfg.clam_light_flag_default;
        m.seekOres = cfg.clam_ores_flag_default;
        m.protectItself = cfg.clam_protect_itself_default;
        if (!registerModule(m)) {
            return null;
        }
        placeHeartBlockForModule(world, new BlockPos(x, y, z), m);
        CommandToolbox.buildStub(world, x, y, z);
        m.stubBuilt = true;
        maybeSaveLegacyModulesSiva(world.getServer());
        return m.clamId;
    }

    public static void clamKill(MinecraftServer server, UUID clamId, boolean saveAfter) {
        clamKillBlocking(server, clamId, saveAfter);
    }

    public static void requestRepairCommand(ServerWorld world, UUID clamId) {
        Module m = getModuleById(clamId);
        if (m == null) return;
        requestGrowCommand(world, clamId, m.currentSize);
    }

    public static void requestGrowCommand(ServerWorld world, UUID clamId, int targetSize) {
        Module target = getModuleById(clamId);
        if (target == null) return;
        if (growPendingWorld == null) {
            growSavedSeekLights.clear();
            growSavedSeekOres.clear();
            growSavedSeekLights.put(clamId, target.seekLights);
            growSavedSeekOres.put(clamId, target.seekOres);
            target.seekLights = false;
            target.seekOres = false;
            growPendingWorld = world;
        } else if (growPendingWorld.getRegistryKey().equals(world.getRegistryKey())) {
            UUID prev = growCommandClamId;
            if (prev != null && !prev.equals(clamId)) {
                restoreSeekFlagsFromGrowSnapshots(prev);
                growSavedSeekLights.put(clamId, target.seekLights);
                growSavedSeekOres.put(clamId, target.seekOres);
                target.seekLights = false;
                target.seekOres = false;
            }
        }
        if (growPendingWorld.getRegistryKey().equals(world.getRegistryKey())) {
            growCommandClamId = clamId;
            growCommandTargetSize = targetSize;
        }
    }

    private static void restoreSeekFlagsFromGrowSnapshots(UUID clamId) {
        Module m = getModuleById(clamId);
        if (m != null) {
            Boolean sl = growSavedSeekLights.remove(clamId);
            Boolean so = growSavedSeekOres.remove(clamId);
            if (sl != null) m.seekLights = sl;
            if (so != null) m.seekOres = so;
        } else {
            growSavedSeekLights.remove(clamId);
            growSavedSeekOres.remove(clamId);
        }
    }

    public static void tickGrowPendingCheck(ServerWorld world) {
        if (growPendingWorld == null) return;
        if (asyncPathfindingKillBarrierInEffect) return;
        if (!growPendingWorld.getRegistryKey().equals(world.getRegistryKey())) return;
        UUID cmdId = growCommandClamId;
        if (cmdId == null) {
            growPendingWorld = null;
            growCommandTargetSize = 0;
            growSavedSeekLights.clear();
            growSavedSeekOres.clear();
            return;
        }
        Module m = getModuleById(cmdId);
        boolean idle = (m == null || m.busyFlagMainCycle == 0);
        if (!idle || !isTargetsQueueEmpty() || VoidClamModScheduler.hasPendingTasks(world)) return;
        int cmdSize = growCommandTargetSize;
        growPendingWorld = null;
        growCommandClamId = null;
        growCommandTargetSize = 0;
        if (m != null) {
            if (cmdSize < 0) {
                runAutoGrowRoutineSingle(world, m);
            } else {
                CommandToolbox.clamReSize(world, cmdId, cmdSize);
            }
        }
        restoreSeekFlagsFromGrowSnapshots(cmdId);
        growSavedSeekLights.clear();
        growSavedSeekOres.clear();
    }

    /** Auto repair + optional grow for one clam (scheduled periodically when awake). */
    private static void runAutoGrowRoutineSingle(ServerWorld world, Module m) {
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        int x = m.x, y = m.y, z = m.z, csize = m.currentSize;
        CommandToolbox.clamReSize(world, m.clamId, m.currentSize);
        m.lightsBlackList.clear();
        m.oresBlackList.clear();
        VoidClamConfig cfg = VoidClamConfig.get();
        if (m.energy > cfg.clam_grow_energymultiplier * m.currentSize && m.currentSize < cfg.clam_size_max) {
            double cst = 0;
            int hasRoom = 1;
            for (int ix = x - csize + 2; ix <= x + csize - 2; ix++) {
                for (int iz = z - csize + 2; iz <= z + csize - 2; iz++) {
                    for (int iy = y - 2; iy <= y + csize / 2 + 2; iy++) {
                        BlockState state = world.getBlockState(new BlockPos(ix, iy, iz));
                        Block b = state.getBlock();
                        if (b != Blocks.AIR && b != Blocks.WATER && b != Blocks.LAVA && b != Blocks.OBSIDIAN
                            && b != Blocks.NETHER_WART_BLOCK && b != VoidClamCoreBlocks.CORE_BLOCK) {
                            float br = b.getBlastResistance();
                            if (br < 0) hasRoom = 0;
                            else cst += br;
                        }
                    }
                }
            }
            if (cst <= 10 * csize) {
                int nextSize = Math.min(m.currentSize + 2, cfg.clam_size_max);
                if (nextSize > m.currentSize) {
                    m.energy = 0;
                    CommandToolbox.clamReSize(world, m.clamId, nextSize);
                    m.currentSize = nextSize;
                }
            }
        }
        maybeSaveLegacyModulesSiva(world.getServer());
    }

    public static void tickCoreCheck(ServerWorld world) {
        List<UUID> toKill = new ArrayList<>();
        for (Module m : modulesById.values()) {
            if (m == null) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            Block block = world.getBlockState(new BlockPos(m.x, m.y, m.z)).getBlock();
            if (block != VoidClamCoreBlocks.CORE_BLOCK && block != Blocks.NETHER_WART_BLOCK && block != Blocks.OBSIDIAN) {
                toKill.add(m.clamId);
            }
        }
        for (UUID id : toKill) {
            clamKill(world.getServer(), id, false);
        }
    }

    public static void tickCoreCheckAtHeart(ServerWorld world, BlockPos heartPos, @Nullable UUID clamId) {
        if (clamId == null) return;
        Module m = getModuleById(clamId);
        if (m == null || m.x != heartPos.getX() || m.y != heartPos.getY() || m.z != heartPos.getZ()) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        Block block = world.getBlockState(heartPos).getBlock();
        if (block != VoidClamCoreBlocks.CORE_BLOCK && block != Blocks.NETHER_WART_BLOCK && block != Blocks.OBSIDIAN) {
            clamKill(world.getServer(), clamId, false);
        }
    }

    /** Defense for one module (called from heart block entity tick when interval matches). */
    public static void tickDefenseForModule(ServerWorld world, Module m) {
        if (m == null || !m.protectItself || m.currentSize < DEFENSE_MIN_SIZE || !world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        float volume = Math.min(3f, (float) m.currentSize / 4f);
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
            VoidClamSfx.playBlockSound(world, null, playerBlock.getX() + 0.5, playerBlock.getY() + 0.5, playerBlock.getZ() + 0.5,
                hornSound, SoundCategory.HOSTILE, volume, DEFENSE_HORN_PITCH);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, DEFENSE_EFFECT_TICKS, 0));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, DEFENSE_EFFECT_TICKS, 0));
        }
    }

    /** Legacy: defense for all modules (prefer {@link #tickDefenseForModule} from heart ticks). */
    public static void tickDefense(ServerWorld world) {
        for (Module m : modulesById.values()) {
            tickDefenseForModule(world, m);
        }
    }

    /** Heartbeat for one module (from heart block entity tick). */
    public static void tickHeartbeatForModule(ServerWorld world, Module m) {
        if (m == null || !world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        float volume = (float) m.currentSize / 4;
        VoidClamSfx.playBlockSound(world, null, m.x + 0.5, m.y + 0.5, m.z + 0.5,
            net.minecraft.sound.SoundEvents.BLOCK_CONDUIT_AMBIENT, net.minecraft.sound.SoundCategory.BLOCKS, volume, 0.7f);
    }

    /** Legacy: heartbeat for all loaded modules. */
    public static void tickHeartbeat(ServerWorld world) {
        for (Module m : modulesById.values()) {
            tickHeartbeatForModule(world, m);
        }
    }

}
