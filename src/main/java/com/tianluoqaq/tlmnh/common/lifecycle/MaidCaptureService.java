/*
 * Mechanics adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: event/maid/SlabClickEvent.java and item/ItemSmartSlab.java, MIT
 * See licenses/TouhouLittleMaid-MIT.txt; native full NBT is held by the server ledger
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;

/** 普通主手收纳与原UUID放出；不把保管中的活体降格成剥离库存的死亡胶片 */
public final class MaidCaptureService {

    static final String RECEIPT = "TLMNHCaptureReceipt";
    private static final String RELEASED = "TLMNHCaptureReleased";
    private static final Map<World, Map<UUID, Long>> COOLDOWNS = new WeakHashMap<>();
    private static final Set<EntityMaid> CAPTURING = Collections.newSetFromMap(new IdentityHashMap<>());

    private MaidCaptureService() {}

    public static boolean isEmpty(ItemStack stack) {
        return stack != null && stack.stackSize == 1 && stack.getItem() == MaidResurrectionContent.EMPTY_SLAB;
    }

    /** 返回是否处理了空魂符请求；客户端仅确认交互，所有变更由服务端执行 */
    public static boolean interact(EntityMaid maid, EntityPlayer player, ItemStack held) {
        if (!isEmpty(held) || maid == null || player == null) return false;
        if (!maid.worldObj.isRemote) MaidLifecycleService.tell(player, capture(maid, player, held));
        return true;
    }

