package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedNavigationController.Outcome;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Path;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.WalkTarget;

/** 增强导航在清理本战斗节点前交还现有事实，不增加导航请求或通用行为协议 */
public interface CombatNavigationObserver {

    boolean isCombatNode(WalkTarget target);

    default boolean combatPathReproofRequired() {
        return false;
    }

    boolean combatNodeArrived(WalkTarget target, Path path);

    void combatNavigationOutcome(WalkTarget target, Path path, Outcome outcome, long retryAt);
}
