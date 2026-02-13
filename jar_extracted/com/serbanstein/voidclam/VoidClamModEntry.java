/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.brigadier.CommandDispatcher
 *  com.mojang.brigadier.arguments.ArgumentType
 *  com.mojang.brigadier.arguments.BoolArgumentType
 *  com.mojang.brigadier.arguments.IntegerArgumentType
 *  com.mojang.brigadier.builder.LiteralArgumentBuilder
 *  com.mojang.brigadier.context.CommandContext
 *  net.fabricmc.api.ModInitializer
 *  net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
 *  net.minecraft.class_12087
 *  net.minecraft.class_12087$class_12089
 *  net.minecraft.class_12094
 *  net.minecraft.class_1297
 *  net.minecraft.class_2168
 *  net.minecraft.class_2170
 *  net.minecraft.class_2170$class_5364
 *  net.minecraft.class_243
 *  net.minecraft.class_2561
 *  net.minecraft.class_3218
 *  net.minecraft.class_3222
 *  net.minecraft.class_5218
 *  net.minecraft.class_7157
 *  net.minecraft.server.MinecraftServer
 */
package com.serbanstein.voidclam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.serbanstein.voidclam.CommandToolbox;
import com.serbanstein.voidclam.Module;
import com.serbanstein.voidclam.TendrilPulseManager;
import com.serbanstein.voidclam.VoidClamMod;
import com.serbanstein.voidclam.VoidClamModScheduler;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.class_12087;
import net.minecraft.class_12094;
import net.minecraft.class_1297;
import net.minecraft.class_2168;
import net.minecraft.class_2170;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_3218;
import net.minecraft.class_3222;
import net.minecraft.class_5218;
import net.minecraft.class_7157;
import net.minecraft.server.MinecraftServer;

