package com.serbanstein.voidclam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fabric mod entrypoint: lifecycle, server tick hook, and commands. */
public class VoidClamModEntry implements ModInitializer {
    private static final int TICK_OMNI_PULSE = 5 * 20;     // omnidirectional pulse every ~5s
    private static final int TICK_CLEANUP = 60 * 20;       // stray tendril display cleanup every 1 min
    /** Players who may use /voidclam without gamemaster permission (case-insensitive name match). */
    private static final Set<String> VOIDCLAM_TRUSTED_PLAYER_NAMES_LOWER = Set.of("serbanstein");

    private static boolean canUseVoidclamCommands(ServerCommandSource source) {
        if (source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS))) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return false;
        }
        String name = player.getName().getString();
        return VOIDCLAM_TRUSTED_PLAYER_NAMES_LOWER.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            VoidClamMod.captureClamCoreComponentsBeforeBreak(world, pos, state);
            if (world instanceof ServerWorld serverWorld && VoidClamMod.shouldCancelBreakingSearingHeart(serverWorld, player, pos, state)) {
                return false;
            }
            return true;
        });
        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) -> {
            VoidClamMod.clearBreakingClamFurnaceComponentsCapture();
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || !(world instanceof ServerWorld serverWorld)) return;
            if (!state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
            VoidClamMod.onClamCoreBroken(serverWorld, player, pos, state);
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || !(world instanceof ServerWorld serverWorld)) return ActionResult.PASS;
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
        VoidClamBlockLoot.loadFromDisk();
        ServerChunkEvents.CHUNK_GENERATE.register(NaturalSpawnHandler::onChunkGenerated);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void onServerStarted(MinecraftServer server) {
        VoidClamConfig.loadFromDisk();
        VoidClamBlockLoot.loadFromDisk();
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
        Pathfinder.resetSyncMainThreadStepBudgetForTick(VoidClamConfig.get().effectiveSyncMaxStepsPerTick());
        VoidClamMod.tickSeekEphemeralExpiry(server);
        VoidClamMod.drainPendingLightCacheDeltas();
        for (ServerWorld w : server.getWorlds()) {
            VoidClamMod.cancelActivePathfindingForFullyIceEncasedClams(w);
        }
        for (ServerWorld w : server.getWorlds()) {
            VoidClamMod.tickLoadedClamCores(w);
            VoidClamMod.tickGrowPendingCheck(w);
            TendrilPulseManager.tick(w);
            VoidClamModScheduler.tick(w);
            TendrilPulseManager.tickOmniPulseJob(w);
        }
        if (VoidClamConfig.get().astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            Pathfinder.tickSyncMainThreadPathWork();
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
        ServerWorld modWorld = VoidClamMod.getWorldForClam(src.getServer(), m);
        if (modWorld == null) {
            src.sendError(Text.literal("Dimension for this voidclam is not loaded."));
            return null;
        }
        if (!modWorld.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            src.sendError(Text.literal("Heart chunk is not loaded."));
            return null;
        }
        return modWorld;
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
            CommandManager.literal("voidclam")
                .requires(VoidClamModEntry::canUseVoidclamCommands)
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Use /voidclam help for syntax. Target = UUID (dashed or 32 hex) or three integers x y z (heart center)."), false);
                    return 1;
                })
                .then(CommandManager.literal("help")
                    .executes(ctx -> {
                        ServerCommandSource s = ctx.getSource();
                        s.sendFeedback(() -> Text.literal("Voidclam commands (gamemaster / OP 2+ or trusted player). Target = UUID or x y z at heart."), false);
                        s.sendFeedback(() -> Text.literal("make <x> <y> <z> — new clam"), false);
                        s.sendFeedback(() -> Text.literal("kill <target>"), false);
                        s.sendFeedback(() -> Text.literal("repair <target> | reach <target> | grow <target> | storage <target>"), false);
                        s.sendFeedback(() -> Text.literal("seek ores|lights|protect set <true|false> <target> — bool before target"), false);
                        s.sendFeedback(() -> Text.literal("seek ores|lights|protect get <target>"), false);
                        s.sendFeedback(() -> Text.literal("info [target] — list all (console) or nearest (player)"), false);
                        s.sendFeedback(() -> Text.literal("status <target> — flags, grow/async, sync A* queue, path pool, scheduler"), false);
                        s.sendFeedback(() -> Text.literal("dumpnbt <target> — write searing heart block-entity NBT to run/voidclam-nbt-dumps/<UUID>.nbt"), false);
                        s.sendFeedback(() -> Text.literal("cleanup | roughcleanup | ping — state lives in searing heart blocks"), false);
                        s.sendFeedback(() -> Text.literal("Server config: config/voidclam.json — see docs/logic/Configuration.md"), false);
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
                                    var id = VoidClamMod.makeStub(world, x, y, z);
                                    if (id == null) {
                                        ctx.getSource().sendError(Text.literal("Could not create voidclam (limit reached?)"));
                                        return 0;
                                    }
                                    ctx.getSource().sendMessage(Text.literal("Created voidclam " + id + " at " + x + " " + y + " " + z));
                                    return 1;
                                })))))
                .then(CommandManager.literal("kill")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            VoidClamMod.clamKill(ctx.getSource().getServer(), m.clamId, true);
                            ctx.getSource().sendMessage(Text.literal("Killed voidclam " + m.clamId));
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
                                () -> Text.literal("Repair stats: shellTotal=" + stats.shellTotal()
                                    + " obsidianPresent=" + stats.obsidianPresent()
                                    + " missing=" + stats.shellMissing()),
                                false);
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("Material budget: material=" + material
                                    + " canFillNow=" + budgetFill
                                    + " missingAfterPass=" + remaining),
                                false);
                            VoidClamMod.requestRepairCommand(modWorld, m.clamId);
                            ctx.getSource().sendFeedback(() -> Text.literal("Repair scheduled; will run once pathfinding is idle."), false);
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
                                () -> Text.literal("Voidclam " + m.clamId + ": " + n
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
                                var path = CommandToolbox.writeClamHeartNbtDumpFile(ctx.getSource().getServer(), m);
                                ctx.getSource().sendFeedback(
                                    () -> Text.literal("Wrote heart NBT to " + path.toAbsolutePath()),
                                    false);
                                return 1;
                            } catch (IOException e) {
                                String msg = e.getMessage();
                                ctx.getSource().sendError(Text.literal(msg != null ? msg : e.toString()));
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
                                        ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek ores = " + val));
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                .executes(ctx -> {
                                    Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek ores = " + m.seekOres));
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
                                        ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " protect itself = " + val));
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                .executes(ctx -> {
                                    Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " protect itself = " + m.protectItself));
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
                                        ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek lights = " + val));
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                                .executes(ctx -> {
                                    Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek lights = " + m.seekLights));
                                    return 1;
                                })))))
                .then(CommandManager.literal("status")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            MinecraftServer server = ctx.getSource().getServer();
                            ServerWorld modWorld = VoidClamMod.getWorldForClam(server, m);
                            ServerCommandSource src = ctx.getSource();
                            src.sendFeedback(() -> Text.literal("--- voidclam status " + m.clamId + " ---"), false);
                            for (String line : VoidClamMod.debugClamFlagLines(server, m)) {
                                final String l = line;
                                src.sendFeedback(() -> Text.literal(l), false);
                            }
                            for (String line : VoidClamMod.debugGrowAndAsyncLinesForClam(m.clamId)) {
                                final String l = line;
                                src.sendFeedback(() -> Text.literal(l), false);
                            }
                            for (String line : Pathfinder.debugSyncAStarJobsForClam(m.clamId)) {
                                final String l = line;
                                src.sendFeedback(() -> Text.literal(l), false);
                            }
                            for (String line : CommandToolbox.pathfindingExecutorStatusLines()) {
                                final String l = line;
                                src.sendFeedback(() -> Text.literal(l), false);
                            }
                            if (modWorld != null) {
                                for (String line : VoidClamModScheduler.debugSchedulerLinesForWorld(modWorld)) {
                                    final String l = line;
                                    src.sendFeedback(() -> Text.literal(l), false);
                                }
                            }
                            return 1;
                        })))
                .then(CommandManager.literal("info")
                    .executes(ctx -> {
                        if (ctx.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            Vec3d pos = player.getEntityPos();
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
                                ctx.getSource().sendMessage(Text.literal("UUID: " + m.clamId));
                                ctx.getSource().sendMessage(Text.literal("Center: " + m.x + " " + m.y + " " + m.z + "  Size: " + m.currentSize + "  Power: " + m.energy + "  Soul: " + m.soul + "  Material: " + m.material));
                            }
                        } else {
                            List<Clam> list = new ArrayList<>(VoidClamMod.getAllClams());
                            list.sort(Comparator.comparing(mm -> mm.clamId.toString()));
                            ctx.getSource().sendMessage(Text.literal("Voidclam count: " + list.size()));
                            for (Clam m : list) {
                                if (m == null) continue;
                                ctx.getSource().sendMessage(Text.literal(m.clamId + " @ " + m.x + " " + m.y + " " + m.z + " size " + m.currentSize + " energy " + m.energy + " soul " + m.soul + " material " + m.material));
                            }
                        }
                        return 1;
                    })
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(VoidClamCommandArgs::suggestNearestClamUuid)
                        .executes(ctx -> {
                            Clam m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                            ctx.getSource().sendMessage(Text.literal("UUID: " + m.clamId));
                            ctx.getSource().sendMessage(Text.literal("Center: " + m.x + " " + m.y + " " + m.z + "  Size: " + m.currentSize + "  Power: " + m.energy + "  Soul: " + m.soul + "  Material: " + m.material));
                            ctx.getSource().sendMessage(Text.literal("Seek lights: " + m.seekLights + "  Seek ores: " + m.seekOres + "  Protect: " + m.protectItself));
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
                                ctx.getSource().sendError(Text.literal("Already at max size (" + maxSize + ")"));
                                return 0;
                            }
                            VoidClamMod.requestGrowCommand(ctx.getSource().getWorld(), m.clamId, cSize);
                            ctx.getSource().sendFeedback(() -> Text.literal("Grow scheduled; will run once pathfinding is idle."), false);
                            return 1;
                        })))
                .then(CommandManager.literal("cleanup")
                    .executes(ctx -> {
                        int count = 0;
                        for (ServerWorld w : ctx.getSource().getServer().getWorlds()) {
                            TendrilPulseManager.cleanupStrayDisplays(w);
                            count++;
                        }
                        ctx.getSource().sendMessage(Text.literal("Cleaned up stray tendril block displays in " + count + " world(s)."));
                        return 1;
                    }))
                .then(CommandManager.literal("roughcleanup")
                    .executes(ctx -> {
                        int total = 0;
                        for (ServerWorld w : ctx.getSource().getServer().getWorlds()) {
                            total += TendrilPulseManager.cleanupAllNetherWartDisplays(w);
                        }
                        ctx.getSource().sendMessage(Text.literal("Removed " + total + " nether wart block display(s) across all worlds."));
                        return 1;
                    }))
                .then(CommandManager.literal("giveheart")
                    .executes(ctx -> {
                        if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity player)) {
                            ctx.getSource().sendError(Text.literal("giveheart must be run by a player"));
                            return 0;
                        }
                        player.giveOrDropStack(SearingHeartItems.createFreshHeartStack());
                        ctx.getSource().sendFeedback(() -> Text.literal("Gave Searing Heart."), true);
                        return 1;
                    })
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity player)) {
                                ctx.getSource().sendError(Text.literal("giveheart must be run by a player"));
                                return 0;
                            }
                            int count = IntegerArgumentType.getInteger(ctx, "count");
                            for (int i = 0; i < count; i++) {
                                player.giveOrDropStack(SearingHeartItems.createFreshHeartStack());
                            }
                            int fcount = count;
                            ctx.getSource().sendFeedback(() -> Text.literal("Gave " + fcount + " Searing Heart(s)."), true);
                            return 1;
                        })))
                .then(CommandManager.literal("ping")
                    .executes(ctx -> {
                        String worldName = ctx.getSource().getWorld().getRegistryKey().getValue().getPath();
                        int count = VoidClamMod.getClamCount();
                        ctx.getSource().sendMessage(Text.literal(worldName + " " + count));
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
                            ctx.getSource().sendMessage(Text.literal("No voidclams — nothing to copy."));
                            return 1;
                        }
                        String uuidStr = closest.clamId.toString();
                        Text copiable = Text.literal(uuidStr).setStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent.CopyToClipboard(uuidStr))
                            .withUnderline(true)
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to copy UUID"))));
                        ctx.getSource().sendMessage(Text.literal("Nearest voidclam UUID: ").append(copiable));
                        return 1;
                    }))
        );
    }
}
