package com.tianluoqaq.tlmnh.common.combat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.attributes.BaseAttributeMap;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.RangedAttribute;

/** 原女仆攻击速度与Forge近战距离的实体属性，物品伤害仍由目标平台提供 */
public final class MaidCombatAttributes {

    public static final IAttribute ATTACK_SPEED = new RangedAttribute(
        MaidCombatIds.ID_GENERIC_ATTACKSPEED,
        MaidCombatTuning.CHUNK_SECTION_SHIFT,
        MaidCombatTuning.MAX_INITIAL_VALUE,
        MaidCombatTuning.COMBAT_ATTRIBUTES_RANGED_ATTRIBUTE_FOURTH_PARAM).setShouldWatch(true);
    public static final IAttribute ENTITY_REACH = new RangedAttribute(
        MaidCombatIds.ID_TLMNH_ENTITYREACH,
        MaidCombatTuning.TARGET_SIZE_THRESHOLD,
        MaidCombatTuning.MAX_INITIAL_VALUE,
        MaidCombatTuning.COMBAT_ATTRIBUTES_RANGED_ATTRIBUTE_FOURTH_PARAM).setShouldWatch(true);

    public static final IAttribute SHOOT_COOLDOWN = new RangedAttribute(
        MaidCombatIds.ID_TLMNH_MAIDSHOOTCOOLDOWN,
        MaidCombatTuning.TARGET_SIZE_THRESHOLD,
        MaidCombatTuning.MAX_INITIAL_VALUE,
        Integer.MAX_VALUE).setShouldWatch(true);
    public static final IAttribute USE_ITEM_SPEED = new RangedAttribute(
        MaidCombatIds.ID_TLMNH_MAIDUSEITEMSPEED,
        MaidCombatTuning.MAX_SINGLE_UNIT,
        Integer.MIN_VALUE,
        Integer.MAX_VALUE).setShouldWatch(true);

    /** fixed8a3 InitAttribute.MAID_HUNGER及YSMBinding.food_level，独立于旧MaidHunger存档字段 */
    public static final IAttribute MAID_HUNGER = new RangedAttribute(
        MaidCombatIds.ID_TLMNH_MAIDHUNGER,
        MaidCombatTuning.BOW_ATTACK_RETRY_AT,
        MaidCombatTuning.MAX_INITIAL_VALUE,
        Integer.MAX_VALUE).setShouldWatch(true);

    private MaidCombatAttributes() {}

    public static void register(BaseAttributeMap attributes) {
        registerIfAbsent(attributes, ATTACK_SPEED);
        registerIfAbsent(attributes, ENTITY_REACH);
        registerIfAbsent(attributes, SHOOT_COOLDOWN);
        registerIfAbsent(attributes, USE_ITEM_SPEED);
        registerIfAbsent(attributes, MAID_HUNGER);
    }

    private static void registerIfAbsent(BaseAttributeMap attributes, IAttribute attribute) {
        if (attributes.getAttributeInstanceByName(attribute.getAttributeUnlocalizedName()) == null)
            attributes.registerAttribute(attribute);
    }

    public static double attackSpeed(EntityLivingBase entity) {
        return entity.getAttributeMap()
            .getAttributeInstanceByName(ATTACK_SPEED.getAttributeUnlocalizedName())
            .getAttributeValue();
    }

    public static double entityReach(EntityLivingBase entity) {
        return entity.getAttributeMap()
            .getAttributeInstanceByName(ENTITY_REACH.getAttributeUnlocalizedName())
            .getAttributeValue();
    }
}
