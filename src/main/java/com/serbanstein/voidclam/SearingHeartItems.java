package com.serbanstein.voidclam;

import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Vanilla blast furnace items tagged in {@link DataComponentTypes#CUSTOM_DATA} so modules stay server-side
 * without a custom item registry entry. Furnace block data is merged from {@link BlockEntity#createComponentMap()}
 * captured before the block is removed.
 */
public final class SearingHeartItems {
    public static final Text SEARING_NAME = Text.literal("Searing Heart")
        .styled(s -> s.withColor(Formatting.RED).withBold(true).withItalic(false));
    private static final String ROOT_KEY = "voidclam";
    private static final String MODULE_KEY = "module";

    /**
     * Seek caches / blacklists are no longer written to the heart NBT; this snapshot is retained for debug commands and
     * always reports zero persisted list sizes when lists were removed from {@link #writeModuleNbt}.
     */
    public record PersistedSeekCacheSnapshot(int lightsC, int oresC, int oresBL, boolean hadVoidclamModuleNbt) {
    }

    private SearingHeartItems() {
    }

    public static boolean isSearingHeartStack(ItemStack stack) {
        if (stack.isEmpty() || !stack.isOf(Items.BLAST_FURNACE)) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return false;
        NbtCompound root = data.copyNbt();
        return root.getCompound(ROOT_KEY).flatMap(clam -> clam.getCompound(MODULE_KEY)).isPresent();
    }

    /**
     * New heart for {@code /voidclam giveheart}: same template defaults as a fresh stub ({@link VoidClamMod#makeStub}),
     * excluding world position and {@link Module#clamId} (assigned on placement).
     */
    public static ItemStack createFreshHeartStack() {
        Module template = new Module();
        template.type = 1;
        template.currentSize = 1;
        template.status = 0;
        template.energy = 0;
        template.age = 0;
        VoidClamConfig cfg = VoidClamConfig.get();
        template.seekLights = cfg.clam_light_flag_default;
        template.seekOres = cfg.clam_ores_flag_default;
        template.protectItself = cfg.clam_protect_itself_default;
        template.stubBuilt = false;
        return createDropFromBreak(template, null);
    }

    public static ItemStack createDropFromBreak(Module m, @Nullable ComponentMap furnaceComponents) {
        ItemStack stack = new ItemStack(Items.BLAST_FURNACE, 1);
        if (furnaceComponents != null) {
            stack.applyComponentsFrom(furnaceComponents);
        }
        stack.set(DataComponentTypes.CUSTOM_NAME, SEARING_NAME);
        NbtCompound root = new NbtCompound();
        NbtComponent existingData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (existingData != null) {
            root.copyFrom(existingData.copyNbt());
        }
        NbtCompound clam = new NbtCompound();
        clam.put(MODULE_KEY, writeModuleNbt(m));
        root.put(ROOT_KEY, clam);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        return stack;
    }

    public static NbtCompound writeModuleNbt(Module m) {
        NbtCompound n = new NbtCompound();
        if (m.clamId != null) {
            n.putString("clamId", m.clamId.toString());
        }
        n.putInt("type", m.type);
        n.putInt("currentSize", m.currentSize);
        n.putInt("status", m.status);
        n.putInt("material", m.material);
        n.putInt("energy", m.energy);
        n.putInt("age", m.age);
        n.putBoolean("seekLights", m.seekLights);
        n.putBoolean("seekOres", m.seekOres);
        n.putBoolean("protectItself", m.protectItself);
        n.putBoolean("stubBuilt", m.stubBuilt);
        if (m.worldKey != null) {
            n.putString("dimension", m.worldKey.getValue().toString());
        }
        return n;
    }

    public static @Nullable Module readModuleTemplateFromStack(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return null;
        NbtCompound root = data.copyNbt();
        return root.getCompound(ROOT_KEY)
            .flatMap(clam -> clam.getCompound(MODULE_KEY))
            .map(SearingHeartItems::readModuleNbt)
            .orElse(null);
    }

    /** Same tags as a Searing Heart stack: read clam module fields from a block entity's components. */
    public static @Nullable Module readModuleTemplateFromComponentMap(ComponentMap components) {
        NbtComponent data = components.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return null;
        NbtCompound root = data.copyNbt();
        return root.getCompound(ROOT_KEY)
            .flatMap(clam -> clam.getCompound(MODULE_KEY))
            .map(SearingHeartItems::readModuleNbt)
            .orElse(null);
    }

    public static Module readModuleNbt(NbtCompound n) {
        Module m = new Module();
        n.getString("clamId").ifPresent(s -> {
            try {
                m.clamId = UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
            }
        });
        m.type = n.getInt("type").orElse(0);
        m.currentSize = n.getInt("currentSize").orElse(1);
        if (m.currentSize < 1) m.currentSize = 1;
        m.status = n.getInt("status").orElse(0);
        m.material = Math.max(0, n.getInt("material").orElse(0));
        m.energy = n.getInt("energy").orElse(0);
        m.age = n.getInt("age").orElse(0);
        m.seekLights = n.getBoolean("seekLights").orElse(false);
        m.seekOres = n.getBoolean("seekOres").orElse(false);
        m.protectItself = n.getBoolean("protectItself").orElse(true);
        m.stubBuilt = n.getBoolean("stubBuilt").orElse(true);
        n.getString("dimension").ifPresent(str -> {
            Identifier id = Identifier.tryParse(str);
            if (id != null) {
                m.worldKey = RegistryKey.of(RegistryKeys.WORLD, id);
            }
        });
        return m;
    }

    /** Copy fields from {@code snapshot} onto {@code into} (coordinates unchanged; {@code clamId} copied only if set on snapshot). */
    public static void applyTemplateOntoModule(Module snapshot, Module into) {
        if (snapshot.clamId != null) {
            into.clamId = snapshot.clamId;
        }
        into.type = snapshot.type;
        into.currentSize = snapshot.currentSize;
        into.status = snapshot.status;
        into.material = snapshot.material;
        into.energy = snapshot.energy;
        into.age = snapshot.age;
        into.seekLights = snapshot.seekLights;
        into.seekOres = snapshot.seekOres;
        into.protectItself = snapshot.protectItself;
        into.stubBuilt = snapshot.stubBuilt;
        into.worldKey = snapshot.worldKey;
        into.lightsCache.clear();
        into.oresCache.clear();
        into.oresBlackList.clear();
        into.lightsBlackList.clear();
        into.busyFlagPlaceEvent = 0;
        into.busyFlagMainCycle = 0;
        into.pathApplyPendingSteps = 0;
        into.lightPathGoalPacked = null;
        into.orePathGoalPacked = null;
        into.lightCacheRebuildTicksRemaining = 0;
        into.lightCacheRebuildCursor = 0L;
        into.oreCacheRebuildTicksRemaining = 0;
        into.oreCacheRebuildCursor = 0L;
        into.pathfindingResumeWorldTime = 0;
        into.prioritizeRepairOreSeek = false;
        into.orePathForMaterialHunger = false;
    }

    public static boolean isPlainBlastFurnaceDrop(ItemStack stack) {
        return stack.isOf(Items.BLAST_FURNACE) && !isSearingHeartStack(stack);
    }

    /**
     * Writes authoritative {@link Module} fields into the heart blast furnace’s {@link DataComponentTypes#CUSTOM_DATA}
     * so chunk save survives server restart (replaces CSV mirror).
     */
    public static void syncModuleToBlockEntity(AbstractFurnaceBlockEntity furnace, Module m) {
        if (furnace == null || m == null) {
            return;
        }
        m.ensureClamId();
        m.material = Math.max(0, m.material);
        m.energy = Math.max(0, m.energy);
        ComponentMap current = furnace.getComponents();
        NbtCompound root = new NbtCompound();
        NbtComponent existingData = current.get(DataComponentTypes.CUSTOM_DATA);
        if (existingData != null) {
            root.copyFrom(existingData.copyNbt());
        }
        NbtCompound clam = new NbtCompound();
        clam.put(MODULE_KEY, writeModuleNbt(m));
        root.put(ROOT_KEY, clam);
        NbtComponent updatedData = NbtComponent.of(root);
        boolean sameData = existingData != null && existingData.equals(updatedData);
        boolean sameName = SEARING_NAME.equals(current.get(DataComponentTypes.CUSTOM_NAME));
        if (sameData && sameName) {
            return;
        }
        ComponentMap merged = ComponentMap.builder()
            .addAll(current)
            .add(DataComponentTypes.CUSTOM_DATA, updatedData)
            .add(DataComponentTypes.CUSTOM_NAME, SEARING_NAME)
            .build();
        furnace.setComponents(merged);
        furnace.markDirty();
    }

    /**
     * Reads seek-cache list lengths from the furnace block entity’s custom data without requiring a {@link Module} round-trip.
     * Missing keys yield size {@code 0}; {@code hadVoidclamModuleNbt} is false when no {@code voidclam/module} compound exists.
     */
    public static PersistedSeekCacheSnapshot readPersistedSeekCacheSnapshot(AbstractFurnaceBlockEntity furnace) {
        if (furnace == null) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        net.minecraft.component.ComponentMap current = furnace.getComponents();
        NbtComponent data = current.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        NbtCompound root = data.copyNbt();
        var clamOpt = root.getCompound(ROOT_KEY);
        if (clamOpt.isEmpty()) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        var modOpt = clamOpt.get().getCompound(MODULE_KEY);
        if (modOpt.isEmpty()) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        return new PersistedSeekCacheSnapshot(0, 0, 0, true);
    }
}
