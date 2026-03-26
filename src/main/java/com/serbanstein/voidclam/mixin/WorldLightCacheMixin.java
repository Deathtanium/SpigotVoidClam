package com.serbanstein.voidclam.mixin;

import com.serbanstein.voidclam.VoidClamMod;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps per-clam {@link com.serbanstein.voidclam.Module#lightsCache} in sync when any code path changes a block.
 */
@Mixin(World.class)
public abstract class WorldLightCacheMixin {
    @Unique
    private static final ThreadLocal<Deque<BlockState>> voidclam$OLD_BLOCK = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"))
    private void voidclam$captureOldState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        voidclam$OLD_BLOCK.get().push(self.getBlockState(pos));
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("RETURN"))
    private void voidclam$notifyLightChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        Deque<BlockState> dq = voidclam$OLD_BLOCK.get();
        if (dq.isEmpty()) {
            return;
        }
        BlockState oldState = dq.pop();
        if (!cir.getReturnValueZ()) {
            return;
        }
        World self = (World) (Object) this;
        if (self.isClient() || !(self instanceof ServerWorld sw)) {
            return;
        }
        // Use requested `state` (successful apply) — avoid getBlockState here (heavy for beacon pyramids).
        VoidClamMod.enqueueLightCacheDeltaFromBlockChange(sw, pos, oldState, state);
    }
}
