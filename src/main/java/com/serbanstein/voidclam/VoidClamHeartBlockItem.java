package com.serbanstein.voidclam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class VoidClamHeartBlockItem extends BlockItem {
    private static final ThreadLocal<VoidClamHeartItemData> PENDING_PLACEMENT = new ThreadLocal<>();

    public VoidClamHeartBlockItem(Block block, Settings settings) {
        super(block, settings.component(VoidClamDataComponents.HEART_STACK, VoidClamHeartItemData.defaultForNewClam()));
    }

    static void preparePlacementFromStack(ItemStack stack) {
        VoidClamHeartItemData data = stack.get(VoidClamDataComponents.HEART_STACK);
        PENDING_PLACEMENT.set(data != null ? data : VoidClamHeartItemData.defaultForNewClam());
    }

    static @Nullable VoidClamHeartItemData consumePendingPlacementData() {
        VoidClamHeartItemData d = PENDING_PLACEMENT.get();
        PENDING_PLACEMENT.remove();
        return d;
    }

    @Override
    public ItemPlacementContext getPlacementContext(ItemPlacementContext context) {
        if (!context.getWorld().isClient()) {
            preparePlacementFromStack(context.getStack());
        }
        return super.getPlacementContext(context);
    }

    @Override
    protected boolean postPlacement(BlockPos pos, World world, @Nullable PlayerEntity player, ItemStack stack, BlockState state) {
        boolean ok = super.postPlacement(pos, world, player, stack, state);
        if (ok && !world.isClient() && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            VoidClamMod.onHeartPlaced(serverWorld, pos);
        }
        return ok;
    }
}
