package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.SearingHeartItems;
import com.serbanstein.voidclam.VoidClamCoreBlocks;
import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {
    private static final ThreadLocal<ItemStack> VOIDCLAM_SEARING_CAPTURE = new ThreadLocal<>();

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;Lnet/minecraft/block/BlockState;)Z", at = @At("HEAD"))
    private void voidclam$captureSearing(ItemPlacementContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        ItemStack stack = context.getStack();
        if (stack.isOf(Items.BLAST_FURNACE) && SearingHeartItems.isSearingHeartStack(stack)) {
            VOIDCLAM_SEARING_CAPTURE.set(stack.copy());
        }
    }

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;Lnet/minecraft/block/BlockState;)Z", at = @At("RETURN"))
    private void voidclam$afterPlaceSearing(ItemPlacementContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        ItemStack captured = VOIDCLAM_SEARING_CAPTURE.get();
        VOIDCLAM_SEARING_CAPTURE.remove();
        if (!Boolean.TRUE.equals(cir.getReturnValue()) || captured == null) return;
        World world = context.getWorld();
        if (world.isClient() || !(world instanceof ServerWorld sw)) return;
        if (!state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) return;
        VoidClamMod.onSearingHeartItemPlaced(sw, context.getBlockPos(), captured);
    }
}
