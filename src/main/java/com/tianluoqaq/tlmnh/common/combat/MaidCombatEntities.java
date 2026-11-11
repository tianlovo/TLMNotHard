package com.tianluoqaq.tlmnh.common.combat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import com.tianluoqaq.tlmnh.compat.etfuturum.common.entity.EtFuturumEntities;

import cpw.mods.fml.common.registry.EntityRegistry;

/** 原实体类型查询的目标环境适配，识别实装EFR盔甲架，不加载客户端类 */
public final class MaidCombatEntities {

    private MaidCombatEntities() {}

    public static ResourceLocation id(Entity entity) {
        String nativeName = EntityList.getEntityString(entity);
        if (nativeName == null) return null;
        return typeId(entity.getClass(), nativeName);
    }

    private static ResourceLocation typeId(Class<? extends Entity> type, String nativeName) {
        var registration = EntityRegistry.instance()
            .lookupModSpawn(type, true);
        String name = registration == null ? nativeName
            : registration.getContainer()
                .getModId() + ":"
                + registration.getEntityName();
        return new ResourceLocation(name.toLowerCase(Locale.ENGLISH));
    }

    /** 只读取注册类型，不为编辑器构造模组实体；ID与实际选敌使用同一归一化 */
    @SuppressWarnings("unchecked")
    public static Map<ResourceLocation, String> registeredLivingTypes() {
        var result = new HashMap<ResourceLocation, String>();
        for (Object raw : EntityList.stringToClassMapping.entrySet()) {
            var entry = (Map.Entry<?, ?>) raw;
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Class<?>type)
                || !EntityLivingBase.class.isAssignableFrom(type)) continue;
            result.putIfAbsent(typeId((Class<? extends Entity>) type, name), name);
        }
        return result;
    }

    public static boolean isArmorStand(Entity entity) {
        return EtFuturumEntities.isArmorStand(entity);
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
