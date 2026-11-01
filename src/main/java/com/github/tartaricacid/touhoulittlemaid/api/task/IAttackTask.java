/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/task/IAttackTask.java, MIT
 * Adaptation: retained combat predicates; guarded visible memory and entity registry/settings supplied by platform
 */
package com.github.tartaricacid.touhoulittlemaid.api.task;

import java.util.Optional;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.DefaultMonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVisibleLivingEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidCombatConfig;

public interface IAttackTask {

    String MAID_NO_ATTACK_TAG = "MaidNoAttack";

    static Optional<EntityLivingBase> findFirstValidAttackTarget(EntityMaid maid, MaidVisibleLivingEntities visible,
        IAttackTask task) {
        if (visible == null) return Optional.empty();
        return Optional.ofNullable(
            visible.findClosest(
                e -> task.canAttack(maid, e) && maid.getHomeArea()
                    .withinRestriction(
                        MathHelper.floor_double(e.posX),
                        MathHelper.floor_double(e.posY),
                        MathHelper.floor_double(e.posZ))));
    }

    default boolean canAttack(EntityMaid maid, EntityLivingBase target) {
        var id = MaidCombatEntities.id(target);
        if (id == null) return false;
        if (MaidCombatEntities.isArmorStand(target) || MaidCombatEntities.isMaidItemEntity(target)
            || target instanceof EntityPlayer) return false;
        if (target instanceof EntityTameable tame && tame.func_152113_b() != null
            && !tame.func_152113_b()
                .isEmpty())
            return false;
        if (target instanceof EntityLiving mob && mob.hasCustomNameTag()
            && mob.getCustomNameTag()
                .startsWith(MAID_NO_ATTACK_TAG))
            return false;
        if (MaidCombatConfig.ignored(id.toString())) return false;
        var groups = maid.getAttackListData()
            .attackGroups();
        var type = groups.containsKey(id) ? groups.get(id) : DefaultMonsterType.getMonsterType(target);
        return DefaultMonsterType.canAttack(maid, target, type);
    }
}
