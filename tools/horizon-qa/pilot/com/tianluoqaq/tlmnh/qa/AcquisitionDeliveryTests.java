package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;
import com.tianluoqaq.tlmnh.compat.backhand.development.player.BackhandAccess;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 实装玩家库存和Backhand空槽策略；同步夹具不冒充玩家磁盘保存或真实网络 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "backhand" })
public final class AcquisitionDeliveryTests {

    private AcquisitionDeliveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "acquisition_delivery")
    public static void nativeEmptySlotPolicyAndActualCredentialIdentityPreserveOneGift(GameTestHelper helper)
        throws ReflectiveOperationException {
        // 固定目标Backhand 1.8.18的实际公开字段，不模拟其原生InventoryPlayer补丁
        var pickup = Class.forName("xonin.backhand.utils.BackhandConfig")
            .getField("OffhandPickup");
        boolean oldPickup = pickup.getBoolean(null);
        boolean oldGift = MaidAcquisitionConfig.giveInitialSlab;
        helper.afterTest(() -> {
            MaidAcquisitionConfig.giveInitialSlab = oldGift;
            try {
                pickup.setBoolean(null, oldPickup);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Could not restore actual Backhand pickup configuration", failure);
            }
        });
        pickup.setBoolean(null, false);
        MaidAcquisitionConfig.giveInitialSlab = true;

        var full = player(helper);
        int offhand = BackhandAccess.offhandSlot(full);
        helper.assertTrue(
            offhand > 0 && offhand < full.inventory.mainInventory.length,
            "Actual Backhand offhand slot not present in native main inventory");
        for (int slot = 0; slot < full.inventory.mainInventory.length; slot++)
            if (slot != offhand) full.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
        int normalSlot = full.inventory.currentItem;
        helper.assertTrue(
            normalSlot != offhand && full.inventory.getFirstEmptyStack() == -1,
            "Actual native inventory did not apply disabled offhand pickup policy");
        var ledger = MaidLifecycleData.get(helper.getWorld());
        var token = ledger.initialToken(full.getUniqueID());
        var fullBefore = full.inventory.writeToNBT(new NBTTagList());
        helper.assertTrue(
            !MaidLifecycleEvents.giveInitial(full) && !ledger.delivered(token)
                && fullBefore.equals(full.inventory.writeToNBT(new NBTTagList())),
            "Full regular inventory bypassed native policy or consumed the gift right");
        full.inventory.mainInventory[normalSlot] = null;
        helper.assertTrue(
            MaidLifecycleEvents.giveInitial(full) && ledger.delivered(token)
                && token.equals(MaidLifecycleItems.token(full.inventory.mainInventory[normalSlot]))
                && full.inventory.mainInventory[offhand] == null
                && gifts(full) == 1,
            "Available regular slot did not receive exactly one original credential");
        var delivered = full.inventory.writeToNBT(new NBTTagList());
        helper.assertTrue(
            !MaidLifecycleEvents.giveInitial(full) && delivered.equals(full.inventory.writeToNBT(new NBTTagList()))
                && gifts(full) == 1,
            "Repeated delivery changed the completed gift inventory");

        var existing = player(helper);
        var existingToken = ledger.initialToken(existing.getUniqueID());
        var slab = MaidLifecycleItems
            .credential(MaidResurrectionContent.INITIAL_SLAB, existingToken, existing.getUniqueID());
        slab.getTagCompound()
            .setIntArray("Opaque", new int[] { 7, 3, 11 });
        int existingOffhand = BackhandAccess.offhandSlot(existing);
        existing.inventory.mainInventory[existingOffhand] = slab;
        var existingBefore = existing.inventory.writeToNBT(new NBTTagList());
        helper.assertTrue(
            !ledger.delivered(existingToken) && !MaidLifecycleEvents.giveInitial(existing)
                && ledger.delivered(existingToken)
                && gifts(existing) == 1
                && existing.inventory.mainInventory[existingOffhand] == slab
                && existingBefore.equals(existing.inventory.writeToNBT(new NBTTagList())),
            "Existing exact initial slab was duplicated, changed or not recorded as delivered");

        var ordinary = player(helper);
        var ordinaryToken = ledger.initialToken(ordinary.getUniqueID());
        var markedStick = MaidLifecycleItems.credential(Items.stick, ordinaryToken, ordinary.getUniqueID());
        var stickBefore = markedStick.copy();
        ordinary.inventory.mainInventory[ordinary.inventory.currentItem] = markedStick;
        helper.assertTrue(
            MaidLifecycleEvents.giveInitial(ordinary) && ledger.delivered(ordinaryToken)
                && ordinary.getCurrentEquippedItem() == markedStick
                && ItemStack.areItemStacksEqual(markedStick, stickBefore)
                && gifts(ordinary) == 1,
            "Ordinary item with matching token suppressed the gift or was overwritten");
        helper.succeedWhen(() -> true);
    }

    private static int gifts(EntityPlayerMP player) {
        int count = 0;
        for (var stack : player.inventory.mainInventory)
            if (stack != null && stack.getItem() == MaidResurrectionContent.INITIAL_SLAB) count += stack.stackSize;
        return count;
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "acquisition_payload")
    public static void sameOwnerAndTokenWithChangedPayloadRemainsHeldWithoutReplacingTheSuccessor(
        GameTestHelper helper) {
        boolean oldGift = MaidAcquisitionConfig.giveInitialSlab;
        helper.afterTest(() -> MaidAcquisitionConfig.giveInitialSlab = oldGift);
        MaidAcquisitionConfig.giveInitialSlab = true;
        var owner = player(helper);
        var ledger = MaidLifecycleData.get(helper.getWorld());
        var token = ledger.initialToken(owner.getUniqueID());
        var saving = new AcquisitionPersistenceTests.SavingProbe(helper, owner, "payload");
        MinecraftForge.EVENT_BUS.register(saving);
        try {
            helper.assertTrue(!MaidLifecycleEvents.giveInitial(owner), "Changed gift payload completed delivery");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(saving);
        }
        var changed = owner.inventory.mainInventory[0];
        helper.assertTrue(
            saving.triggers == 1 && changed != null
                && changed.getItem() == MaidResurrectionContent.INITIAL_SLAB
                && token.equals(MaidLifecycleItems.token(changed))
                && owner.getUniqueID()
                    .toString()
                    .equals(
                        changed.getTagCompound()
                            .getString("InitMaidOwner"))
                && changed.getTagCompound()
                    .getString("CallbackPayload")
                    .equals("callback-owned"),
            "The real same-token slab save callback was not exercised");
        var expected = (NBTTagList) owner.inventory.writeToNBT(new NBTTagList())
            .copy();
        owner.worldObj.getSaveHandler()
            .getSaveHandler()
            .writePlayerData(owner);
        new MaidLifecycleEvents().onLogin(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(owner));
        helper.assertTrue(
            owner.inventory.mainInventory[0] == changed && expected.equals(owner.inventory.writeToNBT(new NBTTagList()))
                && !ledger.delivered(token)
                && ledger.acquisitionPhase(token)
                    .name()
                    .equals("ISSUING")
                && ledger.reserve(token, MaidLifecycleData.Kind.INITIAL) == null
                && !MaidLifecycleEvents.giveInitial(owner)
                && gifts(owner) == 1,
            "Pending resume adopted changed same-token payload or issued another credential");
        helper.succeed();
    }

    private static EntityPlayerMP player(GameTestHelper helper) {
        var server = MinecraftServer.getServer();
        var player = new EntityPlayerMP(
            server,
            helper.getWorld(),
            new GameProfile(UUID.randomUUID(), "[QA-Acquire]"),
            new ItemInWorldManager(helper.getWorld()));
        var manager = new NetworkManager(false);
        var channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, channel);
        new NetHandlerPlayServer(server, manager, player);
        player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        helper.afterTest(() -> {
            player.setDead();
            channel.finish();
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        });
        return player;
    }
}
