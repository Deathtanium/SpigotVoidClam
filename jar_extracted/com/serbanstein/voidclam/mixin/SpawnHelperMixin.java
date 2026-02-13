/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_1311
 *  net.minecraft.class_1948
 *  net.minecraft.class_2338
 *  net.minecraft.class_2338$class_2339
 *  net.minecraft.class_2794
 *  net.minecraft.class_3218
 *  net.minecraft.class_5138
 *  net.minecraft.class_5483$class_1964
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
 */
package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.class_1311;
import net.minecraft.class_1948;
import net.minecraft.class_2338;
import net.minecraft.class_2794;
import net.minecraft.class_3218;
import net.minecraft.class_5138;
import net.minecraft.class_5483;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={class_1948.class})
public class SpawnHelperMixin {
    @Inject(method={"method_24934"}, at={@At(value="HEAD")}, cancellable=true)
    private static void voidclam$blockHostileSpawnInNoSpawnZones(class_3218 world, class_1311 group, class_5138 structureAccessor, class_2794 chunkGenerator, class_5483.class_1964 spawnEntry, class_2338.class_2339 pos, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (group != class_1311.field_6302) {
            return;
        }
        if (VoidClamMod.isHostileSpawnBlocked(world, (class_2338)pos)) {
            cir.setReturnValue((Object)false);
            cir.cancel();
        }
    }
}

