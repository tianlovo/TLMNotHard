package com.tianluoqaq.tlmnh.api.behavior;

import java.util.UUID;

import com.tianluoqaq.tlmnh.api.behavior.MaidObservationView.BlockPoint;

/** 仅在获胜会话 execute 阶段开放的受控动作入口；每次调用均重验当前 Token 与活动域 */
public interface MaidActionAccess {

    enum NavigationOutcome {
        IDLE,
        ARRIVED,
        RUNNING,
        PARTIAL_PATH,
        WAITING_BUDGET,
        NOT_NAVIGABLE,
        UNREACHABLE,
        COOLDOWN,
        RECOVERY_WAIT
    }

    boolean walkToBlock(int x, int y, int z, float speed, int closeEnough);

    default boolean walkToBlock(BlockPoint point, float speed, int closeEnough) {
        return walkToBlock(point.x, point.y, point.z, speed, closeEnough);
    }

    boolean walkToEntity(UUID uniqueId, int entityId, float speed, int closeEnough);

    void stopWalk();

    void lookAt(double x, double y, double z);

    boolean lookAtEntity(UUID uniqueId, int entityId);

    void clearLook();

    NavigationOutcome lastNavigationOutcome();

    /** 仅允许在准入战斗槽位范围内切换已准入武器，非法槽位、非准入物品或越权状态拒绝 */
    boolean selectHotbarOrInventoryWeapon(int maidInventorySlot);

    /** 请求对同维已注册合法敌对目标发起一次受节拍约束的近战攻击 */
    boolean requestMeleeAttack(UUID targetUniqueId, int targetEntityId);

    /** 请求启动或维持副手盾牌格挡，具体许可由平台盾牌控制器裁决 */
    boolean requestShieldGuard();

    void stopShieldGuard();
}
