package com.tianluoqaq.tlmnh.common.combat.weapon;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 只属于一次近战的真实武器绑定，命中凭证持有它并负责证明物品归属 */
public final class MaidWeaponStrike {

    public interface HitEffects {

        DamageSource damageSource(EntityMaid maid, EntityLivingBase target);

        void settleAcceptedHit(ItemStack original, EntityLivingBase target, EntityMaid maid);
    }

    private final ItemStack original, contents;
    private final MaidWeaponProfile profile;
    private final HitEffects effects;
    private final boolean normalizationChanged;
    private boolean settlementStarted;

    public MaidWeaponStrike(ItemStack original, MaidWeaponProfile profile, boolean normalizationChanged,
        HitEffects effects) {
        if (original == null || profile == null || !profile.melee() || effects == null)
            throw new IllegalArgumentException(WeaponCombatDiagnostics.MSG_MELEE_WEAPON_BINDING_REQUIRED);
        this.original = original;
        this.contents = original.copy();
        this.profile = profile;
        this.normalizationChanged = normalizationChanged;
        this.effects = effects;
    }

    public MaidWeaponProfile profile() {
        return profile;
    }

    public ItemStack stack() {
        return original;
    }

    /** true时本次准备只完成规范化，调用方必须等待后续原生装备阶段 */
    public boolean normalizationChanged() {
        return normalizationChanged;
    }

    /** 原生比较直接比较物品字段、数量、损伤和NBT，不调用Item查询接口 */
    public boolean isCurrent() {
        return !settlementStarted && ItemStack.areItemStacksEqual(original, contents);
    }

    /** 附魔分量由近战入口在此结果之后加一次 */
    public float physicalDamage(double equippedAttribute, boolean fallingCritical) {
        double physical = equippedAttribute + profile.physicalBonus;
        if (fallingCritical && physical > 0) physical *= profile.criticalMultiplier;
        return Double.isFinite(physical) && physical <= Float.MAX_VALUE ? (float) physical : Float.NaN;
    }

    public DamageSource damageSource(EntityMaid maid, EntityLivingBase target) {
        if (!isCurrent())
            throw new IllegalStateException(WeaponCombatDiagnostics.MSG_WEAPON_CHANGED_BEFORE_DAMAGE_SOURCE);
        return effects.damageSource(maid, target);
    }

    /** 开始后即不可重试，异常交由命中凭证保留待处理事实 */
    public void settleAcceptedHit(EntityLivingBase target, EntityMaid maid) {
        if (!isCurrent())
            throw new IllegalStateException(WeaponCombatDiagnostics.MSG_WEAPON_CHANGED_OR_SETTLEMENT_ALREADY);
        settlementStarted = true;
        effects.settleAcceptedHit(original, target, maid);
    }
}
