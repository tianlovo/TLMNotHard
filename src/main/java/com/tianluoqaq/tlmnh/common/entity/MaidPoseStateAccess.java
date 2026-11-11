package com.tianluoqaq.tlmnh.common.entity;

/** 只读暴露实体首次更新标志，使姿态尺寸计算无需直接导入 mixin 包 */
public interface MaidPoseStateAccess {

    boolean tlmnh$isFirstUpdate();
}
