package com.tianluoqaq.tlmnh.common.ai.faithful;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

/** 跨行为重建保留的原攻击记忆，不携带执行租约、不写入NBT */
public final class MaidCombatMemory {

    private World world;
    private EntityLivingBase target;
    private Long cooldown;
    private int entityAge = Integer.MIN_VALUE;
    private long worldTime = Long.MIN_VALUE;

    /** 原Brain先hasExpired再tick，TTL=5在第六次后续Brain帧擦除；GUI/策略暂停不凭世界时间扣TTL */
    public void beginBrainFrame(World currentWorld, int age, long time) {
        if (world != currentWorld || age < entityAge) {
            target = null;
            cooldown = null;
            world = currentWorld;
            entityAge = Integer.MIN_VALUE;
            worldTime = Long.MIN_VALUE;
        }
        if (age == entityAge && time == worldTime) throw new IllegalStateException("Duplicate combat memory frame");
        entityAge = age;
        worldTime = time;
        if (cooldown != null) {
            if (cooldown <= 0) cooldown = null;
            else cooldown--;
        }
    }

    public EntityLivingBase target() {
        return target;
    }

    public void target(EntityLivingBase value) {
        target = value;
    }

    public boolean coolingDown() {
        return cooldown != null;
    }

    public void coolDown(long ticks) {
        cooldown = ticks;
    }

    public Long remaining() {
        return cooldown;
    }

    public void clear() {
        world = null;
        target = null;
        cooldown = null;
        entityAge = Integer.MIN_VALUE;
        worldTime = Long.MIN_VALUE;
    }
}
