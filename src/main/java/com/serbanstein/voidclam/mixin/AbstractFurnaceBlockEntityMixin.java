package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import com.serbanstein.voidclam.VoidClamCoreBlocks;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep the blast furnace {@code lit} visuals in sync with clam wake state (vanilla smelting otherwise overwrites {@link AbstractFurnaceBlock#LIT}).
 * 1.16 uses instance {@code tick()} — not the static {@code tick(World, BlockPos, ...)} from 1.18+.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void voidclam$forceLitForClamCore(CallbackInfo ci) {
        AbstractFurnaceBlockEntity blockEntity = (AbstractFurnaceBlockEntity) (Object) this;
        if (!(blockEntity.getWorld() instanceof ServerWorld)) {
            return;
        }
        ServerWorld world = (ServerWorld) blockEntity.getWorld();
        BlockPos pos = blockEntity.getPos();
        BlockState state = blockEntity.getCachedState();
        if (!state.isOf(VoidClamCoreBlocks.CORE_BLOCK)) {
            return;
        }
        VoidClamMod.tryRegisterFromClamCoreBlockEntity(world, pos, blockEntity);
        com.serbanstein.voidclam.Clam clam = VoidClamMod.findClamAt(world, pos);
        if (clam == null) return;
        boolean wantLit = VoidClamMod.isSearingHeartThermallyActive(world, clam);
        if (state.get(AbstractFurnaceBlock.LIT) != wantLit) {
            world.setBlockState(pos, state.with(AbstractFurnaceBlock.LIT, wantLit), 2);
        }
    }
}
