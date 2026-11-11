package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineBlock;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryParcel;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import io.netty.buffer.Unpooled;

/** 神龛外观适配的原生库存、碰撞、包体和拒绝边界，不替代真实客户端成像 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ShrineDisplayTests {

    private ShrineDisplayTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "shrine_native_orientation")
    public static void nativePlacementFacingRetainsFilmAndUsesOriginalThreeTierCollision(GameTestHelper helper) {
        var f = new Fixture(helper);
        var expected = f.saved();
        for (int yaw = 0; yaw < 4; yaw++) {
            f.base.owner.rotationYaw = yaw * 90;
            MaidResurrectionContent.SHRINE.onBlockPlacedBy(helper.getWorld(), f.x, f.y, f.z, f.base.owner, f.placed);
            helper.assertTrue(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z) == yaw && MaidShrineBlock.facingDirection(yaw) == ((yaw + 2) & 3),
                "Native shrine opposite yaw mapping changed");
            helper.assertTrue(
                helper.getWorld()
                    .getTileEntity(f.x, f.y, f.z) == f.shrine && expected.equals(f.saved()),
                "Metadata change rebuilt the shrine or changed complete stored film NBT");
        }
        var boxes = new ArrayList<AxisAlignedBB>();
        MaidResurrectionContent.SHRINE.addCollisionBoxesToList(
            helper.getWorld(),
            f.x,
            f.y,
            f.z,
            AxisAlignedBB.getBoundingBox(f.x - 1, f.y - 1, f.z - 1, f.x + 2, f.y + 2, f.z + 2),
            boxes,
            null);
        helper.assertTrue(
            boxes.size() == 3 && boxes.get(0).maxY == f.y + 5.0 / 16
                && boxes.get(1).minX == f.x + 2.0 / 16
                && boxes.get(1).maxY == f.y + 10.0 / 16
                && boxes.get(2).minX == f.x + 4.0 / 16
                && boxes.get(2).maxY == f.y + 1,
            "Original shrine three-tier collision differs");
        for (double offset : new double[] { .05, .2, .5 }) {
            var hit = MaidResurrectionContent.SHRINE.collisionRayTrace(
                helper.getWorld(),
                f.x,
                f.y,
                f.z,
                Vec3.createVectorHelper(f.x + offset, f.y + 2, f.z + offset),
                Vec3.createVectorHelper(f.x + offset, f.y - 1, f.z + offset));
            double top = offset < .125 ? 5.0 / 16 : offset < .25 ? 10.0 / 16 : 1;
            helper.assertTrue(
                hit != null && hit.sideHit == 1 && Math.abs(hit.hitVec.yCoord - f.y - top) < 1E-8,
                "Native ray selection differs from stepped shrine geometry");
        }
        helper.succeedWhen(() -> expected.equals(f.saved()));
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "shrine_display_wire")
    public static void serializeActualEmptyFilmAndRecoveryDescriptionPacketsWithoutPrivateStorage(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        var expected = f.saved();
        f.capture("film");
        var display = f.shrine.displayItem();
        helper.assertTrue(
            display != null && display.getItem() == MaidResurrectionContent.FILM && !display.hasTagCompound(),
            "Film display exposed token/owner/private item NBT");
        display.setTagCompound(new NBTTagCompound());
        display.getTagCompound()
            .setString("TLMNHToken", "client_visual_mutation");
        helper.assertTrue(expected.equals(f.saved()), "Display item mutation reached the stored film");
        f.shrine.extract();
        f.capture("empty");
        var raw = new NBTTagCompound();
        raw.setString("id", "future:missing_film");
        raw.setString("Opaque", "retain_unavailable_storage");
        var saved = f.saved();
        saved.setTag("StorageItem", raw);
        f.shrine.readFromNBT(saved);
        helper.assertTrue(
            f.shrine.getFilm()
                .getItem() == RecoveryParcel.ITEM
                && f.shrine.displayItem()
                    .getItem() == RecoveryParcel.ITEM
                && !f.shrine.displayItem()
                    .hasTagCompound(),
            "Recovery storage was lost or its private payload exposed as display data");
        f.capture("recovery");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "shrine_display_refusal")
    public static void serverRejectsDisplayPacketsAndUnknownOrientationRetainsRawFilm(GameTestHelper helper) {
        var f = new Fixture(helper);
        var expected = f.saved();
        var incoming = new NBTTagCompound();
        incoming.setByte("DisplayVersion", (byte) 1);
        incoming.setByte("StorageKind", (byte) 0);
        incoming.setTag("StorageItem", new NBTTagCompound());
        f.shrine.onDataPacket(null, new S35PacketUpdateTileEntity(f.x, f.y, f.z, 0, incoming));
        helper.assertTrue(expected.equals(f.saved()), "Incoming display packet overwrote authoritative storage");
        helper.getWorld()
            .setBlockMetadataWithNotify(f.x, f.y, f.z, 12, 2);
        f.base.owner.rotationYaw = 90;
        MaidResurrectionContent.SHRINE.onBlockPlacedBy(helper.getWorld(), f.x, f.y, f.z, f.base.owner, f.placed);
        helper.assertTrue(
            helper.getWorld()
                .getBlockMetadata(f.x, f.y, f.z) == 12
                && helper.getWorld()
                    .getTileEntity(f.x, f.y, f.z) == f.shrine
                && expected.equals(f.saved()),
            "Unknown metadata was normalized or destroyed stored film");
        var loaded = new MaidShrineTile();
        loaded.readFromNBT(expected);
        var actual = new NBTTagCompound();
        loaded.writeToNBT(actual);
        helper.assertTrue(expected.equals(actual), "Authoritative original film changed after NBT rebuild");
        helper.succeedWhen(() -> expected.equals(f.saved()));
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final ModernCoreTests.Fixture base;
        final int x, y, z;
        final ItemStack placed;
        final MaidShrineTile shrine;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            helper.afterTest(this::close);
            base = new ModernCoreTests.Fixture(helper, false);
            base.maid.getBrainCoordinator()
                .setSuspended(true);
            var pos = helper.absolute(8, 1, 4);
            x = pos.x();
            y = pos.y();
            z = pos.z();
            helper.setBlock(8, 0, 4, Blocks.stone);
            placed = new ItemStack(Item.getItemFromBlock(MaidResurrectionContent.SHRINE));
            base.owner.rotationYaw = 0;
            base.owner.inventory.mainInventory[base.owner.inventory.currentItem] = placed;
            helper.assertTrue(
                placed.getItem()
                    .onItemUse(placed, base.owner, helper.getWorld(), x, y - 1, z, 1, .5F, .5F, .5F)
                    && placed.stackSize == 0,
                "Native shrine ItemBlock placement failed or consumed more than one");
            shrine = (MaidShrineTile) helper.getWorld()
                .getTileEntity(x, y, z);
            var film = MaidLifecycleItems
                .credential(MaidResurrectionContent.FILM, UUID.randomUUID(), base.owner.getUniqueID());
            film.getTagCompound()
                .setString("PrivatePayload", "do_not_send_to_client");
            film.getTagCompound()
                .setIntArray("Opaque", new int[] { 9, 2, 7 });
            helper.assertTrue(shrine.insert(film), "Test original film was not admitted");
        }

        NBTTagCompound saved() {
            var tag = new NBTTagCompound();
            shrine.writeToNBT(tag);
            return tag;
        }

        void capture(String kind) {
            var bytes = Unpooled.buffer();
            try {
                shrine.getDescriptionPacket()
                    .writePacketData(new PacketBuffer(bytes));
                var wire = new byte[bytes.writerIndex()];
                bytes.getBytes(0, wire);
                java.nio.file.Files.write(
                    java.nio.file.Paths.get(System.getProperty("horizonqa.reportDir"), "shrine-" + kind + ".s35"),
                    wire);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot capture actual shrine description", error);
            } finally {
                bytes.release();
            }
        }

        void close() {
            if (shrine != null && helper.getWorld()
                .getTileEntity(x, y, z) == shrine) shrine.extract();
        }
    }
}
