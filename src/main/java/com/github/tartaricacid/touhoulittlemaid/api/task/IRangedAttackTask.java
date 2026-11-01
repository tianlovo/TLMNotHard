/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/task/IRangedAttackTask.java, MIT
 * Adaptation: native living facts and fresh ranged TargetingConditions, separate from cached visible memory
 */
package com.github.tartaricacid.touhoulittlemaid.api.task;

import java.util.List;
import java.util.Optional;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public interface IRangedAttackTask extends IAttackTask {

    static Optional<EntityLivingBase> findFirstValidAttackTarget(EntityMaid maid, List<EntityLivingBase> nearby,
        IRangedAttackTask task) {
        var attacker = maid.getAITarget();
        if (attacker != null && task.canAttack(maid, attacker) && task.canSee(maid, attacker))
            return Optional.of(attacker);
        for (var candidate : nearby) {
            if (task.canAttack(maid, candidate) && task.canSee(maid, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    static boolean targetConditionsTest(EntityMaid maid, EntityLivingBase target, float configuredRange,
        IRangedAttackTask task) {
        if (target == maid || target.worldObj != maid.worldObj
            || !target.isEntityAlive()
            || target instanceof EntityPlayerMP player && player.theItemInWorldManager.getGameType()
                .getID() == 3
            || !task.canAttack(maid, target)
            || maid.isOnSameTeam(target)) return false;
        double visibility = target.isSneaking() ? .8 : 1;
        if (target.isInvisible()) {
            int armor = 0;
            for (int slot = 1; slot <= 4; slot++) {
                var stack = target.getEquipmentInSlot(slot);
                if (stack != null && stack.stackSize > 0) armor++;
            }
            visibility *= .7 * Math.max(.1F, armor / 4F);
        }
        double range = Math.max(2, configuredRange * visibility);
        return maid.getDistanceSqToEntity(target) <= range * range && maid.getEntitySenses()
            .canSee(target);
    }

    boolean canSee(EntityMaid maid, EntityLivingBase target);
}
