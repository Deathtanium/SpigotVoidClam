package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One pathfinding job’s in-memory BFS flood from the clam heart within {@link Pathfinder} search bounds.
 * Stores per-cell {@link BlockState} snapshots at visit time (same thread as BFS) and graph distance from the start cell.
 * Discarded after the path job; enables A* to read a consistent view for cells inside the flood and supports BFS-depth target ordering.
 */
public final class ReachabilityVolatileMap {
    private final Map<Long, BlockState> cellStates;
    private final Map<Long, Integer> bfsDistance;

    ReachabilityVolatileMap(Map<Long, BlockState> cellStates, Map<Long, Integer> bfsDistance) {
        this.cellStates = Collections.unmodifiableMap(new HashMap<>(cellStates));
        this.bfsDistance = Collections.unmodifiableMap(new HashMap<>(bfsDistance));
    }

    public @Nullable BlockState stateAtPacked(long packed) {
        return cellStates.get(packed);
    }

    public boolean wasVisited(long packed) {
        return bfsDistance.containsKey(packed);
    }

    public int visitedCount() {
        return bfsDistance.size();
    }

    public Map<Long, Integer> distancesView() {
        return bfsDistance;
    }
}
