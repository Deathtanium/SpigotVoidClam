package com.serbanstein.voidclam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

/** Fabric mod entrypoint: lifecycle, server tick hook, and commands. */
public class VoidClamModEntry implements ModInitializer {
    private static final int TICK_REACH = 20;              // auto-reach (search for lights) every second
    private static final int TICK_TARGETS = 20;            // drain path queue every second
    private static final int TICK_AUTO_GROW = 5 * 60 * 20; // auto-repair/grow every 5 min
    private static final int TICK_HEARTBEAT = 4 * 20;      // heartbeat every 4s
    private static final int TICK_OMNI_PULSE = 5 * 20;     // omnidirectional pulse every ~5s
    private static final int TICK_DEFENSE = 5 * 20;        // defense (encase + effects + horn) every 5s
    private static final int TICK_CLEANUP = 60 * 20;       // stray tendril display cleanup every 1 min
    private static final int OP_LEVEL = 2;                 // commands hidden unless player has this OP level

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void onServerStarted(MinecraftServer server) {
        VoidClamMod.load(server);
    }

    private void onServerStopping(MinecraftServer server) {
        VoidClamMod.save(server);
    }

    private void onServerTick(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) return;
        long tick = world.getTime();

        VoidClamMod.tickTargets(world);
        VoidClamModScheduler.tick(world);
        for (ServerWorld w : server.getWorlds()) {
            VoidClamMod.tickGrowPendingCheck(w);
            TendrilPulseManager.tick(w);
        }
        TendrilPulseManager.tickOmniPulseJob(world);

