package com.tianluoqaq.tlmnh.common.combat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.attributes.BaseAttributeMap;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.RangedAttribute;

/** 原女仆攻击速度与Forge近战距离的实体属性，物品伤害仍由目标平台提供 */
public final class MaidCombatAttributes {

    public static final IAttribute ATTACK_SPEED = new RangedAttribute("generic.attackSpeed", 4, 0, 1024)
        .setShouldWatch(true);
    public static final IAttribute ENTITY_REACH = new RangedAttribute("tlmnh.entityReach", 2, 0, 1024)
        .setShouldWatch(true);

    private MaidCombatAttributes() {}

    public static void register(BaseAttributeMap attributes) {
        registerIfAbsent(attributes, ATTACK_SPEED);
        registerIfAbsent(attributes, ENTITY_REACH);
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
