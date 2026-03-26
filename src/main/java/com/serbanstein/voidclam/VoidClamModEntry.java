package com.serbanstein.voidclam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fabric mod entrypoint: lifecycle, server tick hook, and commands. */
public class VoidClamModEntry implements ModInitializer {
    private static final int TICK_AUTO_GROW = 5 * 60 * 20; // auto-repair/grow every 5 min
    private static final int TICK_OMNI_PULSE = 5 * 20;     // omnidirectional pulse every ~5s
    private static final int TICK_CLEANUP = 60 * 20;       // stray tendril display cleanup every 1 min
    private static final int OP_LEVEL = 2;                 // commands hidden unless player has this OP level

    @Override
    public void onInitialize() {
        VoidClamDataComponents.register();
        VoidClamBlocks.register();
        VoidClamConfig.loadFromDisk();
        ServerChunkEvents.CHUNK_GENERATE.register(NaturalSpawnHandler::onChunkGenerated);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void onServerStarted(MinecraftServer server) {
        VoidClamConfig.loadFromDisk();
        VoidClamMod.onAsyncPathfindingSessionStart();
        VoidClamMod.loadOptionalLegacyModulesSiva(server);
        VoidClamMod.migrateLoadedModulesToHeartBlocks(server);
    }

    private void onServerStopping(MinecraftServer server) {
        VoidClamMod.onAsyncPathfindingSessionStop();
        VoidClamMod.maybeSaveLegacyModulesSiva(server);
    }

    private void onServerTick(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) return;
        long tick = world.getTime();

        if (VoidClamConfig.get().astarModeEnum() == VoidClamConfig.AstarMode.SYNC_BATCHED) {
            Pathfinder.tickSyncAStarJobs(VoidClamConfig.get().effectiveSyncMaxStepsPerTick());
        }
        VoidClamMod.tickTargets(world);
        VoidClamModScheduler.tick(world);
        for (ServerWorld w : server.getWorlds()) {
            VoidClamMod.tickGrowPendingCheck(w);
            TendrilPulseManager.tick(w);
        }
        TendrilPulseManager.tickOmniPulseJob(world);

        if (tick % TICK_OMNI_PULSE == 0)
            TendrilPulseManager.runOmnidirectionalPulse(world);
        if (tick % TICK_AUTO_GROW == 0)
            VoidClamMod.tickAutoRepairAndGrow(world);
        if (tick % TICK_CLEANUP == 0) {
            for (ServerWorld w : server.getWorlds())
                TendrilPulseManager.cleanupStrayDisplays(w);
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
            CommandManager.literal("voidclam")
                .requires(s -> s.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)))
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
                        .executes(ctx -> {
                            try {
                                Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                VoidClamMod.clamKill(ctx.getSource().getServer(), m.clamId, true);
                                ctx.getSource().sendMessage(Text.literal("Killed voidclam " + m.clamId));
                                return 1;
                            } catch (CommandSyntaxException e) {
                                ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                return 0;
                            }
                        })))
                .then(CommandManager.literal("resize")
                    .then(CommandManager.argument("size", IntegerArgumentType.integer(1))
                        .then(CommandManager.argument("target", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                try {
                                    Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                    int tsize = IntegerArgumentType.getInteger(ctx, "size");
                                    int csize = m.currentSize;
                                    if (csize > tsize) {
                                        ctx.getSource().sendError(Text.literal("target size cannot be smaller than current size"));
                                        return 0;
                                    }
                                    int maxSize = VoidClamConfig.get().clam_size_max;
                                    if (tsize > maxSize) {
                                        ctx.getSource().sendError(Text.literal("target size cannot exceed config clam_size_max (" + maxSize + ")"));
                                        return 0;
                                    }
                                    CommandToolbox.clamReSize(ctx.getSource().getWorld(), m.clamId, tsize);
                                    return 1;
                                } catch (CommandSyntaxException e) {
                                    ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                    return 0;
                                }
                            }))))
                .then(CommandManager.literal("repair")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                VoidClamMod.requestRepairCommand(ctx.getSource().getWorld(), m.clamId);
                                ctx.getSource().sendFeedback(() -> Text.literal("Repair scheduled; will run once pathfinding is idle."), false);
                                return 1;
                            } catch (CommandSyntaxException e) {
                                ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                return 0;
                            }
                        })))
                .then(CommandManager.literal("reach")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                CommandToolbox.clamReach(ctx.getSource().getWorld(), m.clamId);
                                return 1;
                            } catch (CommandSyntaxException e) {
                                ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                return 0;
                            }
                        })))
                .then(CommandManager.literal("seek")
                    .then(CommandManager.literal("ores")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        try {
                                            Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                            boolean val = BoolArgumentType.getBool(ctx, "value");
                                            m.seekOres = val;
                                            VoidClamMod.save(ctx.getSource().getServer());
                                            ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek ores = " + val));
                                            return 1;
                                        } catch (CommandSyntaxException e) {
                                            ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                            return 0;
                                        }
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    try {
                                        Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                        ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek ores = " + m.seekOres));
                                        return 1;
                                    } catch (CommandSyntaxException e) {
                                        ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                        return 0;
                                    }
                                }))))
                    .then(CommandManager.literal("protect")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        try {
                                            Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                            boolean val = BoolArgumentType.getBool(ctx, "value");
                                            m.protectItself = val;
                                            VoidClamMod.save(ctx.getSource().getServer());
                                            ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " protect itself = " + val));
                                            return 1;
                                        } catch (CommandSyntaxException e) {
                                            ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                            return 0;
                                        }
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    try {
                                        Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                        ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " protect itself = " + m.protectItself));
                                        return 1;
                                    } catch (CommandSyntaxException e) {
                                        ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                        return 0;
                                    }
                                }))))
                    .then(CommandManager.literal("lights")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        try {
                                            Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                            boolean val = BoolArgumentType.getBool(ctx, "value");
                                            m.seekLights = val;
                                            VoidClamMod.save(ctx.getSource().getServer());
                                            ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek lights = " + val));
                                            return 1;
                                        } catch (CommandSyntaxException e) {
                                            ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                            return 0;
                                        }
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    try {
                                        Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                        ctx.getSource().sendMessage(Text.literal("Voidclam " + m.clamId + " seek lights = " + m.seekLights));
                                        return 1;
                                    } catch (CommandSyntaxException e) {
                                        ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                        return 0;
                                    }
                                })))))
                .then(CommandManager.literal("info")
                    .executes(ctx -> {
                        if (ctx.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            Vec3d pos = player.getEntityPos();
                            Module closest = null;
                            double best = Double.MAX_VALUE;
                            for (Module m : VoidClamMod.getAllModules()) {
                                if (m == null) continue;
                                double d = pos.squaredDistanceTo(m.x + 0.5, m.y + 0.5, m.z + 0.5);
                                if (d < best) {
                                    best = d;
                                    closest = m;
                                }
                            }
                            if (closest != null) {
                                Module m = closest;
                                ctx.getSource().sendMessage(Text.literal("UUID: " + m.clamId));
                                ctx.getSource().sendMessage(Text.literal("Center: " + m.x + " " + m.y + " " + m.z + "  Size: " + m.currentSize + "  Power: " + m.energy));
                            }
                        } else {
                            List<Module> list = new ArrayList<>(VoidClamMod.getAllModules());
                            list.sort(Comparator.comparing(mm -> mm.clamId.toString()));
                            ctx.getSource().sendMessage(Text.literal("Voidclam count: " + list.size()));
                            for (Module m : list) {
                                if (m == null) continue;
                                ctx.getSource().sendMessage(Text.literal(m.clamId + " @ " + m.x + " " + m.y + " " + m.z + " size " + m.currentSize));
                            }
                        }
                        return 1;
                    })
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                ctx.getSource().sendMessage(Text.literal("UUID: " + m.clamId));
                                ctx.getSource().sendMessage(Text.literal("Center: " + m.x + " " + m.y + " " + m.z + "  Size: " + m.currentSize + "  Power: " + m.energy));
                                ctx.getSource().sendMessage(Text.literal("Seek lights: " + m.seekLights + "  Seek ores: " + m.seekOres + "  Protect: " + m.protectItself));
                                return 1;
                            } catch (CommandSyntaxException e) {
                                ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                return 0;
                            }
                        })))
                .then(CommandManager.literal("grow")
                    .then(CommandManager.argument("target", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                Module m = VoidClamCommandArgs.parseTarget(StringArgumentType.getString(ctx, "target"), ctx.getSource());
                                int maxSize = VoidClamConfig.get().clam_size_max;
                                int cur = m.currentSize;
                                int cSize = Math.min(cur + 2, maxSize);
                                if (cSize <= cur) {
                                    ctx.getSource().sendError(Text.literal("Already at max size (" + maxSize + ")"));
                                    return 0;
                                }
                                VoidClamMod.requestGrowCommand(ctx.getSource().getWorld(), m.clamId, cSize);
                                ctx.getSource().sendFeedback(() -> Text.literal("Grow scheduled; will run once pathfinding is idle."), false);
                                return 1;
                            } catch (CommandSyntaxException e) {
                                ctx.getSource().sendError(Text.literal(e.getRawMessage().getString()));
                                return 0;
                            }
                        })))
                .then(CommandManager.literal("save")
                    .executes(ctx -> {
                        VoidClamMod.save(ctx.getSource().getServer());
                        ctx.getSource().sendMessage(Text.literal("Saved"));
                        return 1;
                    }))
                .then(CommandManager.literal("ingestlegacy")
                    .executes(ctx -> {
                        String msg = VoidClamMod.importLegacyModulesSiva(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> Text.literal(msg), true);
                        return 1;
                    }))
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
                .then(CommandManager.literal("ping")
                    .executes(ctx -> {
                        String worldName = ctx.getSource().getWorld().getRegistryKey().getValue().getPath();
                        ctx.getSource().sendMessage(Text.literal(worldName + " " + VoidClamMod.getModuleCount()));
                        return 1;
                    }))
                .then(CommandManager.literal("testfile")
                    .executes(ctx -> {
                        try {
                            var path = ctx.getSource().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("modules.siva");
                            if (java.nio.file.Files.exists(path)) {
                                java.nio.file.Files.lines(path).forEach(line ->
                                    ctx.getSource().sendMessage(Text.literal(line)));
                            }
                        } catch (Exception e) {
                            ctx.getSource().sendError(Text.literal(e.getMessage()));
                        }
                        return 1;
                    }))
        );
    }
}
