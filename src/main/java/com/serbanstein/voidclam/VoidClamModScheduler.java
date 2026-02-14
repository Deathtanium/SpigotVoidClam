package com.serbanstein.voidclam;

import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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

    /** True if there is any scheduled task for the given world (e.g. path steps not yet run). Uses dimension so ref equality is not required. */
    public static boolean hasPendingTasks(ServerWorld world) {
        var key = world.getRegistryKey();
        for (PendingTask t : pending) {
            if (t.world.getRegistryKey().equals(key)) return true;
        }
        return false;
    }

    /** Call from server tick; runs due tasks in schedule order (earliest runAt first) so path steps run start→goal. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        List<PendingTask> toRun = new ArrayList<>();
        for (PendingTask t : pending) {
            if (t.world == world && now >= t.runAtTick)
                toRun.add(t);
        }
        // Identity set: O(1) contains, avoids removeAll(ArrayList) O(n*m); use identity so distinct tasks are never merged
        Set<PendingTask> toRunSet = Collections.newSetFromMap(new IdentityHashMap<>());
        toRunSet.addAll(toRun);
        pending.removeIf(toRunSet::contains);
        toRun.sort(Comparator.comparingLong(t -> t.runAtTick));
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
