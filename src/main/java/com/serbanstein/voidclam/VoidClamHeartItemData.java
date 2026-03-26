package com.serbanstein.voidclam;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable module fields carried on a heart item stack (no world position; assigned on place).
 */
public record VoidClamHeartItemData(
    int type,
    int currentSize,
    int status,
    int energy,
    int age,
    boolean seekLights,
    boolean seekOres,
    boolean protectItself,
    List<Long> lightsBlacklistEncoded,
    List<Long> oresBlacklistEncoded
) {
    private static final Codec<List<Long>> LONG_LIST_CODEC = Codec.LONG.listOf();

    public static final Codec<VoidClamHeartItemData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("type").forGetter(VoidClamHeartItemData::type),
        Codec.INT.fieldOf("currentSize").forGetter(VoidClamHeartItemData::currentSize),
        Codec.INT.fieldOf("status").forGetter(VoidClamHeartItemData::status),
        Codec.INT.fieldOf("energy").forGetter(VoidClamHeartItemData::energy),
        Codec.INT.fieldOf("age").forGetter(VoidClamHeartItemData::age),
        Codec.BOOL.fieldOf("seekLights").forGetter(VoidClamHeartItemData::seekLights),
        Codec.BOOL.fieldOf("seekOres").forGetter(VoidClamHeartItemData::seekOres),
        Codec.BOOL.fieldOf("protectItself").forGetter(VoidClamHeartItemData::protectItself),
        LONG_LIST_CODEC.optionalFieldOf("lightsBlacklist", List.of()).forGetter(VoidClamHeartItemData::lightsBlacklistEncoded),
        LONG_LIST_CODEC.optionalFieldOf("oresBlacklist", List.of()).forGetter(VoidClamHeartItemData::oresBlacklistEncoded)
    ).apply(instance, VoidClamHeartItemData::new));

    public static VoidClamHeartItemData defaultForNewClam() {
        VoidClamConfig cfg = VoidClamConfig.get();
        return new VoidClamHeartItemData(
            1,
            1,
            1,
            0,
            0,
            cfg.clam_light_flag_default,
            cfg.clam_ores_flag_default,
            cfg.clam_protect_itself_default,
            List.of(),
            List.of()
        );
    }

    public static VoidClamHeartItemData fromModule(Module m) {
        List<Long> lights = new ArrayList<>(m.lightsBlackList.size());
        for (BlockPos p : m.lightsBlackList) {
            lights.add(p.asLong());
        }
        List<Long> ores = new ArrayList<>(m.oresBlackList.size());
        for (BlockPos p : m.oresBlackList) {
            ores.add(p.asLong());
        }
        return new VoidClamHeartItemData(
            m.type,
            m.currentSize,
            m.status,
            m.energy,
            m.age,
            m.seekLights,
            m.seekOres,
            m.protectItself,
            List.copyOf(lights),
            List.copyOf(ores)
        );
    }

    public void applyToModule(Module m) {
        m.type = type;
        m.currentSize = currentSize;
        m.status = status;
        m.energy = energy;
        m.age = age;
        m.seekLights = seekLights;
        m.seekOres = seekOres;
        m.protectItself = protectItself;
        m.lightsBlackList.clear();
        m.oresBlackList.clear();
        for (long l : lightsBlacklistEncoded) {
            m.lightsBlackList.add(BlockPos.fromLong(l).toImmutable());
        }
        for (long l : oresBlacklistEncoded) {
            m.oresBlackList.add(BlockPos.fromLong(l).toImmutable());
        }
    }

    public void writeModulePayload(WriteView view) {
        view.putInt("type", type);
        view.putInt("currentSize", currentSize);
        view.putInt("status", status);
        view.putInt("energy", energy);
        view.putInt("age", age);
        view.putBoolean("seekLights", seekLights);
        view.putBoolean("seekOres", seekOres);
        view.putBoolean("protectItself", protectItself);
        view.put("lightsBlacklist", LONG_LIST_CODEC, lightsBlacklistEncoded);
        view.put("oresBlacklist", LONG_LIST_CODEC, oresBlacklistEncoded);
    }

    public static VoidClamHeartItemData fromReadView(ReadView view) {
        List<Long> lights = view.read("lightsBlacklist", LONG_LIST_CODEC).orElse(List.of());
        List<Long> ores = view.read("oresBlacklist", LONG_LIST_CODEC).orElse(List.of());
        return new VoidClamHeartItemData(
            view.getInt("type", 1),
            view.getInt("currentSize", 1),
            view.getInt("status", 1),
            view.getInt("energy", 0),
            view.getInt("age", 0),
            view.getBoolean("seekLights", false),
            view.getBoolean("seekOres", false),
            view.getBoolean("protectItself", true),
            lights,
            ores
        );
    }
}