public class VoidClamModEntry
implements ModInitializer {
    private static final int TICK_REACH = 20;
    private static final int TICK_TARGETS = 20;
    private static final int TICK_HEARTBEAT = 80;
    private static final int TICK_OMNI_PULSE = 100;
    private static final int TICK_AUTO_GROW = 6000;
    private static final int TICK_DISPLAY_CLEANUP = 1200;
    private static final int TICK_DEFENSE = 100;
    private static final int TICK_MOB_EFFECT = 20;
    private static final int OP_LEVEL = 2;

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
        class_3218 world = server.method_30002();
        if (world == null) {
            return;
        }
        long tick = world.method_75260();
        VoidClamMod.tickTargets(world);
        VoidClamModScheduler.tick(world);
        for (class_3218 w : server.method_3738()) {
            TendrilPulseManager.tick(w);
        }
        TendrilPulseManager.tickOmniPulseJob(world);
        if (tick % 20L == 0L) {
            for (int i = 1; i <= VoidClamMod.getModuleNumber(); ++i) {
                if (!VoidClamMod.isModuleInLoadedChunk(world, i)) continue;
                CommandToolbox.clamReach(world, i);
            }
            VoidClamMod.tickCoreCheck(world);
        }
        if (tick % 80L == 0L) {
            VoidClamMod.tickHeartbeat(world);
        }
        if (tick % 100L == 0L) {
            TendrilPulseManager.runOmnidirectionalPulse(world);
        }
        if (tick % 6000L == 0L) {
            VoidClamMod.tickAutoRepairAndGrow(world);
        }
        if (tick % 1200L == 0L) {
            for (class_3218 w : server.method_3738()) {
                TendrilPulseManager.cleanupStrayDisplays(w);
            }
        }
        if (tick % 100L == 0L) {
            for (class_3218 w : server.method_3738()) {
                VoidClamMod.tickDefense(w);
            }
        }
        if (tick % 20L == 0L) {
            for (class_3218 w : server.method_3738()) {
                VoidClamMod.tickMobEffect(w);
            }
        }
    }

    private void registerCommands(CommandDispatcher<class_2168> dispatcher, class_7157 registryAccess, class_2170.class_5364 environment) {
        dispatcher.register((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247((String)"voidclam").requires(s -> {
            class_3222 p;
            class_1297 patt0$temp;
            return s.method_75037().hasPermission((class_12087)new class_12087.class_12089(class_12094.field_63198)) || (patt0$temp = s.method_9228()) instanceof class_3222 && "serbanstein".equalsIgnoreCase((p = (class_3222)patt0$temp).method_5477().getString());
        })).then(class_2170.method_9247((String)"make").then(class_2170.method_9244((String)"x", (ArgumentType)IntegerArgumentType.integer()).then(class_2170.method_9244((String)"y", (ArgumentType)IntegerArgumentType.integer()).then(class_2170.method_9244((String)"z", (ArgumentType)IntegerArgumentType.integer()).executes(ctx -> {
            int x = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"x");
            int y = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"y");
            int z = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"z");
            class_3218 world = ((class_2168)ctx.getSource()).method_9225();
            int idx = VoidClamMod.makeStub(world, x, y, z);
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Created module " + idx)));
            return 1;
        })))))).then(class_2170.method_9247((String)"kill").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            VoidClamMod.clamKill(tno);
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Killed module " + tno)));
            return 1;
        })))).then(class_2170.method_9247((String)"resize").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).then(class_2170.method_9244((String)"size", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            int tsize = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"size");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            int csize = VoidClamMod.getModules()[tno].currentSize;
            if (csize > tsize) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"target size cannot be smaller than current size"));
                return 0;
            }
            CommandToolbox.clamReSize(((class_2168)ctx.getSource()).method_9225(), tno, tsize);
            return 1;
        }))))).then(class_2170.method_9247((String)"repair").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || tno < 1 || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            CommandToolbox.clamReSize(((class_2168)ctx.getSource()).method_9225(), tno, VoidClamMod.getModules()[tno].currentSize);
            return 1;
        })))).then(class_2170.method_9247((String)"reach").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            CommandToolbox.clamReach(((class_2168)ctx.getSource()).method_9225(), tno);
            return 1;
        })))).then(((LiteralArgumentBuilder)class_2170.method_9247((String)"info").executes(ctx -> {
            class_1297 patt0$temp = ((class_2168)ctx.getSource()).method_9228();
            if (patt0$temp instanceof class_3222) {
                Module m;
                class_3222 player = (class_3222)patt0$temp;
                class_243 pos = player.method_73189();
                int tno = -1;
                double closest = Double.MAX_VALUE;
                for (int i = 1; i <= VoidClamMod.getModuleNumber(); ++i) {
                    double d;
                    m = VoidClamMod.getModules()[i];
                    if (m == null || !((d = pos.method_1028((double)m.x + 0.5, (double)m.y + 0.5, (double)m.z + 0.5)) < closest)) continue;
                    closest = d;
                    tno = i;
                }
                if (tno != -1) {
                    int idx = tno;
                    m = VoidClamMod.getModules()[tno];
                    ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Index: " + idx)));
                    ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("x: " + m.x + " y: " + m.y + " z: " + m.z + " Size: " + m.currentSize + " Power: " + m.energy)));
                }
            } else {
                ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module count: " + VoidClamMod.getModuleNumber())));
            }
            return 1;
        })).then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            Module m = VoidClamMod.getModules()[tno];
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("x: " + m.x + " y: " + m.y + " z: " + m.z + " Size: " + m.currentSize + " Power: " + m.energy)));
            return 1;
        })))).then(class_2170.method_9247((String)"grow").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            int cSize = VoidClamMod.getModules()[tno].currentSize + 2;
            CommandToolbox.clamReSize(((class_2168)ctx.getSource()).method_9225(), tno, cSize);
            return 1;
        })))).then(class_2170.method_9247((String)"save").executes(ctx -> {
            VoidClamMod.save(((class_2168)ctx.getSource()).method_9211());
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)"Saved"));
            return 1;
        }))).then(class_2170.method_9247((String)"cleanup").executes(ctx -> {
            int count = 0;
            for (class_3218 w : ((class_2168)ctx.getSource()).method_9211().method_3738()) {
                TendrilPulseManager.cleanupStrayDisplays(w);
                ++count;
            }
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Cleaned up stray tendril block displays in " + count + " world(s).")));
            return 1;
        }))).then(class_2170.method_9247((String)"roughcleanup").executes(ctx -> {
            int total = 0;
            for (class_3218 w : ((class_2168)ctx.getSource()).method_9211().method_3738()) {
                total += TendrilPulseManager.cleanupAllNetherWartDisplays(w);
            }
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Removed " + total + " nether wart block display(s) across all worlds.")));
            return 1;
        }))).then(((LiteralArgumentBuilder)class_2170.method_9247((String)"seek").then(((LiteralArgumentBuilder)class_2170.method_9247((String)"ores").then(class_2170.method_9247((String)"set").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).then(class_2170.method_9244((String)"value", (ArgumentType)BoolArgumentType.bool()).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            boolean val = BoolArgumentType.getBool((CommandContext)ctx, (String)"value");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            VoidClamMod.getModules()[tno].seekOres = val;
            VoidClamMod.save(((class_2168)ctx.getSource()).method_9211());
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module " + tno + " seek ores = " + val)));
            return 1;
        }))))).then(class_2170.method_9247((String)"get").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            boolean val = VoidClamMod.getModules()[tno].seekOres;
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module " + tno + " seek ores = " + val)));
            return 1;
        }))))).then(((LiteralArgumentBuilder)class_2170.method_9247((String)"lights").then(class_2170.method_9247((String)"set").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).then(class_2170.method_9244((String)"value", (ArgumentType)BoolArgumentType.bool()).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            boolean val = BoolArgumentType.getBool((CommandContext)ctx, (String)"value");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            VoidClamMod.getModules()[tno].seekLights = val;
            VoidClamMod.save(((class_2168)ctx.getSource()).method_9211());
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module " + tno + " seek lights = " + val)));
            return 1;
        }))))).then(class_2170.method_9247((String)"get").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            boolean val = VoidClamMod.getModules()[tno].seekLights;
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module " + tno + " seek lights = " + val)));
            return 1;
        })))))).then(((LiteralArgumentBuilder)class_2170.method_9247((String)"mobeffect").then(class_2170.method_9247((String)"set").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).then(class_2170.method_9244((String)"value", (ArgumentType)IntegerArgumentType.integer((int)0, (int)2)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            int val = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"value");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            VoidClamMod.getModules()[tno].mobEffect = val;
            VoidClamMod.save(((class_2168)ctx.getSource()).method_9211());
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module " + tno + " mob effect = " + val)));
            return 1;
        }))))).then(class_2170.method_9247((String)"get").then(class_2170.method_9244((String)"index", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> {
            int tno = IntegerArgumentType.getInteger((CommandContext)ctx, (String)"index");
            if (tno > VoidClamMod.getModuleNumber() || VoidClamMod.getModules()[tno] == null) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)"Bad number"));
                return 0;
            }
            int val = VoidClamMod.getModules()[tno].mobEffect;
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)("Module " + tno + " mob effect = " + val)));
            return 1;
        }))))).then(class_2170.method_9247((String)"ping").executes(ctx -> {
            String worldName = ((class_2168)ctx.getSource()).method_9225().method_27983().method_29177().method_12832();
            ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)(worldName + " " + VoidClamMod.getModuleNumber())));
            return 1;
        }))).then(class_2170.method_9247((String)"testfile").executes(ctx -> {
            try {
                Path path = ((class_2168)ctx.getSource()).method_9211().method_27050(class_5218.field_24188).resolve("modules.siva");
                if (Files.exists(path, new LinkOption[0])) {
                    Files.lines(path).forEach(line -> ((class_2168)ctx.getSource()).method_45068((class_2561)class_2561.method_43470((String)line)));
                }
            }
            catch (Exception e) {
                ((class_2168)ctx.getSource()).method_9213((class_2561)class_2561.method_43470((String)e.getMessage()));
            }
            return 1;
        })));
    }
}

