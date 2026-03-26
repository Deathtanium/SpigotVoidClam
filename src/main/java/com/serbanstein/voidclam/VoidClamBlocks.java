package com.serbanstein.voidclam;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

import java.util.Optional;

public final class VoidClamBlocks {
    private static final RegistryKey<LootTable> HEART_LOOT_TABLE =
        RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of("voidclam", "blocks/voidclam_heart"));

    public static final Block HEART_BLOCK = Registry.register(
        Registries.BLOCK,
        Identifier.of("voidclam", "voidclam_heart"),
        new VoidClamHeartBlock(
            AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .strength(3.5f, 6.0f)
                .requiresTool()
                .sounds(BlockSoundGroup.METAL)
                .lootTable(Optional.of(HEART_LOOT_TABLE))
        )
    );

    public static final BlockEntityType<VoidClamHeartBlockEntity> HEART_BLOCK_ENTITY_TYPE = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of("voidclam", "voidclam_heart"),
        FabricBlockEntityTypeBuilder.create(VoidClamHeartBlockEntity::new, HEART_BLOCK).build()
    );

    public static final Item HEART_BLOCK_ITEM = Registry.register(
        Registries.ITEM,
        Identifier.of("voidclam", "voidclam_heart"),
        new VoidClamHeartBlockItem(HEART_BLOCK, new Item.Settings())
    );

    public static void register() {
    }

    private VoidClamBlocks() {
    }
}
