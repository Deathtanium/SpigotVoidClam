package com.serbanstein.voidclam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-block rewards when clams consume blocks during pathing (and related hooks): {@code energy}, {@code material},
 * {@code soul}, and {@code drops} (namespaced items). Loaded from {@code config/voidclam/block_loot.json}; when missing,
 * the bundled defaults from classpath {@code /voidclam/default_block_loot.json} are copied there once as an editable example.
 */
public final class VoidClamBlockLoot {
    private static final Logger LOGGER = LoggerFactory.getLogger("voidclam/block_loot");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voidclam/block_loot.json");
    private static volatile VoidClamBlockLoot INSTANCE = new VoidClamBlockLoot();

    /** Defaults applied when a block is a detected light but has no explicit row. */
    public static final class Defaults {
        public int energy_for_unknown_light = 1;
        public int energy_for_bright_light = 2;
        public int bright_light_min_luminance = 15;
    }

    public static final class DropEntry {
        /** Registry id, e.g. {@code minecraft:diamond} or {@code somemod:special_ingot}. */
        public String item = "";
        public int count = 1;
    }

    public static final class BlockEntry {
        public int energy = 0;
        public int material = 0;
        public int soul = 0;
        public List<DropEntry> drops = new ArrayList<>();
    }

    public static final class Root {
        /** Optional documentation only; ignored by the game. */
        @SuppressWarnings("unused")
        public String readme;
        public Defaults defaults = new Defaults();
        public LinkedHashMap<String, BlockEntry> blocks = new LinkedHashMap<>();
    }

    private Root root = new Root();
    /** One log line per unknown item id per session. */
    private final ConcurrentHashMap<String, Boolean> warnedUnknownItems = new ConcurrentHashMap<>();

    public static VoidClamBlockLoot get() {
        return INSTANCE;
    }

    public static void loadFromDisk() {
        VoidClamBlockLoot loot = new VoidClamBlockLoot();
        ensureBundledDefaultsOnDisk();
        Root parsed = null;
        if (Files.isRegularFile(CONFIG_PATH)) {
            try (BufferedReader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                parsed = GSON.fromJson(r, Root.class);
            } catch (IOException e) {
                LOGGER.warn("failed to read {}: {}", CONFIG_PATH, e.getMessage());
            }
        }
        if (parsed == null) {
            parsed = readBundledRootFromClasspath();
        }
        if (parsed != null) {
            loot.root = parsed;
        } else {
            Root bundled = readBundledRootFromClasspath();
            if (bundled != null) {
                loot.root = bundled;
            }
        }
        loot.normalize();
        INSTANCE = loot;
    }

    private static void ensureBundledDefaultsOnDisk() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
        } catch (IOException e) {
            LOGGER.warn("could not create config/voidclam: {}", e.getMessage());
            return;
        }
        if (Files.isRegularFile(CONFIG_PATH)) {
            return;
        }
        try (InputStream in = VoidClamBlockLoot.class.getResourceAsStream("/voidclam/default_block_loot.json")) {
            if (in == null) {
                LOGGER.error("missing classpath resource /voidclam/default_block_loot.json");
                return;
            }
            Files.copy(in, CONFIG_PATH);
            LOGGER.info("wrote default block loot table to {} (edit freely)", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.warn("could not write default block loot to {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    @Nullable
    private static Root readBundledRootFromClasspath() {
        try (InputStream in = VoidClamBlockLoot.class.getResourceAsStream("/voidclam/default_block_loot.json")) {
            if (in == null) {
                return null;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return GSON.fromJson(r, Root.class);
            }
        } catch (IOException e) {
            LOGGER.warn("could not read bundled default block loot: {}", e.getMessage());
            return null;
        }
    }

    private void normalize() {
        if (root.defaults == null) {
            root.defaults = new Defaults();
        }
        if (root.blocks == null) {
            root.blocks = new LinkedHashMap<>();
        }
        for (BlockEntry be : root.blocks.values()) {
            if (be == null) {
                continue;
            }
            if (be.drops == null) {
                be.drops = new ArrayList<>();
            }
            be.energy = Math.max(0, be.energy);
            be.material = Math.max(0, be.material);
            be.soul = Math.max(0, be.soul);
        }
    }

    @Nullable
    private BlockEntry entryFor(Block block) {
        if (block == null) {
            return null;
        }
        Identifier id = Registries.BLOCK.getId(block);
        return root.blocks.get(id.toString());
    }

    public int resolveEnergy(BlockState state, @Nullable BlockView world, @Nullable BlockPos pos) {
        Block block = state.getBlock();
        BlockEntry e = entryFor(block);
        if (e != null) {
            return e.energy;
        }
        if (VoidClamMod.isLight(state, world, pos)) {
            VoidClamConfig cfg = VoidClamConfig.get();
            if (cfg != null && cfg.clam_light_detect_dynamic
                && state.getLuminance() >= root.defaults.bright_light_min_luminance) {
                return Math.max(0, root.defaults.energy_for_bright_light);
            }
            return Math.max(0, root.defaults.energy_for_unknown_light);
        }
        return 0;
    }

    public int resolveSoul(BlockState state, @Nullable BlockView world, @Nullable BlockPos pos) {
        BlockEntry e = entryFor(state.getBlock());
        if (e != null) {
            return e.soul;
        }
        return 0;
    }

    public int resolveMaterial(Block block) {
        BlockEntry e = entryFor(block);
        return e == null ? 0 : e.material;
    }

    /**
     * Item drops for storage routing (fortune-style rows). Empty if none or all ids invalid.
     */
    public List<ItemStack> resolveDrops(Block block) {
        BlockEntry e = entryFor(block);
        if (e == null || e.drops.isEmpty()) {
            return Collections.emptyList();
        }
        List<ItemStack> out = new ArrayList<>();
        for (DropEntry d : e.drops) {
            if (d == null || d.item == null || d.item.isEmpty()) {
                continue;
            }
            Identifier iid = Identifier.tryParse(d.item.trim());
            if (iid == null || !Registries.ITEM.containsId(iid)) {
                if (warnedUnknownItems.putIfAbsent(d.item, Boolean.TRUE) == null) {
                    LOGGER.warn("block_loot: unknown or missing item {}, drop skipped (check namespace:id)", d.item);
                }
                continue;
            }
            int c = Math.max(1, d.count);
            ItemStack stack = new ItemStack(Registries.ITEM.get(iid), c);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }
}
