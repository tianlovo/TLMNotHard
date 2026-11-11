package com.tianluoqaq.tlmnh.common.ai.navigation;

import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.pathfinding.PathNavigate;

/** 实体初始化时替换原生导航与移动控制字段的边界 */
public interface EntityNavigationAccess {

    void tlmnh$setNavigator(PathNavigate navigator);

    void tlmnh$setMoveHelper(EntityMoveHelper helper);
}
