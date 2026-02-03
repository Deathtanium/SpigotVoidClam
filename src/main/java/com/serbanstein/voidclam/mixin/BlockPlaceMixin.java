package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a light block is placed, notify nearby VoidClam modules to pathfind to it (preserves original place event).
 */
@Mixin(AbstractBlock.class)
public class BlockPlaceMixin {

    @Inject(method = "onBlockAdded", at = @At("TAIL"))
    private void voidclam$onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify, CallbackInfo ci) {
        if (world.isClient() || !(world instanceof ServerWorld serverWorld)) return;
        if (!VoidClamMod.isLight(state.getBlock())) return;
        VoidClamMod.onLightPlaced(serverWorld, pos, state.getBlock());
    }
}
