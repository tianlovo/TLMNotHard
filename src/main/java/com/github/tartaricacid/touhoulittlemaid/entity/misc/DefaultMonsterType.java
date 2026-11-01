/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/misc/DefaultMonsterType.java, MIT
 * Adaptation: native IMob/INpc and living revenge/last-attacked references
 */
package com.github.tartaricacid.touhoulittlemaid.entity.misc;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.INpc;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public final class DefaultMonsterType {

    private DefaultMonsterType() {}

    public static MonsterType getMonsterType(EntityLivingBase target) {
        if (target instanceof IMob) return MonsterType.HOSTILE;
        if (target instanceof EntityTameable || target instanceof INpc) return MonsterType.FRIENDLY;
        return MonsterType.NEUTRAL;
    }

    public static boolean canAttack(EntityMaid maid, EntityLivingBase target, MonsterType type) {
        if (type == MonsterType.FRIENDLY) return false;
        if (type == MonsterType.NEUTRAL) return checkNeutral(maid, target);
        return true;
    }

    private static boolean checkNeutral(EntityMaid maid, EntityLivingBase target) {
        if (maid.getOwner() instanceof EntityPlayer player) {
            if (target == player.getAITarget()) return true;
            if (target == player.getLastAttacker()) return true;
        }
        return target == maid.getAITarget();
    }
}
