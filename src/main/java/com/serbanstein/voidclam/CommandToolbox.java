package com.serbanstein.voidclam;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoidClam block building and reach (pathfind) logic. Preserves original behaviour and busy-flag locks.
 */
public final class CommandToolbox {
    /** Shared executor for pathfinding (reach + block-place) so it doesn't block main thread. */
    static final ExecutorService pathfinderExecutor = Executors.newFixedThreadPool(2);

    /** Run pathfinding off-thread (used by clamReach and VoidClamMod.onLightPlaced). */
    public static void submitPathfinding(Runnable task) {
        pathfinderExecutor.execute(task);
    }

    public static void buildStub(ServerWorld world, int x, int y, int z) {
        for (int ix = x - 1; ix <= x + 1; ix++) {
            for (int iy = y - 2; iy <= y + 2; iy++) {
                for (int iz = z - 1; iz <= z + 1; iz++) {
                    boolean black = !(((iy != y + 2 && iy != y - 2) || iz != z || ix != x)
                        && ((iy != y - 1 && iy != y + 1) || ((iz != z || (ix != x + 1 && ix != x - 1))
                        && (ix != x || (iz != z + 1 && iz != z - 1)))));
                    boolean red = (ix == x && iz == z && iy < y + 2 && iy > y - 2);
                    final int ux = ix, uy = iy, uz = iz;
                    if (red || black) {
                        long delay = Math.abs(iy - y) * 20L;
                        VoidClamMod.scheduleDelayed(world, delay, () -> {
                            world.setBlockState(new BlockPos(ux, uy, uz), Blocks.NETHER_WART_BLOCK.getDefaultState());
                            world.playSound(null, ux + 0.5, uy + 0.5, uz + 0.5,
                                SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
                        });
                    }
                    if (black) {
                        long delay = Math.abs(iy - y) * 30L;
                        VoidClamMod.scheduleDelayed(world, delay, () -> {
                            world.setBlockState(new BlockPos(ux, uy, uz), Blocks.OBSIDIAN.getDefaultState());
                            world.playSound(null, ux + 0.5, uy + 0.5, uz + 0.5,
                                SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
                        });
                    }
                }
            }
        }
    }

    public static void buildShell(ServerWorld world, int x, int y, int z, int tsize, net.minecraft.block.Block mat) {
        net.minecraft.block.BlockState state = mat.getDefaultState();
        for (int iy = y + tsize - 1; iy >= y + 1; iy--) {
            int k = Math.abs(iy - y);
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z + tsize - 1 - k - Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z + tsize - 1 - k - Math.abs(x - j);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
        }
        for (int iy = y - tsize / 2; iy <= y - 1; iy++) {
            int k = Math.abs(iy - y);
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x - tsize + 1 + k; j <= x; j++) {
                int iz = z + tsize - 1 - k - Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z - tsize + 1 + k + Math.abs(j - x);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
            for (int j = x + tsize - 1 - k; j >= x; j--) {
                int iz = z + tsize - 1 - k - Math.abs(x - j);
                world.setBlockState(new BlockPos(j, iy, iz), state);
            }
        }
    }

    public static void clamReSize(ServerWorld world, int tno, int tsize) {
        Module[] modules = VoidClamMod.getModules();
        if (tno < 1 || tno > VoidClamMod.getModuleNumber() || modules[tno] == null) return;
        Module m = modules[tno];
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        int ctype = m.type;
        net.minecraft.block.Block mat = Blocks.NETHER_WART_BLOCK;
        if (ctype == 2) mat = Blocks.WARPED_WART_BLOCK;
        int csize = m.currentSize;
        int x = m.x, y = m.y, z = m.z;
        int timer = 0;

        for (int i = 1; i <= tsize; i += 2) {
            final int iFinal = i;
            VoidClamMod.scheduleDelayed(world, timer * 10L, () -> {
                buildShell(world, x, y, z, iFinal, Blocks.NETHER_WART_BLOCK);
                world.playSound(null, x + 0.5, y + 0.5, z + 0.5,
                    SoundEvents.BLOCK_CHORUS_FLOWER_GROW, SoundCategory.BLOCKS, 3f, 0.01f);
            });
            timer++;
        }

        for (int ix = x - csize; ix <= x + csize; ix++) {
            for (int iy = y - csize - 1; iy <= y + csize + 1; iy++) {
                for (int iz = z - csize; iz <= z + csize; iz++) {
                    BlockPos pos = new BlockPos(ix, iy, iz);
                    if (world.getBlockState(pos).isOf(Blocks.OBSIDIAN))
                        world.setBlockState(pos, mat.getDefaultState());
                }
            }
        }

        for (int i = y + csize; i <= y + tsize - 1; i++)
            world.setBlockState(new BlockPos(x, i, z), mat.getDefaultState());
        for (int i = y - csize; i >= y - tsize + 1; i--)
            world.setBlockState(new BlockPos(x, i, z), mat.getDefaultState());

        VoidClamMod.scheduleDelayed(world, timer * 20L, () -> buildShell(world, x, y, z, tsize, Blocks.OBSIDIAN));

        m.currentSize = tsize;
        VoidClamMod.save(world.getServer());

        int ts = tsize - 2;
        for (int ix = x - ts + 1; ix <= x; ix++) {
            int iz = z - ts + 1 + Math.abs(ix - x);
            world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
        for (int ix = x - ts + 1; ix <= x; ix++) {
            int iz = z + ts - 1 - Math.abs(ix - x);
            world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
        for (int ix = x + ts - 1; ix >= x; ix--) {
            int iz = z - ts + 1 + Math.abs(ix - x);
            world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
        for (int ix = x + ts - 1; ix >= x; ix--) {
            int iz = z + ts - 1 - Math.abs(x - ix);
            world.setBlockState(new BlockPos(ix, y, iz), mat.getDefaultState());
        }
    }

    /** Start pathfinding to nearest light for module tno. Uses executor + queue like original async. No-op if module chunk not loaded. */
    public static void clamReach(ServerWorld world, int tno) {
        Module[] modules = VoidClamMod.getModules();
        if (tno < 1 || tno > VoidClamMod.getModuleNumber() || modules[tno] == null) return;
        Module m = modules[tno];
        if (!world.isChunkLoaded(m.x >> 4, m.z >> 4)) return;
        if (m.busyFlagMainCycle != 0) return;
        m.busyFlagMainCycle = 1;

        submitPathfinding(() -> {
            try {
                int x = m.x, y = m.y, z = m.z, cSize = m.currentSize;
                BlockPos modPos = new BlockPos(x, y, z);
                BlockPos closest = null;
                double closestDist = Double.MAX_VALUE;

                for (int iy = y - 4 * cSize; iy <= y + 4 * cSize; iy++) {
                    for (int ix = x - 4 * cSize; ix <= x + 4 * cSize; ix++) {
                        for (int iz = z - 4 * cSize; iz <= z + 4 * cSize; iz++) {
                            BlockPos pos = new BlockPos(ix, iy, iz);
                            if (!VoidClamMod.isLight(world.getBlockState(pos).getBlock())) continue;
                            if (m.lightsBlackList.contains(pos)) continue;
                            double dist = modPos.getSquaredDistance(pos);
                            if (dist < closestDist) {
                                closestDist = dist;
                                closest = pos;
                            }
                        }
                    }
                }

                if (closest != null && !m.lightsBlackList.contains(closest)) {
                    m.lightsBlackList.add(closest.toImmutable());
                    Pathfinder.calculatePath(world, tno, x, y, z, closest.getX(), closest.getY(), closest.getZ());
                    // energy granted only when light is eaten in buildPath
                }
            } finally {
                m.busyFlagMainCycle = 0;
            }
        });
    }
}
