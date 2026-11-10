package com.tianluoqaq.tlmnh.common.ai;

import java.util.function.BooleanSupplier;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.potion.Potion;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.combat.CombatHitReceipt;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponStrike;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 复用目标版本的属性、伤害事件和附魔执行，不直接修改目标生命值 */
public final class MaidMeleeAttack {

    public static final class Result {

        public final boolean apiResult, nativeAccepted, preCancelled, settlementHeld;

        private Result(boolean apiResult, boolean nativeAccepted, boolean preCancelled) {
            this(apiResult, nativeAccepted, preCancelled, false);
        }

        private Result(boolean apiResult, boolean nativeAccepted, boolean preCancelled, boolean settlementHeld) {
            this.apiResult = apiResult;
            this.nativeAccepted = nativeAccepted;
            this.preCancelled = preCancelled;
            this.settlementHeld = settlementHeld;
        }
    }

    private MaidMeleeAttack() {}

    public static boolean attack(EntityMaid maid, Entity target) {
        if (maid.isUnifiedCombat()) {
            if (!(target instanceof EntityLivingBase living)) return false;
            return hurtForUnified(
                maid,
                living,
                () -> maid.getTask() == MaidTask.ATTACK && !maid.getBrainCoordinator()
                    .isSuspended()
                    && !maid.isSitting()
                    && maid.ridingEntity == null
                    && !maid.getLeashed()
                    && !maid.hasPose(MaidPose.SLEEPING)
                    && MaidCombatTargeting.canAttack(maid, maid.getOwner(), living)
                    && maid.getDistanceSq(living.posX, living.boundingBox.minY, living.posZ)
                        <= MaidCombatTargeting.meleeReachSq(maid, living)
                    && maid.getEntitySenses()
                        .canSee(living)).apiResult;
        }
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
        return hurtNative(maid, living, () -> true);
    }

    /** 现代任务独立提供当前目标许可，不复用原型的移动/主人/距离限制 */
    public static Result hurtForModern(EntityMaid maid, EntityLivingBase target, BooleanSupplier admitted,
        BooleanSupplier mayFinish) {
        if (maid.isUnifiedCombat()) return hurtForUnified(maid, target, admitted);
        if (maid.worldObj.isRemote || !admitted.getAsBoolean()) return new Result(false, false, false);
        if (MinecraftForge.EVENT_BUS.post(new MaidHurtTarget.Pre(maid, target))) return new Result(true, false, true);
        if (!admitted.getAsBoolean()) return new Result(false, false, false);
        // 致死攻击结算时执行原武器损耗，允许目标在此阶段离场
        boolean accepted = hurtNative(maid, target, mayFinish);
        MinecraftForge.EVENT_BUS.post(new MaidHurtTarget.Post(maid, target, accepted));
        return new Result(accepted, accepted, false);
    }

