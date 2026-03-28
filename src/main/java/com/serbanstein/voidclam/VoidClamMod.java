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
import net.minecraft.registry.RegistryKey;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central state: modules keyed by {@link Module#clamId}, path-result queue, grow-pending coordination.
 * Persistence is the searing heart (blast furnace) in-world (no seek cache / blacklist lists on disk); each {@link Module}
 * records {@link Module#worldKey}.
 */
public final class VoidClamMod {
    private static final int MAX_MODULES = 1001;

    private static final Map<UUID, Module> modulesById = new ConcurrentHashMap<>();

    private record PendingLightCacheDelta(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {}
    /** Avoid synchronous cache work inside {@code World#setBlockState} (beacon/pyramid causes huge update chains). */
    private static final ConcurrentLinkedQueue<PendingLightCacheDelta> pendingLightCacheDeltas = new ConcurrentLinkedQueue<>();
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
    /**
     * Outstanding {@link CommandToolbox#clamReSize} delayed shell/obsidian steps per dimension. Grow/repair waits on this instead of
     * {@link VoidClamModScheduler#hasPendingTasks(ServerWorld)} so path-apply and other scheduled work does not wedge the queue.
     */
    private static final Map<RegistryKey<World>, AtomicInteger> resizeShellAnimationPendingByWorld = new ConcurrentHashMap<>();
    /** Blast furnace fuel slot (see {@code AbstractFurnaceBlockEntity.FUEL_SLOT_INDEX} in mappings). */
    private static final int CLAM_CORE_FUEL_SLOT = 1;
    /** Default per-clam auto repair/grow cadence in ticks when config is absent. */
    public static final int DEFAULT_AUTO_GROW_REPAIR_INTERVAL_TICKS = 5 * 60 * 20;
    /** Batched ticks to spread a full light-cache rescan after repair (sync on server thread). */
    public static final int LIGHT_CACHE_REBUILD_TICKS = 100;
    /** No reach/pathfind until this many ticks after obsidian shell from {@link CommandToolbox#clamReSize}. */
    public static final int POST_RESIZE_OBSIDIAN_PATHFINDING_DELAY_TICKS = 20;
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

    public static int autoGrowRepairIntervalTicks() {
        VoidClamConfig cfg = VoidClamConfig.get();
        if (cfg == null) {
            return DEFAULT_AUTO_GROW_REPAIR_INTERVAL_TICKS;
        }
        return cfg.autoGrowRepairIntervalTicks();
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

    /** Drop queued path ends for this clam (resize/kill); does not clear busy — use with {@link #releasePathfindingMainCycle}. */
    static void purgeTargetsForClam(UUID clamId) {
        purgeTargetsForVictimClamId(clamId);
    }

    /**
     * Clears busy, targets queue, and sync A* jobs for this clam. Call at the start of {@link CommandToolbox#clamReSize}.
     */
    public static void prepareClamForResizeShell(Module m) {
        if (m == null) return;
        releasePathfindingMainCycle(m);
        m.lightsBlackList.clear();
        m.oresBlackList.clear();
        m.seekEphemeralDataExpireAtWorldTime = 0L;
        m.seekEphemeralNeedSeekDataRefresh = false;
        purgeTargetsForClam(m.clamId);
        Pathfinder.clearSyncAStarJobsForClam(m.clamId);
    }

    /** Whether {@code clamReach}, path enqueues, and sync A* may run (after obsidian + grace when resizing). */
    public static boolean isPathfindingAllowedYet(ServerWorld world, Module m) {
        if (m == null) return true;
        long t = m.pathfindingResumeWorldTime;
        return t == 0 || world.getTime() >= t;
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
        releasePathfindingMainCycle(victim);
        victim.lightsCache.clear();
        victim.oresCache.clear();
        victim.lightCacheRebuildTicksRemaining = 0;
        victim.lightCacheRebuildCursor = 0L;
        victim.oreCacheRebuildTicksRemaining = 0;
        victim.oreCacheRebuildCursor = 0L;
        victim.lightsBlackList.clear();
        victim.oresBlackList.clear();
        victim.seekEphemeralDataExpireAtWorldTime = 0L;
        victim.seekEphemeralNeedSeekDataRefresh = false;
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
                releasePathfindingMainCycle(m);
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

    /** Heart position in a specific dimension (avoids collisions across dimensions at the same block coords). */
    public static @Nullable Module findModuleAt(ServerWorld world, BlockPos pos) {
        RegistryKey<World> dim = world.getRegistryKey();
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (Module m : modulesById.values()) {
            if (m != null && m.x == px && m.y == py && m.z == pz && m.dimensionWorldKey().equals(dim)) {
                return m;
            }
        }
        return null;
    }

    public static @Nullable ServerWorld getWorldForModule(MinecraftServer server, @Nullable Module m) {
        if (m == null || server == null) {
            return null;
        }
        return server.getWorld(m.dimensionWorldKey());
    }

    /**
     * After world reload: link runtime {@link Module} from heart blast furnace block entity custom data
     * (replaces legacy {@code modules.siva} bootstrap).
     */
    public static void tryRegisterFromClamCoreBlockEntity(ServerWorld world, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return;
        }
        if (findModuleAt(world, pos) != null) {
            return;
        }
        Module snap = SearingHeartItems.readModuleTemplateFromComponentMap(furnace.getComponents());
        if (snap == null) {
            return;
        }
        Module m = new Module();
        SearingHeartItems.applyTemplateOntoModule(snap, m);
        m.x = pos.getX();
        m.y = pos.getY();
        m.z = pos.getZ();
        m.worldKey = world.getRegistryKey();
        m.ensureClamId();
        Module existing = modulesById.get(m.clamId);
        if (existing != null) {
            if (existing.x == m.x && existing.y == m.y && existing.z == m.z
                && existing.dimensionWorldKey().equals(m.dimensionWorldKey())) {
                return;
            }
            return;
        }
        if (registerModule(m)) {
            SearingHeartItems.syncModuleToBlockEntity(furnace, m);
            VoidClamConfig rcfg = VoidClamConfig.get();
            if (rcfg.lightBlockCacheEnabled() && m.seekLights && m.lightsCache.isEmpty()
                && m.lightCacheRebuildTicksRemaining == 0) {
                startLightCacheRebuild(m);
            }
            if (rcfg.oreBlockCacheEnabled() && m.seekOres && m.oresCache.isEmpty()
                && m.oreCacheRebuildTicksRemaining == 0) {
                startOreCacheRebuild(m);
            }
        }
    }

    /**
     * When a clam’s heart chunk stays unloaded for {@link #autoGrowRepairIntervalTicks()} in that dimension’s world time,
     * drops in-memory seek caches, blacklists, and path state (same cadence as auto repair). Reloading the chunk triggers
     * cache rebuild when {@link VoidClamConfig#seekTargetCacheEnabled()}.
     */
    public static void tickSeekEphemeralExpiry(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (Module m : modulesById.values()) {
            if (m == null) {
                continue;
            }
            ServerWorld w = getWorldForModule(server, m);
            if (w == null) {
                continue;
            }
            boolean loaded = w.isChunkLoaded(m.x >> 4, m.z >> 4);
            if (loaded) {
                m.seekEphemeralDataExpireAtWorldTime = 0L;
                continue;
            }
            long t = w.getTime();
            if (m.seekEphemeralDataExpireAtWorldTime == 0L) {
                m.seekEphemeralDataExpireAtWorldTime = t + (long) autoGrowRepairIntervalTicks();
            } else if (t >= m.seekEphemeralDataExpireAtWorldTime) {
                clearSeekCachesAndBlacklistsAfterChunkUnloadExpiry(m);
                m.seekEphemeralDataExpireAtWorldTime = 0L;
            }
        }
    }

    static void clearSeekCachesAndBlacklistsAfterChunkUnloadExpiry(Module m) {
        if (m == null) {
            return;
        }
        releasePathfindingMainCycle(m);
        purgeTargetsForClam(m.clamId);
        Pathfinder.clearSyncAStarJobsForClam(m.clamId);
        m.lightsCache.clear();
        m.oresCache.clear();
        m.lightsBlackList.clear();
        m.oresBlackList.clear();
        m.lightCacheRebuildTicksRemaining = 0;
        m.lightCacheRebuildCursor = 0L;
        m.oreCacheRebuildTicksRemaining = 0;
        m.oreCacheRebuildCursor = 0L;
        m.seekEphemeralNeedSeekDataRefresh = true;
    }

    /** Push live {@link Module} fields into the heart blast furnace so chunk NBT persists across restarts. */
    public static void syncClamCoreBlockEntityFromModule(ServerWorld world, Module m) {
        if (m == null) return;
        BlockPos pos = new BlockPos(m.x, m.y, m.z);
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            SearingHeartItems.syncModuleToBlockEntity(furnace, m);
        }
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
        int spread = Math.floorMod(m.x * 31 + m.y * 17 + m.z * 13, autoGrowRepairIntervalTicks());
        m.nextAutoGrowRepairWorldTime = t + 1 + spread;
    }

    /** After CSV load: give every loaded module a first auto-grow fire time. */
    public static void seedAutoGrowScheduleForAllModules(ServerWorld world) {
        for (Module mm : modulesById.values()) {
            if (mm != null && mm.dimensionWorldKey().equals(world.getRegistryKey())) {
                ensureAutoGrowScheduled(world, mm);
                startSeekCachesRebuild(mm);
            }
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
        if (findModuleAt((ServerWorld) world, pos) == null) return;
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
        ComponentMap current = be.getComponents();
        if (SearingHeartItems.SEARING_NAME.equals(current.get(DataComponentTypes.CUSTOM_NAME))) {
            return;
        }
        ComponentMap withName = ComponentMap.builder()
            .addAll(current)
            .add(DataComponentTypes.CUSTOM_NAME, SearingHeartItems.SEARING_NAME)
            .build();
        be.setComponents(withName);
        be.markDirty();
    }

    /** Place or replace the block at {@code pos} with the vanilla clam core block (blast furnace). */
    public static void placeHeartBlockForModule(ServerWorld world, BlockPos pos, Module m) {
        boolean lit = m != null && m.status == 1;
        BlockState state = VoidClamCoreBlocks.CORE_BLOCK.getDefaultState().with(AbstractFurnaceBlock.LIT, lit);
        world.setBlockState(pos, state);
        BlockEntity be = world.getBlockEntity(pos);
        if (m != null && be instanceof AbstractFurnaceBlockEntity furnace) {
            SearingHeartItems.syncModuleToBlockEntity(furnace, m);
        } else {
            applySearingHeartBlockLabel(world, pos);
        }
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
     * Clam core broken: replace the default blast furnace drop with a fresh Searing Heart (baby template),
     * then remove the module from the save.
     */
    public static void onClamCoreBroken(ServerWorld world, @Nullable PlayerEntity player, BlockPos pos, BlockState state) {
        breakingClamFurnaceComponents.remove();
        Module m = findModuleAt(world, pos);
        if (m == null) return;
        stripVanillaBlastFurnaceDropsNear(world, pos);
        // Baby heart only: no carry-over module size/stats or furnace contents (furnaceSnap ignored).
        ItemStack drop = SearingHeartItems.createFreshHeartStack();
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
        if (findModuleAt(world, pos) != null) return;
        Module m = new Module();
        m.clamId = UUID.randomUUID();
        SearingHeartItems.applyTemplateOntoModule(snap, m);
        m.x = pos.getX();
        m.y = pos.getY();
        m.z = pos.getZ();
        m.worldKey = world.getRegistryKey();
        if (!registerModuleForSearingPlace(m)) {
            world.breakBlock(pos, false);
            net.minecraft.block.Block.dropStack(world, pos, templateFromBeforeConsume.copy());
            return;
        }
        m.status = 0;
        m.stubBuilt = false;
        syncClamCoreBlockEntityFromModule(world, m);
        startSeekCachesRebuild(m);
    }

    /**
     * Server tick for modules whose {@link Module#dimensionWorldKey()} matches {@code world}.
     */
    public static void tickLoadedClamCores(ServerWorld world) {
        long t = world.getTime();
        for (Module m : modulesById.values()) {
            if (m == null || !m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            if (m.seekEphemeralNeedSeekDataRefresh) {
                m.seekEphemeralNeedSeekDataRefresh = false;
                VoidClamConfig rcfg0 = VoidClamConfig.get();
                if (rcfg0.seekTargetCacheEnabled()) {
                    if (m.seekLights) {
                        startLightCacheRebuild(m);
                    }
                    if (m.seekOres) {
                        startOreCacheRebuild(m);
                    }
                }
            }
            syncClamCoreBlockEntityFromModule(world, m);
            tickLightCacheRebuildStep(world, m);
            tickOreCacheRebuildStep(world, m);
            if (m.busyFlagMainCycle == 0
                && (m.lightPathGoalPacked != null || m.orePathGoalPacked != null)) {
                releasePathfindingMainCycle(m);
            }
            BlockPos pos = new BlockPos(m.x, m.y, m.z);
            tryConsumeFuelAndWakeClam(world, m);
            ensureAutoGrowScheduled(world, m);
            if (m.status == 1) {
                long due = m.nextAutoGrowRepairWorldTime;
                if (due > 0 && t >= due) {
                    if (tryScheduleAutoGrowRepairForClam(world, m.clamId)) {
                        m.nextAutoGrowRepairWorldTime = t + autoGrowRepairIntervalTicks();
                    }
                }
            }
            int phase = Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, 20);
            int seekIntervalTicks = VoidClamConfig.get().seekAttemptIntervalTicks();
            int seekPhase = Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, seekIntervalTicks);
            UUID clamId = m.clamId;
            if ((t + phase) % 20 == 0) {
                tickCoreCheckAtHeart(world, pos, clamId);
            }
            if (m.status != 1) continue;
            if ((t + seekPhase) % seekIntervalTicks == 0) {
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

    /**
     * Half-width of the light seek box on each axis: {@code max(dx,dy,dz) <= 4 * effectiveSize},
     * where {@code effectiveSize = max(1, currentSize)} so legacy or invalid size {@code 0} still gets a normal scan box.
     */
    public static int lightSeekHalfExtent(int currentSize) {
        return 4 * Math.max(1, currentSize);
    }

    public static boolean inLightSeekRange(Module m, BlockPos pos) {
        int e = lightSeekHalfExtent(m.currentSize);
        return Math.abs(pos.getX() - m.x) <= e
            && Math.abs(pos.getY() - m.y) <= e
            && Math.abs(pos.getZ() - m.z) <= e;
    }

    /** Linear volume of the clamReach scan box for {@code currentSize}. */
    static long lightSeekScanVolume(int currentSize) {
        long e = lightSeekHalfExtent(currentSize);
        long span = 2L * e + 1;
        return span * span * span;
    }

    /** Clears the cache and rescans the seek box over {@link #LIGHT_CACHE_REBUILD_TICKS} server ticks. */
    public static void startLightCacheRebuild(Module m) {
        if (m == null) return;
        if (!VoidClamConfig.get().lightBlockCacheEnabled()) return;
        m.lightsCache.clear();
        m.lightCacheRebuildTicksRemaining = LIGHT_CACHE_REBUILD_TICKS;
        m.lightCacheRebuildCursor = 0L;
    }

    /** Clears the ore cache and rescans the seek box over {@link #LIGHT_CACHE_REBUILD_TICKS} server ticks. */
    public static void startOreCacheRebuild(Module m) {
        if (m == null) return;
        if (!VoidClamConfig.get().oreBlockCacheEnabled()) return;
        m.oresCache.clear();
        m.oreCacheRebuildTicksRemaining = LIGHT_CACHE_REBUILD_TICKS;
        m.oreCacheRebuildCursor = 0L;
    }

    /** After resize/repair/wake: restart both seek caches when their respective configs are enabled. */
    public static void startSeekCachesRebuild(Module m) {
        startLightCacheRebuild(m);
        startOreCacheRebuild(m);
    }

    /**
     * Call from server tick for each loaded clam while {@link Module#lightCacheRebuildTicksRemaining} &gt; 0.
     * Processes a fair slice of the scan volume for this tick.
     */
    public static void tickLightCacheRebuildStep(ServerWorld world, Module m) {
        if (m == null || m.lightCacheRebuildTicksRemaining <= 0) return;
        if (!VoidClamConfig.get().lightBlockCacheEnabled()) {
            m.lightCacheRebuildTicksRemaining = 0;
            m.lightCacheRebuildCursor = 0;
            return;
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        if (!isPathfindingAllowedYet(world, m)) return;
        long total = lightSeekScanVolume(m.currentSize);
        long cursor = m.lightCacheRebuildCursor;
        int ticksLeft = m.lightCacheRebuildTicksRemaining;
        long remaining = total - cursor;
        if (remaining <= 0) {
            m.lightCacheRebuildTicksRemaining = 0;
            m.lightCacheRebuildCursor = 0;
            return;
        }
        int e = lightSeekHalfExtent(m.currentSize);
        long span = 2L * e + 1;
        long layer = span * span;
        long toProcess = (remaining + (long) ticksLeft - 1L) / (long) ticksLeft;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (long j = 0; j < toProcess && cursor < total; j++, cursor++) {
            long xi = cursor / layer;
            long rem = cursor % layer;
            long yi = rem / span;
            long zi = rem % span;
            int bx = m.x - e + (int) xi;
            int by = m.y - e + (int) yi;
            int bz = m.z - e + (int) zi;
            mut.set(bx, by, bz);
            if (!world.isChunkLoaded(mut.getX() >> 4, mut.getZ() >> 4)) {
                continue;
            }
            if (VoidClamMod.isLight(world.getBlockState(mut).getBlock())) {
                m.lightsCache.add(mut.asLong());
            }
        }
        m.lightCacheRebuildCursor = cursor;
        m.lightCacheRebuildTicksRemaining--;
        if (m.lightCacheRebuildTicksRemaining <= 0 || cursor >= total) {
            m.lightCacheRebuildTicksRemaining = 0;
            m.lightCacheRebuildCursor = 0;
        }
    }

    /**
     * Call from server tick for each loaded clam while {@link Module#oreCacheRebuildTicksRemaining} &gt; 0.
     * Processes a fair slice of the scan volume for this tick.
     */
    public static void tickOreCacheRebuildStep(ServerWorld world, Module m) {
        if (m == null || m.oreCacheRebuildTicksRemaining <= 0) return;
        if (!VoidClamConfig.get().oreBlockCacheEnabled()) {
            m.oreCacheRebuildTicksRemaining = 0;
            m.oreCacheRebuildCursor = 0;
            return;
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        if (!isPathfindingAllowedYet(world, m)) return;
        long total = lightSeekScanVolume(m.currentSize);
        long cursor = m.oreCacheRebuildCursor;
        int ticksLeft = m.oreCacheRebuildTicksRemaining;
        long remaining = total - cursor;
        if (remaining <= 0) {
            m.oreCacheRebuildTicksRemaining = 0;
            m.oreCacheRebuildCursor = 0;
            return;
        }
        int e = lightSeekHalfExtent(m.currentSize);
        long span = 2L * e + 1;
        long layer = span * span;
        long toProcess = (remaining + (long) ticksLeft - 1L) / (long) ticksLeft;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (long j = 0; j < toProcess && cursor < total; j++, cursor++) {
            long xi = cursor / layer;
            long rem = cursor % layer;
            long yi = rem / span;
            long zi = rem % span;
            int bx = m.x - e + (int) xi;
            int by = m.y - e + (int) yi;
            int bz = m.z - e + (int) zi;
            mut.set(bx, by, bz);
            if (!world.isChunkLoaded(mut.getX() >> 4, mut.getZ() >> 4)) {
                continue;
            }
            if (isOre(world.getBlockState(mut).getBlock())) {
                m.oresCache.add(mut.asLong());
            }
        }
        m.oreCacheRebuildCursor = cursor;
        m.oreCacheRebuildTicksRemaining--;
        if (m.oreCacheRebuildTicksRemaining <= 0 || cursor >= total) {
            m.oreCacheRebuildTicksRemaining = 0;
            m.oreCacheRebuildCursor = 0;
        }
    }

    /** When pathfinding cannot reach a light goal, drop it from the cache until the next repair rebuild. */
    public static void removeLightFromClamCacheAfterFailedPath(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) {
            long p = pos.asLong();
            m.lightsCache.remove(p);
            m.lightsBlackList.remove(p);
            if (m.lightPathGoalPacked != null && m.lightPathGoalPacked == p) {
                m.lightPathGoalPacked = null;
            }
        }
    }

    /** If the path goal is still a registered light block, drop it from this clam's cache (unreachable prepass). */
    public static void removeLightGoalFromCacheIfPrepassUnreachable(ServerWorld world, UUID clamId, int gx, int gy, int gz) {
        BlockPos goal = new BlockPos(gx, gy, gz);
        if (!isLight(world.getBlockState(goal).getBlock())) {
            return;
        }
        removeLightFromClamCacheAfterFailedPath(clamId, goal);
    }

    /** If the path goal is still a registered ore block, drop it from this clam's cache (unreachable prepass). */
    public static void removeOreGoalFromCacheIfPrepassUnreachable(ServerWorld world, UUID clamId, int gx, int gy, int gz) {
        BlockPos goal = new BlockPos(gx, gy, gz);
        if (!isOre(world.getBlockState(goal).getBlock())) {
            return;
        }
        removeOreFromClamCacheAfterFailedPath(clamId, goal);
    }

    /** When pathfinding cannot reach an ore goal, drop it from the cache until the next repair rebuild. */
    public static void removeOreFromClamCacheAfterFailedPath(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) {
            long p = pos.asLong();
            m.oresCache.remove(p);
            m.oresBlackList.remove(p);
            if (m.orePathGoalPacked != null && m.orePathGoalPacked == p) {
                m.orePathGoalPacked = null;
            }
        }
    }

    /**
     * After A* cap/abort while the goal block is still a light or ore: update the matching seek cache like other failure paths.
     */
    public static void removeSeekGoalFromCachesAfterFailedPath(ServerWorld world, UUID clamId, int gx, int gy, int gz) {
        BlockPos goal = new BlockPos(gx, gy, gz);
        Block b = world.getBlockState(goal).getBlock();
        if (isLight(b)) {
            removeLightFromClamCacheAfterFailedPath(clamId, goal);
        }
        if (isOre(b)) {
            removeOreFromClamCacheAfterFailedPath(clamId, goal);
        }
    }

    /**
     * Clears main-cycle busy, releases path locks in {@link Module#lightsBlackList} / {@link Module#oresBlackList},
     * and clears active light / ore goals.
     */
    public static void releasePathfindingMainCycle(Module m) {
        if (m == null) return;
        m.pathApplyPendingSteps = 0;
        Long lightGoal = m.lightPathGoalPacked;
        if (lightGoal != null) {
            m.lightsBlackList.remove(lightGoal);
        }
        Long oreGoal = m.orePathGoalPacked;
        if (oreGoal != null) {
            m.oresBlackList.remove(oreGoal);
        }
        m.busyFlagMainCycle = 0;
        m.lightPathGoalPacked = null;
        m.orePathGoalPacked = null;
    }

    /**
     * One delayed {@link Pathfinder#buildPath} step finished (server thread). When the last step completes, calls
     * {@link #releasePathfindingMainCycle}. Stale steps after a force-release see pending 0 and do nothing.
     */
    public static void completeOnePathApplyStep(Module m) {
        if (m == null || m.pathApplyPendingSteps <= 0) {
            return;
        }
        m.pathApplyPendingSteps--;
        if (m.pathApplyPendingSteps == 0) {
            releasePathfindingMainCycle(m);
        }
    }

    /**
     * Mixin hook: queue work for {@link #drainPendingLightCacheDeltas}; do not scan modules inside {@code setBlockState}.
     */
    public static void enqueueLightCacheDeltaFromBlockChange(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        Block ob = oldState.getBlock();
        Block nb = newState.getBlock();
        boolean lightRel = isLight(ob) || isLight(nb);
        boolean oreRel = isOre(ob) || isOre(nb);
        if (!lightRel && !oreRel) {
            return;
        }
        VoidClamConfig cfg = VoidClamConfig.get();
        boolean needLight = lightRel && cfg.lightBlockCacheEnabled();
        boolean needOre = oreRel && cfg.oreBlockCacheEnabled();
        if (!needLight && !needOre) {
            return;
        }
        pendingLightCacheDeltas.add(new PendingLightCacheDelta(world, pos.toImmutable(), oldState, newState));
    }

    /**
     * Apply queued light cache updates (server tick). Each delta carries the {@link ServerWorld} where
     * {@link World#setBlockState} ran so Nether/End placements update caches like the overworld.
     */
    public static void drainPendingLightCacheDeltas() {
        int budget = 16384;
        PendingLightCacheDelta d;
        while (budget-- > 0 && (d = pendingLightCacheDeltas.poll()) != null) {
            applyLightCacheDelta(d.world, d.pos, d.oldState, d.newState);
            applyOreCacheDelta(d.world, d.pos, d.oldState, d.newState);
        }
    }

    /**
     * If this clam has no light seek cache work in flight and the set is still empty, start the same batched rebuild used
     * after repair so deltas are not the only source of truth until the next repair tick.
     */
    private static void ensureLightSeekCacheForIncomingDelta(Module m, ServerWorld world) {
        if (m == null || !VoidClamConfig.get().lightBlockCacheEnabled() || !m.seekLights) {
            return;
        }
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) {
            return;
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            return;
        }
        if (!world.getBlockState(new BlockPos(m.x, m.y, m.z)).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return;
        }
        if (m.lightCacheRebuildTicksRemaining > 0 || !m.lightsCache.isEmpty()) {
            return;
        }
        startLightCacheRebuild(m);
    }

    /** Same idea as {@link #ensureLightSeekCacheForIncomingDelta} for ores. */
    private static void ensureOreSeekCacheForIncomingDelta(Module m, ServerWorld world) {
        if (m == null || !VoidClamConfig.get().oreBlockCacheEnabled() || !m.seekOres) {
            return;
        }
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) {
            return;
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            return;
        }
        if (!world.getBlockState(new BlockPos(m.x, m.y, m.z)).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return;
        }
        if (m.oreCacheRebuildTicksRemaining > 0 || !m.oresCache.isEmpty()) {
            return;
        }
        startOreCacheRebuild(m);
    }

    private static void applyLightCacheDelta(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!VoidClamConfig.get().lightBlockCacheEnabled()) {
            return;
        }
        Block ob = oldState.getBlock();
        Block nb = newState.getBlock();
        boolean wasLight = isLight(ob);
        boolean nowLight = isLight(nb);
        if (!wasLight && !nowLight) {
            return;
        }
        long packed = pos.asLong();
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (Module m : modulesById.values()) {
            if (m == null || !m.seekLights) continue;
            if (!m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
            BlockPos heart = new BlockPos(m.x, m.y, m.z);
            if (!world.getBlockState(heart).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
                continue;
            }
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            int e = lightSeekHalfExtent(m.currentSize);
            if (Math.abs(m.x - px) > e || Math.abs(m.y - py) > e || Math.abs(m.z - pz) > e) continue;
            if (wasLight && !nowLight) {
                m.lightsCache.remove(packed);
                m.lightsBlackList.remove(packed);
                if (m.lightPathGoalPacked != null && m.lightPathGoalPacked == packed) {
                    m.lightPathGoalPacked = null;
                }
            } else if (nowLight) {
                ensureLightSeekCacheForIncomingDelta(m, world);
                m.lightsCache.add(packed);
            }
        }
    }

    private static void applyOreCacheDelta(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!VoidClamConfig.get().oreBlockCacheEnabled()) {
            return;
        }
        Block ob = oldState.getBlock();
        Block nb = newState.getBlock();
        boolean wasOre = isOre(ob);
        boolean nowOre = isOre(nb);
        if (!wasOre && !nowOre) {
            return;
        }
        long packed = pos.asLong();
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (Module m : modulesById.values()) {
            if (m == null || !m.seekOres) continue;
            if (!m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
            BlockPos heart = new BlockPos(m.x, m.y, m.z);
            if (!world.getBlockState(heart).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
                continue;
            }
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            int e = lightSeekHalfExtent(m.currentSize);
            if (Math.abs(m.x - px) > e || Math.abs(m.y - py) > e || Math.abs(m.z - pz) > e) continue;
            if (wasOre && !nowOre) {
                m.oresCache.remove(packed);
                m.oresBlackList.remove(packed);
                if (m.orePathGoalPacked != null && m.orePathGoalPacked == packed) {
                    m.orePathGoalPacked = null;
                }
            } else if (nowOre) {
                ensureOreSeekCacheForIncomingDelta(m, world);
                m.oresCache.add(packed);
            }
        }
    }

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
        startSeekCachesRebuild(m);
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

    /** OP debug: weakly consistent count of queued path ends for {@code clamId}. */
    public static int countTargetsQueuedForClam(@Nullable UUID clamId) {
        if (clamId == null) {
            return 0;
        }
        int c = 0;
        for (Node node : targets) {
            if (clamId.equals(node.clamId)) {
                c++;
            }
        }
        return c;
    }

    /** OP debug: flags, busy state, caches, and queue stats for one module. */
    public static List<String> debugModuleFlagLines(MinecraftServer server, Module m) {
        List<String> lines = new ArrayList<>();
        ServerWorld world = getWorldForModule(server, m);
        if (m == null) {
            return lines;
        }
        if (world == null) {
            lines.add("flags: seekLights=" + m.seekLights + " seekOres=" + m.seekOres + " protectItself=" + m.protectItself
                + " status=" + m.status + " stubBuilt=" + m.stubBuilt + " (dimension not loaded)");
            lines.add("busy: mainCycle=" + m.busyFlagMainCycle + " placeEvent=" + m.busyFlagPlaceEvent
                + " pathApplyPendingSteps=" + m.pathApplyPendingSteps);
            lines.add("path: lightPathGoalPacked=" + m.lightPathGoalPacked + " orePathGoalPacked=" + m.orePathGoalPacked
                + " resumeWorldTime=" + m.pathfindingResumeWorldTime + " pathAllowed=?");
            lines.add("caches: lightsCache=" + m.lightsCache.size() + " oresCache=" + m.oresCache.size()
                + " lightsBL=" + m.lightsBlackList.size() + " oresBL=" + m.oresBlackList.size()
                + " lightOreRebuildTicks=" + m.lightCacheRebuildTicksRemaining + "/" + m.oreCacheRebuildTicksRemaining);
            lines.add("schedule: nextAutoGrowRepairWT=" + m.nextAutoGrowRepairWorldTime + " worldTime=?");
            lines.add("targetsQueuedForClam=" + countTargetsQueuedForClam(m.clamId));
            return lines;
        }
        lines.add("flags: seekLights=" + m.seekLights + " seekOres=" + m.seekOres + " protectItself=" + m.protectItself
            + " status=" + m.status + " stubBuilt=" + m.stubBuilt);
        lines.add("busy: mainCycle=" + m.busyFlagMainCycle + " placeEvent=" + m.busyFlagPlaceEvent
            + " pathApplyPendingSteps=" + m.pathApplyPendingSteps);
        lines.add("path: lightPathGoalPacked=" + m.lightPathGoalPacked + " orePathGoalPacked=" + m.orePathGoalPacked
            + " resumeWorldTime=" + m.pathfindingResumeWorldTime + " pathAllowed=" + isPathfindingAllowedYet(world, m));
        lines.add("caches: lightsCache=" + m.lightsCache.size() + " oresCache=" + m.oresCache.size()
            + " lightsBL=" + m.lightsBlackList.size() + " oresBL=" + m.oresBlackList.size()
            + " lightOreRebuildTicks=" + m.lightCacheRebuildTicksRemaining + "/" + m.oreCacheRebuildTicksRemaining);
        lines.add("schedule: nextAutoGrowRepairWT=" + m.nextAutoGrowRepairWorldTime + " worldTime=" + world.getTime());
        lines.add("targetsQueuedForClam=" + countTargetsQueuedForClam(m.clamId));
        return lines;
    }

    /**
     * OP debug focused on chunk-unload behavior: whether activity should be paused, unload-expiry timer state,
     * and whether cache/blacklist refresh is pending on next loaded tick.
     */
    public static List<String> debugChunkUnloadPauseLines(MinecraftServer server, Module m) {
        List<String> lines = new ArrayList<>();
        if (m == null) {
            lines.add("module=null");
            return lines;
        }
        ServerWorld world = getWorldForModule(server, m);
        int intervalTicks = autoGrowRepairIntervalTicks();
        lines.add("interval: autoGrowRepairTicks=" + intervalTicks + " (" + (intervalTicks / 20) + "s)");
        if (world == null) {
            lines.add("dimensionLoaded=false dimKey=" + m.dimensionWorldKey().getValue());
            lines.add("activityPausedBecauseChunkUnloaded=true (dimension unavailable)");
            lines.add("ephemeral: unloadExpiryWorldTime=" + m.seekEphemeralDataExpireAtWorldTime
                + " needPostUnloadRefresh=" + m.seekEphemeralNeedSeekDataRefresh);
            lines.add("cachesNow: lightsCache=" + m.lightsCache.size() + " oresCache=" + m.oresCache.size()
                + " lightsBL=" + m.lightsBlackList.size() + " oresBL=" + m.oresBlackList.size());
            return lines;
        }
        long now = world.getTime();
        boolean chunkLoaded = world.isChunkLoaded(m.x >> 4, m.z >> 4);
        long expiryAt = m.seekEphemeralDataExpireAtWorldTime;
        long remaining = expiryAt > 0 ? Math.max(0L, expiryAt - now) : -1L;
        lines.add("world: dim=" + world.getRegistryKey().getValue() + " worldTime=" + now
            + " heartChunkLoaded=" + chunkLoaded);
        lines.add("activity: tickLoadedClamCoreActive=" + chunkLoaded
            + " pathfindingAllowedNow=" + (chunkLoaded && isPathfindingAllowedYet(world, m))
            + " busyMainCycle=" + m.busyFlagMainCycle
            + " status=" + m.status);
        lines.add("ephemeral: unloadExpiryWorldTime=" + expiryAt
            + " remainingTicks=" + (remaining >= 0 ? remaining : "not-armed")
            + " needPostUnloadRefresh=" + m.seekEphemeralNeedSeekDataRefresh);
        lines.add("targetsQueuedForClam=" + countTargetsQueuedForClam(m.clamId)
            + " nextAutoGrowRepairWT=" + m.nextAutoGrowRepairWorldTime);
        lines.add("cachesNow: lightsCache=" + m.lightsCache.size() + " oresCache=" + m.oresCache.size()
            + " lightsBL=" + m.lightsBlackList.size() + " oresBL=" + m.oresBlackList.size());
        return lines;
    }

    /** OP debug: global async pathfinding barrier and grow-pending coordinator; optional per-clam snapshot rows. */
    public static List<String> debugGrowAndAsyncLinesForClam(@Nullable UUID clamId) {
        List<String> lines = new ArrayList<>();
        lines.add("asyncPathfinding: shutdownRequested=" + asyncPathfindingShutdownRequested
            + " killBarrier=" + asyncPathfindingKillBarrierInEffect
            + " killVictim=" + asyncPathfindingKillVictimClamId);
        lines.add("growPending: active=" + (growPendingWorld != null)
            + " cmdClamId=" + growCommandClamId
            + " targetSize=" + growCommandTargetSize
            + " dim=" + (growPendingWorld == null ? "null" : growPendingWorld.getRegistryKey().getValue().toString())
            + " resizeShellAnimPending="
            + (growPendingWorld != null && isResizeShellAnimationPending(growPendingWorld)));
        if (clamId != null) {
            Boolean savedL = growSavedSeekLights.get(clamId);
            Boolean savedO = growSavedSeekOres.get(clamId);
            lines.add("thisClam: isGrowCmdTarget=" + Objects.equals(growCommandClamId, clamId)
                + " savedSeekLightsWhilePending=" + savedL + " savedSeekOresWhilePending=" + savedO);
        }
        return lines;
    }

    public static void removeOresBlackList(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) m.oresBlackList.remove(pos.asLong());
    }

    public static void addOresBlackList(UUID clamId, BlockPos pos) {
        Module m = getModuleById(clamId);
        if (m != null) m.oresBlackList.add(pos.asLong());
    }

    public static void addEnergy(UUID clamId, int delta) {
        Module m = getModuleById(clamId);
        if (m != null) m.energy = Math.max(0, m.energy + delta);
    }

    /** Schedule runnable on main thread after delayTicks (call from main thread). */
    public static void scheduleDelayed(ServerWorld world, long delayTicks, Runnable run) {
        VoidClamModScheduler.schedule(world, delayTicks, run);
    }

    static void trackResizeShellTaskScheduled(ServerWorld world) {
        resizeShellAnimationPendingByWorld
            .computeIfAbsent(world.getRegistryKey(), k -> new AtomicInteger(0))
            .incrementAndGet();
    }

    static void trackResizeShellTaskCompleted(ServerWorld world) {
        AtomicInteger c = resizeShellAnimationPendingByWorld.get(world.getRegistryKey());
        if (c != null) {
            c.decrementAndGet();
        }
    }

    /** True while {@link CommandToolbox#clamReSize} still has delayed shell/obsidian runnables queued for this dimension. */
    public static boolean isResizeShellAnimationPending(ServerWorld world) {
        AtomicInteger c = resizeShellAnimationPendingByWorld.get(world.getRegistryKey());
        return c != null && c.get() > 0;
    }

    /**
     * Like {@link #scheduleDelayed} but pairs with {@link #trackResizeShellTaskScheduled}/{@link #trackResizeShellTaskCompleted}
     * so grow/repair can wait only for resize animation, not all main-thread delayed tasks.
     */
    public static void scheduleResizeShellDelayed(ServerWorld world, long delayTicks, Runnable run) {
        trackResizeShellTaskScheduled(world);
        VoidClamModScheduler.schedule(world, delayTicks, () -> {
            try {
                run.run();
            } finally {
                trackResizeShellTaskCompleted(world);
            }
        });
    }

    /** Called every tick on server thread: drain path queue and run buildPath in each clam's dimension. */
    public static void tickTargets(MinecraftServer server) {
        List<Node> stalled = new ArrayList<>();
        Node n;
        while ((n = targets.poll()) != null) {
            Module tm = getModuleById(n.clamId);
            ServerWorld world = getWorldForModule(server, tm);
            if (world == null || !isPathfindingAllowedYet(world, tm)) {
                stalled.add(n);
                continue;
            }
            Pathfinder.buildPath(world, n);
        }
        for (Node s : stalled) {
            targets.offer(s);
        }
    }

    /**
     * On server start: in each dimension, if a registered clam center is still wart or obsidian, place the blast furnace heart.
     */
    public static void migrateLoadedModulesToHeartBlocks(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            for (Module m : modulesById.values()) {
                if (m == null) continue;
                if (!m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
                if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
                BlockPos p = new BlockPos(m.x, m.y, m.z);
                Block b = world.getBlockState(p).getBlock();
                if (b == VoidClamCoreBlocks.CORE_BLOCK) {
                    continue;
                }
                if (b == Blocks.NETHER_WART_BLOCK || b == Blocks.OBSIDIAN) {
                    placeHeartBlockForModule(world, p, m);
                }
            }
        }
    }

    /** Create a new stub at (x,y,z). Returns clam UUID string for commands, or null on failure. */
    public static @Nullable UUID makeStub(ServerWorld world, int x, int y, int z) {
        Module m = new Module();
        m.clamId = UUID.randomUUID();
        m.type = 1;
        m.x = x;
        m.y = y;
        m.z = z;
        m.worldKey = world.getRegistryKey();
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
        startSeekCachesRebuild(m);
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
        if (!idle || countTargetsQueuedForClam(cmdId) > 0 || isResizeShellAnimationPending(world)) {
            return;
        }
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
        m.oresBlackList.clear();
        m.lightsBlackList.clear();
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
    }

    public static void tickCoreCheck(ServerWorld world) {
        List<UUID> toKill = new ArrayList<>();
        for (Module m : modulesById.values()) {
            if (m == null) continue;
            if (!m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
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
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        Block block = world.getBlockState(heartPos).getBlock();
        if (block != VoidClamCoreBlocks.CORE_BLOCK && block != Blocks.NETHER_WART_BLOCK && block != Blocks.OBSIDIAN) {
            clamKill(world.getServer(), clamId, false);
        }
    }

    /** Defense for one module (called from heart block entity tick when interval matches). */
    public static void tickDefenseForModule(ServerWorld world, Module m) {
        if (m == null || !m.protectItself || m.currentSize < DEFENSE_MIN_SIZE) return;
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
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
        if (m == null) return;
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
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
