package com.tianluoqaq.tlmnh.common.ai.faithful;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 首版原生蛋糕偷吃的搜索/工作记忆与守恒事务边界 */
public interface MaidStealActor extends MaidLifeActor {

    MaidEdibleBlockAction edibleAction();

    void setEdibleAction(MaidEdibleBlockAction action);

    boolean isHomeModeEnable();

    Point restrictionCenter();

    Point scheduleFeet();

    float getRestrictRadius();

    MaidPathFindingBFS openWorkSearch();

    boolean workPointNearOwner(Point point);

    ItemStack firstCakeStack();

    boolean shouldStealCake(Point point);

    boolean shouldPlaceCake(Point point, ItemStack stack);

    boolean consumeCake(Point point);

    boolean placeCake(Point point);

    void rewardSteal();

    void swingMainHand();

    boolean walkMatchesWorkTarget();
}
