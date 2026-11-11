package com.tianluoqaq.tlmnh.common.combat.weapon;

import java.util.Objects;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;

/** 当前主手的当tick能力观测；原地改NBT或换世界不能继续复用旧能力 */
public final class MaidCombatWeaponView {

    private final EntityMaid maid;
    private Object world;
    private long tick = Long.MIN_VALUE;
    private ItemStack reference, contents;
    private MaidWeaponProfile profile;

    public MaidCombatWeaponView(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid, "maid");
    }

    public MaidWeaponProfile current() {
        if (maid.worldObj == null || maid.worldObj.isRemote)
            return MaidWeaponProfile.unknown("server_weapon_view_required");
        var actual = maid.getHeldItem();
        long now = maid.worldObj.getTotalWorldTime();
        if (profile != null && world == maid.worldObj
            && tick == now
            && reference == actual
            && ItemStack.areItemStacksEqual(actual, contents)) return profile;
        var quota = CombatWorldBudget.heartbeat(maid.worldObj, maid.getUniqueID(), now, false);
        if (!quota.spend(Work.ITEM)) return MaidWeaponProfile.unknown("item_budget_pending");
        var before = ItemStack.copyItemStack(actual);
        var observed = MaidWeaponCatalog.inspect(actual);
        if (actual != maid.getHeldItem() || !ItemStack.areItemStacksEqual(actual, before)) {
            invalidate();
            return MaidWeaponProfile.unknown("weapon_changed_during_observation");
        }
        world = maid.worldObj;
        tick = now;
        reference = actual;
        contents = before;
        profile = observed;
        return observed;
    }

    public void invalidate() {
        world = null;
        tick = Long.MIN_VALUE;
        reference = contents = null;
        profile = null;
    }
}
