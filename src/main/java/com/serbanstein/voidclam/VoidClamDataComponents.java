package com.serbanstein.voidclam;

import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Item stack data for a picked voidclam heart (persists module fields across break / place).
 */
public final class VoidClamDataComponents {
    public static final ComponentType<VoidClamHeartItemData> HEART_STACK =
        Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            Identifier.of("voidclam", "heart_stack"),
            ComponentType.<VoidClamHeartItemData>builder().codec(VoidClamHeartItemData.CODEC).build()
        );

    public static void register() {
    }

    private VoidClamDataComponents() {
    }
}
