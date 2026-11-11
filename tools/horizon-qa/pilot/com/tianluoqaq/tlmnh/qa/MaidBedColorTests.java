package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraftforge.common.util.FakePlayer;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.life.bed.MaidBedColor;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import io.netty.buffer.Unpooled;

/** 真实方块交互、物品和NBT/数据包边界；不把受控FakePlayer调用冒称客户端点击或联机同步 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class MaidBedColorTests {

    private MaidBedColorTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "maid_bed_color")
    public static void sevenDyesUseEitherHalfKeepMetadataAndPersistWithoutExtraConsumption(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(f.bed.getColor() == MaidBedColor.PINK, "New bed must use the original pink default");
        var colors = new MaidBedColor[] { MaidBedColor.WHITE, MaidBedColor.BLACK, MaidBedColor.YELLOW,
            MaidBedColor.BLUE, MaidBedColor.GREEN, MaidBedColor.PURPLE, MaidBedColor.PINK };
        int[] dyes = { 15, 0, 11, 4, 2, 5, 9 };
        // 模拟已经占用的metadata；本例不运行睡眠AI或用它证明自然租约语义
        helper.getWorld()
            .setBlockMetadataWithNotify(f.x, f.y, f.z, 14, 2);
        helper.assertTrue(
            helper.getWorld()
                .getTileEntity(f.x, f.y, f.z) == f.bed,
            "Metadata fixture replaced the authoritative tile");
        for (int i = 0; i < colors.length; i++) {
            var stack = f.held(Items.dye, dyes[i]);
            boolean foot = (i & 1) == 0;
            helper.assertTrue(
                f.click(foot) && stack.stackSize == 2 && f.bed.getColor() == colors[i],
                "Supported dye did not change the authoritative head exactly once: " + colors[i]);
            helper.assertTrue(!f.click(!foot) && stack.stackSize == 2, "Same color consumed another dye");
            helper.assertTrue(
                stack.getTagCompound()
                    .getString("QualityMarker")
                    .equals("retained"),
                "Dye NBT changed");
            var saved = new NBTTagCompound();
            f.bed.writeToNBT(saved);
            var loaded = new TileEntityMaidBed();
            loaded.readFromNBT(saved);
            helper.assertTrue(
                loaded.getColor() == colors[i] && saved.getInteger("BedColor") == colors[i].id,
                "Supported color did not round trip through the native tile save");
            f.captureDescription(colors[i]);
            helper.assertTrue(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z) == 14,
                "Color change rewrote bed direction/head/occupied metadata");
        }
        f.player.capabilities.isCreativeMode = true;
        var creative = f.held(Items.dye, 4);
        helper.assertTrue(
            f.click(true) && creative.stackSize == 3 && f.bed.getColor() == MaidBedColor.BLUE,
            "Creative dye changed inventory count");
        f.player.capabilities.isCreativeMode = false;
        for (int dye : new int[] { 1, 3, 6, 7, 8, 10, 12, 13, 14, 16 }) {
            var unsupported = f.held(Items.dye, dye);
            helper.assertTrue(
                !f.click(false) && unsupported.stackSize == 3 && f.bed.getColor() == MaidBedColor.BLUE,
                "Unsupported dye changed data or inventory");
        }
        var nonDye = f.held(Items.diamond, 0);
        helper.assertTrue(!f.click(true) && nonDye.stackSize == 3, "Non-dye item was consumed");
        System.out.println("TLMNH_BED_COLOR_SEVEN_PASS native_interaction=true client_click=false");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "maid_bed_color_unreadable")
    public static void unreadableColorsRoundTripRefuseDyeAndServerIgnoresIncomingDescription(GameTestHelper helper) {
        var f = new Fixture(helper);
        var future = new NBTTagCompound();
        future.setInteger("Revision", 2);
        future.setString("Color", "future_blue");
        for (NBTBase raw : ImmutableList.of(new NBTTagInt(999), new NBTTagString("pink"), future)) {
            var tag = new NBTTagCompound();
            f.bed.writeToNBT(tag);
            tag.setTag("BedColor", raw.copy());
            f.bed.readFromNBT(tag);
            var held = f.held(Items.dye, 15);
            helper.assertTrue(
                f.bed.getColor() == null && !f.click(true) && held.stackSize == 3,
                "Unreadable color was guessed or consumed dye");
            // 收到的显示包不能在服务端改颜色、坐标或执行readFromNBT清掉权威状态
            var incoming = new NBTTagCompound();
            incoming.setInteger("BedColor", MaidBedColor.WHITE.id);
            f.bed.onDataPacket(null, new S35PacketUpdateTileEntity(f.x, f.y, f.z, 0, incoming));
            var saved = new NBTTagCompound();
            f.bed.writeToNBT(saved);
            helper.assertTrue(
                saved.getTag("BedColor")
                    .equals(raw) && f.bed.getColor() == null,
                "Unknown raw NBT was normalized or overwritten by an incoming display packet");
            var loaded = new TileEntityMaidBed();
            loaded.readFromNBT(saved);
            var again = new NBTTagCompound();
            loaded.writeToNBT(again);
            helper.assertTrue(
                again.getTag("BedColor")
                    .equals(raw),
                "Unknown color was not retained across reload");
        }
        var legacy = new NBTTagCompound();
        f.bed.writeToNBT(legacy);
        legacy.removeTag("BedColor");
        f.bed.readFromNBT(legacy);
        helper.assertTrue(f.bed.getColor() == MaidBedColor.PINK, "Legacy bed without color lost the default");
        System.out.println("TLMNH_BED_COLOR_UNREADABLE_PASS unknown_payloads=3 server_packet_ignored=true");
        helper.succeedWhen(() -> true);
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final FakePlayer player;
        final TileEntityMaidBed bed;
        final int x, y, z;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            var head = helper.absolute(8, 1, 4);
            x = head.x();
            y = head.y();
            z = head.z();
            player = new FakePlayer(helper.getWorld(), new GameProfile(UUID.randomUUID(), "[QA-BedColor]"));
            helper.afterTest(() -> {
                player.setDead();
                helper.getWorld()
                    .setBlock(x, y, z, Blocks.air, 0, 2);
                helper.getWorld()
                    .setBlock(x, y, z + 1, Blocks.air, 0, 2);
            });
            helper.setBlock(8, 0, 4, Blocks.stone);
            helper.setBlock(8, 0, 5, Blocks.stone);
            helper.setBlock(8, 1, 5, MaidLifeContent.maidBed, 2);
            helper.setBlock(8, 1, 4, MaidLifeContent.maidBed, 10);
            bed = (TileEntityMaidBed) helper.getWorld()
                .getTileEntity(x, y, z);
            helper.assertTrue(bed != null && bed.complete(), "Complete real bed required");
        }

        ItemStack held(net.minecraft.item.Item item, int damage) {
            var stack = new ItemStack(item, 3, damage);
            stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setString("QualityMarker", "retained");
            player.inventory.mainInventory[player.inventory.currentItem] = stack;
            return stack;
        }

        void captureDescription(MaidBedColor color) {
            var bytes = Unpooled.buffer();
            try {
                var buffer = new PacketBuffer(bytes);
                bed.getDescriptionPacket()
                    .writePacketData(buffer);
                byte[] wire = new byte[bytes.writerIndex()];
                bytes.getBytes(0, wire);
                java.nio.file.Files.write(
                    java.nio.file.Paths
                        .get(System.getProperty("horizonqa.reportDir"), "bed-color-" + color.resourceName + ".s35"),
                    wire);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot serialize native color description", error);
            } finally {
                bytes.release();
            }
        }

        boolean click(boolean foot) {
            return MaidLifeContent.maidBed
                .onBlockActivated(helper.getWorld(), x, y, foot ? z + 1 : z, player, 1, .5F, .5F, .5F);
        }
    }
}
