package com.tianluoqaq.tlmnh.common.combat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.spi.HealingAxeSpiTuning;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;

/** 已通过准入与命中归属检查后的治愈之斧结算；不调用物品耐久回调 */
public final class MaidHealingAxeCombat {

    private MaidHealingAxeCombat() {}

    public static boolean execute(EntityMaid maid, EntityLivingBase target) {
        var outcome = CompatServices.healingAxe()
            .resolveMainhandStrike(maid.getHeldItem(), target);
        maid.getHungerState()
            .addExhaustion(outcome.exhaustionCost);
        return switch (outcome.kind) {
            case UNDEAD_MOB_DAMAGE -> target.attackEntityFrom(DamageSource.causeMobDamage(maid), outcome.undeadDamage);
            case ZOMBIE_VILLAGER_CURE -> {
                if (target instanceof EntityZombie zombie && !zombie.isConverting()) {
                    zombie.getDataWatcher()
                        .updateObject(HealingAxeSpiTuning.CONVERSION_WATCHER, HealingAxeSpiTuning.CONVERSION_FLAG);
                    zombie.worldObj.setEntityState(zombie, HealingAxeSpiTuning.CONVERSION_STATUS);
                }
                yield false;
            }
            default -> false;
        };
    }
}
