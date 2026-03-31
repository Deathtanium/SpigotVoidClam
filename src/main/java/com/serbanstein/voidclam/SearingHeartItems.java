package com.serbanstein.voidclam;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Vanilla blast furnace items tagged with {@link ItemStack} NBT so clam state stays server-side
 * without a custom item registry entry.
 */
public final class SearingHeartItems {
    public static final Text SEARING_NAME = new LiteralText("Searing Heart").setStyle(
        Style.EMPTY.withColor(Formatting.RED).withBold(true));
    private static final String ROOT_KEY = "voidclam";
    private static final String CLAM_NBT_SUBKEY = "module";

    public static final class PersistedSeekCacheSnapshot {
        public final int lightsC;
        public final int oresC;
        public final int oresBL;
        public final boolean hadVoidclamClamNbt;

        public PersistedSeekCacheSnapshot(int lightsC, int oresC, int oresBL, boolean hadVoidclamClamNbt) {
            this.lightsC = lightsC;
            this.oresC = oresC;
            this.oresBL = oresBL;
            this.hadVoidclamClamNbt = hadVoidclamClamNbt;
        }
    }

    private SearingHeartItems() {
    }

    public static boolean isSearingHeartStack(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.BLAST_FURNACE) return false;
        NbtCompound root = stack.getTag();
        if (root == null) return false;
        NbtCompound clam = root.getCompound(ROOT_KEY);
        if (clam.isEmpty()) return false;
        return clam.contains(CLAM_NBT_SUBKEY, 10);
    }

    public static ItemStack createFreshHeartStack() {
        Clam template = new Clam();
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
        template.repairWakeCyclesRemaining = VoidClamMod.SEARING_WAKE_REPAIR_CYCLES;
        return createDropFromBreak(template, null);
    }

    public static ItemStack createDropFromBreak(Clam m, @Nullable NbtCompound mergeFurnaceTag) {
        ItemStack stack = new ItemStack(Items.BLAST_FURNACE, 1);
        if (mergeFurnaceTag != null) {
            NbtCompound tag = mergeFurnaceTag.copy();
            tag.remove("voidclam");
            stack.setTag(tag);
        }
        stack.setCustomName(SEARING_NAME);
        NbtCompound root = stack.getTag() != null ? stack.getTag().copy() : new NbtCompound();
        NbtCompound clam = new NbtCompound();
        clam.put(CLAM_NBT_SUBKEY, writeClamNbt(m));
        root.put(ROOT_KEY, clam);
        stack.setTag(root);
        return stack;
    }

    public static NbtCompound writeClamNbt(Clam m) {
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
        n.putInt("repairWakeCyclesRemaining", m.repairWakeCyclesRemaining);
        if (m.worldKey != null) {
            n.putString("dimension", m.worldKey.getValue().toString());
        }
        return n;
    }

    public static @Nullable Clam readClamTemplateFromStack(ItemStack stack) {
        NbtCompound root = stack.getTag();
        if (root == null || !root.contains(ROOT_KEY, 10)) return null;
        NbtCompound clam = root.getCompound(ROOT_KEY);
        if (!clam.contains(CLAM_NBT_SUBKEY, 10)) return null;
        return readClamNbt(clam.getCompound(CLAM_NBT_SUBKEY));
    }

    public static @Nullable Clam readClamTemplateFromBlockEntity(AbstractFurnaceBlockEntity furnace) {
        if (furnace == null) return null;
        NbtCompound tag = new NbtCompound();
        furnace.writeNbt(tag);
        NbtCompound clam = tag.getCompound(ROOT_KEY);
        if (!tag.contains(ROOT_KEY, 10)) return null;
        if (!clam.contains(CLAM_NBT_SUBKEY, 10)) return null;
        return readClamNbt(clam.getCompound(CLAM_NBT_SUBKEY));
    }

    public static Clam readClamNbt(NbtCompound n) {
        Clam m = new Clam();
        if (n.contains("clamId", 8)) {
            try {
                m.clamId = UUID.fromString(n.getString("clamId"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        m.type = n.contains("type") ? n.getInt("type") : 0;
        m.currentSize = n.contains("currentSize") ? n.getInt("currentSize") : 1;
        if (m.currentSize < 1) m.currentSize = 1;
        m.status = n.contains("status") ? n.getInt("status") : 0;
        m.material = Math.max(0, n.contains("material") ? n.getInt("material") : 0);
        m.energy = n.contains("energy") ? n.getInt("energy") : 0;
        m.age = n.contains("age") ? n.getInt("age") : 0;
        m.seekLights = n.contains("seekLights") && n.getBoolean("seekLights");
        m.seekOres = n.contains("seekOres") && n.getBoolean("seekOres");
        m.protectItself = !n.contains("protectItself") || n.getBoolean("protectItself");
        m.stubBuilt = !n.contains("stubBuilt") || n.getBoolean("stubBuilt");
        if (n.contains("repairWakeCyclesRemaining")) {
            m.repairWakeCyclesRemaining = Math.max(0, n.getInt("repairWakeCyclesRemaining"));
        } else if (n.contains("needsFirstRepairWake") && n.getBoolean("needsFirstRepairWake")) {
            m.repairWakeCyclesRemaining = VoidClamMod.SEARING_WAKE_REPAIR_CYCLES;
        } else {
            m.repairWakeCyclesRemaining = 0;
        }
        if (n.contains("dimension", 8)) {
            String str = n.getString("dimension");
            try {
                Identifier id = new Identifier(str);
                m.worldKey = RegistryKey.of(Registry.WORLD_KEY, id);
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    public static void applyTemplateOntoClam(Clam snapshot, Clam into) {
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
        into.repairWakeCyclesRemaining = snapshot.repairWakeCyclesRemaining;
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
        into.repairResizeChainAwaitingCompletion = false;
    }

    public static boolean isPlainBlastFurnaceDrop(ItemStack stack) {
        return stack.getItem() == Items.BLAST_FURNACE && !isSearingHeartStack(stack);
    }

    public static void syncClamToBlockEntity(AbstractFurnaceBlockEntity furnace, Clam m) {
        if (furnace == null || m == null) {
            return;
        }
        m.ensureClamId();
        m.material = Math.max(0, m.material);
        m.energy = Math.max(0, m.energy);
        NbtCompound tag = new NbtCompound();
        furnace.writeNbt(tag);
        NbtCompound clam = new NbtCompound();
        clam.put(CLAM_NBT_SUBKEY, writeClamNbt(m));
        tag.put(ROOT_KEY, clam);
        furnace.fromTag(furnace.getCachedState(), tag);
        furnace.setCustomName(SEARING_NAME);
        furnace.markDirty();
    }

    public static PersistedSeekCacheSnapshot readPersistedSeekCacheSnapshot(AbstractFurnaceBlockEntity furnace) {
        if (furnace == null) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        NbtCompound tag = new NbtCompound();
        furnace.writeNbt(tag);
        NbtCompound clam = tag.getCompound(ROOT_KEY);
        if (!tag.contains(ROOT_KEY, 10)) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        if (!clam.contains(CLAM_NBT_SUBKEY, 10)) {
            return new PersistedSeekCacheSnapshot(0, 0, 0, false);
        }
        return new PersistedSeekCacheSnapshot(0, 0, 0, true);
    }
}
