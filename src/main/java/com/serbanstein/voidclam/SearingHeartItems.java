package com.serbanstein.voidclam;

import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

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
        n.putInt("type", m.type);
        n.putInt("currentSize", m.currentSize);
        n.putInt("status", m.status);
        n.putInt("energy", m.energy);
        n.putInt("age", m.age);
        n.putBoolean("seekLights", m.seekLights);
        n.putBoolean("seekOres", m.seekOres);
        n.putBoolean("protectItself", m.protectItself);
        n.putBoolean("stubBuilt", m.stubBuilt);
        NbtList lights = new NbtList();
        for (Long p : m.lightsCache) {
            lights.add(NbtLong.of(p));
        }
        n.put("lightsC", lights);
        NbtList ores = new NbtList();
        for (BlockPos p : m.oresBlackList) {
            ores.add(NbtLong.of(p.asLong()));
        }
        n.put("oresBL", ores);
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

    public static Module readModuleNbt(NbtCompound n) {
        Module m = new Module();
        m.type = n.getInt("type").orElse(0);
        m.currentSize = n.getInt("currentSize").orElse(1);
        if (m.currentSize < 1) m.currentSize = 1;
        m.status = n.getInt("status").orElse(0);
        m.energy = n.getInt("energy").orElse(0);
        m.age = n.getInt("age").orElse(0);
        m.seekLights = n.getBoolean("seekLights").orElse(false);
        m.seekOres = n.getBoolean("seekOres").orElse(false);
        m.protectItself = n.getBoolean("protectItself").orElse(true);
        m.stubBuilt = n.getBoolean("stubBuilt").orElse(true);
        n.getList("lightsC").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                NbtElement el = list.get(i);
                if (el instanceof AbstractNbtNumber num) {
                    m.lightsCache.add(num.longValue());
                }
            }
        });
        n.getList("oresBL").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                NbtElement el = list.get(i);
                if (el instanceof AbstractNbtNumber num) {
                    m.oresBlackList.add(BlockPos.fromLong(num.longValue()));
                }
            }
        });
        return m;
    }

    /** Copy fields from {@code snapshot} onto {@code into} (coordinates and id unchanged on {@code into}). */
    public static void applyTemplateOntoModule(Module snapshot, Module into) {
        into.type = snapshot.type;
        into.currentSize = snapshot.currentSize;
        into.status = snapshot.status;
        into.energy = snapshot.energy;
        into.age = snapshot.age;
        into.seekLights = snapshot.seekLights;
        into.seekOres = snapshot.seekOres;
        into.protectItself = snapshot.protectItself;
        into.stubBuilt = snapshot.stubBuilt;
        into.lightsCache.clear();
        into.lightsCache.addAll(snapshot.lightsCache);
        into.oresBlackList.clear();
        for (BlockPos p : snapshot.oresBlackList) {
            into.oresBlackList.add(p.toImmutable());
        }
        into.lightsBlackList.clear();
        into.busyFlagPlaceEvent = 0;
        into.busyFlagMainCycle = 0;
        into.pathApplyPendingSteps = 0;
        into.lightPathGoalPacked = null;
        into.pathfindingResumeWorldTime = 0;
    }

    public static boolean isPlainBlastFurnaceDrop(ItemStack stack) {
        return stack.isOf(Items.BLAST_FURNACE) && !isSearingHeartStack(stack);
    }
}
