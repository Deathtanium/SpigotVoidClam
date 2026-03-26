package com.serbanstein.voidclam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.ComponentsAccess;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

public class VoidClamHeartBlockEntity extends BlockEntity {
    private VoidClamHeartItemData moduleData;

    public VoidClamHeartBlockEntity(BlockPos pos, BlockState state) {
        super(VoidClamBlocks.HEART_BLOCK_ENTITY_TYPE, pos, state);
        VoidClamHeartItemData pending = VoidClamHeartBlockItem.consumePendingPlacementData();
        this.moduleData = pending != null ? pending : VoidClamHeartItemData.defaultForNewClam();
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
