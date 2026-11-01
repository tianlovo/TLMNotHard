package com.tianluoqaq.tlmnh.common.ai.perception;

/** 原版 lastDamageSource 的 40 tick 寿命；只保存当前世界中的受伤事实，不写入存档 */
public final class MaidHurtMemory {

    public static final int MAXIMUM_AGE = 40;
    private Object world;
    private long observedAt;

    public void record(Object world, long gameTime) {
        this.world = world;
        observedAt = gameTime;
    }

    public boolean isRecent(Object world, long gameTime) {
        long age = gameTime - observedAt;
        if (this.world != null && this.world == world && age >= 0 && age <= MAXIMUM_AGE) return true;
        clear();
        return false;
    }

    public void clear() {
        world = null;
        observedAt = 0;
    }
}
