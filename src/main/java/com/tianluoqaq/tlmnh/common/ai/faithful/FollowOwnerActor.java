package com.tianluoqaq.tlmnh.common.ai.faithful;

/**
 * 原版跟随任务的窄适配边界，不包含 Brain 调度、路径 sink 或完整忠实策略
 * 所有读取均来自当前实体；适配层负责运行 token、已加载范围和原生副作用的所有权
 */
public interface FollowOwnerActor {

    /** 每次解析当前在线主人，可返回 null；检查阶段与启动阶段分别调用 */
    Owner getOwner();

    /** 实际世界对象，仅按引用身份比较，不使用维度编号或 equals */
    Object getWorldIdentity();

    /** 实际实体半径，保持原版 float 到 int 的截断，不在本边界取整或钳制 */
    float getRestrictRadius();

    boolean isGoingToBreath();

    boolean isHomeModeEnable();

    boolean canBrainMoving();

    /** 与原版 Entity.closerThan 一致：三维距离平方严格小于 distance 的平方 */
    boolean closerThan(Owner owner, double distance);

    /** 仅当 WALK_TARGET 是追踪此主人的 EntityTracker 时为 true */
    boolean ownerIsWalkTarget(Owner owner);

    /** 使用原生候选与碰撞规则；是否成功只由实际传送结果决定 */
    boolean teleportToOwner(Owner owner);

    void resetNavigation();

    void setGoingToBreath(boolean goingToBreath);

    void eraseWalkTarget();

    void eraseTargetPos();

    /** 建立指向实际 owner 的 LOOK 与 WALK 目标，保留速度和整格到达距离参数 */
    void setWalkAndLookTargetMemories(Owner owner, float speedModifier, int stopDistance);

    /** 只对应当前原版 Behavior.doStop，不表示取消全部策略或清除全部目标 */
    void stopFollowingTask();

    /** 包装一次解析得到的实际主人对象，不得用旧 UUID 的离线快照代替 */
    interface Owner {

        Object getWorldIdentity();

        boolean isSpectator();

        boolean isDeadOrDying();

        boolean isUnderWater();
    }
}
