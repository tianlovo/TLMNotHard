package com.tianluoqaq.tlmnh.common.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.combat.AiCombatTuning;
import com.tianluoqaq.tlmnh.common.ai.combat.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatFeedback;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidEquipmentTransfer;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** Legacy只补统一任务的确定性装备回退，不接入增强战术或预演 */
final class MaidLegacyCombatEquipment {

    private MaidLegacyCombatEquipment() {}

    static void select(EntityMaid maid) {
        if (!permitted(maid)) return;
        var state = maid.getCombatTaskState();
        var inventory = maid.getMaidInventory();
        var current = maid.getCombatWeaponProfile();
        boolean arrows = MaidBowAttack.modernArrowSlot(maid) >= 0;
        if (!state.preferencePending() && usable(current, null, arrows)) return;
        var snapshot = MaidEquipmentTransfer.capture(maid);
        if (snapshot == null) return;
        var quota = CombatWorldBudget
            .heartbeat(maid.worldObj, maid.getUniqueID(), maid.worldObj.getTotalWorldTime(), false);
        var profiles = new MaidWeaponProfile[MaidInventoryLayout.BACKPACK_SIZE];
        for (int slot = 0; slot < profiles.length; slot++) {
            if (snapshot.stack(slot) == null)
                profiles[slot] = MaidWeaponProfile.unknown(MaidAiIds.ID_MISSING_OR_INVALID_STACK);
            else {
                if (!quota.spend(Work.ITEM)) return;
                profiles[slot] = MaidWeaponCatalog.inspect(snapshot.stack(slot));
            }
        }
        if (!snapshot.owned(inventory) || !permitted(maid)) return;
        MaidTask preferred = state.preferencePending() ? state.preferredTask() : null;
        if (preferred != null) {
            if (usable(current, preferred, arrows)) {
                state.consumePreference();
                return;
            }
            for (int slot = 0; slot < profiles.length; slot++) if (usable(profiles[slot], preferred, arrows)) {
                if (MaidEquipmentTransfer.exchange(maid, snapshot, slot, () -> permitted(maid))
                    .changed()) state.consumePreference();
                return;
            }
        }
        if (usable(current, null, arrows)) {
            state.consumePreference();
            return;
        }
        for (int slot = 0; slot < profiles.length; slot++) if (usable(profiles[slot], null, arrows)) {
            if (MaidEquipmentTransfer.exchange(maid, snapshot, slot, () -> permitted(maid))
                .changed()) state.consumePreference();
            return;
        }
        if (snapshot.owned(inventory)) {
            state.consumePreference();
            MaidCombatFeedback.unsupported(maid);
        }
    }

    private static boolean usable(MaidWeaponProfile profile, MaidTask requested, boolean arrows) {
        if (profile.bow()) return requested != MaidTask.ATTACK && arrows;
        return profile.melee() && requested != MaidTask.RANGED_ATTACK;
    }

    private static boolean permitted(EntityMaid maid) {
        if (!maid.isUnifiedCombat() || !maid.isEntityAlive()
            || maid.worldObj.isRemote
            || maid.getTask() != MaidTask.ATTACK
            || !maid.getCombatTaskState()
                .available()
            || !maid.isTamed()
            || maid.guiOpening
            || maid.isSitting()
            || maid.ridingEntity != null
            || maid.getLeashed()
            || maid.getBrainCoordinator()
                .isSuspended()
            || maid.getCombatHitReceipt()
                .blocksTransfer()
            || maid.getItemUseState()
                .peek() != null)
            return false;
        var home = maid.getHomeArea();
        if (home.activity() != Activity.WORK) return false;
        if (home.enabled()) return home.sameDimension() && home.containsMaid();
        var owner = maid.getOwner();
        return MaidCombatTargeting.validOwner(maid, owner)
            && maid.getDistanceSqToEntity(owner) <= AiCombatTuning.OWNER_RADIUS_SQ;
    }
}
