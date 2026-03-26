package com.serbanstein.voidclam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Clam core uses only vanilla blocks so vanilla (non-mod) clients can join a Fabric server running VoidClam.
 * Module state lives in {@link VoidClamMod} / {@code modules.siva} only, not in block NBT or custom items.
 */
public final class VoidClamCoreBlocks {
    /** Block placed at each clam center; vanilla clients recognize it. */
    public static final Block CORE_BLOCK = Blocks.BLAST_FURNACE;

    public static boolean isCoreBlock(Block block) {
        return block == CORE_BLOCK;
    }

    /** Nether wart tunnels + blast furnace core share pathfinding / pulse rules. */
    public static boolean isWartOrCore(BlockState state) {
        return state.isOf(Blocks.NETHER_WART_BLOCK) || state.isOf(CORE_BLOCK);
    }

    private VoidClamCoreBlocks() {
    }
}
