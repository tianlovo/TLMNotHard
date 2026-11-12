package com.tianluoqaq.tlmnh.common.work.bell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleInventory;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;

import io.netty.buffer.ByteBuf;

/**
 * 女仆当前已解锁饰品槽内佩戴铃铛的范围只读快照：
 * 仅携带维护区块列表与育种田块锚点，不同步完整饰品栏；无绑定铃铛时返回空绑定标志供客户端驱逐旧缓存
 */
public final class BellPreviewSnapshot {

    private final int maidEntityId;
    private final int worldDimension;
    private final boolean maintenanceBound;
    private final int maintenanceDimension;
    private final List<int[]> maintenanceChunks;
    private final boolean breedingBound;
    private final int breedingDimension;
    private final int breedingCenterX;
    private final int breedingSoilY;
    private final int breedingCenterZ;

    private BellPreviewSnapshot(int maidEntityId, int worldDimension, boolean maintenanceBound,
        int maintenanceDimension, List<int[]> maintenanceChunks, boolean breedingBound, int breedingDimension,
        int breedingCenterX, int breedingSoilY, int breedingCenterZ) {
        this.maidEntityId = maidEntityId;
        this.worldDimension = worldDimension;
        this.maintenanceBound = maintenanceBound;
        this.maintenanceDimension = maintenanceDimension;
        this.maintenanceChunks = maintenanceChunks;
        this.breedingBound = breedingBound;
        this.breedingDimension = breedingDimension;
        this.breedingCenterX = breedingCenterX;
        this.breedingSoilY = breedingSoilY;
        this.breedingCenterZ = breedingCenterZ;
    }

    public static BellPreviewSnapshot empty(int maidEntityId, int worldDimension) {
        return new BellPreviewSnapshot(
            maidEntityId,
            worldDimension,
            false,
            BellTuning.ZERO,
            Collections.emptyList(),
            false,
            BellTuning.ZERO,
            BellTuning.ZERO,
            BellTuning.ZERO,
            BellTuning.ZERO);
    }

    public static BellPreviewSnapshot of(int maidEntityId, int worldDimension, MaintenanceBellData maintenance,
        BreedingBellData breeding) {
        boolean hasMaint = maintenance != null && maintenance.isBound();
        int maintDim = hasMaint ? maintenance.dimension() : BellTuning.ZERO;
        List<int[]> copiedChunks = Collections.emptyList();
        if (hasMaint) {
            List<int[]> src = maintenance.chunks();
            int limit = Math.min(src.size(), BellTuning.MAX_BOUND_CHUNKS);
            List<int[]> list = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                int[] pair = src.get(i);
                int[] copy = new int[BellTuning.PAIR_LEN];
                copy[BellTuning.IDX_X] = pair[BellTuning.IDX_X];
                copy[BellTuning.IDX_Z] = pair[BellTuning.IDX_Z];
                list.add(copy);
            }
            copiedChunks = Collections.unmodifiableList(list);
        }