        if (tick % TICK_TARGETS == 0) {
            for (int i = 1; i <= VoidClamMod.getModuleNumber(); i++) {
                if (!VoidClamMod.isModuleInLoadedChunk(world, i)) continue;
                CommandToolbox.clamReach(world, i);
            }
            VoidClamMod.tickCoreCheck(world);
        }
        if (tick % TICK_HEARTBEAT == 0)
            VoidClamMod.tickHeartbeat(world);
        if (tick % TICK_OMNI_PULSE == 0)
            TendrilPulseManager.runOmnidirectionalPulse(world);
        if (tick % TICK_DEFENSE == 0) {
            for (ServerWorld w : server.getWorlds())
                VoidClamMod.tickDefense(w);
        }
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
                                    int idx = VoidClamMod.makeStub(world, x, y, z);
                                    ctx.getSource().sendMessage(Text.literal("Created module " + idx));
                                    return 1;
                                })))))
                .then(CommandManager.literal("kill")
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int tno = IntegerArgumentType.getInteger(ctx, "index");
                            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                ctx.getSource().sendError(Text.literal("Bad number"));
                                return 0;
                            }
                            VoidClamMod.clamKill(tno);
                            ctx.getSource().sendMessage(Text.literal("Killed module " + tno));
                            return 1;
                        })))
                .then(CommandManager.literal("resize")
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(1))
                            .executes(ctx -> {
                                int tno = IntegerArgumentType.getInteger(ctx, "index");
                                int tsize = IntegerArgumentType.getInteger(ctx, "size");
                                if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                    ctx.getSource().sendError(Text.literal("Bad number"));
                                    return 0;
                                }
                                int csize = VoidClamMod.getModules()[tno].currentSize;
                                if (csize > tsize) {
                                    ctx.getSource().sendError(Text.literal("target size cannot be smaller than current size"));
                                    return 0;
                                }
                                CommandToolbox.clamReSize(ctx.getSource().getWorld(), tno, tsize);
                                return 1;
                            }))))
                .then(CommandManager.literal("repair")
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int tno = IntegerArgumentType.getInteger(ctx, "index");
                            if (tno > VoidClamMod.getModuleNumber() || tno < 1 || VoidClamMod.getModules()[tno] == null) {
                                ctx.getSource().sendError(Text.literal("Bad number"));
                                return 0;
                            }
                            VoidClamMod.requestRepairCommand(ctx.getSource().getWorld(), tno);
                            ctx.getSource().sendFeedback(() -> Text.literal("Repair scheduled; will run once pathfinding is idle."), false);
                            return 1;
                        })))
                .then(CommandManager.literal("reach")
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int tno = IntegerArgumentType.getInteger(ctx, "index");
                            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                ctx.getSource().sendError(Text.literal("Bad number"));
                                return 0;
                            }
                            CommandToolbox.clamReach(ctx.getSource().getWorld(), tno);
                            return 1;
                        })))
                .then(CommandManager.literal("seek")
                    .then(CommandManager.literal("ores")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                                .then(CommandManager.argument("value", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        int tno = IntegerArgumentType.getInteger(ctx, "index");
                                        boolean val = BoolArgumentType.getBool(ctx, "value");
                                        if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                            ctx.getSource().sendError(Text.literal("Bad number"));
                                            return 0;
                                        }
                                        VoidClamMod.getModules()[tno].seekOres = val;
                                        VoidClamMod.save(ctx.getSource().getServer());
                                        ctx.getSource().sendMessage(Text.literal("Module " + tno + " seek ores = " + val));
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    int tno = IntegerArgumentType.getInteger(ctx, "index");
                                    if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                        ctx.getSource().sendError(Text.literal("Bad number"));
                                        return 0;
                                    }
                                    boolean val = VoidClamMod.getModules()[tno].seekOres;
                                    ctx.getSource().sendMessage(Text.literal("Module " + tno + " seek ores = " + val));
                                    return 1;
                                }))))
                    .then(CommandManager.literal("lights")
                        .then(CommandManager.literal("set")
                            .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                                .then(CommandManager.argument("value", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        int tno = IntegerArgumentType.getInteger(ctx, "index");
                                        boolean val = BoolArgumentType.getBool(ctx, "value");
                                        if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                            ctx.getSource().sendError(Text.literal("Bad number"));
                                            return 0;
                                        }
                                        VoidClamMod.getModules()[tno].seekLights = val;
                                        VoidClamMod.save(ctx.getSource().getServer());
                                        ctx.getSource().sendMessage(Text.literal("Module " + tno + " seek lights = " + val));
                                        return 1;
                                    }))))
                        .then(CommandManager.literal("get")
                            .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    int tno = IntegerArgumentType.getInteger(ctx, "index");
                                    if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                        ctx.getSource().sendError(Text.literal("Bad number"));
                                        return 0;
                                    }
                                    boolean val = VoidClamMod.getModules()[tno].seekLights;
                                    ctx.getSource().sendMessage(Text.literal("Module " + tno + " seek lights = " + val));
                                    return 1;
                                })))))
                .then(CommandManager.literal("info")
                    .executes(ctx -> {
                        if (ctx.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            Vec3d pos = player.getEntityPos();
                            int tno = -1;
                            double closest = Double.MAX_VALUE;
                            for (int i = 1; i <= VoidClamMod.getModuleNumber(); i++) {
                                var m = VoidClamMod.getModules()[i];
                                if (m == null) continue;
                                double d = pos.squaredDistanceTo(m.x + 0.5, m.y + 0.5, m.z + 0.5);
                                if (d < closest) { closest = d; tno = i; }
                            }
                            if (tno != -1) {
                                final int idx = tno;
                                var m = VoidClamMod.getModules()[tno];
                                ctx.getSource().sendMessage(Text.literal("Index: " + idx));
                                ctx.getSource().sendMessage(Text.literal("x: " + m.x + " y: " + m.y + " z: " + m.z + " Size: " + m.currentSize + " Power: " + m.energy));
                            }
                        } else {
                            ctx.getSource().sendMessage(Text.literal("Module count: " + VoidClamMod.getModuleNumber()));
                        }
                        return 1;
                    })
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int tno = IntegerArgumentType.getInteger(ctx, "index");
                            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                ctx.getSource().sendError(Text.literal("Bad number"));
                                return 0;
                            }
                            var m = VoidClamMod.getModules()[tno];
                            ctx.getSource().sendMessage(Text.literal("x: " + m.x + " y: " + m.y + " z: " + m.z + " Size: " + m.currentSize + " Power: " + m.energy));
                            return 1;
                        })))
                .then(CommandManager.literal("grow")
                    .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int tno = IntegerArgumentType.getInteger(ctx, "index");
                            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                                ctx.getSource().sendError(Text.literal("Bad number"));
                                return 0;
                            }
                            int cSize = VoidClamMod.getModules()[tno].currentSize + 2;
                            VoidClamMod.requestGrowCommand(ctx.getSource().getWorld(), tno, cSize);
                            ctx.getSource().sendFeedback(() -> Text.literal("Grow scheduled; will run once pathfinding is idle."), false);
                            return 1;
                        })))
                .then(CommandManager.literal("save")
                    .executes(ctx -> {
                        VoidClamMod.save(ctx.getSource().getServer());
                        ctx.getSource().sendMessage(Text.literal("Saved"));
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
                        ctx.getSource().sendMessage(Text.literal(worldName + " " + VoidClamMod.getModuleNumber()));
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
