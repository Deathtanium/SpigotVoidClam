package com.serbanstein.voidclam;

import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs delayed tasks on the main thread. Each tick we check world time and run tasks whose delay has passed.
 * Used for path-building steps (original Bukkit runTaskLater behaviour).
 */
public final class VoidClamModScheduler {
    private static final List<PendingTask> pending = new CopyOnWriteArrayList<>();

    static void schedule(ServerWorld world, long delayTicks, Runnable run) {
        long runAt = world.getTime() + delayTicks;
        pending.add(new PendingTask(world, runAt, run));
    }

    /** Call from server tick; runs due tasks. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        List<PendingTask> toRun = new ArrayList<>();
        for (PendingTask t : pending) {
            if (t.world == world && now >= t.runAtTick)
                toRun.add(t);
        }
        pending.removeAll(toRun);
        for (PendingTask t : toRun)
            t.run.run();
    }

    private static class PendingTask {
        final ServerWorld world;
        final long runAtTick;
        final Runnable run;

        PendingTask(ServerWorld world, long runAtTick, Runnable run) {
            this.world = world;
            this.runAtTick = runAtTick;
            this.run = run;
        }
    }
}
