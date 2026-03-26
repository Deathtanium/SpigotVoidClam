package com.serbanstein.voidclam;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;

/** Applies global {@link VoidClamConfig#sfx_volume_multiplier} to mod sounds. */
public final class VoidClamSfx {
    private VoidClamSfx() {}

    public static void playBlockSound(ServerWorld world, Entity entity, double x, double y, double z,
                                      SoundEvent sound, SoundCategory category, float volume, float pitch) {
        float v = (float) (volume * VoidClamConfig.get().sfx_volume_multiplier);
        world.playSound(entity, x, y, z, sound, category, v, pitch);
    }

    public static void playBlockSound(ServerWorld world, BlockPos pos, SoundEvent sound, SoundCategory category,
                                      float volume, float pitch) {
        playBlockSound(world, null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, category, volume, pitch);
    }
}
