package com.serbanstein.voidclam;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class VoidClamHeartBlock extends BlockWithEntity {
    public static final MapCodec<VoidClamHeartBlock> CODEC = createCodec(VoidClamHeartBlock::new);

    public VoidClamHeartBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new VoidClamHeartBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return validateTicker(
            type,
            VoidClamBlocks.HEART_BLOCK_ENTITY_TYPE,
            VoidClamHeartBlockEntity::tick
        );
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient() && world instanceof ServerWorld serverWorld) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof VoidClamHeartBlockEntity) {
                VoidClamMod.onHeartBroken(serverWorld, pos);
            }
        }
        return super.onBreak(world, pos, state, player);
    }

}
