/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/task/TaskBowAttack.java, MIT
 * Adaptation: native bow/basic-arrow capabilities; behavior registration and guarded release supplied by modern
 * component
 */
package com.github.tartaricacid.touhoulittlemaid.entity.task;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;

public final class TaskBowAttack implements IRangedAttackTask {

    public boolean isWeapon(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBow;
    }

    public boolean hasBow(EntityMaid maid) {
        return isWeapon(maid.getHeldItem());
    }

    public boolean hasArrow(EntityMaid maid) {
        return hasBow(maid) && MaidBowAttack.modernArrowSlot(maid) >= 0;
    }

    public boolean farAway(EntityLivingBase target, EntityMaid maid) {
        return maid.getDistanceToEntity(target) > MaidPerceptionConfig.bowRange;
    }

    @Override
    public boolean canSee(EntityMaid maid, EntityLivingBase target) {
        return IRangedAttackTask.targetConditionsTest(maid, target, MaidPerceptionConfig.bowRange, this);
    }
}
