package com.tianluoqaq.tlmnh.common.registry;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.registry.EntityRegistry;

/** 实体数字 ID 在模组内稳定，存档名称使用 tlmnh.maid */
public final class ModEntities {

    private ModEntities() {}

    public static void register(Object mod) {
        EntityRegistry.registerModEntity(EntityMaid.class, "maid", 0, mod, 80, 3, true);
        EntityRegistry.registerModEntity(
            com.tianluoqaq.tlmnh.common.combat.MaidGravityFreeArrow.class,
            "gravity_free_arrow",
            2,
            mod,
            80,
            20,
            true);
    }
}
