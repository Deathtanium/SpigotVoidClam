package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootWorldContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/**
 * Clam cores reuse the blast furnace block; suppress its default loot so only the Searing Heart drop is produced.
 */
@Mixin(AbstractFurnaceBlock.class)
public abstract class AbstractFurnaceBlockMixin {
    @Inject(
        method = "getDroppedStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/loot/context/LootWorldContext$Builder;)Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void voidclam$suppressDuplicateBlastFurnaceDrop(
        BlockState state,
        LootWorldContext.Builder builder,
        CallbackInfoReturnable<List<ItemStack>> cir
    ) {
        BlockPos pos = null;
        var be = builder.getOptional(LootContextParameters.BLOCK_ENTITY);
        if (be != null) {
            pos = be.getPos();
        } else {
            Vec3d origin = builder.getOptional(LootContextParameters.ORIGIN);
            if (origin != null) {
                pos = BlockPos.ofFloored(origin.x, origin.y, origin.z);
            }
        }
        if (pos == null) return;
        ServerWorld lootWorld = builder.getWorld();
        if (lootWorld != null && VoidClamMod.findClamAt(lootWorld, pos) != null) {
            cir.setReturnValue(Collections.emptyList());
        }
    }
}
