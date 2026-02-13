/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_2246
 *  net.minecraft.class_2248
 *  net.minecraft.class_2338
 *  net.minecraft.class_238
 *  net.minecraft.class_2382
 *  net.minecraft.class_2680
 *  net.minecraft.class_3218
 *  net.minecraft.class_3222
 *  net.minecraft.class_3417
 *  net.minecraft.class_3419
 */
package com.serbanstein.voidclam;

import com.serbanstein.voidclam.Module;
import com.serbanstein.voidclam.Pathfinder;
import com.serbanstein.voidclam.VoidClamMod;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.class_2246;
import net.minecraft.class_2248;
import net.minecraft.class_2338;
import net.minecraft.class_238;
import net.minecraft.class_2382;
import net.minecraft.class_2680;
import net.minecraft.class_3218;
import net.minecraft.class_3222;
import net.minecraft.class_3417;
import net.minecraft.class_3419;

public final class CommandToolbox {
    static final ExecutorService pathfinderExecutor = Executors.newFixedThreadPool(2);

    public static void submitPathfinding(Runnable task) {
        pathfinderExecutor.execute(task);
    }

    public static void buildStub(class_3218 world, int x, int y, int z) {
        for (int ix = x - 1; ix <= x + 1; ++ix) {
            for (int iy = y - 2; iy <= y + 2; ++iy) {
                for (int iz = z - 1; iz <= z + 1; ++iz) {
                    long delay;
                    boolean black = (iy == y + 2 || iy == y - 2) && iz == z && ix == x || (iy == y - 1 || iy == y + 1) && (iz == z && (ix == x + 1 || ix == x - 1) || ix == x && (iz == z + 1 || iz == z - 1));
                    boolean red = ix == x && iz == z && iy < y + 2 && iy > y - 2;
                    int ux = ix;
                    int uy = iy;
                    int uz = iz;
                    if (red || black) {
                        delay = (long)Math.abs(iy - y) * 20L;
                        VoidClamMod.scheduleDelayed(world, delay, () -> {
                            world.method_8501(new class_2338(ux, uy, uz), class_2246.field_10541.method_9564());
                            world.method_43128(null, (double)ux + 0.5, (double)uy + 0.5, (double)uz + 0.5, class_3417.field_14817, class_3419.field_15245, 3.0f, 0.01f);
                        });
                    }
                    if (!black) continue;
                    delay = (long)Math.abs(iy - y) * 30L;
                    VoidClamMod.scheduleDelayed(world, delay, () -> {
                        world.method_8501(new class_2338(ux, uy, uz), class_2246.field_10540.method_9564());
                        world.method_43128(null, (double)ux + 0.5, (double)uy + 0.5, (double)uz + 0.5, class_3417.field_14817, class_3419.field_15245, 3.0f, 0.01f);
                    });
                }
            }
        }
    }

    public static boolean isInsideOctahedronInterior(double px, double py, double pz, int tsize) {
        if (py < (double)(-tsize / 2 + 1) || py > (double)(tsize - 2)) {
            return false;
        }
        double horiz = Math.abs(px) + Math.abs(pz);
        if (py >= 0.0) {
            return horiz <= (double)(tsize - 2) - py;
        }
        return horiz <= (double)(tsize - 2) + py;
    }

    public static boolean isPlayerTooCloseToModule(class_3222 player, Module m) {
        class_238 box = player.method_5829();
        int mx = m.x;
        int my = m.y;
        int mz = m.z;
        for (double x : new double[]{box.field_1323, box.field_1320}) {
            for (double y : new double[]{box.field_1322, box.field_1325}) {
                for (double z : new double[]{box.field_1321, box.field_1324}) {
                    if (CommandToolbox.isInsideOctahedronInterior(x - (double)mx, y - (double)my, z - (double)mz, m.currentSize)) continue;
                    return false;
                }
            }
        }
        return true;
    }

