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
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.relauncher.Side;

/** 一次近战调用的结算所有权；未闭合事实只保留，绝不自动重放伤害或扣费 */
public final class CombatHitReceipt {

    public static final String TAG = "TLMNHCombatHitReceipt";
    private static final int VERSION = 1;

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
        this.maid = Objects.requireNonNull(maid, "maid");
    }

    /** 真实栈规范化也可能回调；准备门只防重入，不表示已经发起命中 */
    public boolean beginPreparation() {
        if (blocksTransfer() || !captureContext()) return false;
        preparing = true;
        accepted = false;
        identity = UUID.randomUUID();
        capability = "preparation";
        weapon = maid.getHeldItem();
        before = ItemStack.copyItemStack(weapon);
        return true;
    }

    public void endPreparation() {
        if (!preparing) return;
        if (!contextCurrent()) hold("ownership_changed_during_preparation");
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
            throw new IllegalStateException("Invalid accepted hit transition");
        accepted = true;
        // 同步回调已造成HELD时仍记录真实返回的ACCEPTED事实，但不自动续扣
        if (stage != Stage.HELD) stage = Stage.ACCEPTED;
    }

    /** 不要求目标存活、当前任务或会话仍有效，只核本次原真实栈 */
    public boolean beginSettlement() {
        if (!active || stage != Stage.ACCEPTED) return false;
        if (!originalOwned(true)) {
            hold("original_stack_changed_before_settlement");
            return false;
        }
        stage = Stage.SETTLING;
        return true;
    }

    public void settled() {
        if (!active || stage != Stage.SETTLING) throw new IllegalStateException("Invalid settled hit transition");
        stage = Stage.SETTLED;
    }

    public void hold(String detail) {
        if (stage == Stage.HELD && !reason.isEmpty()) return;
        stage = Stage.HELD;
        reason = detail;
        IntegrationGuard.report(
            Side.SERVER,
            "combat_hit/" + maid.getUniqueID(),
            new IllegalStateException("Unresolved combat hit retained: " + detail));
    }

    /** 真实引用必须仍唯一处于原主手；不按物品种类寻找替代结算对象 */
    public boolean originalOwned(boolean unchanged) {
        if (!contextCurrent() || weapon == null || maid.getHeldItem() != weapon) return false;
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
            if (slot != MaidInventory.MAIN_HAND && inventory.getStackInSlot(slot) == weapon) return false;
        return !unchanged || ItemStack.areItemStacksEqual(weapon, before);
    }

    public void closeCall() {
        if (!active) return;
        if (!contextCurrent()) hold("ownership_changed_during_hit");
        if (stage == Stage.ATTEMPTING || stage == Stage.ACCEPTED || stage == Stage.SETTLING)
            hold("call_closed_without_known_settlement");
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
            root.setTag(TAG, retained.copy());
            return;
        }
        if (!active && !preparing && stage != Stage.HELD) {
            root.removeTag(TAG);
            return;
        }
        var data = new NBTTagCompound();
        data.setInteger("Version", VERSION);
        data.setString("Stage", preparing ? "PREPARING" : stage.name());
        data.setBoolean("Accepted", accepted);
        data.setString("Receipt", identity == null ? "" : identity.toString());
        data.setString("Target", target == null ? "" : target.toString());
        data.setString("Subject", subject == null ? "" : subject.toString());
        data.setString("Owner", owner);
        if (world != null) data.setInteger("Dimension", world.provider.dimensionId);
        data.setLong("EquipmentEpoch", equipmentGeneration);
        data.setString("Capability", capability);
        data.setString("Reason", reason);
        if (before != null) data.setTag("WeaponBefore", ProtectedItemCodec.encode(before));
        // 这里只是无法兑现的证据副本，加载不会把它解码成库存或补偿物品
        if (weapon != null) data.setTag("WeaponObserved", ProtectedItemCodec.encode(weapon.copy()));
        root.setTag(TAG, data);
    }

    public void load(NBTTagCompound root) {
        if (blocksTransfer()) {
            hold("persistent_read_attempted_during_unclosed_hit");
            throw new IllegalStateException("Unclosed combat receipt cannot be overwritten by an in-place read");
        }
        active = preparing = false;
        clearEvidence();
        accepted = false;
        capability = reason = "";
        retained = null;
        stage = Stage.SETTLED;
        if (!root.hasKey(TAG)) return;
        retained = root.getTag(TAG)
            .copy();
        stage = Stage.HELD;
        // 原调用栈和对象身份无法跨重载证明，已知版本同样不能自动续扣或重放
        if (retained instanceof NBTTagCompound data && data.hasKey("Version", Constants.NBT.TAG_INT)
            && data.getInteger("Version") == VERSION) accepted = data.getBoolean("Accepted");
        IntegrationGuard.report(
            Side.SERVER,
            "combat_hit/reload/" + maid.getUniqueID(),
            new IllegalStateException("Unclosed or unknown combat receipt retained without replay"));
    }
}
