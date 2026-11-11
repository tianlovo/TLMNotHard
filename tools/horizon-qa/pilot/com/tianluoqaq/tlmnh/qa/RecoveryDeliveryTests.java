package com.tianluoqaq.tlmnh.qa;

import java.util.Arrays;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.biome.BiomeGenBase;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionDiagnostics;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryStore;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.compat.backhand.development.player.BackhandAccess;

import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 原生库存交付与真实群系注册表的有界检查，不代表磁盘、真实多人或自然生成采样 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "backhand" })
public final class RecoveryDeliveryTests {

    private RecoveryDeliveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "recovery_delivery_space")
    public static void nativeEmptySlotsPreserveRecoveryAndWholeTombstoneThenAllowOffhandStacking(GameTestHelper helper)
        throws ReflectiveOperationException {
        var pickup = Class.forName("xonin.backhand.utils.BackhandConfig")
            .getField("OffhandPickup");
        boolean oldPickup = pickup.getBoolean(null);
        helper.afterTest(() -> {
            try {
                pickup.setBoolean(null, oldPickup);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Could not restore Backhand pickup configuration", failure);
            }
        });
        pickup.setBoolean(null, false);
        var original = arrows(5);

        var recoveryPlayer = player(helper);
        int recoveryOffhand = fillRegular(helper, recoveryPlayer);
        var recovery = recovery(helper, original);
        var retained = recovery.write();
        var full = inventory(recoveryPlayer);
        boolean blockedRecovery = recovery.recoverOne(recoveryPlayer);
        TLMNotHard.LOG.info(
            "TLMNH_RECOVERY_DELIVERY_OBSERVED case=recovery_full offhand={} result={} remaining={}",
            recoveryOffhand,
            blockedRecovery,
            recovery.size());
        helper.assertTrue(
            !blockedRecovery && retained.equals(recovery.write()) && full.equals(inventory(recoveryPlayer)),
            "Recovery bypassed disabled empty offhand or changed source NBT");
        int regularSlot = recoveryPlayer.inventory.currentItem;
        recoveryPlayer.inventory.mainInventory[regularSlot] = null;
        boolean recovered = recovery.recoverOne(recoveryPlayer);
        var delivered = inventory(recoveryPlayer);
        helper.assertTrue(
            recovered && recovery.size() == 0
                && ItemStack.areItemStacksEqual(original, recoveryPlayer.inventory.mainInventory[regularSlot])
                && recoveryPlayer.inventory.mainInventory[recoveryOffhand] == null
                && !recovery.recoverOne(recoveryPlayer)
                && delivered.equals(inventory(recoveryPlayer)),
            "Recovery retry did not deliver exactly one complete stack to the regular slot");

        var tombPlayer = player(helper);
        int tombOffhand = fillRegular(helper, tombPlayer);
        var tombstone = tombstone(helper, tombPlayer, original);
        var ledger = MaidLifecycleData.get(helper.getWorld());
        var source = escrow(ledger, tombstone);
        full = inventory(tombPlayer);
        boolean blockedTombstone = MaidTombstoneTransfer.collect(tombstone, tombPlayer);
        var retainedEscrow = escrow(ledger, tombstone);
        TLMNotHard.LOG.info(
            "TLMNH_RECOVERY_DELIVERY_OBSERVED case=tombstone_full offhand={} result={} sourceEntries={}",
            tombOffhand,
            blockedTombstone,
            retainedEscrow == null ? -1 : retainedEscrow.tagCount());
        helper.assertTrue(
            !blockedTombstone && MaidSpawnChecks.present(tombstone)
                && source != null
                && source.equals(retainedEscrow)
                && full.equals(inventory(tombPlayer)),
            "Tombstone bypassed disabled empty offhand or changed its complete escrow");
        regularSlot = tombPlayer.inventory.currentItem;
        tombPlayer.inventory.mainInventory[regularSlot] = null;
        boolean collected = MaidTombstoneTransfer.collect(tombstone, tombPlayer);
        delivered = inventory(tombPlayer);
        helper.assertTrue(
            collected && ledger.tombstoneEmpty(tombstone.getToken())
                && ItemStack.areItemStacksEqual(original, tombPlayer.inventory.mainInventory[regularSlot])
                && tombPlayer.inventory.mainInventory[tombOffhand] == null
                && !MaidTombstoneTransfer.collect(tombstone, tombPlayer)
                && delivered.equals(inventory(tombPlayer)),
            "Tombstone retry did not preserve one complete delivery and one consumed escrow");

        var mergePlayer = player(helper);
        int mergeOffhand = fillRegular(helper, mergePlayer);
        mergePlayer.inventory.mainInventory[mergeOffhand] = arrows(59);
        var merge = tombstone(helper, mergePlayer, original);
        boolean merged = MaidTombstoneTransfer.collect(merge, mergePlayer);
        TLMNotHard.LOG.info(
            "TLMNH_RECOVERY_DELIVERY_OBSERVED case=occupied_offhand result={} count={}",
            merged,
            mergePlayer.inventory.mainInventory[mergeOffhand] == null ? -1
                : mergePlayer.inventory.mainInventory[mergeOffhand].stackSize);
        helper.assertTrue(
            merged && ledger.tombstoneEmpty(merge.getToken())
                && ItemStack.areItemStacksEqual(arrows(64), mergePlayer.inventory.mainInventory[mergeOffhand]),
            "Disabled empty offhand pickup incorrectly prevented an existing matching offhand stack from merging");

        pickup.setBoolean(null, true);
        var allowedPlayer = player(helper);
        int allowedOffhand = fillRegular(helper, allowedPlayer);
        helper.assertTrue(
            allowedPlayer.inventory.getFirstEmptyStack() == allowedOffhand,
            "Actual native inventory did not enable the configured empty offhand");
        var allowedTombstone = tombstone(helper, allowedPlayer, original);
        boolean allowed = MaidTombstoneTransfer.collect(allowedTombstone, allowedPlayer);
        helper.assertTrue(
            allowed && ledger.tombstoneEmpty(allowedTombstone.getToken())
                && ItemStack.areItemStacksEqual(original, allowedPlayer.inventory.mainInventory[allowedOffhand]),
            "Native tombstone planner did not honor enabled offhand pickup");
        var allowedRecoveryPlayer = player(helper);
        int allowedRecoveryOffhand = fillRegular(helper, allowedRecoveryPlayer);
        var allowedRecovery = recovery(helper, original);
        boolean recoveredOffhand = allowedRecovery.recoverOne(allowedRecoveryPlayer);
        helper.assertTrue(
            recoveredOffhand && allowedRecovery.size() == 0
                && ItemStack.areItemStacksEqual(
                    original,
                    allowedRecoveryPlayer.inventory.mainInventory[allowedRecoveryOffhand]),
            "Recovery did not honor enabled offhand pickup");
        TLMNotHard.LOG.info(
            "TLMNH_RECOVERY_DELIVERY_OBSERVED case=allowed_offhand tombstone={} recovery={}",
            allowed,
            recoveredOffhand);
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "acquisition_biome_diagnostics")
    public static void actualBiomeResolutionRetainsArraysAndDistinguishesPartialFromUnavailable(GameTestHelper helper) {
        helper.assertTrue(
            IntegrationGuard.faults(Side.SERVER)
                .isEmpty(),
            "Biome diagnostic fault injection requires an isolated healthy process state");
        helper.afterTest(() -> IntegrationGuard.resetForDevelopment(Side.SERVER));
        var originalConfig = MaidAcquisitionConfig.naturalBiomes;
        helper.afterTest(() -> MaidAcquisitionConfig.naturalBiomes = originalConfig);
        var registry = BiomeGenBase.getBiomeGenArray();
        int validId = -1;
        for (int id = 0; id < registry.length; id++) if (registry[id] != null) {
            validId = id;
            break;
        }
        helper.assertTrue(validId >= 0, "Actual biome registry contains no usable entry");
        var valid = new int[] { validId, validId };
        var unchanged = valid.clone();
        MaidAcquisitionConfig.naturalBiomes = valid;
        var resolved = MaidAcquisitionDiagnostics.resolveBiomes(MaidAcquisitionConfig.naturalBiomes);
        helper.assertTrue(
            resolved.length == 1 && resolved[0] == registry[validId]
                && MaidAcquisitionConfig.naturalBiomes == valid
                && Arrays.equals(valid, unchanged)
                && IntegrationGuard.faults(Side.SERVER)
                    .isEmpty(),
            "Valid native biome resolution changed configuration, duplicated a biome or emitted a fault");

        var partial = new int[] { validId, registry.length, -1, validId };
        unchanged = partial.clone();
        MaidAcquisitionConfig.naturalBiomes = partial;
        resolved = MaidAcquisitionDiagnostics.resolveBiomes(MaidAcquisitionConfig.naturalBiomes);
        var partialFault = IntegrationGuard.faults(Side.SERVER)
            .get("acquisition/natural_biomes/partial");
        helper.assertTrue(
            resolved.length == 1 && resolved[0] == registry[validId]
                && MaidAcquisitionConfig.naturalBiomes == partial
                && Arrays.equals(partial, unchanged)
                && partialFault != null,
            "Partly invalid biome configuration discarded a valid biome or changed raw IDs");
        var partialMessage = MaidAcquisitionDiagnostics.message(partialFault);
        helper.assertTrue(
            partialMessage != null && partialMessage.getKey()
                .equals("chat.tlmnh.acquisition.biomes_partial")
                && partialMessage.getFormatArgs().length == 3
                && partialMessage.getFormatArgs()[0].equals(partialFault.code),
            "Partly invalid biome configuration incorrectly used the total-unavailable warning");
        MaidAcquisitionDiagnostics.resolveBiomes(partial);
        helper.assertTrue(
            IntegrationGuard.faults(Side.SERVER)
                .get("acquisition/natural_biomes/partial") == partialFault,
            "Repeated diagnostics did not reuse the original deduplicated fault");

        var unavailable = new int[] { registry.length, -1 };
        unchanged = unavailable.clone();
        MaidAcquisitionConfig.naturalBiomes = unavailable;
        resolved = MaidAcquisitionDiagnostics.resolveBiomes(MaidAcquisitionConfig.naturalBiomes);
        var noneFault = IntegrationGuard.faults(Side.SERVER)
            .get("acquisition/natural_biomes/none");
        helper.assertTrue(
            resolved.length == 0 && MaidAcquisitionConfig.naturalBiomes == unavailable
                && Arrays.equals(unavailable, unchanged)
                && noneFault != null,
            "Unavailable biome configuration was replaced with guessed defaults or changed its raw IDs");
        var noneMessage = MaidAcquisitionDiagnostics.message(noneFault);
        helper.assertTrue(
            noneMessage != null && noneMessage.getKey()
                .equals("chat.tlmnh.acquisition.biomes_none")
                && noneMessage.getFormatArgs().length == 2
                && noneMessage.getFormatArgs()[1].equals(Arrays.toString(unavailable)),
            "All-invalid biome configuration did not retain the complete IDs in its distinct warning");
        TLMNotHard.LOG.info(
            "TLMNH_BIOME_DIAGNOSTICS_OBSERVED validId={} partialKey={} noneKey={} unchanged=true",
            validId,
            partialMessage.getKey(),
            noneMessage.getKey());
        helper.succeedWhen(() -> true);
    }

    private static RecoveryStore recovery(GameTestHelper helper, ItemStack original) {
        var data = new NBTTagList();
        data.appendTag(ProtectedItemCodec.encode(original));
        helper.setBlock(6, 1, 4, MaidLifeContent.snackCabinet);
        var position = helper.absolute(6, 1, 4);
        var cabinet = (TileEntitySnackCabinet) helper.getWorld()
            .getTileEntity(position.x(), position.y(), position.z());
        var result = cabinet.recovery();
        result.read(data);
        cabinet.markDirty();
        return result;
    }

    private static ItemStack arrows(int amount) {
        var result = new ItemStack(Items.arrow, amount);
        var tag = new NBTTagCompound();
        tag.setIntArray("Opaque", new int[] { 4, 9, 2 });
        tag.setString("Label", "完整交付");
        result.setTagCompound(tag);
        return result;
    }

    private static NBTTagList inventory(EntityPlayerMP player) {
        return player.inventory.writeToNBT(new NBTTagList());
    }

    private static int fillRegular(GameTestHelper helper, EntityPlayerMP player) throws ReflectiveOperationException {
        int offhand = BackhandAccess.offhandSlot(player);
        helper.assertTrue(
            offhand >= 0 && offhand < player.inventory.mainInventory.length && offhand != player.inventory.currentItem,
            "Native Backhand offhand layout unavailable");
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++)
            if (slot != offhand) player.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
        return offhand;
    }

    private static MaidTombstoneEntity tombstone(GameTestHelper helper, EntityPlayerMP owner, ItemStack original) {
        var ledger = MaidLifecycleData.get(helper.getWorld());
        var items = new NBTTagList();
        items.appendTag(ProtectedItemCodec.encode(original));
        var position = helper.absolute(7, 1, 4);
        var token = ledger.depositDeath(
            owner.getUniqueID(),
            UUID.randomUUID(),
            new NBTTagCompound(),
            items,
            helper.getWorld().provider.dimensionId,
            position.x() + .5,
            position.y(),
            position.z() + .5,
            "[QA-Delivery]");
        ledger.markPublished(token);
        var result = new MaidTombstoneEntity(helper.getWorld());
        result.initialize(token, owner.getUniqueID(), "[QA-Delivery]");
        helper.spawnEntity(result, 7.5, 1, 4.5);
        helper.afterTest(
            () -> {
                if (MaidSpawnChecks.present(result)) helper.getWorld()
                    .removeEntity(result);
            });
        return result;
    }

    private static NBTTagList escrow(MaidLifecycleData ledger, MaidTombstoneEntity tombstone) {
        var result = ledger.reserveTombstone(tombstone.getToken());
        ledger.rollback(tombstone.getToken());
        return result;
    }

    private static EntityPlayerMP player(GameTestHelper helper) {
        var server = MinecraftServer.getServer();
        var result = new EntityPlayerMP(
            server,
            helper.getWorld(),
            new GameProfile(UUID.randomUUID(), "[QA-Delivery]"),
            new ItemInWorldManager(helper.getWorld()));
        var manager = new NetworkManager(false);
        var channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, channel);
        new NetHandlerPlayServer(server, manager, result);
        result.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        var position = helper.absolute(5, 1, 4);
        result.setPosition(position.x() + .5, position.y(), position.z() + .5);
        helper.afterTest(() -> {
            result.setDead();
            channel.finish();
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        });
        return result;
    }
}
