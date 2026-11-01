package com.tianluoqaq.tlmnh.common.combat;

import java.util.Locale;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.common.registry.EntityRegistry;

/** 原实体类型查询的目标环境适配，识别实装EFR盔甲架，不加载客户端类 */
public final class MaidCombatEntities {

    private MaidCombatEntities() {}

    public static ResourceLocation id(Entity entity) {
        String nativeName = EntityList.getEntityString(entity);
        if (nativeName == null) return null;
        var registration = EntityRegistry.instance()
            .lookupModSpawn(entity.getClass(), true);
        String name = registration == null ? nativeName
            : registration.getContainer()
                .getModId() + ":"
                + registration.getEntityName();
        return new ResourceLocation(name.toLowerCase(Locale.ENGLISH));
    }

    public static boolean isArmorStand(Entity entity) {
        return inherits(entity, "ganymedes01.etfuturum.entities.EntityArmourStand");
    }

    public static boolean isMaidItemEntity(Entity entity) {
        return inherits(entity, "com.github.tartaricacid.touhoulittlemaid.entity.item.AbstractEntityFromItem");
    }

    private static boolean inherits(Entity entity, String name) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
            .equals(name)) return true;
        return false;
    }
}