    public static MaidLifecycleService.Result capture(EntityMaid maid, EntityPlayer player, ItemStack empty) {
        if (!admitted(maid, player, empty) || empty.hasTagCompound() && empty.getTagCompound()
            .hasKey(RECEIPT)) return MaidLifecycleService.Result.DENIED;
        if (coolingDown(player)) return MaidLifecycleService.Result.COOLDOWN;
        if (!CAPTURING.add(maid)) return MaidLifecycleService.Result.DENIED;
        var world = maid.worldObj;
        MaidLifecycleData ledger = null;
        MaidPlayerTransferSnapshot frame = null;
        MaidLifecycleDurability.Publication source = null;
        UUID token = UUID.randomUUID();
        boolean attemptedRemoval = false, removed = false, complete = false;
        try {
            MaidLifecycleDurability.requireEntities(world);
            // 原生1.7会递归保存Riding，不能把载体或乘客复制进魂符
            if (maid.riddenByEntity != null) return MaidLifecycleService.Result.BLOCKED;
            var originalMaid = rawSnapshot(maid);
            if (maid.ridingEntity != null) {
                var vehicle = maid.ridingEntity;
                if (vehicle.worldObj != world || vehicle.riddenByEntity != maid)
                    return MaidLifecycleService.Result.BLOCKED;
                maid.mountEntity(null);
                if (maid.ridingEntity != null || vehicle.riddenByEntity == maid)
                    return MaidLifecycleService.Result.BLOCKED;
            }
            // 撤销临时借手/床租约等本域动作，再取得与再次放出一致的完整保存快照
            maid.getBrainCoordinator()
                .resetTask();
            maid.getBrainCoordinator()
                .getLifeController()
                .cancel();
            maid.getMealController()
                .cancel();
            maid.getShieldController()
                .cancelUse("maid_capture");
            if (!maid.getHomeArea()
                .setEnabled(false) || !admitted(maid, player, empty)) return MaidLifecycleService.Result.DENIED;
            var raw = MaidLifecycleDurability.maidSnapshot(maid);
            frame = new MaidPlayerTransferSnapshot(player);
            if (player.getCurrentEquippedItem() != empty || !frame.unchanged())
                return MaidLifecycleService.Result.DENIED;
            ledger = MaidLifecycleData.get(world);
            var receipt = empty.copy();
            if (!receipt.hasTagCompound()) receipt.setTagCompound(new NBTTagCompound());
            receipt.getTagCompound()
                .setString(RECEIPT, token.toString());
            var credential = MaidLifecycleItems
                .credential(MaidResurrectionContent.FILLED_SLAB, token, player.getUniqueID());
            var record = record(
                token,
                player,
                maid,
                originalMaid,
                raw,
                frame,
                receipt,
                credential,
                ledger.nextCaptureSequence(maid.getUniqueID()));
            source = new MaidLifecycleDurability.Publication(maid, raw);
            ledger.prepareCapture(world, token, player.getUniqueID(), maid.getUniqueID(), raw, record);
            source.check(world, true);
            if (!frame.unchanged()) return MaidLifecycleService.Result.DENIED;
            frame.apply(frame.selected, receipt);
            frame.persist();
            source.check(world, true);
            if (!frame.applied()) return MaidLifecycleService.Result.DENIED;
            attemptedRemoval = true;
            source.discard(world);
            MaidLifecycleDurability.entities(world, Collections.singletonList(source), false);
            removed = true;
            ledger.captureStage(world, token, "REMOVED");
            if (!frame.applied()) throw new IllegalStateException("Capture receipt changed after source removal");
            complete = finishDelivery(player, ledger, token);
            if (complete) cooldown(player);
            return complete ? MaidLifecycleService.Result.SUCCESS : MaidLifecycleService.Result.DENIED;
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, token, failure);
            return MaidLifecycleService.Result.DENIED;
        } finally {
            if (ledger != null && !ledger.capture(token)
                .hasNoTags()) {
                if (!complete) {
                    try {
                        // 只有完整原活体仍在且原槽安全撤回时，才撤销这次尚未发生的收纳
                        if (!attemptedRemoval && source != null
                            && source.owned(world)
                            && MaidSpawnChecks.present(maid, world)
                            && frame != null) {
                            if (frame.touched()) frame.retract();
                            if (!frame.originalStored() || !source.owned(world)
                                || !MaidSpawnChecks.present(maid, world))
                                throw new IllegalStateException("Capture cancellation source changed");
                            ledger.cancelCapture(world, token);
                        } else if (!removed || !"REMOVED".equals(
                            ledger.capture(token)
                                .getString("Stage"))
                            || !knownDelivery(player, ledger.capture(token))) {
                                ledger.holdCapture(world, token, observation(maid, player));
                            }
                        // 已确认移除且准确收据/输出仍在时保留REMOVED，重登只完成原同槽交付
                    } catch (Exception | LinkageError | AssertionError failure) {
                        report(player, token, failure);
                        try {
                            ledger.holdCapture(world, token, observation(maid, player));
                        } catch (Exception | LinkageError | AssertionError holdFailure) {
                            report(player, token, holdFailure);
                        }
                    }
                }
                ledger.endCapture(token);
            }
            CAPTURING.remove(maid);
        }
    }

    /** pending身份拒绝使用不等于授权删除未知后继；只依据自有完整收据恢复交付 */
    static boolean blocks(EntityMaid maid) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return false;
        try {
            var ledger = MaidLifecycleData.get(maid.worldObj);
            var token = ledger.captureHead(maid.getUniqueID());
            if (token == null) {
                if (!maid.getEntityData()
                    .hasKey(RELEASED)) return false;
                report(null, maid.getUniqueID(), new IllegalStateException("Unmatched captured-maid marker retained"));
                return true;
            }
            return !ledger.captureConsumed(token) || !token.toString()
                .equals(
                    maid.getEntityData()
                        .getString(RELEASED));
        } catch (Exception | LinkageError | AssertionError failure) {
            report(null, maid.getUniqueID(), failure);
            return true;
        }
    }

    static void markReleased(EntityMaid maid, UUID token) {
        maid.getEntityData()
            .setString(RELEASED, token.toString());
    }

    static void resume(EntityPlayer player) {
        if (!MaidLifecycleService.canUse(player)) return;
        var held = player.getCurrentEquippedItem();
        if (held == null || !held.hasTagCompound()) return;
        UUID token;
        try {
            String raw = held.getItem() == MaidResurrectionContent.FILLED_SLAB ? held.getTagCompound()
                .getString("TLMNHToken")
                : held.getTagCompound()
                    .getString(RECEIPT);
            if (raw.isEmpty()) return;
            token = UUID.fromString(raw);
        } catch (IllegalArgumentException failure) {
            return;
        }
        MaidLifecycleData ledger = null;
        boolean opened = false;
        try {
            ledger = MaidLifecycleData.get(player.worldObj);
            var record = ledger.capture(token);
            if (record.hasNoTags() || !"REMOVED".equals(record.getString("Stage"))
                || !player.getUniqueID()
                    .toString()
                    .equals(record.getString("Owner")))
                return;
            opened = ledger.beginCapture(token);
            if (opened && !finishDelivery(player, ledger, token))
                ledger.holdCapture(player.worldObj, token, observation(null, player));
        } catch (Exception | LinkageError | AssertionError failure) {
            report(player, token, failure);
        } finally {
            if (opened) ledger.endCapture(token);
        }
    }

    private static boolean finishDelivery(EntityPlayer player, MaidLifecycleData ledger, UUID token) {
        var record = ledger.capture(token);
        if (!"REMOVED".equals(record.getString("Stage")) || !knownDelivery(player, record)) return false;
        var frame = new MaidPlayerTransferSnapshot(player);
        var credential = ProtectedItemCodec.decode(record.getCompoundTag("Credential"));
        if (record.getTagList("InventoryReceipt", Constants.NBT.TAG_COMPOUND)
            .equals(frame.original)) {
            frame.apply(frame.selected, credential);
            frame.persist();
        } else {
            MaidLifecycleDurability
                .player(player, record.getTagList("InventoryFilled", Constants.NBT.TAG_COMPOUND), frame.selected);
        }
        if (!record.getTagList("InventoryFilled", Constants.NBT.TAG_COMPOUND)
            .equals(player.inventory.writeToNBT(new NBTTagList()))
            || !ItemStack.areItemStacksEqual(credential, player.getCurrentEquippedItem()))
            throw new IllegalStateException("Unknown capture delivery successor retained");
        ledger.captureStage(player.worldObj, token, "DELIVERED");
        return true;
    }

    private static boolean knownDelivery(EntityPlayer player, NBTTagCompound record) {
        if (!MaidLifecycleService.canUse(player) || !player.getUniqueID()
            .toString()
            .equals(record.getString("Owner"))
            || player.worldObj.provider.dimensionId != record.getInteger("Dimension")
            || player.inventory.currentItem != record.getInteger("Selected")
            || !ItemStack.areItemStacksEqual(
                player.inventory.getItemStack(),
                record.getCompoundTag("Cursor")
                    .hasNoTags() ? null : ProtectedItemCodec.decode(record.getCompoundTag("Cursor"))))
            return false;
        var inventory = player.inventory.writeToNBT(new NBTTagList());
        return record.getTagList("InventoryReceipt", Constants.NBT.TAG_COMPOUND)
            .equals(inventory)
            || record.getTagList("InventoryFilled", Constants.NBT.TAG_COMPOUND)
                .equals(inventory);
    }

    private static boolean admitted(EntityMaid maid, EntityPlayer player, ItemStack empty) {
        return maid != null && player != null
            && !maid.getCombatHitReceipt()
                .blocksTransfer()
            && MaidLifecycleService.canUse(player)
            && isEmpty(empty)
            && player.getCurrentEquippedItem() == empty
            && maid.worldObj == player.worldObj
            && maid.isEntityAlive()
            && maid.isOwner(player)
            && maid.getDistanceSqToEntity(player) <= 64
            && MaidSpawnChecks.present(maid, player.worldObj)
            && !MaidLifecycleService.isPendingRedemption(maid);
    }

    static boolean coolingDown(EntityPlayer player) {
        var clocks = COOLDOWNS.get(player.worldObj);
        return clocks != null
            && clocks.getOrDefault(player.getUniqueID(), Long.MIN_VALUE) > player.worldObj.getTotalWorldTime();
    }

    static void cooldown(EntityPlayer player) {
        COOLDOWNS.computeIfAbsent(player.worldObj, ignored -> new HashMap<>())
            .put(player.getUniqueID(), player.worldObj.getTotalWorldTime() + 20);
    }

    private static NBTTagCompound record(UUID token, EntityPlayer player, EntityMaid maid, NBTTagCompound original,
        NBTTagCompound source, MaidPlayerTransferSnapshot frame, ItemStack receipt, ItemStack credential,
        int sequence) {
        var data = new NBTTagCompound();
        data.setString("Token", token.toString());
        data.setString(
            "Owner",
            player.getUniqueID()
                .toString());
        data.setString(
            "Subject",
            maid.getUniqueID()
                .toString());
        data.setString("Stage", "PREPARED");
        data.setInteger("Sequence", sequence);
        data.setTag("Source", source.copy());
        data.setTag("OriginalMaid", original.copy());
        data.setTag(
            "Receipt",
            receipt.writeToNBT(new NBTTagCompound())
                .copy());
        data.setTag(
            "Credential",
            credential.writeToNBT(new NBTTagCompound())
                .copy());
        data.setTag("InventoryBefore", frame.original.copy());
        data.setTag("InventoryReceipt", frame.plan(frame.selected, receipt));
        data.setTag("InventoryFilled", frame.plan(frame.selected, credential));
        data.setInteger("Selected", frame.selected);
        data.setInteger("Dimension", player.worldObj.provider.dimensionId);
        data.setTag(
            "Cursor",
            frame.cursor == null ? new NBTTagCompound()
                : frame.cursor.writeToNBT(new NBTTagCompound())
                    .copy());
        data.setTag("Observed", new NBTTagCompound());
        return data;
    }

    private static NBTTagCompound observation(EntityMaid maid, EntityPlayer player) {
        var result = new NBTTagCompound();
        if (maid != null) result.setTag("Maid", rawSnapshot(maid));
        if (player != null) result.setTag(
            "Inventory",
            player.inventory.writeToNBT(new NBTTagList())
                .copy());
        return result;
    }

    private static NBTTagCompound rawSnapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        var type = EntityList.getEntityString(maid);
        if (type == null) throw new IllegalStateException("Unregistered maid snapshot type");
        result.setString("id", type);
        maid.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    private static void report(EntityPlayer player, UUID token, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(Side.SERVER, "capture/" + token, failure, player == null ? null : player.getUniqueID());
    }
}
