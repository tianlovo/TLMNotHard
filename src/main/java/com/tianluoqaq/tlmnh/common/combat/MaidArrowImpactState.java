package com.tianluoqaq.tlmnh.common.combat;

/** 当前箭实际跳过的友方碰撞数；不写入存档或改变飞行物理 */
public interface MaidArrowImpactState {

    int tlmnh$ignoredImpacts();
}
