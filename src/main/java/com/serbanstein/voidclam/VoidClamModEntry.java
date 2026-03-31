package com.serbanstein.voidclam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Fabric mod entrypoint: lifecycle, server tick hook, and commands. */
public class VoidClamModEntry implements ModInitializer {
    private static final int TICK_OMNI_PULSE = 5 * 20;     // omnidirectional pulse every ~5s
    private static final int TICK_CLEANUP = 60 * 20;       // stray tendril display cleanup every 1 min
    /** Players who may use /voidclam without gamemaster permission (case-insensitive name match). */
    private static final Set<String> VOIDCLAM_TRUSTED_PLAYER_NAMES_LOWER = Collections.singleton("serbanstein");

    private static boolean canUseVoidclamCommands(ServerCommandSource source) {
        if (source.hasPermissionLevel(2)) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity)) {
            return false;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();
        String name = player.getName().asString();
        return VOIDCLAM_TRUSTED_PLAYER_NAMES_LOWER.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            VoidClamMod.captureClamCoreComponentsBeforeBreak(world, pos, state);
            if (world instanceof ServerWorld) {
                ServerWorld serverWorld = (ServerWorld) world;
                if (VoidClamMod.shouldCancelBreakingSearingHeart(serverWorld, player, pos, state)) {
                    return false;
                }
            }
            return true;
        });
        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) -> {
            VoidClamMod.clearBreakingClamFurnaceComponentsCapture();
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || !(world instanceof ServerWorld)) return;
            if (!state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
            VoidClamMod.onClamCoreBroken((ServerWorld) world, player, pos, state);
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || !(world instanceof ServerWorld)) return ActionResult.PASS;
            ServerWorld serverWorld = (ServerWorld) world;
            BlockPos pos = hitResult.getBlockPos();
            if (!world.getBlockState(pos).isOf(VoidClamCoreBlocks.CORE_BLOCK)) return ActionResult.PASS;
            if (VoidClamMod.findClamAt(serverWorld, pos) == null) return ActionResult.PASS;
            if (player.isSneaking()) {
                return ActionResult.PASS;
            }
            if (VoidClamMod.shouldCancelUsingSearingHeart(serverWorld, pos)) {
                player.setFireTicks(Math.max(player.getFireTicks(), 100));
                return ActionResult.FAIL;
            }
            VoidClamMod.applySearingHeartBlockLabel(serverWorld, pos);
            return ActionResult.PASS;
        });
        VoidClamConfig.loadFromDisk();
        ServerChunkEvents.CHUNK_LOAD.register(NaturalSpawnHandler::onChunkGenerated);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> registerCommands(dispatcher));
    }

    private void onServerStarted(MinecraftServer server) {
        VoidClamConfig.loadFromDisk();
        VoidClamMod.onAsyncPathfindingSessionStart();
        VoidClamMod.migrateLoadedClamsToHeartBlocks(server);
        for (ServerWorld w : server.getWorlds()) {
            VoidClamMod.seedAutoGrowScheduleForAllClams(w);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        VoidClamMod.onAsyncPathfindingSessionStop();
    }

    private void onServerTick(MinecraftServer server) {
        VoidClamMod.tickSeekEphemeralExpiry(server);
        VoidClamMod.drainPendingLightCacheDeltas();
        if (VoidClamConfig.get().astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            Pathfinder.tickSyncAStarJobs(VoidClamConfig.get().effectiveSyncMaxStepsPerTick());
        }
        for (ServerWorld w : server.getWorlds()) {
            VoidClamMod.tickLoadedClamCores(w);
            VoidClamMod.tickGrowPendingCheck(w);
            TendrilPulseManager.tick(w);
            VoidClamModScheduler.tick(w);
            TendrilPulseManager.tickOmniPulseJob(w);
        }
        VoidClamMod.tickTargets(server);
        VoidClamMod.tickOrphanedClamActivityWarnings(server);

        ServerWorld clockWorld = server.getOverworld();
        if (clockWorld == null) {
            Iterator<ServerWorld> worlds = server.getWorlds().iterator();
            if (worlds.hasNext()) {
                clockWorld = worlds.next();
            }
        }
        long tick = clockWorld != null ? clockWorld.getTime() : 0L;
        if (tick % TICK_OMNI_PULSE == 0) {
            for (ServerWorld w : server.getWorlds()) {
                TendrilPulseManager.runOmnidirectionalPulse(w);
            }
        }
        if (tick % TICK_CLEANUP == 0) {
            for (ServerWorld w : server.getWorlds()) {
                TendrilPulseManager.cleanupStrayDisplays(w);
            }
        }
    }

    private static @Nullable ServerWorld resolveHeartWorldForCommands(ServerCommandSource src, Clam m) {
        ServerWorld modWorld = VoidClamMod.getWorldForClam(src.getMinecraftServer(), m);
        if (modWorld == null) {
            src.sendError(new LiteralText("Dimension for this voidclam is not loaded."));
            return null;
        }
        if (!modWorld.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            src.sendError(new LiteralText("Heart chunk is not loaded."));
            return null;
        }
        return modWorld;
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("voidclam")
                .requires(VoidClamModEntry::canUseVoidclamCommands)
                .executes(ctx -> {
                    ctx.getSource().sendFeedback( new LiteralText("Use /voidclam help for syntax. Target = UUID (dashed or 32 hex) or three integers x y z (heart center)."), false);
                    return 1;
                })
                .then(CommandManager.literal("help")
                    .executes(ctx -> {
                        ServerCommandSource s = ctx.getSource();
                        s.sendFeedback( new LiteralText("Voidclam commands (gamemaster or trusted player). Target = UUID or x y z at heart."), false);
                        s.sendFeedback( new LiteralText("make <x> <y> <z> — new clam"), false);
                        s.sendFeedback( new LiteralText("kill <target>"), false);
                        s.sendFeedback( new LiteralText("repair <target> | reach <target> | grow <target> | storage <target>"), false);
                        s.sendFeedback( new LiteralText("seek ores|lights|protect set <true|false> <target> — bool before target"), false);
                        s.sendFeedback( new LiteralText("seek ores|lights|protect get <target>"), false);
                        s.sendFeedback( new LiteralText("info [target] — list all (console) or nearest (player)"), false);
                        s.sendFeedback( new LiteralText("status <target> — flags, grow/async, sync A* queue, path pool, scheduler"), false);
                        s.sendFeedback( new LiteralText("dumpnbt <target> — write searing heart block-entity NBT to run/voidclam-nbt-dumps/<UUID>.nbt"), false);
                        s.sendFeedback( new LiteralText("cleanup | roughcleanup | ping — state lives in searing heart blocks"), false);
                        s.sendFeedback( new LiteralText("Server config: config/voidclam.json — see docs/logic/Configuration.md"), false);
                        return 1;
                    }))
                .then(CommandManager.literal("make")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                            .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int x = IntegerArgumentType.getInteger(ctx, "x");
                                    int y = IntegerArgumentType.getInteger(ctx, "y");
                                    int z = IntegerArgumentType.getInteger(ctx, "z");
                                    ServerWorld world = ctx.getSource().getWorld();
                                    UUID id = VoidClamMod.makeStub(world, x, y, z);
                                    if (id == null) {
                                        ctx.getSource().sendError(new LiteralText("Could not create voidclam (limit reached?)"));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(new LiteralText("Created voidclam " + id + " at " + x + " " + y + " " + z), false);
                                    return 1;
                                })))))
                .then(CommandManager.literal("kill")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            VoidClamMod.clamKill(ctx.getSource().getMinecraftServer(), m.clamId, true);
                            ctx.getSource().sendFeedback(new LiteralText("Killed voidclam " + m.clamId), false);
                            return 1;
                        })))
                .then(CommandManager.literal("repair")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            ServerWorld modWorld = resolveHeartWorldForCommands(ctx.getSource(), m);
                            if (modWorld == null) return 0;
                            VoidClamMod.ShellDamageStats stats = VoidClamMod.inspectObsidianShellDamage(modWorld, m);
                            int material = Math.max(0, m.material);
                            int budgetFill = Math.min(stats.shellMissing(), material);
                            int remaining = Math.max(0, stats.shellMissing() - budgetFill);
                            ctx.getSource().sendFeedback(
                                new LiteralText("Repair stats: shellTotal=" + stats.shellTotal()
                                    + " obsidianPresent=" + stats.obsidianPresent()
                                    + " missing=" + stats.shellMissing()),
                                false);
                            ctx.getSource().sendFeedback(
                                new LiteralText("Material budget: material=" + material
                                    + " canFillNow=" + budgetFill
                                    + " missingAfterPass=" + remaining),
                                false);
                            VoidClamMod.requestRepairCommand(modWorld, m.clamId);
                            ctx.getSource().sendFeedback( new LiteralText("Repair scheduled; will run once pathfinding is idle."), false);
                            return 1;
                        })))
                .then(CommandManager.literal("reach")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            CommandToolbox.clamReach(ctx.getSource().getWorld(), m.clamId);
                            return 1;
                        })))
                .then(CommandManager.literal("storage")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            m.ensureClamId();
                            ServerWorld modWorld = resolveHeartWorldForCommands(ctx.getSource(), m);
                            if (modWorld == null) return 0;
                            int n = Pathfinder.countConnectedStorageBlocks(modWorld, m);
                            ctx.getSource().sendFeedback(
                                new LiteralText("Voidclam " + m.clamId + ": " + n
                                    + " storage block(s) (chest / trapped chest / barrel) reachable via wart from heart."),
                                false);
                            return 1;
                        })))
                .then(CommandManager.literal("dumpnbt")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            try {
                                Path path = CommandToolbox.writeClamHeartNbtDumpFile(ctx.getSource().getMinecraftServer(), m);
                                ctx.getSource().sendFeedback(
                                    new LiteralText("Wrote heart NBT to " + path.toAbsolutePath()),
                                    false);
                                return 1;
                            } catch (IOException e) {
                                String msg = e.getMessage();
                                ctx.getSource().sendError(new LiteralText(msg != null ? msg : e.toString()));
                                return 0;
                            }
                        })))
                .then(CommandManager.literal("seek")
                    .then(CommandManager.literal("ores")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                    .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                    .executes(ctx -> {
                                        Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                        boolean val = BoolArgumentType.getBool(ctx, "value");
                                        m.seekOres = val;
                                        ctx.getSource().sendFeedback(new LiteralText("Voidclam " + m.clamId + " seek ores = " + val), false);
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                .executes(ctx -> {
                                    Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    ctx.getSource().sendFeedback(new LiteralText("Voidclam " + m.clamId + " seek ores = " + m.seekOres), false);
                                    return 1;
                                }))))
                    .then(CommandManager.literal("protect")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                    .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                    .executes(ctx -> {
                                        Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                        boolean val = BoolArgumentType.getBool(ctx, "value");
                                        m.protectItself = val;
                                        ctx.getSource().sendFeedback(new LiteralText("Voidclam " + m.clamId + " protect itself = " + val), false);
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                .executes(ctx -> {
                                    Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    ctx.getSource().sendFeedback(new LiteralText("Voidclam " + m.clamId + " protect itself = " + m.protectItself), false);
                                    return 1;
                                }))))
                    .then(CommandManager.literal("lights")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                    .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                    .executes(ctx -> {
                                        Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                        boolean val = BoolArgumentType.getBool(ctx, "value");
                                        m.seekLights = val;
                                        ctx.getSource().sendFeedback(new LiteralText("Voidclam " + m.clamId + " seek lights = " + val), false);
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                .executes(ctx -> {
                                    Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    ctx.getSource().sendFeedback(new LiteralText("Voidclam " + m.clamId + " seek lights = " + m.seekLights), false);
                                    return 1;
                                })))))
                .then(CommandManager.literal("status")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            MinecraftServer server = ctx.getSource().getMinecraftServer();
                            ServerWorld modWorld = VoidClamMod.getWorldForClam(server, m);
                            ServerCommandSource src = ctx.getSource();
                            src.sendFeedback( new LiteralText("--- voidclam status " + m.clamId + " ---"), false);
                            for (String line : VoidClamMod.debugClamFlagLines(server, m)) {
                                final String l = line;
                                src.sendFeedback( new LiteralText(l), false);
                            }
                            for (String line : VoidClamMod.debugGrowAndAsyncLinesForClam(m.clamId)) {
                                final String l = line;
                                src.sendFeedback( new LiteralText(l), false);
                            }
                            for (String line : Pathfinder.debugSyncAStarJobsForClam(m.clamId)) {
                                final String l = line;
                                src.sendFeedback( new LiteralText(l), false);
                            }
                            for (String line : CommandToolbox.pathfindingExecutorStatusLines()) {
                                final String l = line;
                                src.sendFeedback( new LiteralText(l), false);
                            }
                            if (modWorld != null) {
                                for (String line : VoidClamModScheduler.debugSchedulerLinesForWorld(modWorld)) {
                                    final String l = line;
                                    src.sendFeedback( new LiteralText(l), false);
                                }
                            }
                            return 1;
                        })))
                .then(CommandManager.literal("info")
                    .executes(ctx -> {
                        if (ctx.getSource().getEntity() instanceof ServerPlayerEntity) {
                            ServerPlayerEntity player = (ServerPlayerEntity) ctx.getSource().getEntity();
                            Vec3d pos = player.getPos();
                            Clam closest = null;
                            double best = Double.MAX_VALUE;
                            for (Clam m : VoidClamMod.getAllClams()) {
                                if (m == null) continue;
                                double d = pos.squaredDistanceTo(m.x + 0.5, m.y + 0.5, m.z + 0.5);
                                if (d < best) {
                                    best = d;
                                    closest = m;
                                }
                            }
                            if (closest != null) {
                                Clam m = closest;
                                ctx.getSource().sendFeedback(new LiteralText("UUID: " + m.clamId), false);
                                ctx.getSource().sendFeedback(new LiteralText("Center: " + m.x + " " + m.y + " " + m.z + "  Size: " + m.currentSize + "  Power: " + m.energy + "  Material: " + m.material), false);
                            }
                        } else {
                            List<Clam> list = new ArrayList<>(VoidClamMod.getAllClams());
                            list.sort(Comparator.comparing(mm -> mm.clamId.toString()));
                            ctx.getSource().sendFeedback(new LiteralText("Voidclam count: " + list.size()), false);
                            for (Clam m : list) {
                                if (m == null) continue;
                                ctx.getSource().sendFeedback(new LiteralText(m.clamId + " @ " + m.x + " " + m.y + " " + m.z + " size " + m.currentSize + " material " + m.material), false);
                            }
                        }
                        return 1;
                    })
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            ctx.getSource().sendFeedback(new LiteralText("UUID: " + m.clamId), false);
                            ctx.getSource().sendFeedback(new LiteralText("Center: " + m.x + " " + m.y + " " + m.z + "  Size: " + m.currentSize + "  Power: " + m.energy + "  Material: " + m.material), false);
                            ctx.getSource().sendFeedback(new LiteralText("Seek lights: " + m.seekLights + "  Seek ores: " + m.seekOres + "  Protect: " + m.protectItself), false);
                            return 1;
                        })))
                .then(CommandManager.literal("grow")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            int maxSize = VoidClamConfig.get().clam_size_max;
                            int cur = m.currentSize;
                            int cSize = Math.min(cur + 1, maxSize);
                            if (cSize <= cur) {
                                ctx.getSource().sendError(new LiteralText("Already at max size (" + maxSize + ")"));
                                return 0;
                            }
                            VoidClamMod.requestGrowCommand(ctx.getSource().getWorld(), m.clamId, cSize);
                            ctx.getSource().sendFeedback( new LiteralText("Grow scheduled; will run once pathfinding is idle."), false);
                            return 1;
                        })))
                .then(CommandManager.literal("cleanup")
                    .executes(ctx -> {
                        int count = 0;
                        for (ServerWorld w : ctx.getSource().getMinecraftServer().getWorlds()) {
                            TendrilPulseManager.cleanupStrayDisplays(w);
                            count++;
                        }
                        ctx.getSource().sendFeedback(new LiteralText("Cleaned up stray tendril block displays in " + count + " world(s)."), false);
                        return 1;
                    }))
                .then(CommandManager.literal("roughcleanup")
                    .executes(ctx -> {
                        int total = 0;
                        for (ServerWorld w : ctx.getSource().getMinecraftServer().getWorlds()) {
                            total += TendrilPulseManager.cleanupAllNetherWartDisplays(w);
                        }
                        ctx.getSource().sendFeedback(new LiteralText("Removed " + total + " nether wart block display(s) across all worlds."), false);
                        return 1;
                    }))
                .then(CommandManager.literal("giveheart")
                    .executes(ctx -> {
                        if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity)) {
                            ctx.getSource().sendError(new LiteralText("giveheart must be run by a player"));
                            return 0;
                        }
                        ServerPlayerEntity player = (ServerPlayerEntity) ctx.getSource().getEntity();
                        player.inventory.offerOrDrop(player.world, SearingHeartItems.createFreshHeartStack());
                        ctx.getSource().sendFeedback( new LiteralText("Gave Searing Heart."), true);
                        return 1;
                    })
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity)) {
                                ctx.getSource().sendError(new LiteralText("giveheart must be run by a player"));
                                return 0;
                            }
                            ServerPlayerEntity player = (ServerPlayerEntity) ctx.getSource().getEntity();
                            int count = IntegerArgumentType.getInteger(ctx, "count");
                            for (int i = 0; i < count; i++) {
                                player.inventory.offerOrDrop(player.world, SearingHeartItems.createFreshHeartStack());
                            }
                            int fcount = count;
                            ctx.getSource().sendFeedback( new LiteralText("Gave " + fcount + " Searing Heart(s)."), true);
                            return 1;
                        })))
                .then(CommandManager.literal("ping")
                    .executes(ctx -> {
                        String worldName = ctx.getSource().getWorld().getRegistryKey().getValue().getPath();
                        int count = VoidClamMod.getClamCount();
                        ctx.getSource().sendFeedback(new LiteralText(worldName + " " + count), false);
                        Vec3d pos = ctx.getSource().getPosition();
                        Clam closest = null;
                        double best = Double.MAX_VALUE;
                        for (Clam m : VoidClamMod.getAllClams()) {
                            if (m == null) continue;
                            double d = pos.squaredDistanceTo(m.x + 0.5, m.y + 0.5, m.z + 0.5);
                            if (d < best) {
                                best = d;
                                closest = m;
                            }
                        }
                        if (closest == null) {
                            ctx.getSource().sendFeedback(new LiteralText("No voidclams — nothing to copy."), false);
                            return 1;
                        }
                        String uuidStr = closest.clamId.toString();
                        ctx.getSource().sendFeedback(new LiteralText("Nearest voidclam UUID: " + uuidStr), false);
                        return 1;
                    }))
        );
    }
}
