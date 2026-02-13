/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_1937
 *  net.minecraft.class_2338
 *  net.minecraft.class_2680
 *  net.minecraft.class_3218
 *  net.minecraft.class_4970
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2680;
import net.minecraft.class_3218;
import net.minecraft.class_4970;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={class_4970.class})
public class BlockPlaceMixin {
    @Inject(method={"method_9615"}, at={@At(value="TAIL")})
    private void voidclam$onBlockAdded(class_2680 state, class_1937 world, class_2338 pos, class_2680 oldState, boolean notify, CallbackInfo ci) {
        if (world.method_8608() || !(world instanceof class_3218)) {
            return;
        }
        class_3218 serverWorld = (class_3218)world;
        if (!VoidClamMod.isLight(state.method_26204())) {
            return;
        }
        VoidClamMod.onLightPlaced(serverWorld, pos, state.method_26204());
    }
}

