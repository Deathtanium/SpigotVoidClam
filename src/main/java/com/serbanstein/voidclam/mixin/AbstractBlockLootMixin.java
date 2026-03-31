package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/** Suppress duplicate blast-furnace loot for clam cores; target is {@link AbstractBlock} where {@code getDroppedStacks} is declared. */
@Mixin(AbstractBlock.class)
public abstract class AbstractBlockLootMixin {
    @Inject(
        method = "getDroppedStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/loot/context/LootContext$Builder;)Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void voidclam$suppressDuplicateBlastFurnaceDrop(
        BlockState state,
        LootContext.Builder builder,
        CallbackInfoReturnable<List<ItemStack>> cir
    ) {
        if (!((Object) this instanceof AbstractFurnaceBlock)) return;
        BlockEntity be = builder.get(LootContextParameters.BLOCK_ENTITY);
        if (!(be instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity)) return;
        if (!(be.getWorld() instanceof ServerWorld sw)) return;
        if (VoidClamMod.findClamAt(sw, be.getPos()) != null) {
            cir.setReturnValue(Collections.emptyList());
        }
    }
}
