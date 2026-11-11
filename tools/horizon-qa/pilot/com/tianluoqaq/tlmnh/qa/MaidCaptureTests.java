package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 普通服务端Entity/Item入口，不冒充真实网络操作或任意硬崩溃原子性 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class MaidCaptureTests {

    private MaidCaptureTests() {}

    // The reused fixture restores one server-global animal policy; run its instances in sequential batches.
    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "maid_capture_1_normal")
    public static void ordinaryOwnerCapturePreservesFullPayloadAndCanceledReleaseRetriesOnce(GameTestHelper helper) {
        var f = new RedemptionDurabilityTests.Fixture(helper, UUID.randomUUID(), false);
        fullPlayer(f);
        var maid = maid(f);
        var identity = maid.getUniqueID();
        var before = snapshot(maid);
        helper.assertTrue(!f.player.isSneaking(), "Capture must exercise ordinary right click, not sneak");
        helper.assertTrue(maid.interact(f.player), "Normal maid interaction did not handle empty slab");
        var filled = f.player.getCurrentEquippedItem();
        var token = MaidLifecycleItems.token(filled);
        helper.assertTrue(
            filled != null && filled.getItem() == MaidResurrectionContent.FILLED_SLAB
                && token != null
                && maid.isDead
                && !MaidSpawnChecks.present(maid)
                && f.ledger()
                    .capture(token)
                    .getString("Stage")
                    .equals("DELIVERED")
                && before.equals(
                    f.ledger()
                        .capture(token)
                        .getCompoundTag("Source")),
            "Normal full-inventory capture lost the full source or did not deliver exactly one filled slab");
        var duplicate = filled.copy();
        helper.startSequence()
            .thenIdle(21)
            .thenExecute(() -> {
                f.cancel = true;
                use(f);
                helper.assertTrue(
                    ItemStack.areItemStacksEqual(filled, f.player.getCurrentEquippedItem()) && f.ledger()
                        .redemption(token)
                        .hasNoTags() && f.live() == 0,
                    "Safe native spawn cancellation consumed the slab or left a second maid");
                f.cancel = false;
                use(f);
                var restored = f.last;
                helper.assertTrue(
                    restored != null && restored != maid
                        && restored.getUniqueID()
                            .equals(identity)
                        && MaidSpawnChecks.present(restored)
                        && f.live() == 1
                        && f.player.getCurrentEquippedItem()
                            .getItem() == MaidResurrectionContent.EMPTY_SLAB
                        && payload(before).equals(payload(snapshot(restored))),
                    "Normal block-top release did not preserve original UUID and complete native payload");
                restored.getBrainCoordinator()
                    .setSuspended(true);
                f.player.inventory.mainInventory[0] = duplicate;
            })
            .thenIdle(21)
            .thenExecute(() -> {
                boolean animals = net.minecraft.server.MinecraftServer.getServer()
                    .getCanSpawnAnimals();
                int beforeLive = f.live();
                int beforeCalls = f.calls;
                helper.assertTrue(
                    animals && beforeLive == 1 && MaidSpawnChecks.present(f.last),
                    "Original released maid disappeared before duplicate use: nativeAnimals=" + animals
                        + " live="
                        + beforeLive
                        + " dead="
                        + f.last.isDead
                        + " indexed="
                        + (f.last.worldObj.getEntityByID(f.last.getEntityId()) == f.last));
                use(f);
                int afterLive = f.live();
                boolean sameCredential = ItemStack.areItemStacksEqual(duplicate, f.player.getCurrentEquippedItem());
                helper.assertTrue(
                    afterLive == 1 && sameCredential && f.calls == beforeCalls,
                    "Copied consumed captured-maid slab changed the settled world or credential: beforeLive="
                        + beforeLive
                        + " afterLive="
                        + afterLive
                        + " sameCredential="
                        + sameCredential
                        + " joinCallsBefore="
                        + beforeCalls
                        + " joinCallsAfter="
                        + f.calls
                        + " nativeAnimals="
                        + net.minecraft.server.MinecraftServer.getServer()
                            .getCanSpawnAnimals());
                var saved = new NBTTagCompound();
                f.ledger()
                    .writeToNBT(saved);
                var reloaded = new MaidLifecycleData("capture-fixture");
                reloaded.readFromNBT(saved);
                helper.assertTrue(
                    reloaded.reserve(token, MaidLifecycleData.Kind.CAPTURED) == null,
                    "Consumed captured-maid right returned after ledger reload");
                System.out.println(
                    "TLMNH_CAPTURE_NORMAL_PASS ordinaryRightClick=true fullInventory=true originalUuid=true fullNbt=true safeCancelRetry=true duplicateDenied=true");
                helper.succeed();
            });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "maid_capture_2_creative")
    public static void creativeAndMountedOwnerUseTheSameSingleSlotCaptureWithoutCloningTheCarrier(
        GameTestHelper helper) {
        var f = new RedemptionDurabilityTests.Fixture(helper, UUID.randomUUID(), false);
        f.player.theItemInWorldManager.setGameType(GameType.CREATIVE);
        fullPlayer(f);
        var maid = maid(f);
        var identity = maid.getUniqueID();
        var carrier = new EntityCow(helper.getWorld());
        carrier.setPosition(f.x + 5.5, f.y + 1, f.z + 3.5);
        helper.assertTrue(
            helper.getWorld()
                .spawnEntityInWorld(carrier) && MaidSpawnChecks.present(carrier),
            "Native owned carrier was not registered");
        helper.afterTest(() -> {
            helper.assertTrue(
                carrier.worldObj == helper.getWorld() && carrier.riddenByEntity == null,
                "Unknown carrier successor retained");
            helper.getWorld()
                .removePlayerEntityDangerously(carrier);
        });
        maid.mountEntity(carrier);
        helper.assertTrue(maid.interact(f.player), "Normal mounted-owner interaction was not handled");
        var filled = f.player.getCurrentEquippedItem();
        var token = MaidLifecycleItems.token(filled);
        helper.assertTrue(
            filled != null && filled.getItem() == MaidResurrectionContent.FILLED_SLAB
                && token != null
                && maid.isDead
                && maid.ridingEntity == null
                && carrier.riddenByEntity == null
                && MaidSpawnChecks.present(carrier)
                && !f.ledger()
                    .capture(token)
                    .getCompoundTag("Source")
                    .hasKey("Riding"),
            "Creative capture duplicated or deleted the native carrier or bypassed the single-slot exchange");
        helper.startSequence()
            .thenIdle(21)
            .thenExecute(() -> {
                use(f);
                helper.assertTrue(
                    f.live() == 1 && f.last.getUniqueID()
                        .equals(identity)
                        && f.player.getCurrentEquippedItem()
                            .getItem() == MaidResurrectionContent.EMPTY_SLAB
                        && f.last.getExperience() == 37
                        && MaidSpawnChecks.present(carrier),
                    "Creative release retained a reusable filled slab or lost the original maid/carrier");
                System.out.println(
                    "TLMNH_CAPTURE_CREATIVE_PASS fullInventory=true mountedSource=unmounted nativeCarrier=retained originalUuid=true emptyReturned=true");
                helper.succeed();
            });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 60, batch = "maid_capture_3_unknown")
    public static void saveCallbackUnknownSourceRemainsLiveAndDurablyHeldWithoutPublishingASecondMaid(
        GameTestHelper helper) {
        var f = new RedemptionDurabilityTests.Fixture(helper, UUID.randomUUID(), false);
        fullPlayer(f);
        var maid = maid(f);
        var before = snapshot(maid);
        var callback = new SaveMutation(f, maid);
        MinecraftForge.EVENT_BUS.register(callback);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(callback));
        helper.assertTrue(maid.interact(f.player), "Normal empty slab request was not handled");
        var token = f.ledger()
            .captureHead(maid.getUniqueID());
        var held = f.ledger()
            .capture(token);
        helper.assertTrue(
            callback.calls == 1 && token != null
                && held.getString("Stage")
                    .equals("HELD")
                && MaidSpawnChecks.present(maid)
                && maid.getEntityData()
                    .getString("UnknownCaptureSuccessor")
                    .equals("retained")
                && MaidLifecycleService.isPendingRedemption(maid)
                && before.equals(held.getCompoundTag("Source"))
                && f.player.getCurrentEquippedItem()
                    .getItem() == MaidResurrectionContent.EMPTY_SLAB,
            "Unknown native-save successor was removed, overwritten or given a usable output");
        var saved = new NBTTagCompound();
        f.ledger()
            .writeToNBT(saved);
        var reloaded = new MaidLifecycleData("capture-fixture");
        reloaded.readFromNBT(saved);
        helper.assertTrue(
            reloaded.capture(token)
                .equals(held) && reloaded.reserve(token, MaidLifecycleData.Kind.CAPTURED) == null
                && held.getCompoundTag("Observed")
                    .getCompoundTag("Maid")
                    .getCompoundTag("ForgeData")
                    .getString("UnknownCaptureSuccessor")
                    .equals("retained"),
            "Capture hold or full unknown successor disappeared after persistent ledger read");
        var inventory = (NBTTagList) f.player.inventory.writeToNBT(new NBTTagList())
            .copy();
        maid.interact(f.player);
        helper.assertTrue(
            callback.calls == 1 && f.live() == 1 && inventory.equals(f.player.inventory.writeToNBT(new NBTTagList())),
            "Held source was recaptured or its receipt overwritten");
        System.out.println(
            "TLMNH_CAPTURE_UNKNOWN_PASS actualSaveCallback=1 sourceAlive=true sourceFrozen=true originalRaw=true observedRaw=true heldAfterReload=true");
        helper.succeed();
    }

    private static void fullPlayer(RedemptionDurabilityTests.Fixture f) {
        for (int i = 0; i < f.player.inventory.mainInventory.length; i++)
            f.player.inventory.mainInventory[i] = new ItemStack(Items.stick, 64);
        f.player.inventory.currentItem = 0;
        f.player.inventory.mainInventory[0] = new ItemStack(MaidResurrectionContent.EMPTY_SLAB);
        f.player.inventory.setItemStack(null);
        f.player.setSneaking(false);
    }

    private static EntityMaid maid(RedemptionDurabilityTests.Fixture f) {
        var maid = new EntityMaid(f.helper.getWorld());
        f.helper.assertTrue(maid.bindTo(f.player.getUniqueID()), "Owner binding failed");
        maid.setPosition(f.x + 5.5, f.y + 1, f.z + 3.5);
        maid.setModelId("geckolib:winefox_magical");
        maid.setSoundPackId("PECO");
        maid.setExperience(37);
        maid.setCustomNameTag("capture-full-native");
        for (int i = 0; i < MaidInventory.TOTAL_SIZE; i++) {
            var stack = new ItemStack(Items.diamond, i + 1);
            stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setIntArray("OpaqueSlot", new int[] { i, 7, 3 });
            maid.getMaidInventory()
                .setInventorySlotContents(i, stack);
        }
        var recovery = new NBTTagCompound();
        recovery.setString("UnknownRecoveryRaw", "all bytes retained");
        recovery.setIntArray("Opaque", new int[] { 4, 2, 9 });
        maid.getMaidInventory()
            .recovery()
            .retain(recovery);
        maid.getEntityData()
            .setIntArray("UnknownCaptureNative", new int[] { 9, 5, 2 });
        f.helper.assertTrue(
            f.helper.getWorld()
                .spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid),
            "Native source maid was not registered");
        maid.getBrainCoordinator()
            .setSuspended(true);
        return maid;
    }

    private static void use(RedemptionDurabilityTests.Fixture f) {
        var held = f.player.getCurrentEquippedItem();
        f.helper.assertTrue(
            held.getItem() == MaidResurrectionContent.FILLED_SLAB && held.getItem()
                .onItemUse(held, f.player, f.helper.getWorld(), f.x + 7, f.y, f.z + 3, 1, .5F, 1, .5F),
            "Ordinary filled-slab block-top item entry was not handled");
    }

    private static NBTTagCompound snapshot(EntityMaid maid) {
        var raw = new NBTTagCompound();
        raw.setString("id", EntityList.getEntityString(maid));
        maid.writeToNBT(raw);
        return (NBTTagCompound) raw.copy();
    }

    private static NBTTagCompound payload(NBTTagCompound source) {
        var copy = (NBTTagCompound) source.copy();
        for (String key : new String[] { "Pos", "Motion", "Rotation", "Dimension" }) copy.removeTag(key);
        if (copy.hasKey("ForgeData", 10)) copy.getCompoundTag("ForgeData")
            .removeTag("TLMNHCaptureReleased");
        return copy;
    }

    public static final class SaveMutation {

        final RedemptionDurabilityTests.Fixture f;
        final EntityMaid maid;
        int calls;

        SaveMutation(RedemptionDurabilityTests.Fixture f, EntityMaid maid) {
            this.f = f;
            this.maid = maid;
        }

        @SubscribeEvent
        public void saved(PlayerEvent.SaveToFile event) {
            if (event.entityPlayer == f.player && calls == 0) {
                calls++;
                maid.getEntityData()
                    .setString("UnknownCaptureSuccessor", "retained");
            }
        }
    }
}
