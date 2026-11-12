package com.tianluoqaq.tlmnh.common.work;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.work.bell.BellPreviewSnapshot;
import com.tianluoqaq.tlmnh.common.work.bell.BellTuning;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class BellPreviewSnapshotTest {

    @Test
    public void roundTripPreservesMaintenanceChunksAndBreedingAnchor() {
        NBTTagCompound maintRoot = new NBTTagCompound();
        MaintenanceBellData.toggleChunkOnRoot(maintRoot, 0, -3, 5);
        MaintenanceBellData.toggleChunkOnRoot(maintRoot, 0, 2, -1);
        MaintenanceBellData maint = MaintenanceBellData.readFromRoot(maintRoot);

        NBTTagCompound breedRoot = new NBTTagCompound();
        BreedingBellData.bindOnRoot(breedRoot, 0, 128, 64, -256);
        BreedingBellData breed = BreedingBellData.readFromRoot(breedRoot);

        BellPreviewSnapshot original = BellPreviewSnapshot.of(42, 0, maint, breed);
        ByteBuf buf = Unpooled.buffer();
        try {
            original.writeTo(buf);
            BellPreviewSnapshot decoded = BellPreviewSnapshot.readFrom(buf);
            assertEquals(0, buf.readableBytes());
            assertEquals(42, decoded.maidEntityId());
            assertEquals(0, decoded.worldDimension());
            assertTrue(decoded.hasMaintenance());
            assertEquals(0, decoded.maintenanceDimension());
            assertEquals(
                2,
                decoded.maintenanceChunks()
                    .size());
            assertEquals(
                -3,
                decoded.maintenanceChunks()
                    .get(0)[BellTuning.IDX_X]);
            assertEquals(
                5,
                decoded.maintenanceChunks()
                    .get(0)[BellTuning.IDX_Z]);
            assertEquals(
                2,
                decoded.maintenanceChunks()
                    .get(1)[BellTuning.IDX_X]);
            assertEquals(
                -1,
                decoded.maintenanceChunks()
                    .get(1)[BellTuning.IDX_Z]);

            assertTrue(decoded.hasBreeding());
            assertEquals(0, decoded.breedingDimension());
            assertEquals(128, decoded.breedingCenterX());
            assertEquals(64, decoded.breedingSoilY());
            assertEquals(65, decoded.breedingCropY());
            assertEquals(-256, decoded.breedingCenterZ());
        } finally {
            buf.release();
        }
    }

    @Test
    public void emptySnapshotEncodesUnboundEvictionFlags() {
        BellPreviewSnapshot empty = BellPreviewSnapshot.empty(99, 7);
        ByteBuf buf = Unpooled.buffer();
        try {
            empty.writeTo(buf);
            BellPreviewSnapshot decoded = BellPreviewSnapshot.readFrom(buf);
            assertEquals(0, buf.readableBytes());
            assertEquals(99, decoded.maidEntityId());
            assertEquals(7, decoded.worldDimension());
            assertFalse(decoded.hasMaintenance());
            assertTrue(
                decoded.maintenanceChunks()
                    .isEmpty());
            assertFalse(decoded.hasBreeding());
        } finally {
            buf.release();
        }
    }
}