        boolean hasBreed = breeding != null && breeding.isBound();
        int breedDim = hasBreed ? breeding.dimension() : BellTuning.ZERO;
        int cx = hasBreed ? breeding.centerX() : BellTuning.ZERO;
        int sy = hasBreed ? breeding.soilY() : BellTuning.ZERO;
        int cz = hasBreed ? breeding.centerZ() : BellTuning.ZERO;
        return new BellPreviewSnapshot(
            maidEntityId,
            worldDimension,
            hasMaint && !copiedChunks.isEmpty(),
            maintDim,
            copiedChunks,
            hasBreed,
            breedDim,
            cx,
            sy,
            cz);
    }

    public static BellPreviewSnapshot captureFromMaid(EntityMaid maid) {
        if (maid == null || maid.worldObj == null) {
            return empty(BellPreviewProtocol.UNSET_ENTITY_ID, BellTuning.ZERO);
        }
        int worldDim = maid.worldObj.provider.dimensionId;
        MaidBaubleInventory baubles = maid.getMaidBaubleInventory();
        int unlocked = MaidBaubleLayout.unlockedSlots(
            maid.getFavorabilityManager()
                .getLevel());
        int limit = Math.min(unlocked, baubles.getSizeInventory());
        ItemStack maintStack = null;
        ItemStack breedStack = null;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = baubles.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0) continue;
            if (maintStack == null && stack.getItem() instanceof MaintenanceBellItem) {
                maintStack = stack;
            } else if (breedStack == null && stack.getItem() instanceof BreedingBellItem) {
                breedStack = stack;
            }
            if (maintStack != null && breedStack != null) break;
        }
        MaintenanceBellData maintData = maintStack == null ? MaintenanceBellData.unbound()
            : MaintenanceBellData.read(maintStack);
        BreedingBellData breedData = breedStack == null ? BreedingBellData.unbound()
            : BreedingBellData.read(breedStack);
        return of(maid.getEntityId(), worldDim, maintData, breedData);
    }

    public int maidEntityId() {
        return maidEntityId;
    }

    public int worldDimension() {
        return worldDimension;
    }

    public boolean hasMaintenance() {
        return maintenanceBound && !maintenanceChunks.isEmpty();
    }

    public int maintenanceDimension() {
        return maintenanceDimension;
    }

    public List<int[]> maintenanceChunks() {
        return maintenanceChunks;
    }

    public boolean hasBreeding() {
        return breedingBound;
    }

    public int breedingDimension() {
        return breedingDimension;
    }

    public int breedingCenterX() {
        return breedingCenterX;
    }

    public int breedingSoilY() {
        return breedingSoilY;
    }

    public int breedingCropY() {
        return breedingSoilY + BellTuning.ONE;
    }

    public int breedingCenterZ() {
        return breedingCenterZ;
    }

    public void writeTo(ByteBuf buf) {
        buf.writeInt(maidEntityId);
        buf.writeInt(worldDimension);
        boolean writeMaint = hasMaintenance();
        buf.writeBoolean(writeMaint);
        if (writeMaint) {
            buf.writeInt(maintenanceDimension);
            int count = Math.min(maintenanceChunks.size(), BellTuning.MAX_BOUND_CHUNKS);
            buf.writeByte(count);
            for (int i = 0; i < count; i++) {
                int[] pair = maintenanceChunks.get(i);
                buf.writeInt(pair[BellTuning.IDX_X]);
                buf.writeInt(pair[BellTuning.IDX_Z]);
            }
        }
        buf.writeBoolean(breedingBound);
        if (breedingBound) {
            buf.writeInt(breedingDimension);
            buf.writeInt(breedingCenterX);
            buf.writeInt(breedingSoilY);
            buf.writeInt(breedingCenterZ);
        }
    }

    public static BellPreviewSnapshot readFrom(ByteBuf buf) {
        int maidId = buf.readInt();
        int worldDim = buf.readInt();
        boolean hasMaint = buf.readBoolean();
        int maintDim = BellTuning.ZERO;
        List<int[]> chunks = Collections.emptyList();
        if (hasMaint) {
            maintDim = buf.readInt();
            int rawCount = buf.readUnsignedByte();
            int count = Math.min(rawCount, BellTuning.MAX_BOUND_CHUNKS);
            List<int[]> parsed = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int[] pair = new int[BellTuning.PAIR_LEN];
                pair[BellTuning.IDX_X] = buf.readInt();
                pair[BellTuning.IDX_Z] = buf.readInt();
                parsed.add(pair);
            }
            chunks = Collections.unmodifiableList(parsed);
        }
        boolean hasBreed = buf.readBoolean();
        int breedDim = BellTuning.ZERO;
        int cx = BellTuning.ZERO;
        int sy = BellTuning.ZERO;
        int cz = BellTuning.ZERO;
        if (hasBreed) {
            breedDim = buf.readInt();
            cx = buf.readInt();
            sy = buf.readInt();
            cz = buf.readInt();
        }
        return new BellPreviewSnapshot(
            maidId,
            worldDim,
            hasMaint && !chunks.isEmpty(),
            maintDim,
            chunks,
            hasBreed,
            breedDim,
            cx,
            sy,
            cz);
    }
}
