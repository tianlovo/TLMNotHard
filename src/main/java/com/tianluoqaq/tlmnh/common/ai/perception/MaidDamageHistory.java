package com.tianluoqaq.tlmnh.common.ai.perception;

import net.minecraft.util.DamageSource;

/** 对应现代LivingEntity的临时lastDamageSource，策略重置不擦除，实体重读存档时清理 */
public final class MaidDamageHistory {

    private Object world;
    private long acceptedAt;
    private DamageSource source;

    public void record(Object world, long gameTime, DamageSource source) {
        this.world = world;
        acceptedAt = gameTime;
        this.source = source;
    }

    public DamageSource source(Object world, long gameTime) {
        long age = gameTime - acceptedAt;
        if (this.world != world || age < 0 || age > 40) clear();
        return source;
    }

    public void clear() {
        world = null;
        source = null;
    }
}
