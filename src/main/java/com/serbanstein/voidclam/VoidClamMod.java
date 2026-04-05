package com.serbanstein.voidclam;

import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central state: clams keyed by {@link Clam#clamId}, path-result queue, grow-pending coordination.
 * Persistence is the searing heart (blast furnace) in-world (no seek cache / blacklist lists on disk); each {@link Clam}
 * records {@link Clam#worldKey}.
 */
public final class VoidClamMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("voidclam/VoidClamMod");
    private static final int MAX_CLAMS = 1001;
    /** Throttle "orphaned activity" warnings per clam (world-time ticks, overworld clock). */
    private static final int ORPHANED_ACTIVITY_WARN_COOLDOWN_TICKS = 20 * 60;
    private static final Map<UUID, Long> lastOrphanedActivityWarnWorldTick = new ConcurrentHashMap<>();

    private static final Map<UUID, Clam> clamsById = new ConcurrentHashMap<>();

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
    /** Completed {@code clamReSize} chains required before a placed heart (or one that left ice encasement) becomes {@link Clam#status} {@code 1} without relying on this count for {@link #makeStub}. */
    public static final int SEARING_WAKE_REPAIR_CYCLES = 2;
    /** {@link #tryAutogrowIntoPrebuiltShell}: no matching prebuilt shell — default stub may be needed. */
    private static final int PREBUILT_WAKE_NONE = 0;
    /** {@link #tryAutogrowIntoPrebuiltShell}: {@link CommandToolbox#clamReSize} scheduled for larger size. */
    private static final int PREBUILT_WAKE_RESIZED = 1;
    /** {@link #tryAutogrowIntoPrebuiltShell}: valid prebuilt shell already matches {@link Clam#currentSize}. */
    private static final int PREBUILT_WAKE_ALREADY_MATCHES = 2;
    /** Legacy wart/horn defense (annulus) only when size is at least this. */
    private static final int DEFENSE_MIN_SIZE = 3;
    private static final int DEFENSE_EFFECT_TICKS = 6 * 20; // 6 seconds
    private static final float DEFENSE_HORN_PITCH = 0.5f;
    /** Blocks that count as light-source "food" (energy) for clams. */
    private static final Set<Block> lights = new HashSet<>();
    /** Blocks that count as ores (fortune-3 style drops when eaten). */
    private static final Set<Block> ores = new HashSet<>();
    private static final Set<Block> fullBlockLightEnergy2 = new HashSet<>();
    private static final Set<Block> baseCost = new HashSet<>();
    private static final TagKey<Block> SCULK_REPLACEABLE_TAG = TagKey.of(RegistryKeys.BLOCK, Identifier.of("minecraft", "sculk_replaceable"));
    private static final TagKey<Block> PALE_MOSS_REPLACE_TAG = TagKey.of(RegistryKeys.BLOCK, Identifier.of("minecraft", "pale_moss_replace"));
    /** Common convention tag (Fabric / mod ecosystem); fallback list in {@link #ores} still applies. */
    private static final TagKey<Block> COMMON_ORES_TAG = TagKey.of(RegistryKeys.BLOCK, Identifier.of("c", "ores"));
    public record ShellDamageStats(int obsidianPresent, int shellMissing) {
        public int shellTotal() {
            return obsidianPresent + shellMissing;
        }
    }

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
        fullBlockLightEnergy2.add(Blocks.GLOWSTONE);
        fullBlockLightEnergy2.add(Blocks.JACK_O_LANTERN);
        fullBlockLightEnergy2.add(Blocks.SEA_LANTERN);
        fullBlockLightEnergy2.add(Blocks.SHROOMLIGHT);
        fullBlockLightEnergy2.add(Blocks.END_ROD);
        baseCost.add(Blocks.AIR);
        baseCost.add(Blocks.WATER);
        baseCost.add(Blocks.LAVA);
        baseCost.add(Blocks.SNOW);
        baseCost.add(Blocks.SNOW_BLOCK);
    }

    public static boolean isAsyncPathfindingShutdownRequested() {
        return asyncPathfindingShutdownRequested;
    }

    public static int resourceCapForSize(int size) {
        return Math.max(1, size) * 10;
    }

    /**
     * Initial / post-growth value for {@link Clam#materialSeekThreshold}: {@code clam_material_seek_threshold} from config,
     * clamped to {@link #resourceCapForSize} for the clam’s current size (cannot “want” more material than the tank holds).
     */
    public static int materialSeekThresholdBaselineForSize(int currentSize) {
        VoidClamConfig cfg = VoidClamConfig.get();
        int base = cfg != null ? cfg.clam_material_seek_threshold : 5;
        int cap = resourceCapForSize(currentSize);
        return Math.max(0, Math.min(base, cap));
    }

    /** After {@link Clam#currentSize} increases; threshold returns to config baseline capped at the new size’s material cap. */
    public static void resetMaterialSeekThresholdAfterGrowth(Clam m) {
        if (m == null) return;
        m.materialSeekThreshold = materialSeekThresholdBaselineForSize(m.currentSize);
    }

    public static void clampResourcesForSize(Clam m) {
        if (m == null) return;
        int cap = resourceCapForSize(m.currentSize);
        if (m.energy > cap) m.energy = cap;
        if (m.material > cap) m.material = cap;
        if (m.energy < 0) m.energy = 0;
        if (m.material < 0) m.material = 0;
        m.materialSeekThreshold = Math.max(0, Math.min(m.materialSeekThreshold, cap));
    }

    public static int autoGrowRepairIntervalTicks() {
        VoidClamConfig cfg = VoidClamConfig.get();
        if (cfg == null) {
            return DEFAULT_AUTO_GROW_REPAIR_INTERVAL_TICKS;
        }
        return cfg.autoGrowRepairIntervalTicks();
    }

    /**
     * Off-thread pathfinding should stop when the server is shutting down, the clam center chunk is unloaded, this clam's UUID
     * is the coordinated-kill victim, or the heart is **not thermally active** (ice dormancy, {@code status != 1}, etc. —
     * same gate as {@link #isSearingHeartThermallyActive} / {@link CommandToolbox#clamReach}).
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
        if (!world.isChunkLoaded(clamCenterX >> 4, clamCenterZ >> 4)) {
            return true;
        }
        if (pathfindingClamId != null) {
            Clam cm = getClamById(pathfindingClamId);
            if (cm != null && world.getServer() != null) {
                ServerWorld dim = world.getServer().getWorld(cm.dimensionWorldKey());
                if (dim != null && dim.isChunkLoaded(cm.x >> 4, cm.z >> 4)
                    && !isSearingHeartThermallyActive(dim, cm)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isAsyncPathfindingKillBarrierInEffect() {
        return asyncPathfindingKillBarrierInEffect;
    }

    /**
     * Remove/adjust queued path targets and grow-pending indices, then remove the clam from the registry.
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
        clamsById.remove(victimId);
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
    public static void prepareClamForResizeShell(Clam m) {
        if (m == null) return;
        releasePathfindingMainCycle(m);
        m.lightsBlackList.clear();
        m.oresBlackList.clear();
        m.seekEphemeralDataExpireAtWorldTime = 0L;
        m.seekEphemeralNeedSeekDataRefresh = false;
        purgeTargetsForClam(m.clamId);
        Pathfinder.clearSyncAStarJobsForClam(m.clamId);
        m.repairResizeChainAwaitingCompletion = true;
    }

    public static boolean isIceVariant(Block block) {
        return block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE;
    }

    /** True when the six face-adjacent blocks around the heart are all ice variants (any mix). */
    public static boolean isHeartFullyIceEncased(ServerWorld world, BlockPos heart) {
        for (Direction d : Direction.values()) {
            BlockPos n = heart.offset(d);
            if (!world.isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) {
                return false;
            }
            if (!isIceVariant(world.getBlockState(n).getBlock())) {
                return false;
            }
        }
        return true;
    }

    /** {@link Clam#status} {@code 1} and not in full ice dormancy — lit furnace, pathing, aura, and break protection apply. */
    public static boolean isSearingHeartThermallyActive(ServerWorld world, Clam m) {
        if (m == null || m.status != 1) return false;
        return !isHeartFullyIceEncased(world, new BlockPos(m.x, m.y, m.z));
    }

    /** Survival: cancel breaking the searing heart while thermally active unless ice-frozen (then it can be mined). */
    public static boolean shouldCancelBreakingSearingHeart(ServerWorld world, @Nullable PlayerEntity player, BlockPos pos, BlockState state) {
        if (!state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) return false;
        if (player != null && player.isCreative()) return false;
        Clam m = findClamAt(world, pos);
        if (m == null) return false;
        if (isHeartFullyIceEncased(world, pos)) return false;
        return isSearingHeartThermallyActive(world, m);
    }

    /** Cancel opening the blast-furnace UI on an active searing heart. */
    public static boolean shouldCancelUsingSearingHeart(ServerWorld world, BlockPos pos) {
        Clam m = findClamAt(world, pos);
        if (m == null) return false;
        if (isHeartFullyIceEncased(world, pos)) return false;
        return isSearingHeartThermallyActive(world, m);
    }

    /**
     * After the heart is placed awake: try prebuilt-shell autogrow first; only if no suitable shell exists
     * run the fixed {@link CommandToolbox#buildStub}. Seek caches refresh unless a resize was just scheduled.
     */
    private static void applyPostWakeShellAndSeek(ServerWorld world, Clam m) {
        if (m == null) return;
        int prebuilt = tryAutogrowIntoPrebuiltShell(world, m);
        if (prebuilt != PREBUILT_WAKE_RESIZED) {
            startSeekCachesRebuild(m);
        }
        if (prebuilt == PREBUILT_WAKE_NONE && !m.stubBuilt) {
            CommandToolbox.buildStub(world, m.x, m.y, m.z);
            m.stubBuilt = true;
        } else if (prebuilt != PREBUILT_WAKE_NONE) {
            m.stubBuilt = true;
        }
        syncClamCoreBlockEntityFromClam(world, m);
    }

    private static void finishSearingWakeAfterRepairCycles(ServerWorld world, Clam m) {
        m.status = 1;
        ensureAutoGrowScheduled(world, m);
        placeHeartBlockForClam(world, new BlockPos(m.x, m.y, m.z), m);
        applyPostWakeShellAndSeek(world, m);
    }

    /** Called when a {@code clamReSize} delayed shell chain has fully finished (pathidle gate). */
    private static void onRepairResizeChainCompleted(ServerWorld world, Clam m) {
        if (m.repairWakeCyclesRemaining <= 0) {
            return;
        }
        m.repairWakeCyclesRemaining--;
        if (m.repairWakeCyclesRemaining == 0) {
            finishSearingWakeAfterRepairCycles(world, m);
        } else {
            syncClamCoreBlockEntityFromClam(world, m);
        }
    }

    /** Whether {@code clamReach}, path enqueues, and sync A* may run (after obsidian + grace when resizing). */
    public static boolean isPathfindingAllowedYet(ServerWorld world, Clam m) {
        if (m == null) return true;
        long t = m.pathfindingResumeWorldTime;
        return t == 0 || world.getTime() >= t;
    }

    /**
     * Kill one clam: block all new async pathfinding, abort work for it, drain the pathfinder pool off-thread,
     * then on the server thread purge targets/registry and clear the barrier. Kills are serialized; additional
     * requests queue behind an in-progress drain. Saves after the shift when {@code saveAfter}.
     */
    public static void clamKillBlocking(MinecraftServer server, UUID victimId, boolean saveAfter) {
        if (victimId == null || !clamsById.containsKey(victimId)) return;
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
        Clam victim = clamsById.get(victimId);
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
        for (Clam m : clamsById.values()) {
            if (m != null) {
                releasePathfindingMainCycle(m);
            }
        }
    }

    public static Collection<Clam> getAllClams() {
        return clamsById.values();
    }

    public static int getClamCount() {
        return clamsById.size();
    }

    /** @deprecated use {@link #getClamById} */
    @Deprecated
    public static Clam[] getClams() {
        Collection<Clam> c = clamsById.values();
        Clam[] arr = c.toArray(new Clam[0]);
        Arrays.sort(arr, Comparator.comparing(m -> m.clamId.toString()));
        return arr;
    }

    /** @deprecated use {@link #getClamCount} */
    @Deprecated
    public static int getClamNumber() {
        return getClamCount();
    }

    public static @Nullable Clam getClamById(@Nullable UUID id) {
        return id == null ? null : clamsById.get(id);
    }

    /** Heart position in a specific dimension (avoids collisions across dimensions at the same block coords). */
    public static @Nullable Clam findClamAt(ServerWorld world, BlockPos pos) {
        RegistryKey<World> dim = world.getRegistryKey();
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (Clam m : clamsById.values()) {
            if (m != null && m.x == px && m.y == py && m.z == pz && m.dimensionWorldKey().equals(dim)) {
                return m;
            }
        }
        return null;
    }

    public static @Nullable ServerWorld getWorldForClam(MinecraftServer server, @Nullable Clam m) {
        if (m == null || server == null) {
            return null;
        }
        return server.getWorld(m.dimensionWorldKey());
    }

    /**
     * After world reload: link runtime {@link Clam} from heart blast furnace block entity custom data
     * (replaces legacy {@code modules.siva} bootstrap).
     */
    public static void tryRegisterFromClamCoreBlockEntity(ServerWorld world, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return;
        }
        if (findClamAt(world, pos) != null) {
            return;
        }
        Clam snap = SearingHeartItems.readClamTemplateFromComponentMap(furnace.getComponents());
        if (snap == null) {
            return;
        }
        Clam m = new Clam();
        SearingHeartItems.applyTemplateOntoClam(snap, m);
        m.x = pos.getX();
        m.y = pos.getY();
        m.z = pos.getZ();
        m.worldKey = world.getRegistryKey();
        m.ensureClamId();
        Clam existing = clamsById.get(m.clamId);
        if (existing != null) {
            if (existing.x == m.x && existing.y == m.y && existing.z == m.z
                && existing.dimensionWorldKey().equals(m.dimensionWorldKey())) {
                return;
            }
            return;
        }
        if (registerClam(m)) {
            SearingHeartItems.syncClamToBlockEntity(furnace, m);
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
        for (Clam m : clamsById.values()) {
            if (m == null) {
                continue;
            }
            ServerWorld w = getWorldForClam(server, m);
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

    static void clearSeekCachesAndBlacklistsAfterChunkUnloadExpiry(Clam m) {
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

    /** Push live {@link Clam} fields into the heart blast furnace so chunk NBT persists across restarts. */
    public static void syncClamCoreBlockEntityFromClam(ServerWorld world, Clam m) {
        if (m == null) return;
        BlockPos pos = new BlockPos(m.x, m.y, m.z);
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            SearingHeartItems.syncClamToBlockEntity(furnace, m);
        }
    }

    private static boolean registerClam(Clam m) {
        m.ensureClamId();
        if (clamsById.size() >= MAX_CLAMS) return false;
        clamsById.put(m.clamId, m);
        return true;
    }

    /** Placement from searing heart item (same package mixin); {@code false} if at capacity. */
    static boolean registerClamForSearingPlace(Clam m) {
        return registerClam(m);
    }

    /** First auto-grow deadline for a clam that has not been scheduled yet (spread across one interval by position). */
    public static void ensureAutoGrowScheduled(ServerWorld world, Clam m) {
        if (m.nextAutoGrowRepairWorldTime > 0) return;
        long t = world.getTime();
        int spread = Math.floorMod(m.x * 31 + m.y * 17 + m.z * 13, autoGrowRepairIntervalTicks());
        m.nextAutoGrowRepairWorldTime = t + 1 + spread;
    }

    /** After bulk registration (e.g. server start): give every clam in this world a first auto-grow fire time if unset. */
    public static void seedAutoGrowScheduleForAllClams(ServerWorld world) {
        for (Clam mm : clamsById.values()) {
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
        Clam mod = getClamById(clamId);
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
        if (findClamAt((ServerWorld) world, pos) == null) return;
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
    public static void placeHeartBlockForClam(ServerWorld world, BlockPos pos, Clam m) {
        boolean lit = m != null && isSearingHeartThermallyActive(world, m);
        BlockState state = VoidClamCoreBlocks.CORE_BLOCK.getDefaultState().with(AbstractFurnaceBlock.LIT, lit);
        world.setBlockState(pos, state);
        BlockEntity be = world.getBlockEntity(pos);
        if (m != null && be instanceof AbstractFurnaceBlockEntity furnace) {
            SearingHeartItems.syncClamToBlockEntity(furnace, m);
        } else {
            applySearingHeartBlockLabel(world, pos);
        }
    }

    /**
     * Remove blast furnace item entities at the break position so {@link #onClamCoreBroken}'s single Searing Heart drop
     * does not stack with vanilla loot (including a tagged Searing Heart when {@link com.serbanstein.voidclam.mixin.AbstractBlockStateMixin} did not suppress loot).
     */
    public static void stripVanillaBlastFurnaceDropsNear(ServerWorld world, BlockPos pos) {
        Box box = Box.of(Vec3d.ofCenter(pos), 0.45, 0.45, 0.45);
        for (Entity entity : world.getOtherEntities(null, box, e -> e instanceof ItemEntity)) {
            ItemEntity itemEntity = (ItemEntity) entity;
            if (itemEntity.getStack().isOf(Items.BLAST_FURNACE)) {
                itemEntity.discard();
            }
        }
    }

    /**
     * Clam core broken: replace the default blast furnace drop with a fresh Searing Heart (baby template),
     * then remove the clam from the registry (heart NBT / optional CSV mirror updated via kill path).
     */
    public static void onClamCoreBroken(ServerWorld world, @Nullable PlayerEntity player, BlockPos pos, BlockState state) {
        breakingClamFurnaceComponents.remove();
        Clam m = findClamAt(world, pos);
        if (m == null) return;
        stripVanillaBlastFurnaceDropsNear(world, pos);
        // Baby heart only: no carry-over clam size/stats or furnace contents (furnaceSnap ignored).
        ItemStack drop = SearingHeartItems.createFreshHeartStack();
        net.minecraft.block.Block.dropStack(world, pos, drop);
        clamKillBlocking(world.getServer(), m.clamId, true);
    }

    /**
     * After a searing heart item successfully places a blast furnace: new clam id at placement position,
     * inheriting clam fields from the item.
     */
    public static void onSearingHeartItemPlaced(ServerWorld world, BlockPos pos, ItemStack templateFromBeforeConsume) {
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        Clam snap = SearingHeartItems.readClamTemplateFromStack(templateFromBeforeConsume);
        if (snap == null) return;
        if (findClamAt(world, pos) != null) return;
        Clam m = new Clam();
        SearingHeartItems.applyTemplateOntoClam(snap, m);
        m.clamId = UUID.randomUUID();
        m.x = pos.getX();
        m.y = pos.getY();
        m.z = pos.getZ();
        m.worldKey = world.getRegistryKey();
        if (!registerClamForSearingPlace(m)) {
            world.breakBlock(pos, false);
            net.minecraft.block.Block.dropStack(world, pos, templateFromBeforeConsume.copy());
            return;
        }
        m.status = 0;
        m.stubBuilt = false;
        m.repairWakeCyclesRemaining = SEARING_WAKE_REPAIR_CYCLES;
        syncClamCoreBlockEntityFromClam(world, m);
        startSeekCachesRebuild(m);
    }

    /**
     * When the heart is fully ice-encased, clear sync A* jobs, busy flags, and queued path targets for that clam.
     * Called once per world each tick before {@link Pathfinder#tickSyncAStarJobs} so sync path work does not advance after dormancy.
     */
    public static void cancelActivePathfindingForFullyIceEncasedClams(ServerWorld world) {
        for (Clam m : clamsById.values()) {
            if (m == null || !m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            BlockPos heart = new BlockPos(m.x, m.y, m.z);
            if (!isHeartFullyIceEncased(world, heart)) continue;
            Pathfinder.clearSyncAStarJobsForClam(m.clamId);
            releasePathfindingMainCycle(m);
            purgeTargetsForClam(m.clamId);
        }
    }

    /**
     * Server tick for clams whose {@link Clam#dimensionWorldKey()} matches {@code world}.
     */
    public static void tickLoadedClamCores(ServerWorld world) {
        long t = world.getTime();
        for (Clam m : clamsById.values()) {
            if (m == null || !m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
            if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
            if (m.clamId == null) {
                m.ensureClamId();
                syncClamCoreBlockEntityFromClam(world, m);
            }
            BlockPos heartPos = new BlockPos(m.x, m.y, m.z);
            boolean iceEncased = isHeartFullyIceEncased(world, heartPos);
            if (m.iceEncasedLastTick && !iceEncased) {
                m.repairWakeCyclesRemaining = SEARING_WAKE_REPAIR_CYCLES;
                m.status = 0;
                syncClamCoreBlockEntityFromClam(world, m);
                placeHeartBlockForClam(world, heartPos, m);
            }
            m.iceEncasedLastTick = iceEncased;
            if (m.repairResizeChainAwaitingCompletion
                && m.pathfindingResumeWorldTime > 0
                && t >= m.pathfindingResumeWorldTime
                && !isResizeShellAnimationPending(world)) {
                m.repairResizeChainAwaitingCompletion = false;
                onRepairResizeChainCompleted(world, m);
            }
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
            clampResourcesForSize(m);
            syncClamCoreBlockEntityFromClam(world, m);
            tickLightCacheRebuildStep(world, m);
            tickOreCacheRebuildStep(world, m);
            if (m.busyFlagMainCycle == 0
                && (m.lightPathGoalPacked != null || m.orePathGoalPacked != null)) {
                releasePathfindingMainCycle(m);
            }
            BlockPos pos = heartPos;
            tryConsumeFuelAndWakeClam(world, m);
            ensureAutoGrowScheduled(world, m);
            if (isSearingHeartThermallyActive(world, m)) {
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
            int defenseIntervalTicks = VoidClamConfig.get().defenseDetectionIntervalTicks();
            int defensePhase = Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13 + 7, defenseIntervalTicks);
            UUID clamId = m.clamId;
            if ((t + phase) % 20 == 0) {
                tickCoreCheckAtHeart(world, pos, clamId);
            }
            if (!isSearingHeartThermallyActive(world, m)) continue;
            if ((t + seekPhase) % seekIntervalTicks == 0) {
                double seekP = VoidClamConfig.get().clam_seek_attempt_probability;
                if (seekP >= 1.0 || world.random.nextDouble() < seekP) {
                    CommandToolbox.clamReach(world, clamId);
                }
            }
            if ((t + phase + 11) % (4 * 20) == 0) {
                tickHeartbeatForClam(world, getClamByClamId(clamId));
            }
            if ((t + defensePhase) % defenseIntervalTicks == 0) {
                tickDefenseForClam(world, getClamByClamId(clamId));
            }
            tickApproachDefenseForClam(world, m, t);
            tickThermalAmbienceForClam(world, m, t);
        }
    }

    private static boolean isCopperTorchOrLantern(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        if (id == null) return false;
        String p = id.getPath();
        if (!p.contains("copper")) return false;
        if (p.contains("bulb")) return false;
        return p.contains("torch") || p.contains("lantern");
    }

    public static boolean isLight(Block block) {
        return lights.contains(block) || isCopperTorchOrLantern(block);
    }

    public static int lightEnergyForBlock(Block block) {
        if (block == Blocks.BEACON) return 5;
        if (fullBlockLightEnergy2.contains(block)) return 2;
        return 1;
    }

    /**
     * Half-width of the light seek box on each axis: {@code max(dx,dy,dz) <= 4 * effectiveSize},
     * where {@code effectiveSize = max(1, currentSize)} so legacy or invalid size {@code 0} still gets a normal scan box.
     */
    public static int lightSeekHalfExtent(int currentSize) {
        return 4 * Math.max(1, currentSize);
    }

    public static boolean inLightSeekRange(Clam m, BlockPos pos) {
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
    public static void startLightCacheRebuild(Clam m) {
        if (m == null) return;
        if (!VoidClamConfig.get().lightBlockCacheEnabled()) return;
        m.lightsCache.clear();
        m.lightCacheRebuildTicksRemaining = LIGHT_CACHE_REBUILD_TICKS;
        m.lightCacheRebuildCursor = 0L;
    }

    /** Clears the ore cache and rescans the seek box over {@link #LIGHT_CACHE_REBUILD_TICKS} server ticks. */
    public static void startOreCacheRebuild(Clam m) {
        if (m == null) return;
        if (!VoidClamConfig.get().oreBlockCacheEnabled()) return;
        m.oresCache.clear();
        m.oreCacheRebuildTicksRemaining = LIGHT_CACHE_REBUILD_TICKS;
        m.oreCacheRebuildCursor = 0L;
    }

    /** After resize/repair/wake: restart both seek caches when their respective configs are enabled. */
    public static void startSeekCachesRebuild(Clam m) {
        startLightCacheRebuild(m);
        startOreCacheRebuild(m);
    }

    /**
     * Call from server tick for each loaded clam while {@link Clam#lightCacheRebuildTicksRemaining} &gt; 0.
     * Processes a fair slice of the scan volume for this tick.
     */
    public static void tickLightCacheRebuildStep(ServerWorld world, Clam m) {
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
     * Call from server tick for each loaded clam while {@link Clam#oreCacheRebuildTicksRemaining} &gt; 0.
     * Processes a fair slice of the scan volume for this tick.
     */
    public static void tickOreCacheRebuildStep(ServerWorld world, Clam m) {
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
        Clam m = getClamById(clamId);
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
        Clam m = getClamById(clamId);
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
     * Clears main-cycle busy, releases path locks in {@link Clam#lightsBlackList} / {@link Clam#oresBlackList},
     * and clears active light / ore goals.
     */
    public static void releasePathfindingMainCycle(Clam m) {
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
        m.orePathForMaterialHunger = false;
    }

    /**
     * One delayed {@link Pathfinder#buildPath} step finished (server thread). When the last step completes, calls
     * {@link #releasePathfindingMainCycle}. Stale steps after a force-release see pending 0 and do nothing.
     */
    public static void completeOnePathApplyStep(Clam m) {
        if (m == null || m.pathApplyPendingSteps <= 0) {
            return;
        }
        m.pathApplyPendingSteps--;
        if (m.pathApplyPendingSteps == 0) {
            releasePathfindingMainCycle(m);
        }
    }

    /**
     * Mixin hook: queue work for {@link #drainPendingLightCacheDeltas}; do not scan clams inside {@code setBlockState}.
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
    private static void ensureLightSeekCacheForIncomingDelta(Clam m, ServerWorld world) {
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
    private static void ensureOreSeekCacheForIncomingDelta(Clam m, ServerWorld world) {
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
        for (Clam m : clamsById.values()) {
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
        for (Clam m : clamsById.values()) {
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
     * If the core furnace has a valid wake item in the fuel slot, consume one and mark the clam awake ({@link Clam#status} {@code 1}).
     */
    public static void tryConsumeFuelAndWakeClam(ServerWorld world, Clam m) {
        if (m == null || m.status != 0) return;
        BlockPos pos = new BlockPos(m.x, m.y, m.z);
        if (isHeartFullyIceEncased(world, pos)) {
            return;
        }
        if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return;
        ItemStack fuel = furnace.getStack(CLAM_CORE_FUEL_SLOT);
        if (fuel.isEmpty() || !isClamWakeFuel(world, fuel)) return;
        fuel.decrement(1);
        furnace.setStack(CLAM_CORE_FUEL_SLOT, fuel);
        furnace.markDirty();
        m.repairWakeCyclesRemaining = 0;
        m.repairResizeChainAwaitingCompletion = false;
        m.status = 1;
        ensureAutoGrowScheduled(world, m);
        placeHeartBlockForClam(world, pos, m);
        applyPostWakeShellAndSeek(world, m);
    }
    public static boolean isOre(Block block) {
        VoidClamConfig cfg = VoidClamConfig.get();
        if (cfg != null && cfg.clam_ore_detect_with_c_ores_tag && block.getDefaultState().isIn(COMMON_ORES_TAG)) {
            return true;
        }
        return ores.contains(block);
    }
    public static boolean isBaseCost(Block block) { return baseCost.contains(block); }

    public static boolean isClamInLoadedChunk(ServerWorld world, @Nullable UUID clamId) {
        Clam m = getClamById(clamId);
        return m != null && world.isChunkLoaded(m.x >> 4, m.z >> 4);
    }

    /** @deprecated use {@link #clamMatchesAt(UUID, int, int, int)} */
    @Deprecated
    public static boolean clamAtSlotMatchesPosition(int tno, int x, int y, int z) {
        return false;
    }

    /** @deprecated use {@link #getClamById} */
    @Deprecated
    public static int getSlotByClamId(@Nullable UUID clamId) {
        return -1;
    }

    public static @Nullable Clam getClamByClamId(@Nullable UUID clamId) {
        return getClamById(clamId);
    }

    public static boolean clamMatchesAt(@Nullable UUID clamId, int x, int y, int z) {
        if (clamId == null) return false;
        Clam m = getClamById(clamId);
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

    /** Heart dimension loaded, heart chunk loaded, and searing core block still present at recorded center. */
    public static boolean isHeartSurfaceLoadedWithCoreBlock(MinecraftServer server, @Nullable Clam m) {
        if (m == null || server == null) {
            return false;
        }
        ServerWorld world = getWorldForClam(server, m);
        if (world == null) {
            return false;
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            return false;
        }
        return world.getBlockState(new BlockPos(m.x, m.y, m.z)).isOf(VoidClamCoreBlocks.CORE_BLOCK);
    }

    /**
     * True when path/grow/cache bookkeeping for this clam still shows work in flight (queues, goals, busy flags, rebuild ticks).
     * Paired with {@link #isHeartSurfaceLoadedWithCoreBlock} to detect stuck state when the heart cannot be ticked.
     */
    public static boolean clamHasResidualPathfindingOrGrowActivity(@Nullable Clam m) {
        if (m == null) {
            return false;
        }
        if (m.busyFlagMainCycle != 0) {
            return true;
        }
        if (m.busyFlagPlaceEvent != 0) {
            return true;
        }
        if (m.pathApplyPendingSteps != 0) {
            return true;
        }
        if (m.lightPathGoalPacked != null || m.orePathGoalPacked != null) {
            return true;
        }
        if (countTargetsQueuedForClam(m.clamId) > 0) {
            return true;
        }
        if (m.lightCacheRebuildTicksRemaining > 0 || m.oreCacheRebuildTicksRemaining > 0) {
            return true;
        }
        if (Pathfinder.hasSyncAStarWorkForClam(m.clamId)) {
            return true;
        }
        return growPendingWorld != null && m.clamId.equals(growCommandClamId);
    }

    private static String describeHeartNotTickableReason(MinecraftServer server, Clam m) {
        ServerWorld w = getWorldForClam(server, m);
        if (w == null) {
            return "dimension_not_loaded";
        }
        if (!w.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            return "heart_chunk_unloaded";
        }
        if (!w.getBlockState(new BlockPos(m.x, m.y, m.z)).isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return "heart_block_missing_or_wrong";
        }
        return "tickable";
    }

    /**
     * If a clam still has path/grow/cache activity but its heart chunk is missing, wrong block, or dimension unloaded,
     * log occasionally for the mod author (replaces ad-hoc debug commands).
     */
    public static void tickOrphanedClamActivityWarnings(MinecraftServer server) {
        if (server == null) {
            return;
        }
        lastOrphanedActivityWarnWorldTick.keySet().removeIf(id -> getClamById(id) == null);
        ServerWorld clockWorld = server.getOverworld();
        if (clockWorld == null) {
            Iterator<ServerWorld> it = server.getWorlds().iterator();
            clockWorld = it.hasNext() ? it.next() : null;
        }
        if (clockWorld == null) {
            return;
        }
        long now = clockWorld.getTime();
        if (now % 100 != 0) {
            return;
        }
        for (Clam m : clamsById.values()) {
            if (m == null) {
                continue;
            }
            if (isHeartSurfaceLoadedWithCoreBlock(server, m)) {
                continue;
            }
            if (!clamHasResidualPathfindingOrGrowActivity(m)) {
                continue;
            }
            Long last = lastOrphanedActivityWarnWorldTick.get(m.clamId);
            if (last != null && now - last < ORPHANED_ACTIVITY_WARN_COOLDOWN_TICKS) {
                continue;
            }
            lastOrphanedActivityWarnWorldTick.put(m.clamId, now);
            LOGGER.warn(
                "[voidclam] Residual mod activity while heart not tickable (report to mod author): clamId={} at {} {} {} dim={} reason={}",
                m.clamId,
                m.x,
                m.y,
                m.z,
                m.dimensionWorldKey().getValue(),
                describeHeartNotTickableReason(server, m));
        }
    }

    /** OP debug: flags, busy state, caches, and queue stats for one clam. */
    public static List<String> debugClamFlagLines(MinecraftServer server, Clam m) {
        List<String> lines = new ArrayList<>();
        ServerWorld world = getWorldForClam(server, m);
        if (m == null) {
            return lines;
        }
        if (world == null) {
            boolean oreHunger = m.seekLights && m.material < m.materialSeekThreshold;
            boolean materialOreFlow = oreHunger || (m.seekLights && m.prioritizeRepairOreSeek);
            String activeGoal = m.orePathGoalPacked != null ? "ore" : (m.lightPathGoalPacked != null ? "light" : "none");
            lines.add("flags: seekLights=" + m.seekLights + " seekOres=" + m.seekOres + " protectItself=" + m.protectItself
                + " status=" + m.status + " stubBuilt=" + m.stubBuilt + " (dimension not loaded)");
            lines.add("busy: mainCycle=" + m.busyFlagMainCycle + " placeEvent=" + m.busyFlagPlaceEvent
                + " pathApplyPendingSteps=" + m.pathApplyPendingSteps);
            lines.add("path: lightPathGoalPacked=" + m.lightPathGoalPacked + " orePathGoalPacked=" + m.orePathGoalPacked
                + " resumeWorldTime=" + m.pathfindingResumeWorldTime + " pathAllowed=?");
            lines.add("caches: lightsCache=" + m.lightsCache.size() + " oresCache=" + m.oresCache.size()
                + " lightsBL=" + m.lightsBlackList.size() + " oresBL=" + m.oresBlackList.size()
                + " lightOreRebuildTicks=" + m.lightCacheRebuildTicksRemaining + "/" + m.oreCacheRebuildTicksRemaining);
            lines.add("resources: energy=" + m.energy + " material=" + m.material + " cap=" + resourceCapForSize(m.currentSize)
                + " prioritizeRepairOreSeek=" + m.prioritizeRepairOreSeek + " orePathForMaterialHunger=" + m.orePathForMaterialHunger);
            lines.add("seekDecision: materialOreFlow=" + materialOreFlow + " oreHunger=" + oreHunger + " materialSeekThreshold=" + m.materialSeekThreshold + " activeGoal=" + activeGoal);
            lines.add("schedule: nextAutoGrowRepairWT=" + m.nextAutoGrowRepairWorldTime + " worldTime=?");
            lines.add("targetsQueuedForClam=" + countTargetsQueuedForClam(m.clamId));
            return lines;
        }
        boolean oreHunger = m.seekLights && m.material < m.materialSeekThreshold;
        boolean materialOreFlow = oreHunger || (m.seekLights && m.prioritizeRepairOreSeek);
        String activeGoal = m.orePathGoalPacked != null ? "ore" : (m.lightPathGoalPacked != null ? "light" : "none");
        lines.add("flags: seekLights=" + m.seekLights + " seekOres=" + m.seekOres + " protectItself=" + m.protectItself
            + " status=" + m.status + " stubBuilt=" + m.stubBuilt);
        lines.add("busy: mainCycle=" + m.busyFlagMainCycle + " placeEvent=" + m.busyFlagPlaceEvent
            + " pathApplyPendingSteps=" + m.pathApplyPendingSteps);
        lines.add("path: lightPathGoalPacked=" + m.lightPathGoalPacked + " orePathGoalPacked=" + m.orePathGoalPacked
            + " resumeWorldTime=" + m.pathfindingResumeWorldTime + " pathAllowed=" + isPathfindingAllowedYet(world, m));
        lines.add("caches: lightsCache=" + m.lightsCache.size() + " oresCache=" + m.oresCache.size()
            + " lightsBL=" + m.lightsBlackList.size() + " oresBL=" + m.oresBlackList.size()
            + " lightOreRebuildTicks=" + m.lightCacheRebuildTicksRemaining + "/" + m.oreCacheRebuildTicksRemaining);
        lines.add("resources: energy=" + m.energy + " material=" + m.material + " cap=" + resourceCapForSize(m.currentSize)
            + " prioritizeRepairOreSeek=" + m.prioritizeRepairOreSeek + " orePathForMaterialHunger=" + m.orePathForMaterialHunger);
        lines.add("seekDecision: materialOreFlow=" + materialOreFlow + " oreHunger=" + oreHunger + " materialSeekThreshold=" + m.materialSeekThreshold + " activeGoal=" + activeGoal);
        lines.add("schedule: nextAutoGrowRepairWT=" + m.nextAutoGrowRepairWorldTime + " worldTime=" + world.getTime());
        lines.add("targetsQueuedForClam=" + countTargetsQueuedForClam(m.clamId));
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
        Clam m = getClamById(clamId);
        if (m != null) m.oresBlackList.remove(pos.asLong());
    }

    public static void addOresBlackList(UUID clamId, BlockPos pos) {
        Clam m = getClamById(clamId);
        if (m != null) m.oresBlackList.add(pos.asLong());
    }

    public static void addEnergy(UUID clamId, int delta) {
        Clam m = getClamById(clamId);
        if (m != null) {
            m.energy = m.energy + delta;
            clampResourcesForSize(m);
        }
    }

    public static void addMaterial(UUID clamId, int delta) {
        Clam m = getClamById(clamId);
        if (m != null) {
            m.material = m.material + delta;
            clampResourcesForSize(m);
        }
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
            Clam tm = getClamById(n.clamId);
            ServerWorld world = getWorldForClam(server, tm);
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
    public static void migrateLoadedClamsToHeartBlocks(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            for (Clam m : clamsById.values()) {
                if (m == null) continue;
                if (!m.dimensionWorldKey().equals(world.getRegistryKey())) continue;
                if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
                BlockPos p = new BlockPos(m.x, m.y, m.z);
                Block b = world.getBlockState(p).getBlock();
                if (b == VoidClamCoreBlocks.CORE_BLOCK) {
                    continue;
                }
                if (b == Blocks.NETHER_WART_BLOCK || b == Blocks.OBSIDIAN) {
                    placeHeartBlockForClam(world, p, m);
                }
            }
        }
    }

    /** Create a new stub at (x,y,z). Returns clam UUID string for commands, or null on failure. */
    public static @Nullable UUID makeStub(ServerWorld world, int x, int y, int z) {
        Clam m = new Clam();
        m.clamId = UUID.randomUUID();
        m.type = 1;
        m.x = x;
        m.y = y;
        m.z = z;
        m.worldKey = world.getRegistryKey();
        m.currentSize = 3;
        m.status = 0;
        m.energy = 0;
        m.age = 0;
        VoidClamConfig cfg = VoidClamConfig.get();
        m.seekLights = cfg.clam_light_flag_default;
        m.seekOres = cfg.clam_ores_flag_default;
        m.protectItself = cfg.clam_protect_itself_default;
        m.materialSeekThreshold = materialSeekThresholdBaselineForSize(m.currentSize);
        m.repairWakeCyclesRemaining = 0;
        if (!registerClam(m)) {
            return null;
        }
        placeHeartBlockForClam(world, new BlockPos(x, y, z), m);
        CommandToolbox.buildStub(world, x, y, z);
        m.stubBuilt = true;
        startSeekCachesRebuild(m);
        return m.clamId;
    }

    public static void clamKill(MinecraftServer server, UUID clamId, boolean saveAfter) {
        clamKillBlocking(server, clamId, saveAfter);
    }

    public static void requestRepairCommand(ServerWorld world, UUID clamId) {
        Clam m = getClamById(clamId);
        if (m == null) return;
        requestGrowCommand(world, clamId, m.currentSize);
    }

    public static void requestGrowCommand(ServerWorld world, UUID clamId, int targetSize) {
        Clam target = getClamById(clamId);
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
        Clam m = getClamById(clamId);
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
        Clam m = getClamById(cmdId);
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

    /**
     * Expected obsidian shell geometry built by {@link CommandToolbox#buildShell}: diamond rings per Y level,
     * intentional horizontal gap at y=0, and intentionally no full bottom cap.
     */
    public static boolean isExpectedObsidianShellBlock(int dx, int dy, int dz, int size) {
        int t = Math.max(1, size);
        if (dy == 0) return false; // intentional horizontal gap
        int yMin = -t / 2 + 1;     // intentional lack of bottom cap
        int yMax = t - 1;
        if (dy < yMin || dy > yMax) return false;
        int ringRadius = (t - 1) - Math.abs(dy);
        if (ringRadius < 0) return false;
        return Math.abs(dx) + Math.abs(dz) == ringRadius;
    }

    public static ShellDamageStats inspectObsidianShellDamage(ServerWorld world, Clam m) {
        int size = Math.max(1, m.currentSize);
        return inspectObsidianShellDamageAt(world, m.x, m.y, m.z, size);
    }

    /** Obsidian present vs missing on the expected shell lattice for a hypothetical size (same geometry as {@link #inspectObsidianShellDamage}). */
    public static ShellDamageStats inspectObsidianShellDamageAt(ServerWorld world, int cx, int cy, int cz, int size) {
        int t = Math.max(1, size);
        int missing = 0;
        int present = 0;
        int yMin = -t / 2 + 1;
        int yMax = t - 1;
        int horiz = Math.max(0, t - 1);
        for (int dy = yMin; dy <= yMax; dy++) {
            for (int dx = -horiz; dx <= horiz; dx++) {
                for (int dz = -horiz; dz <= horiz; dz++) {
                    if (!isExpectedObsidianShellBlock(dx, dy, dz, t)) continue;
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (world.getBlockState(p).isOf(Blocks.OBSIDIAN)) {
                        present++;
                    } else {
                        missing++;
                    }
                }
            }
        }
        return new ShellDamageStats(present, missing);
    }

    private static boolean clamShellScanRegionChunksLoaded(ServerWorld world, int cx, int cy, int cz, int t) {
        int yMinW = cy + (-t / 2 + 1);
        int yMaxW = cy + (t - 1);
        int horiz = Math.max(0, t - 1);
        for (int iy = yMinW; iy <= yMaxW; iy++) {
            for (int ix = cx - horiz; ix <= cx + horiz; ix++) {
                for (int iz = cz - horiz; iz <= cz + horiz; iz++) {
                    if (!world.isChunkLoaded(ix >> 4, iz >> 4)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isNaturalTerrainBlock(BlockState st) {
        if (st.isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return false;
        }
        return st.isIn(BlockTags.DIRT)
            || st.isIn(BlockTags.SAND)
            || st.isIn(BlockTags.BASE_STONE_OVERWORLD)
            || st.isIn(BlockTags.BASE_STONE_NETHER)
            || st.isIn(BlockTags.LEAVES)
            || st.isIn(BlockTags.LOGS)
            || st.isIn(BlockTags.LOGS_THAT_BURN)
            || st.isIn(BlockTags.ICE)
            || st.isIn(BlockTags.SNOW)
            || st.isIn(BlockTags.CORALS)
            || st.isIn(BlockTags.CORAL_BLOCKS)
            || st.isIn(BlockTags.SMALL_FLOWERS)
            || st.isIn(BlockTags.FLOWERS)
            || st.isIn(BlockTags.CROPS)
            || st.isIn(BlockTags.REPLACEABLE_BY_TREES);
    }

    private static boolean isAllowedPrebuiltShellInteriorBlock(BlockState st) {
        if (st.isAir() || st.isOf(Blocks.WATER) || st.isOf(Blocks.NETHER_WART_BLOCK)) {
            return true;
        }
        return isNaturalTerrainBlock(st);
    }

    /**
     * Octahedron interior (excluding the heart block): air, water, wart, or natural terrain ({@link #isNaturalTerrainBlock}).
     */
    private static boolean isPrebuiltShellInteriorClear(ServerWorld world, int cx, int cy, int cz, int t) {
        int horiz = Math.max(0, t - 1);
        int yLo = cy + (-t / 2 + 1);
        int yHi = cy + (t - 2);
        for (int iy = yLo; iy <= yHi; iy++) {
            for (int ix = cx - horiz; ix <= cx + horiz; ix++) {
                for (int iz = cz - horiz; iz <= cz + horiz; iz++) {
                    int dx = ix - cx, dy = iy - cy, dz = iz - cz;
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!CommandToolbox.isInsideOctahedronInterior(dx, dy, dz, t)) {
                        continue;
                    }
                    if (!world.isChunkLoaded(ix >> 4, iz >> 4)) {
                        return false;
                    }
                    BlockState st = world.getBlockState(new BlockPos(ix, iy, iz));
                    if (!isAllowedPrebuiltShellInteriorBlock(st)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * After placing a searing heart: find the smallest size ≥ current size where an existing shell has {@code >50%} obsidian
     * on expected shell cells and the octahedron interior is clear; grow into that shell if larger than current.
     *
     * @return one of {@code PREBUILT_WAKE_NONE}, {@code PREBUILT_WAKE_RESIZED}, or {@code PREBUILT_WAKE_ALREADY_MATCHES}
     */
    private static int tryAutogrowIntoPrebuiltShell(ServerWorld world, Clam m) {
        if (m == null || !world.getRegistryKey().equals(m.dimensionWorldKey())) {
            return PREBUILT_WAKE_NONE;
        }
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            return PREBUILT_WAKE_NONE;
        }
        int maxSize = VoidClamConfig.get().clam_size_max;
        int start = Math.max(1, m.currentSize);
        for (int t = start; t <= maxSize; t++) {
            if (!clamShellScanRegionChunksLoaded(world, m.x, m.y, m.z, t)) {
                return PREBUILT_WAKE_NONE;
            }
            ShellDamageStats shell = inspectObsidianShellDamageAt(world, m.x, m.y, m.z, t);
            int total = shell.shellTotal();
            if (total <= 0) {
                continue;
            }
            if (shell.obsidianPresent() <= shell.shellMissing()) {
                continue;
            }
            if (!isPrebuiltShellInteriorClear(world, m.x, m.y, m.z, t)) {
                continue;
            }
            if (t > m.currentSize) {
                prepareClamForResizeShell(m);
                CommandToolbox.clamReSize(world, m.clamId, t);
                return PREBUILT_WAKE_RESIZED;
            }
            return PREBUILT_WAKE_ALREADY_MATCHES;
        }
        return PREBUILT_WAKE_NONE;
    }

    private static boolean isGrowthPassThrough(BlockState state) {
        if (state == null || state.isAir() || state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)
            || state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.NETHER_WART_BLOCK)
            || state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return true;
        }
        return state.isIn(SCULK_REPLACEABLE_TAG) || state.isIn(PALE_MOSS_REPLACE_TAG);
    }

    /** Auto repair + optional grow for one clam (scheduled periodically when awake). */
    private static void runAutoGrowRoutineSingle(ServerWorld world, Clam m) {
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        int x = m.x, y = m.y, z = m.z, csize = m.currentSize;
        int shellDamage = inspectObsidianShellDamage(world, m).shellMissing();
        if (shellDamage > 0) {
            m.prioritizeRepairOreSeek = m.seekLights && m.material < shellDamage;
            CommandToolbox.clamReSize(world, m.clamId, m.currentSize);
            m.oresBlackList.clear();
            m.lightsBlackList.clear();
            clampResourcesForSize(m);
            return;
        }
        m.prioritizeRepairOreSeek = false;
        int matCap = resourceCapForSize(m.currentSize);
        m.materialSeekThreshold = Math.min(Math.max(0, m.materialSeekThreshold) + 1, matCap);
        syncClamCoreBlockEntityFromClam(world, m);
        // No shell damage healthy cycle: raise ore comfort target so material gathering scales with time intact.
        CommandToolbox.clamReSize(world, m.clamId, m.currentSize);
        m.oresBlackList.clear();
        m.lightsBlackList.clear();
        VoidClamConfig cfg = VoidClamConfig.get();
        if (m.energy > cfg.clam_grow_energymultiplier * m.currentSize && m.currentSize < cfg.clam_size_max) {
            double cst = 0;
            for (int ix = x - csize + 2; ix <= x + csize - 2; ix++) {
                for (int iz = z - csize + 2; iz <= z + csize - 2; iz++) {
                    for (int iy = y - 2; iy <= y + csize / 2 + 2; iy++) {
                        BlockState state = world.getBlockState(new BlockPos(ix, iy, iz));
                        if (!isGrowthPassThrough(state)) {
                            Block b = state.getBlock();
                            float br = b.getBlastResistance();
                            if (br < 0) {
                                cst = Double.MAX_VALUE;
                                break;
                            }
                            else cst += br;
                        }
                    }
                    if (cst == Double.MAX_VALUE) break;
                }
                if (cst == Double.MAX_VALUE) break;
            }
            if (cst <= 10 * csize) {
                int nextSize = Math.min(m.currentSize + 1, cfg.clam_size_max);
                int matCost = cfg.clam_grow_material_cost;
                if (nextSize > m.currentSize && matCost > 0 && m.material < matCost) {
                    // Room and energy OK; wait for more material before +1 size.
                } else if (nextSize > m.currentSize) {
                    if (matCost > 0) {
                        addMaterial(m.clamId, -matCost);
                    }
                    m.energy = 0;
                    CommandToolbox.clamReSize(world, m.clamId, nextSize);
                    m.currentSize = nextSize;
                }
            }
        }
        clampResourcesForSize(m);
    }

    public static void tickCoreCheck(ServerWorld world) {
        List<UUID> toKill = new ArrayList<>();
        for (Clam m : clamsById.values()) {
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
        Clam m = getClamById(clamId);
        if (m == null || m.x != heartPos.getX() || m.y != heartPos.getY() || m.z != heartPos.getZ()) return;
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        Block block = world.getBlockState(heartPos).getBlock();
        if (block != VoidClamCoreBlocks.CORE_BLOCK && block != Blocks.NETHER_WART_BLOCK && block != Blocks.OBSIDIAN) {
            clamKill(world.getServer(), clamId, false);
        }
    }

    private static boolean shouldApproachDefenseAffect(Entity e) {
        if (e == null || !e.isAlive()) return false;
        if (e instanceof PlayerEntity p) {
            return !p.isSpectator() && !p.isCreative();
        }
        return true;
    }

    private static void playApproachDefenseSizzle(ServerWorld world, Entity entity, Random random) {
        float pitch = 0.85f + random.nextFloat() * 0.28f;
        world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.55f, pitch);
    }

    private static void spawnApproachDefenseTorchParticles(ServerWorld world, Entity entity, Random random, int count) {
        double ex = entity.getX();
        double ey = entity.getY() + entity.getHeight() * (0.35 + random.nextDouble() * 0.5);
        double ez = entity.getZ();
        double w = Math.max(0.25, entity.getWidth() * 0.55);
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * 2.0 * w;
            double oy = random.nextDouble() * Math.max(0.15, entity.getHeight() * 0.5);
            double oz = (random.nextDouble() - 0.5) * 2.0 * w;
            world.spawnParticles(ParticleTypes.FLAME, ex + ox, ey + oy, ez + oz, 1, 0.03, 0.06, 0.03, 0.015);
        }
    }

    public static void tickApproachDefenseForClam(ServerWorld world, Clam m, long worldTime) {
        if (m == null || !m.protectItself || m.currentSize < 3) return;
        if (!isSearingHeartThermallyActive(world, m)) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        double rField = m.currentSize / 4.0;
        if (rField < 0.3) return;
        double cx = m.x + 0.5, cy = m.y + 0.5, cz = m.z + 0.5;
        Box heartBox = new Box(m.x, m.y, m.z, m.x + 1.0, m.y + 1.0, m.z + 1.0);
        double r2 = rField * rField;
        Box fieldBox = new Box(cx - rField, cy - rField, cz - rField, cx + rField, cy + rField, cz + rField);
        Random random = world.getRandom();
        for (Entity entity : world.getOtherEntities(null, fieldBox, VoidClamMod::shouldApproachDefenseAffect)) {
            Vec3d ppos = entity.getEntityPos();
            double dx = ppos.x - cx, dy = ppos.y - cy, dz = ppos.z - cz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= r2) continue;
            double dist = Math.sqrt(d2);
            if (entity instanceof LivingEntity living) {
                if (living.getBoundingBox().intersects(heartBox)) {
                    living.setFireTicks(Math.max(living.getFireTicks(), 100));
                }
            }
            double inward = 1.0 - dist / rField;
            double push = 0.05 + 0.14 * inward * inward;
            if (dist > 1e-3) {
                dx /= dist;
                dy /= dist;
                dz /= dist;
                entity.addVelocity(dx * push, Math.max(0.04, dy * push + 0.025 * inward), dz * push);
            }
            if ((worldTime + entity.getId()) % 3 == 0) {
                spawnApproachDefenseTorchParticles(world, entity, random, 1 + random.nextInt(2));
            }
            if (entity instanceof LivingEntity living) {
                int dmgInterval = Math.max(4, (int) (4 + 15 * (dist / rField)));
                if ((worldTime + entity.getId()) % dmgInterval == 0) {
                    float amount = 0.5f + 3.5f * (float) inward;
                    if (living.damage(world, world.getDamageSources().magic(), amount)) {
                        playApproachDefenseSizzle(world, entity, random);
                        spawnApproachDefenseTorchParticles(world, entity, random, 2 + random.nextInt(2));
                    }
                }
            }
        }
    }

    private static BlockPos randomBlockInThermalSphere(Random random, double cx, double cy, double cz, double radius) {
        if (radius <= 0.0) {
            return BlockPos.ofFloored(cx, cy, cz);
        }
        double u = random.nextDouble();
        double v = random.nextDouble();
        double theta = 2.0 * Math.PI * u;
        double phi = Math.acos(2.0 * v - 1.0);
        double r = radius * Math.cbrt(random.nextDouble());
        double sinPhi = Math.sin(phi);
        double x = cx + r * sinPhi * Math.cos(theta);
        double y = cy + r * Math.cos(phi);
        double z = cz + r * sinPhi * Math.sin(theta);
        return BlockPos.ofFloored(x, y, z);
    }

    private static boolean blockSeesPrecipitatingWeather(ServerWorld world, BlockPos pos) {
        if (!world.isSkyVisible(pos)) return false;
        if (!world.getBiome(pos).value().hasPrecipitation()) return false;
        if (!world.getDimensionEntry().value().hasSkyLight()) return false;
        return world.hasRain(pos);
    }

    private static void tryMeltSnowOrIce(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isOf(Blocks.SNOW)) {
            int layers = state.get(SnowBlock.LAYERS);
            if (layers <= 1) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            } else {
                world.setBlockState(pos, state.with(SnowBlock.LAYERS, layers - 1), Block.NOTIFY_ALL);
            }
        } else if (state.isOf(Blocks.ICE) || state.isOf(Blocks.FROSTED_ICE)) {
            world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    public static void tickThermalAmbienceForClam(ServerWorld world, Clam m, long worldTime) {
        if (!isSearingHeartThermallyActive(world, m)) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        int ambPhase = Math.floorMod(m.x * 29 + m.y * 13 + m.z * 19, 5);
        if ((worldTime + ambPhase) % 5 != 0) return;
        double rad = CommandToolbox.clamOctahedronCircumsphereRadius(m.currentSize);
        double cx = m.x + 0.5, cy = m.y + 0.5, cz = m.z + 0.5;
        int samples = 4 + m.currentSize * 2;
        Random random = world.getRandom();
        for (int i = 0; i < samples; i++) {
            BlockPos pos = randomBlockInThermalSphere(random, cx, cy, cz, rad);
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            BlockState st = world.getBlockState(pos);
            tryMeltSnowOrIce(world, pos, st);
            st = world.getBlockState(pos);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            if (!blockSeesPrecipitatingWeather(world, pos)) continue;
            Biome biome = world.getBiome(pos).value();
            boolean snowBiome = biome.getPrecipitation(pos, world.getSeaLevel()) == Biome.Precipitation.SNOW;
            float passProb = snowBiome ? 0.32f : 0.14f;
            if (random.nextFloat() > passProb) continue;
            double px = pos.getX() + 0.5, py = pos.getY() + 0.65, pz = pos.getZ() + 0.5;
            int puffs = snowBiome ? 2 : 1;
            for (int pi = 0; pi < puffs; pi++) {
                world.spawnParticles(
                    ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                    px + (random.nextDouble() - 0.5) * 0.35,
                    py + random.nextDouble() * 0.15,
                    pz + (random.nextDouble() - 0.5) * 0.35,
                    1,
                    0.05, 0.12, 0.05,
                    snowBiome ? 0.012 : 0.004
                );
            }
            if (snowBiome && random.nextFloat() < 0.45f) {
                world.spawnParticles(
                    ParticleTypes.SMOKE,
                    px + (random.nextDouble() - 0.5) * 0.4,
                    py + 0.1,
                    pz + (random.nextDouble() - 0.5) * 0.4,
                    1,
                    0.02, 0.08, 0.02,
                    0.02
                );
            }
            if (snowBiome) {
                world.spawnParticles(
                    ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    px + (random.nextDouble() - 0.5) * 0.5,
                    py + 0.25 + random.nextDouble() * 0.2,
                    pz + (random.nextDouble() - 0.5) * 0.5,
                    1,
                    0.06, 0.18, 0.06,
                    0.018
                );
                if (random.nextFloat() < 0.55f) {
                    world.spawnParticles(
                        ParticleTypes.LARGE_SMOKE,
                        px + (random.nextDouble() - 0.5) * 0.45,
                        py + 0.35,
                        pz + (random.nextDouble() - 0.5) * 0.45,
                        1,
                        0.04, 0.14, 0.04,
                        0.015
                    );
                }
                if (random.nextFloat() < 0.35f) {
                    world.spawnParticles(
                        ParticleTypes.FLAME,
                        px + (random.nextDouble() - 0.5) * 0.25,
                        py + 0.2,
                        pz + (random.nextDouble() - 0.5) * 0.25,
                        1,
                        0.01, 0.05, 0.01,
                        0.008
                    );
                }
            }
        }
    }

    /** @see #tickDefenseForClam */
    private static boolean isPlayerInLegacyDefenseAnnulus(ServerPlayerEntity player, Clam m) {
        double rPush = m.currentSize / 4.0;
        double rIn = CommandToolbox.octahedronInteriorInscribedSphereRadius(m.currentSize);
        if (rIn <= rPush) {
            return false;
        }
        double cx = m.x + 0.5, cy = m.y + 0.5, cz = m.z + 0.5;
        Box box = player.getBoundingBox();
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > rPush && d <= rIn) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Defense for one clam (from {@link #tickLoadedClamCores} when interval matches). */
    public static void tickDefenseForClam(ServerWorld world, Clam m) {
        if (m == null || !isSearingHeartThermallyActive(world, m) || !m.protectItself || m.currentSize < DEFENSE_MIN_SIZE) {
            return;
        }
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        float volume = Math.min(3f, (float) m.currentSize / 4f);
        SoundEvent soundRef = SoundEvents.BLOCK_NOTE_BLOCK_BASS.value();
        if (SoundEvents.GOAT_HORN_SOUNDS.size() > 6) {
            soundRef = SoundEvents.GOAT_HORN_SOUNDS.get(6).value();
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator()) continue;
            if (!isPlayerInLegacyDefenseAnnulus(player, m)) continue;
            BlockPos playerBlock = player.getBlockPos();
            for (Direction d : Direction.values()) {
                BlockPos adj = playerBlock.offset(d);
                BlockState state = world.getBlockState(adj);
                if (state.isReplaceable() || state.isAir())
                    world.setBlockState(adj, Blocks.NETHER_WART_BLOCK.getDefaultState());
            }
            VoidClamSfx.playBlockSound(world, null, playerBlock.getX() + 0.5, playerBlock.getY() + 0.5, playerBlock.getZ() + 0.5,
                soundRef, SoundCategory.HOSTILE, volume, DEFENSE_HORN_PITCH);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, DEFENSE_EFFECT_TICKS, 0));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, DEFENSE_EFFECT_TICKS, 0));
        }
    }

    /** Legacy: defense for all clams (prefer {@link #tickDefenseForClam} from {@link #tickLoadedClamCores}). */
    public static void tickDefense(ServerWorld world) {
        for (Clam m : clamsById.values()) {
            tickDefenseForClam(world, m);
        }
    }

    /** Heartbeat for one clam (from {@link #tickLoadedClamCores}). */
    public static void tickHeartbeatForClam(ServerWorld world, Clam m) {
        if (m == null) return;
        if (!m.dimensionWorldKey().equals(world.getRegistryKey())) return;
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        float volume = (float) m.currentSize / 4;
        VoidClamSfx.playBlockSound(world, null, m.x + 0.5, m.y + 0.5, m.z + 0.5,
            net.minecraft.sound.SoundEvents.BLOCK_CONDUIT_AMBIENT, net.minecraft.sound.SoundCategory.BLOCKS, volume, 0.7f);
    }

    /** Legacy: heartbeat for all loaded clams. */
    public static void tickHeartbeat(ServerWorld world) {
        for (Clam m : clamsById.values()) {
            tickHeartbeatForClam(world, m);
        }
    }

}
