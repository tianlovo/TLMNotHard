package com.tianluoqaq.tlmnh.common.combat;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 一次近战调用的结算所有权；未闭合事实只保留，绝不自动重放伤害或扣费 */
public final class CombatHitReceipt {

    public enum Stage {
        ATTEMPTING,
        REJECTED,
        ACCEPTED,
        SETTLING,
        SETTLED,
        HELD
    }

    private final EntityMaid maid;
    private Stage stage = Stage.SETTLED;
    private boolean active, accepted, preparing;
    private ItemStack weapon, before;
    private UUID identity, target, subject;
    private World world;
    private String owner = "";
    private long equipmentGeneration;
    private String capability = "", reason = "";
    private NBTBase retained;

    public CombatHitReceipt(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid, MaidCombatIds.ID_MAID);
    }

    /** 真实栈规范化也可能回调；准备门只防重入，不表示已经发起命中 */
    public boolean beginPreparation() {
        if (blocksTransfer() || !captureContext()) return false;
        preparing = true;
        accepted = false;
        identity = UUID.randomUUID();
        capability = MaidCombatIds.ID_PREPARATION;
        weapon = maid.getHeldItem();
        before = ItemStack.copyItemStack(weapon);
        return true;
    }

    public void endPreparation() {
        if (!preparing) return;
        if (!contextCurrent()) hold(MaidCombatIds.ID_OWNERSHIP_CHANGED_DURING_PREPARATION);
        preparing = false;
        if (stage != Stage.HELD) clearEvidence();
        MaidDeathService.completeDeferred(maid);
    }

    public boolean begin(ItemStack original, long equipmentEpoch, String admittedCapability, EntityLivingBase victim) {
        if (blocksTransfer() || original == null
            || original.stackSize <= 0
            || victim == null
            || admittedCapability == null
            || !captureContext()) return false;
        active = true;
        accepted = false;
        stage = Stage.ATTEMPTING;
        weapon = original;
        before = original.copy();
        identity = UUID.randomUUID();
        target = victim.getUniqueID();
        equipmentGeneration = equipmentEpoch;
        capability = admittedCapability;
        reason = "";
        if (!originalOwned(true)) {
            rejected();
            closeCall();
            return false;
        }
        return true;
    }

    public boolean activeCall() {
        return active || preparing;
    }

    public boolean blocksTransfer() {
        return active || preparing || stage == Stage.HELD || retained != null;
    }

    public boolean hasPersistentState() {
        return blocksTransfer();
    }

    public Stage stage() {
        return stage;
    }

    public boolean nativeAccepted() {
        return accepted;
    }

    public void rejected() {
        if (stage == Stage.ATTEMPTING) stage = Stage.REJECTED;
    }

    public void accepted() {
        if (!active || stage != Stage.ATTEMPTING && stage != Stage.HELD)
            throw new IllegalStateException(MaidCombatDiagnostics.MSG_INVALID_ACCEPTED_HIT_TRANSITION);
        accepted = true;
        // 同步回调已造成HELD时仍记录真实返回的ACCEPTED事实，但不自动续扣
        if (stage != Stage.HELD) stage = Stage.ACCEPTED;
    }

    /** 不要求目标存活、当前任务或会话仍有效，只核本次原真实栈 */
    public boolean beginSettlement() {
        if (!active || stage != Stage.ACCEPTED) return false;
        if (!originalOwned(true)) {
            hold(MaidCombatIds.ID_ORIGINAL_STACK_CHANGED_BEFORE_SETTLEMENT);
            return false;
        }
        stage = Stage.SETTLING;
        return true;
    }

    public void settled() {
        if (!active || stage != Stage.SETTLING)
            throw new IllegalStateException(MaidCombatDiagnostics.MSG_INVALID_SETTLED_HIT_TRANSITION);
        stage = Stage.SETTLED;
    }

    public void hold(String detail) {
        if (stage == Stage.HELD && !reason.isEmpty()) return;
        stage = Stage.HELD;
        reason = detail;
        IntegrationGuard.report(
            Side.SERVER,
            MaidCombatIds.ID_COMBAT_HIT + maid.getUniqueID(),
            new IllegalStateException(MaidCombatDiagnostics.MSG_UNRESOLVED_COMBAT_HIT_RETAINED + detail));
    }

    /** 真实引用必须仍唯一处于原主手；不按物品种类寻找替代结算对象 */
    public boolean originalOwned(boolean unchanged) {
        if (!contextCurrent() || weapon == null || maid.getHeldItem() != weapon) return false;
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
            if (slot != MaidInventoryLayout.MAIN_HAND && inventory.getStackInSlot(slot) == weapon) return false;
        return !unchanged || ItemStack.areItemStacksEqual(weapon, before);
    }

    public void closeCall() {
        if (!active) return;
        if (!contextCurrent()) hold(MaidCombatIds.ID_OWNERSHIP_CHANGED_DURING_HIT);
        if (stage == Stage.ATTEMPTING || stage == Stage.ACCEPTED || stage == Stage.SETTLING)
            hold(MaidCombatIds.ID_CALL_CLOSED_WITHOUT_KNOWN_SETTLEMENT);
        active = false;
        if (stage != Stage.HELD) clearEvidence();
        MaidDeathService.completeDeferred(maid);
    }

    private boolean captureContext() {
        if (maid.worldObj == null || maid.worldObj.isRemote || !maid.isTamed() || maid.isDead) return false;
        String claimed = maid.func_152113_b();
        try {
            UUID.fromString(claimed);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
        world = maid.worldObj;
        subject = maid.getUniqueID();
        owner = claimed;
        return true;
    }

    private boolean contextCurrent() {
        return maid.worldObj == world && !maid.isDead
            && maid.isTamed()
            && subject != null
            && subject.equals(maid.getUniqueID())
            && owner.equals(maid.func_152113_b());
    }

    private void clearEvidence() {
        weapon = before = null;
        identity = target = subject = null;
        world = null;
        owner = capability = reason = "";
    }

    public void save(NBTTagCompound root) {
        if (retained != null) {
            root.setTag(MaidCombatNbtKeys.TAG_2, retained.copy());
            return;
        }
        if (!active && !preparing && stage != Stage.HELD) {
            root.removeTag(MaidCombatNbtKeys.TAG_2);
            return;
        }
        var data = new NBTTagCompound();
        data.setInteger(MaidCombatIds.KEY_VERSION, MaidCombatTuning.VERSION);
        data.setString(MaidCombatIds.KEY_STAGE, preparing ? MaidCombatIds.KEY_PREPARING : stage.name());
        data.setBoolean(MaidCombatIds.KEY_ACCEPTED, accepted);
        data.setString(MaidCombatIds.KEY_RECEIPT, identity == null ? "" : identity.toString());
        data.setString(MaidCombatIds.KEY_TARGET, target == null ? "" : target.toString());
        data.setString(MaidCombatIds.KEY_SUBJECT, subject == null ? "" : subject.toString());
        data.setString(MaidCombatIds.KEY_OWNER, owner);
        if (world != null) data.setInteger(MaidCombatIds.KEY_DIMENSION, world.provider.dimensionId);
        data.setLong(MaidCombatIds.KEY_EQUIPMENT_EPOCH, equipmentGeneration);
        data.setString(MaidCombatIds.KEY_CAPABILITY, capability);
        data.setString(MaidCombatIds.KEY_REASON, reason);
        if (before != null) data.setTag(MaidCombatIds.KEY_WEAPON_BEFORE, ProtectedItemCodec.encode(before));
        // 这里只是无法兑现的证据副本，加载不会把它解码成库存或补偿物品
        if (weapon != null) data.setTag(MaidCombatIds.KEY_WEAPON_OBSERVED, ProtectedItemCodec.encode(weapon.copy()));
        root.setTag(MaidCombatNbtKeys.TAG_2, data);
    }

    public void load(NBTTagCompound root) {
        if (blocksTransfer()) {
            hold(MaidCombatIds.ID_PERSISTENT_READ_ATTEMPTED_DURING_UNCLOSED_HIT);
            throw new IllegalStateException(MaidCombatDiagnostics.MSG_UNCLOSED_COMBAT_RECEIPT_CANNOT_BE);
        }
        active = preparing = false;
        clearEvidence();
        accepted = false;
        capability = reason = "";
        retained = null;
        stage = Stage.SETTLED;
        if (!root.hasKey(MaidCombatNbtKeys.TAG_2)) return;
        retained = root.getTag(MaidCombatNbtKeys.TAG_2)
            .copy();
        stage = Stage.HELD;
        // 原调用栈和对象身份无法跨重载证明，已知版本同样不能自动续扣或重放
        if (retained instanceof NBTTagCompound data && data.hasKey(MaidCombatIds.KEY_VERSION, Constants.NBT.TAG_INT)
            && data.getInteger(MaidCombatIds.KEY_VERSION) == MaidCombatTuning.VERSION)
            accepted = data.getBoolean(MaidCombatIds.KEY_ACCEPTED);
        IntegrationGuard.report(
            Side.SERVER,
            MaidCombatIds.ID_COMBAT_HIT_RELOAD + maid.getUniqueID(),
            new IllegalStateException(MaidCombatDiagnostics.MSG_UNCLOSED_OR_UNKNOWN_COMBAT_RECEIPT));
    }
}
