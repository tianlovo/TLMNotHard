package com.tianluoqaq.tlmnh.api.behavior;

import net.minecraft.nbt.NBTTagCompound;

/** 每只服务端女仆独立持有的有界行为模块实例；持久状态与瞬时运行资源分离 */
public interface MaidBehaviorModule {

    MaidBehaviorId id();

    MaidBehaviorStage stage();

    /** 单帧只读评估并提交候选提案，严禁在此阶段产生世界或库存写副作用 */
    void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink);

    /** 同一模块连续获胜但提案目标键变化时回调 */
    default void onTargetChanged(MaidBehaviorContext context, String previousTarget, String nextTarget) {}

    /** 从当前活跃会话切出时回调 */
    default void onDeselected(MaidBehaviorContext context, TransitionReason reason) {}

    /** 策略重置、卸载或退休时清理瞬时会话资源；不应清空待保存的持久状态字段 */
    default void cancel() {}

    /** 读取 TLMNH.BehaviorExt 下归属本模块的数据副本 */
    default void readPersistentData(NBTTagCompound moduleTag) {}

    /** 写出本模块需持久保存的数据；写出空复合标签表示不占用存档槽位 */
    default void writePersistentData(NBTTagCompound moduleTag) {}
}
