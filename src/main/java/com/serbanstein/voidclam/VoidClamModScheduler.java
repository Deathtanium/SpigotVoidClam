package com.serbanstein.voidclam;

import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs delayed tasks on the main thread. Each tick we check world time and run tasks whose delay has passed.
 * Used for staggered path application, stub/shell placement, and similar world-time–keyed steps.
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

    /** OP debug: delayed main-thread tasks for {@code world} (not attributed to individual clams). */
    public static List<String> debugSchedulerLinesForWorld(ServerWorld world) {
        long now = world.getTime();
        int nThis = 0;
        long earliest = Long.MAX_VALUE;
        int nAll = pending.size();
        for (PendingTask t : pending) {
            if (t.world != world) {
                continue;
            }
            nThis++;
            if (t.runAtTick < earliest) {
                earliest = t.runAtTick;
            }
        }
        List<String> lines = new ArrayList<>(3);
        lines.add("mainThreadScheduler: pendingThisWorld=" + nThis + " pendingAllWorlds=" + nAll + " worldTime=" + now);
        if (nThis > 0) {
            lines.add("  earliestRunAtTick=" + earliest + " ticksUntil=" + (earliest - now));
        }
        lines.add("  grow/repair idle gate: hasPendingTasks(this dimension)=" + hasPendingTasks(world));
        return lines;
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
