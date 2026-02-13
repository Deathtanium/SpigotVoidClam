/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_3218
 */
package com.serbanstein.voidclam;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.class_3218;

public final class VoidClamModScheduler {
    private static final List<PendingTask> pending = new CopyOnWriteArrayList<PendingTask>();

    static void schedule(class_3218 world, long delayTicks, Runnable run) {
        long runAt = world.method_75260() + delayTicks;
        pending.add(new PendingTask(world, runAt, run));
    }

    public static void tick(class_3218 world) {
        long now = world.method_75260();
        ArrayList<PendingTask> toRun = new ArrayList<PendingTask>();
        for (PendingTask t : pending) {
            if (t.world != world || now < t.runAtTick) continue;
            toRun.add(t);
        }
        pending.removeAll(toRun);
        for (PendingTask t : toRun) {
            t.run.run();
        }
    }

    private static class PendingTask {
        final class_3218 world;
        final long runAtTick;
        final Runnable run;

        PendingTask(class_3218 world, long runAtTick, Runnable run) {
            this.world = world;
            this.runAtTick = runAtTick;
            this.run = run;
        }
    }
}