    public static void buildShell(class_3218 world, int x, int y, int z, int tsize, class_2248 mat) {
        int iz;
        int j;
        int k;
        int iy;
        class_2680 state = mat.method_9564();
        for (iy = y + tsize - 1; iy >= y + 1; --iy) {
            k = Math.abs(iy - y);
            for (j = x - tsize + 1 + k; j <= x; ++j) {
                iz = z - tsize + 1 + k + Math.abs(j - x);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
            for (j = x - tsize + 1 + k; j <= x; ++j) {
                iz = z + tsize - 1 - k - Math.abs(j - x);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
            for (j = x + tsize - 1 - k; j >= x; --j) {
                iz = z - tsize + 1 + k + Math.abs(j - x);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
            for (j = x + tsize - 1 - k; j >= x; --j) {
                iz = z + tsize - 1 - k - Math.abs(x - j);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
        }
        for (iy = y - tsize / 2; iy <= y - 1; ++iy) {
            k = Math.abs(iy - y);
            for (j = x - tsize + 1 + k; j <= x; ++j) {
                iz = z - tsize + 1 + k + Math.abs(j - x);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
            for (j = x - tsize + 1 + k; j <= x; ++j) {
                iz = z + tsize - 1 - k - Math.abs(j - x);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
            for (j = x + tsize - 1 - k; j >= x; --j) {
                iz = z - tsize + 1 + k + Math.abs(j - x);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
            for (j = x + tsize - 1 - k; j >= x; --j) {
                iz = z + tsize - 1 - k - Math.abs(x - j);
                world.method_8501(new class_2338(j, iy, iz), state);
            }
        }
    }

    public static void clamReSize(class_3218 world, int tno, int tsize) {
        int ix;
        int i;
        Module[] modules = VoidClamMod.getModules();
        if (tno < 1 || tno > VoidClamMod.getModuleNumber() || modules[tno] == null) {
            return;
        }
        Module m = modules[tno];
        if (!world.method_8393(m.x >> 4, m.z >> 4)) {
            return;
        }
        if (m.busyFlagGrowing != 0) {
            return;
        }
        m.busyFlagGrowing = 1;
        int ctype = m.type;
        class_2248 mat = class_2246.field_10541;
        if (ctype == 2) {
            mat = class_2246.field_22115;
        }
        int csize = m.currentSize;
        int x = m.x;
        int y = m.y;
        int z = m.z;
        int timer = 0;
        for (i = 1; i <= tsize; i += 2) {
            int iFinal = i;
            VoidClamMod.scheduleDelayed(world, (long)timer * 10L, () -> {
                CommandToolbox.buildShell(world, x, y, z, iFinal, class_2246.field_10541);
                world.method_43128(null, (double)x + 0.5, (double)y + 0.5, (double)z + 0.5, class_3417.field_14817, class_3419.field_15245, 3.0f, 0.01f);
            });
            ++timer;
        }
        for (int ix2 = x - csize; ix2 <= x + csize; ++ix2) {
            for (int iy = y - csize - 1; iy <= y + csize + 1; ++iy) {
                for (int iz = z - csize; iz <= z + csize; ++iz) {
                    class_2338 pos = new class_2338(ix2, iy, iz);
                    if (!world.method_8320(pos).method_27852(class_2246.field_10540)) continue;
                    world.method_8501(pos, mat.method_9564());
                }
            }
        }
        for (i = y + csize; i <= y + tsize - 1; ++i) {
            world.method_8501(new class_2338(x, i, z), mat.method_9564());
        }
        for (i = y - csize; i >= y - tsize + 1; --i) {
            world.method_8501(new class_2338(x, i, z), mat.method_9564());
        }
        int tnoFinal = tno;
        VoidClamMod.scheduleDelayed(world, (long)timer * 20L, () -> {
            CommandToolbox.buildShell(world, x, y, z, tsize, class_2246.field_10540);
            VoidClamMod.scheduleDelayed(world, 200L, () -> {
                Module[] mods = VoidClamMod.getModules();
                if (tnoFinal >= 1 && tnoFinal <= VoidClamMod.getModuleNumber() && mods[tnoFinal] != null) {
                    mods[tnoFinal].busyFlagGrowing = 0;
                }
            });
        });
        m.currentSize = tsize;
        VoidClamMod.save(world.method_8503());
        int ts = tsize - 2;
        for (ix = x - ts + 1; ix <= x; ++ix) {
            int iz = z - ts + 1 + Math.abs(ix - x);
            world.method_8501(new class_2338(ix, y, iz), mat.method_9564());
        }
        for (ix = x - ts + 1; ix <= x; ++ix) {
            int iz = z + ts - 1 - Math.abs(ix - x);
            world.method_8501(new class_2338(ix, y, iz), mat.method_9564());
        }
        for (ix = x + ts - 1; ix >= x; --ix) {
            int iz = z - ts + 1 + Math.abs(ix - x);
            world.method_8501(new class_2338(ix, y, iz), mat.method_9564());
        }
        for (ix = x + ts - 1; ix >= x; --ix) {
            int iz = z + ts - 1 - Math.abs(x - ix);
            world.method_8501(new class_2338(ix, y, iz), mat.method_9564());
        }
    }

    public static void clamReach(class_3218 world, int tno) {
        Module[] modules = VoidClamMod.getModules();
        if (tno < 1 || tno > VoidClamMod.getModuleNumber() || modules[tno] == null) {
            return;
        }
        Module m = modules[tno];
        if (!world.method_8393(m.x >> 4, m.z >> 4)) {
            return;
        }
        if (m.busyFlagMainCycle != 0 || m.busyFlagGrowing != 0) {
            return;
        }
        m.busyFlagMainCycle = 1;
        CommandToolbox.submitPathfinding(() -> {
            try {
                int x = m.x;
                int y = m.y;
                int z = m.z;
                int cSize = m.currentSize;
                class_2338 modPos = new class_2338(x, y, z);
                class_2338 closestLight = null;
                double closestLightDist = Double.MAX_VALUE;
                class_2338 closestOre = null;
                double closestOreDist = Double.MAX_VALUE;
                if (m.seekLights || m.seekOres) {
                    for (int iy = y - 4 * cSize; iy <= y + 4 * cSize; ++iy) {
                        for (int ix = x - 4 * cSize; ix <= x + 4 * cSize; ++ix) {
                            for (int iz = z - 4 * cSize; iz <= z + 4 * cSize; ++iz) {
                                class_2338 pos = new class_2338(ix, iy, iz);
                                class_2248 block = world.method_8320(pos).method_26204();
                                double dist = modPos.method_10262((class_2382)pos);
                                if (m.seekLights && VoidClamMod.isLight(block) && !m.lightsBlackList.contains(pos) && dist < closestLightDist) {
                                    closestLightDist = dist;
                                    closestLight = pos;
                                }
                                if (!m.seekOres || !VoidClamMod.isOre(block) || m.oresBlackList.contains(pos) || !(dist < closestOreDist)) continue;
                                closestOreDist = dist;
                                closestOre = pos;
                            }
                        }
                    }
                }
                class_2338 closest = null;
                if (closestLight != null && (closestOre == null || closestLightDist <= closestOreDist)) {
                    closest = closestLight;
                    m.lightsBlackList.add(closest.method_10062());
                } else if (closestOre != null) {
                    closest = closestOre;
                    m.oresBlackList.add(closest.method_10062());
                }
                if (closest != null) {
                    Pathfinder.calculatePath(world, tno, x, y, z, closest.method_10263(), closest.method_10264(), closest.method_10260());
                }
            }
            finally {
                m.busyFlagMainCycle = 0;
            }
        });
    }
}

