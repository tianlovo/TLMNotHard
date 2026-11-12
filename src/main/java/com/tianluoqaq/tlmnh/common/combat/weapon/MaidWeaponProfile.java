package com.tianluoqaq.tlmnh.common.combat.weapon;

/** 武器观测的纯数值快照，不持有物品、实体、世界或外部执行器 */
public final class MaidWeaponProfile {

    public enum Kind {
        UNKNOWN,
        MELEE,
        BOW
    }

    public enum Family {
        UNKNOWN,
        VANILLA_SWORD,
        VANILLA_BOW,
        HEALING_AXE,
        GREGTECH_KNIFE,
        GREGTECH_BUTCHERY_KNIFE
    }

    public final Kind kind;
    public final Family family;
    public final String reason, dependency;
    /** 仅GT工具需要在已经同步的原生装备总属性上另加这部分伤害 */
    public final float physicalBonus, criticalMultiplier;
    /** 以女仆基础攻击属性估计，仅供候选比较，实际提交必须读取确认后的原生属性 */
    public final float estimatedMeleePhysical;
    /** 每击原生请求量，原版耐久附魔的随机结果只由真实hitEntity决定 */
    public final long durabilityCost, remainingDurability;
    public final int hurtResistanceMultiplier;
    public final int sharpness, smite, baneOfArthropods, knockback, fireAspect;
    public final int power, punch;
    public final boolean infinity, flame;

    public MaidWeaponProfile(Kind kind, Family family, String reason, String dependency, float physicalBonus,
        float criticalMultiplier, float estimatedMeleePhysical, long durabilityCost, long remainingDurability,
        int hurtResistanceMultiplier, int sharpness, int smite, int baneOfArthropods, int knockback, int fireAspect,
        int power, int punch, boolean infinity, boolean flame) {
        this.kind = kind;
        this.family = family;
        this.reason = reason;
        this.dependency = dependency;
        this.physicalBonus = physicalBonus;
        this.criticalMultiplier = criticalMultiplier;
        this.estimatedMeleePhysical = estimatedMeleePhysical;
        this.durabilityCost = durabilityCost;
        this.remainingDurability = remainingDurability;
        this.hurtResistanceMultiplier = hurtResistanceMultiplier;
        this.sharpness = sharpness;
        this.smite = smite;
        this.baneOfArthropods = baneOfArthropods;
        this.knockback = knockback;
        this.fireAspect = fireAspect;
        this.power = power;
        this.punch = punch;
        this.infinity = infinity;
        this.flame = flame;
    }

    public static MaidWeaponProfile unknown(String reason) {
        return new MaidWeaponProfile(
            Kind.UNKNOWN,
            Family.UNKNOWN,
            reason,
            "",
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
            false,
            false);
    }

    public boolean supported() {
        return kind != Kind.UNKNOWN;
    }

    public boolean melee() {
        return kind == Kind.MELEE;
    }

    public boolean bow() {
        return kind == Kind.BOW;
    }
}
