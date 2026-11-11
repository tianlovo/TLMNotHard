package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidFilmRecovery;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 原生放置/玩家采集/邻居删除与真实交付；不声称实际鼠标、网络或崩溃原子性已验 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class MaidBedItemTests {

    private MaidBedItemTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "bed_item_survival")
    public static void actualSurvivalHeadAndFootHarvestKeepColorCloneAndExactlyOneItem(GameTestHelper helper) {
        var f = new Fixture(helper, new NBTTagInt(10));
        f.place(f.seed());
        f.checkClone();
        helper.assertTrue(f.harvest(false), "Actual survival head harvest failed");
        int[] phase = { 0 };
        helper.succeedWhen(() -> {
            if (!f.oneDelivered()) return false;
            if (phase[0]++ == 0) {
                var item = f.takeDrop();
                f.place(item);
                f.checkClone();
                helper.assertTrue(f.harvest(true), "Actual survival foot harvest failed");
                return false;
            }
            f.assertGone();
            System.out.println("TLMNH_BED_ITEM_SURVIVAL_PASS actual_manager=true phases=2");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "bed_item_unknown")
    public static void nativeWorldDeletionAndRepeatedDropRequestsKeepUnknownColorExactlyOnce(GameTestHelper helper) {
        var raw = new NBTTagCompound();
        raw.setInteger("Revision", 2);
        raw.setString("Color", "future_blue");
        var f = new Fixture(helper, raw);
        var original = f.seed();
        f.place(original);
        original.getTagCompound()
            .setString("BedColor", "edited_after_placement");
        f.checkClone();
        helper.assertTrue(
            f.pending()
                .isEmpty(),
            "A pure clone query queued a real item");
        helper.assertTrue(
            helper.getWorld()
                .func_147480_a(f.x, f.y, f.z + 1, true),
            "Native foot deletion failed");
        int[] phase = { 0 };
        helper.succeedWhen(() -> {
            if (!f.oneDelivered()) return false;
            if (phase[0]++ == 0) {
                f.place(f.takeDrop());
                f.checkClone();
                MaidLifeContent.maidBed.dropBlockAsItem(helper.getWorld(), f.x, f.y, f.z, 10, 0);
                MaidLifeContent.maidBed.dropBlockAsItem(helper.getWorld(), f.x, f.y, f.z, 10, 0);
                helper.assertTrue(
                    f.pending()
                        .size() == 1,
                    "Repeated requests duplicated queued bed ownership");
                helper.assertTrue(
                    helper.getWorld()
                        .func_147480_a(f.x, f.y, f.z, true),
                    "Native head deletion failed");
                return false;
            }
            f.assertGone();
            System.out.println("TLMNH_BED_ITEM_UNKNOWN_PASS native_delete=true repeated_drop_single=true");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "bed_item_cancel")
    public static void creativeHasNoDropAndCancelledRealBirthRetainsEscrowThenDeliversOnce(GameTestHelper helper) {
        var f = new Fixture(helper, new NBTTagInt(11));
        f.base.owner.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
        for (boolean foot : new boolean[] { false, true }) {
            f.place(f.seed());
            helper.assertTrue(f.harvest(foot), "Creative bed removal failed");
            f.assertGone();
            helper.assertTrue(
                f.drops()
                    .isEmpty()
                    && f.pending()
                        .isEmpty(),
                "Creative removal produced bed value");
        }
        f.base.owner.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
        f.place(f.seed());
        var gate = new BirthGate(f);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(gate));
        MinecraftForge.EVENT_BUS.register(gate);
        helper.assertTrue(f.harvest(false), "Escrow bed harvest failed");
        int[] releasedAt = { -1 };
        helper.succeedWhen(() -> {
            MaidFilmRecovery.retryPending(helper.getWorld());
            f.assertGone();
            if (releasedAt[0] < 0) {
                helper.assertTrue(
                    f.drops()
                        .isEmpty()
                        && f.pending()
                            .size() == 1,
                    "Rejected birth lost or duplicated the bed");
                if (gate.cancelled < 3) return false;
                gate.blocked = false;
                releasedAt[0] = (int) helper.getWorld()
                    .getTotalWorldTime();
                return false;
            }
            if (!f.oneDelivered()) return false;
            if (helper.getWorld()
                .getTotalWorldTime() < releasedAt[0] + 3) return false;
            System.out.println("TLMNH_BED_ITEM_CANCEL_PASS cancelled=" + gate.cancelled + " delivered=1");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "bed_item_reload")
    public static void reloadedUnverifiedTransferKeepsRawDataAndRefusesMiningDyeAndNewDrop(GameTestHelper helper) {
        var f = new Fixture(helper, new NBTTagInt(6));
        f.place(f.seed());
        var transfer = new NBTTagCompound();
        transfer.setInteger("Revision", 5);
        transfer.setString("Owner", "unknown_future_owner");
        var saved = new NBTTagCompound();
        f.bed.writeToNBT(saved);
        saved.setTag("TLMNHBedDropQueued", transfer.copy());
        f.bed.readFromNBT(saved);
        helper.assertTrue(!f.harvest(false), "Unverified transfer allowed a second harvest");
        var dye = new ItemStack(Items.dye, 3, 15);
        f.base.owner.inventory.mainInventory[f.base.owner.inventory.currentItem] = dye;
        helper.assertTrue(
            !MaidLifeContent.maidBed
                .onBlockActivated(helper.getWorld(), f.x, f.y, f.z + 1, f.base.owner, 1, .5F, .5F, .5F)
                && dye.stackSize == 3,
            "Unverified transfer consumed dye");
        f.bed.updateEntity();
        var again = new NBTTagCompound();
        f.bed.writeToNBT(again);
        helper.assertTrue(
            again.getTag("TLMNHBedDropQueued")
                .equals(transfer)
                && again.getTag("BedColor")
                    .equals(f.color)
                && f.pending()
                    .isEmpty()
                && f.drops()
                    .isEmpty()
                && helper.getWorld()
                    .getBlock(f.x, f.y, f.z) == MaidLifeContent.maidBed
                && helper.getWorld()
                    .getBlock(f.x, f.y, f.z + 1) == MaidLifeContent.maidBed,
            "Unverified source was normalized, deleted or reissued");
        helper.succeedWhen(() -> true);
    }

    public static final class BirthGate {

        final Fixture fixture;
        boolean blocked = true;
        int cancelled;

        BirthGate(Fixture fixture) {
            this.fixture = fixture;
        }

        @SubscribeEvent
        public void join(EntityJoinWorldEvent event) {
            if (blocked && event.entity instanceof EntityItem item && fixture.owns(item)) {
                event.setCanceled(true);
                cancelled++;
            }
        }
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final NBTBase color;
        final Set<UUID> initialPending;
        final String oldTileDrops;
        ModernCoreTests.Fixture base;
        TileEntityMaidBed bed;
        int x, y, z;

        Fixture(GameTestHelper helper, NBTBase color) {
            this.helper = helper;
            this.color = color.copy();
            oldTileDrops = helper.getWorld()
                .getGameRules()
                .getGameRuleStringValue("doTileDrops");
            initialPending = new HashSet<>(ledger().pendingReturnedFilms(helper.getWorld().provider.dimensionId));
            helper.afterTest(this::close);
            base = new ModernCoreTests.Fixture(helper, false, 16, 16);
            base.maid.getBrainCoordinator()
                .setSuspended(true);
            base.owner.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
            var head = helper.absolute(8, 1, 4);
            x = head.x();
            y = head.y();
            z = head.z();
            helper.setBlock(8, 0, 4, Blocks.stone);
            helper.setBlock(8, 0, 5, Blocks.stone);
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doTileDrops", "true");
        }

        MaidLifecycleData ledger() {
            return MaidLifecycleData.get(helper.getWorld());
        }

        ItemStack seed() {
            var stack = new ItemStack(MaidLifeContent.maidBedItem);
            var tag = new NBTTagCompound();
            tag.setTag("BedColor", color.copy());
            stack.setTagCompound(tag);
            return stack;
        }

        void place(ItemStack stack) {
            base.owner.inventory.mainInventory[base.owner.inventory.currentItem] = stack;
            base.owner.rotationYaw = 180;
            helper.assertTrue(
                MaidLifeContent.maidBedItem
                    .onItemUse(stack, base.owner, helper.getWorld(), x, y - 1, z + 1, 1, .5F, .5F, .5F),
                "Native bed item placement failed");
            helper.assertTrue(
                stack.stackSize == (base.owner.capabilities.isCreativeMode ? 1 : 0),
                "Placement consumed the wrong count");
            bed = (TileEntityMaidBed) helper.getWorld()
                .getTileEntity(x, y, z);
            helper.assertTrue(bed != null && bed.complete(), "Placed head is incomplete");
            var saved = new NBTTagCompound();
            bed.writeToNBT(saved);
            helper.assertTrue(
                saved.getTag("BedColor")
                    .equals(color),
                "Placement changed the raw item color");
            base.owner.inventory.mainInventory[base.owner.inventory.currentItem] = null;
        }

        void checkClone() {
            for (int offset : new int[] { 0, 1 }) {
                var clone = MaidLifeContent.maidBed.getPickBlock(null, helper.getWorld(), x, y, z + offset, base.owner);
                helper.assertTrue(
                    clone != null && clone.stackSize == 1
                        && clone.getTagCompound()
                            .getTag("BedColor")
                            .equals(color),
                    "Head/foot pick API did not preserve the raw color");
            }
        }

        boolean harvest(boolean foot) {
            return base.owner.theItemInWorldManager.tryHarvestBlock(x, y, foot ? z + 1 : z);
        }

        Set<UUID> pending() {
            var result = new HashSet<>(ledger().pendingReturnedFilms(helper.getWorld().provider.dimensionId));
            result.removeAll(initialPending);
            return result;
        }

        boolean owns(EntityItem item) {
            return item.worldObj == helper.getWorld() && item.posX >= x - 1
                && item.posX <= x + 2
                && item.posZ >= z - 1
                && item.posZ <= z + 3
                && item.getEntityItem() != null
                && item.getEntityItem()
                    .getItem() == MaidLifeContent.maidBedItem;
        }

        List<EntityItem> drops() {
            var result = new ArrayList<EntityItem>();
            for (Object entry : helper.getWorld().loadedEntityList)
                if (entry instanceof EntityItem item && item.isEntityAlive() && owns(item)) result.add(item);
            return result;
        }

        boolean oneDelivered() {
            var items = drops();
            helper.assertTrue(items.size() <= 1 && pending().size() <= 1, "Bed value duplicated");
            if (items.isEmpty()) return false;
            var stack = items.get(0)
                .getEntityItem();
            helper.assertTrue(
                pending().isEmpty() && stack.stackSize == 1
                    && stack.getTagCompound() != null
                    && stack.getTagCompound()
                        .getTag("BedColor")
                        .equals(color),
                "Delivered item lost raw color or escrow ownership");
            return true;
        }

        ItemStack takeDrop() {
            var item = drops().get(0);
            var stack = item.getEntityItem();
            item.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(item);
            helper.assertFalse(helper.getWorld().loadedEntityList.contains(item), "Old item source was not removed");
            return stack;
        }

        void assertGone() {
            helper.assertTrue(
                helper.getWorld()
                    .getBlock(x, y, z) != MaidLifeContent.maidBed
                    && helper.getWorld()
                        .getBlock(x, y, z + 1) != MaidLifeContent.maidBed,
                "Bed companion remained");
        }

        void close() {
            if (base != null) {
                for (var item : drops()) {
                    item.setDead();
                    helper.getWorld()
                        .removePlayerEntityDangerously(item);
                }
                helper.getWorld()
                    .setBlock(x, y, z, Blocks.air, 0, 2);
                helper.getWorld()
                    .setBlock(x, y, z + 1, Blocks.air, 0, 2);
            }
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doTileDrops", oldTileDrops);
        }
    }
}