    /** 生产统一入口；开发F继续使用下方冻结的原近战链 */
    public static Result hurtForUnified(EntityMaid maid, EntityLivingBase target, BooleanSupplier admitted) {
        if (maid.worldObj == null || maid.worldObj.isRemote
            || !maid.isUnifiedCombat()
            || target == null
            || maid.getCombatHitReceipt()
                .blocksTransfer())
            return new Result(false, false, false);
        if (!maid.getCombatCadence()
            .ready()) return new Result(false, false, false);
        var quota = CombatWorldBudget
            .heartbeat(maid.worldObj, maid.getUniqueID(), maid.worldObj.getTotalWorldTime(), false);
        // 三次许可重验，每次最多actor和调用方各一条视线；真实武器准备单独计入ITEM
        if (!quota.spend(Work.FINAL_CHECK, 3) || !quota.spend(Work.RAY, 6) || !quota.spend(Work.ITEM))
            return new Result(false, false, false);
        var receipt = maid.getCombatHitReceipt();
        if (!receipt.beginPreparation()) return new Result(false, false, false);
        MaidWeaponStrike strike;
        try {
            if (!maid.getCombatCadence()
                .ready() || !admitted.getAsBoolean()) return new Result(false, false, false);
            strike = MaidWeaponCatalog.prepareMelee(maid, maid.getHeldItem());
            if (strike == null) return new Result(false, false, false);
            if (strike.normalizationChanged()) {
                maid.getEquipmentEpoch()
                    .invalidate();
                return new Result(false, false, false);
            }
            if (!strike.isCurrent() || !maid.getEquipmentEpoch()
                .bindAttackModifiers(strike.stack(), strike.profile(), quota)
                || !maid.getEquipmentEpoch()
                    .acknowledged(strike.stack()))
                return new Result(false, false, false);
        } catch (Exception | LinkageError | AssertionError failure) {
            maid.getEquipmentEpoch()
                .invalidate();
            report(maid, "prepare", failure);
            return new Result(false, false, false);
        } finally {
            receipt.endPreparation();
        }
        if (!receipt.begin(
            strike.stack(),
            maid.getEquipmentEpoch()
                .generation(),
            strike.profile().family.name() + "/" + strike.profile().dependency,
            target)) return new Result(false, false, false);
        boolean nativeEntered = false, post = false;
        try {
            double speed = MaidCombatAttributes.attackSpeed(maid);
            long cooldown = Double.isFinite(speed) && speed > 0 ? Math.max(1, (long) (20 / speed)) : 20;
            if (!maid.getCombatCadence()
                .commit(cooldown)) {
                receipt.rejected();
                return new Result(false, false, false);
            }
            // 节拍与 ATTEMPTING 优先于 Pre 事件设置，严格保障单次打击的时钟原子性
            if (MinecraftForge.EVENT_BUS.post(new MaidHurtTarget.Pre(maid, target))) {
                receipt.rejected();
                return new Result(true, false, true);
            }
            if (!admitted.getAsBoolean() || !strike.isCurrent()
                || !receipt.originalOwned(true)
                || !maid.getEquipmentEpoch()
                    .acknowledged(strike.stack())) {
                receipt.rejected();
                return new Result(false, false, false);
            }
            double physical = strike.physicalDamage(
                maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                    .getAttributeValue(),
                fallingCritical(maid));
            float damage = (float) (physical + EnchantmentHelper.getEnchantmentModifierLiving(maid, target));
            int knockback = EnchantmentHelper.getKnockbackModifier(maid, target);
            int fire = EnchantmentHelper.getFireAspectModifier(maid);
            var source = strike.damageSource(maid, target);
            if (!Float.isFinite(damage) || damage <= 0
                || !Double.isFinite(physical)
                || !admitted.getAsBoolean()
                || !receipt.originalOwned(true)
                || !strike.isCurrent()
                || !maid.getEquipmentEpoch()
                    .acknowledged(strike.stack())) {
                receipt.rejected();
                return new Result(false, false, false);
            }
            nativeEntered = true;
            boolean accepted = target.attackEntityFrom(source, damage);
            nativeEntered = false;
            post = true;
            if (!accepted) receipt.rejected();
            else {
                receipt.accepted();
                if (receipt.stage() == CombatHitReceipt.Stage.ACCEPTED && !receipt.originalOwned(true))
                    receipt.hold("original_stack_changed_during_native_damage");
                if (receipt.stage() == CombatHitReceipt.Stage.ACCEPTED) {
                    maid.setLastAttacker(target);
                    if (knockback > 0) {
                        target.addVelocity(
                            -MathHelper.sin(maid.rotationYaw * (float) Math.PI / 180.0F) * knockback * 0.5D,
                            0.1D,
                            MathHelper.cos(maid.rotationYaw * (float) Math.PI / 180.0F) * knockback * 0.5D);
                        maid.motionX *= 0.6D;
                        maid.motionZ *= 0.6D;
                    }
                    if (fire > 0) target.setFire(fire * 4);
                    EnchantmentHelper.func_151384_a(target, maid);
                    // 反伤死亡或任务取消不撤销本次事实；外部直接改/移原栈则保留未知后继
                    if (receipt.originalOwned(true)) EnchantmentHelper.func_151385_b(maid, target);
                    else receipt.hold("original_stack_changed_during_enchantment");
                }
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            if (nativeEntered) receipt.hold("native_damage_outcome_unknown");
            else if (receipt.stage() == CombatHitReceipt.Stage.ATTEMPTING) receipt.rejected();
            report(maid, "execute", failure);
        } finally {
            try {
                if (receipt.stage() == CombatHitReceipt.Stage.ACCEPTED) settle(maid, target, strike, receipt);
                if (post) try {
                    MinecraftForge.EVENT_BUS.post(new MaidHurtTarget.Post(maid, target, receipt.nativeAccepted()));
                } catch (Exception | LinkageError | AssertionError failure) {
                    report(maid, "post", failure);
                }
            } finally {
                receipt.closeCall();
            }
        }
        return new Result(
            receipt.nativeAccepted(),
            receipt.nativeAccepted(),
            false,
            receipt.stage() == CombatHitReceipt.Stage.HELD);
    }

    private static void settle(EntityMaid maid, EntityLivingBase target, MaidWeaponStrike strike,
        CombatHitReceipt receipt) {
        if (!receipt.beginSettlement()) return;
        try {
            strike.settleAcceptedHit(target, maid);
            if (!receipt.originalOwned(false)) {
                receipt.hold("original_stack_successor_unknown_after_settlement");
                return;
            }
            if (strike.stack().stackSize <= 0) maid.setCurrentItemOrArmor(0, null);
            receipt.settled();
            maid.getEquipmentEpoch()
                .invalidate();
        } catch (Exception | LinkageError | AssertionError failure) {
            receipt.hold("settlement_outcome_unknown");
            report(maid, "settle", failure);
        }
    }

    private static boolean fallingCritical(EntityMaid maid) {
        return maid.fallDistance > 0 && maid.motionY < 0
            && !maid.onGround
            && !maid.isOnLadder()
            && !maid.isInWater()
            && !maid.isPotionActive(Potion.blindness)
            && maid.ridingEntity == null;
    }

    private static void report(EntityMaid maid, String stage, Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        IntegrationGuard.report(Side.SERVER, "combat_melee/" + stage + "/" + maid.getUniqueID(), failure);
    }

    private static boolean hurtNative(EntityMaid maid, EntityLivingBase living, BooleanSupplier permitted) {
        float damage = (float) maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
            .getAttributeValue();
        damage += EnchantmentHelper.getEnchantmentModifierLiving(maid, living);
        if (!Float.isFinite(damage) || damage <= 0) return false;
        int knockback = EnchantmentHelper.getKnockbackModifier(maid, living);
        var weapon = maid.getHeldItem();
        if (!permitted.getAsBoolean()) return false;
        if (!living.attackEntityFrom(DamageSource.causeMobDamage(maid), damage)) return false;
        if (!permitted.getAsBoolean()) return true;
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
        if (!permitted.getAsBoolean()) return true;
        EnchantmentHelper.func_151385_b(maid, living);
        if (!permitted.getAsBoolean()) return true;
        if (weapon != null && weapon.stackSize > 0) {
            weapon.getItem()
                .hitEntity(weapon, living, maid);
            if (weapon.stackSize <= 0 && maid.getHeldItem() == weapon) maid.setCurrentItemOrArmor(0, null);
        }
        return true;
    }
}
