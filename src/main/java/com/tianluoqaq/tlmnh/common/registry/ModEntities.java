package com.tianluoqaq.tlmnh.common.registry;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.ballistics.MaidGravityFreeArrow;

import cpw.mods.fml.common.registry.EntityRegistry;

/** 实体数字 ID 在模组内稳定，存档名称使用 tlmnh.maid */
public final class ModEntities {

    private ModEntities() {}

    public static void register(Object mod) {
        EntityRegistry.registerModEntity(
            EntityMaid.class,
            ContentRegistryIds.ID_MAID,
            ContentRegistryTuning.REGISTER_MOD_ENTITY_INITIAL_VALUE,
            mod,
            ContentRegistryTuning.RESURRECTION_CONTENT_REGISTER_MOD_ENTITY_FIFTH_PARAM,
            ContentRegistryTuning.MOD_ENTITIES_REGISTER_MOD_ENTITY_SIXTH_PARAM,
            true);
        EntityRegistry.registerModEntity(
            MaidGravityFreeArrow.class,
            ContentRegistryIds.ID_GRAVITY_FREE_ARROW,
            ContentRegistryTuning.MOD_ENTITIES_REGISTER_MOD_ENTITY_THIRD_PARAM,
            mod,
            ContentRegistryTuning.RESURRECTION_CONTENT_REGISTER_MOD_ENTITY_FIFTH_PARAM,
            ContentRegistryTuning.MOD_ENTITIES_REGISTER_MOD_ENTITY_SIXTH_PARAM_2,
            true);
    }
}
