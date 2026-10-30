package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 复用目标版本的属性、伤害事件和附魔执行，不直接修改目标生命值 */
public final class MaidMeleeAttack {

    private MaidMeleeAttack() {}

    public static boolean attack(EntityMaid maid, Entity target) {
        if (!(target instanceof EntityLivingBase living) || maid.getTask() != MaidTask.ATTACK
            || maid.getBrainCoordinator()
                .isSuspended()
            || maid.isSitting()
            || maid.ridingEntity != null
            || maid.getLeashed()
            || maid.hasPose(MaidPose.SLEEPING)
            || !MaidCombatTargeting.hasMeleeWeapon(maid)
            || !MaidCombatTargeting.canAttack(maid, maid.getOwner(), living)
            || maid.getDistanceSq(living.posX, living.boundingBox.minY, living.posZ)
                > MaidCombatTargeting.meleeReachSq(maid, living)
            || !maid.getEntitySenses()
                .canSee(living))
            return false;
        float damage = (float) maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
            .getAttributeValue();
        damage += EnchantmentHelper.getEnchantmentModifierLiving(maid, living);
        if (!Float.isFinite(damage) || damage <= 0) return false;
        int knockback = EnchantmentHelper.getKnockbackModifier(maid, living);
        var weapon = maid.getHeldItem();
        if (!living.attackEntityFrom(DamageSource.causeMobDamage(maid), damage)) return false;
        maid.setLastAttacker(living);
        if (knockback > 0) {
            living.addVelocity(
                -MathHelper.sin(maid.rotationYaw * (float) Math.PI / 180.0F) * knockback * 0.5D,
                0.1D,
                MathHelper.cos(maid.rotationYaw * (float) Math.PI / 180.0F) * knockback * 0.5D);
            maid.motionX *= 0.6D;
            maid.motionZ *= 0.6D;
        }
        int fire = EnchantmentHelper.getFireAspectModifier(maid);
        if (fire > 0) living.setFire(fire * 4);
        EnchantmentHelper.func_151384_a(living, maid);
        EnchantmentHelper.func_151385_b(maid, living);
        if (weapon != null && weapon.stackSize > 0) {
            weapon.getItem()
                .hitEntity(weapon, living, maid);
            if (weapon.stackSize <= 0 && maid.getHeldItem() == weapon) maid.setCurrentItemOrArmor(0, null);
        }
        return true;
    }
}
