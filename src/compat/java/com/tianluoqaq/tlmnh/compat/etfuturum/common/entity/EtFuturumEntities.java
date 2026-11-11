package com.tianluoqaq.tlmnh.compat.etfuturum.common.entity;

import net.minecraft.entity.Entity;

public final class EtFuturumEntities {

    private EtFuturumEntities() {}

    public static boolean isArmorStand(Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
            .equals("ganymedes01.etfuturum.entities.EntityArmourStand")) return true;
        return false;
    }
}
