package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.ComponentsAccess;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

public class VoidClamHeartBlockEntity extends BlockEntity {
    private static final int REACH_INTERVAL = 20;
    private static final int HEARTBEAT_INTERVAL = 4 * 20;
    private static final int DEFENSE_INTERVAL = 5 * 20;

    private VoidClamHeartItemData moduleData;

    public VoidClamHeartBlockEntity(BlockPos pos, BlockState state) {
        super(VoidClamBlocks.HEART_BLOCK_ENTITY_TYPE, pos, state);
        VoidClamHeartItemData pending = VoidClamHeartBlockItem.consumePendingPlacementData();
        this.moduleData = pending != null ? pending : VoidClamHeartItemData.defaultForNewClam();
    }

    public static void tick(World world, BlockPos pos, BlockState state, VoidClamHeartBlockEntity be) {
        if (world.isClient() || !(world instanceof ServerWorld serverWorld)) return;
        long t = serverWorld.getTime();
        int phase = Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, REACH_INTERVAL);

        UUID clamId = null;
        String idStr = be.moduleData.clamIdStr();
        if (idStr != null && !idStr.isEmpty()) {
            try {
                clamId = UUID.fromString(idStr);
            } catch (IllegalArgumentException ignored) {
            }
        }
        int slotByPos = VoidClamMod.findModuleSlotByCenter(pos);
        if (clamId == null && slotByPos >= 1) {
            Module mod = VoidClamMod.getModules()[slotByPos];
            if (mod != null) {
                mod.ensureClamId();
                be.syncFromModule(mod);
                clamId = mod.clamId;
            }
        }
        Module modForTick = clamId != null ? VoidClamMod.getModuleByClamId(clamId) : null;
        if (modForTick == null && slotByPos >= 1) {
            modForTick = VoidClamMod.getModules()[slotByPos];
            if (modForTick != null) {
                modForTick.ensureClamId();
                be.syncFromModule(modForTick);
                clamId = modForTick.clamId;
            }
        }
        if (clamId == null || modForTick == null) return;

        if ((t + phase) % REACH_INTERVAL == 0) {
            int slot = VoidClamMod.getSlotByClamId(clamId);
            if (slot >= 1) {
                CommandToolbox.clamReach(serverWorld, slot);
            }
            VoidClamMod.tickCoreCheckAtHeart(serverWorld, pos, clamId);
        }
        if ((t + phase + 11) % HEARTBEAT_INTERVAL == 0) {
            VoidClamMod.tickHeartbeatForModule(serverWorld, VoidClamMod.getModuleByClamId(clamId));
        }
        if ((t + phase + 7) % DEFENSE_INTERVAL == 0) {
            VoidClamMod.tickDefenseForModule(serverWorld, VoidClamMod.getModuleByClamId(clamId));
        }
    }

    public VoidClamHeartItemData getModuleData() {
        return moduleData;
    }

    public void setModuleData(VoidClamHeartItemData data) {
        this.moduleData = data;
        markDirty();
    }

    /** Sync runtime {@link Module} fields into this BE (e.g. after grow or seek flag changes). */
    public void syncFromModule(Module m) {
        this.moduleData = VoidClamHeartItemData.fromModule(m);
        markDirty();
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        moduleData.writeModulePayload(view);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.moduleData = VoidClamHeartItemData.fromReadView(view);
    }

    @Override
    protected void addComponents(ComponentMap.Builder componentMapBuilder) {
        super.addComponents(componentMapBuilder);
        componentMapBuilder.add(VoidClamDataComponents.HEART_STACK, moduleData);
    }

    @Override
    protected void readComponents(ComponentsAccess components) {
        super.readComponents(components);
        VoidClamHeartItemData fromComp = components.get(VoidClamDataComponents.HEART_STACK);
        if (fromComp != null) {
            this.moduleData = fromComp;
        }
    }

    @Override
    public void populateCrashReport(net.minecraft.util.crash.CrashReportSection section) {
        super.populateCrashReport(section);
        section.add("VoidClamHeart", "module fields present");
    }
}
