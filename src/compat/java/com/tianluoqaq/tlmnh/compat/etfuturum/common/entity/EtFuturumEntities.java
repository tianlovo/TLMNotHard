package com.tianluoqaq.tlmnh.compat.etfuturum.common.entity;

import net.minecraft.entity.Entity;

public final class EtFuturumEntities {

    private EtFuturumEntities() {}

    public static boolean isArmorStand(Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
            .equals(EtFuturumEntityReflectionSpec.TARGET_CLASS_ENTITY_ARMOUR_STAND)) return true;
        return false;
    }
}
