/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/event/MaidFavorabilityLevelChangeEvent.java
 * Copyright (c) TartaricAcid contributors, MIT; see licenses/TouhouLittleMaid-MIT.txt
 * RC2 adaptation: native attributes/NBT/status particles; no baubles or excluded activities
 */
package com.github.tartaricacid.touhoulittlemaid.api.event;

import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 女仆好感度等级变化事件
 * <p>
 * 虽然女仆设定上是只能升级不会降级，但是仍然有创造模式道具可以强制降级。<br>
 * 所以也需要考虑上 oldLevel 和 newLevel 的大小
 */
public class MaidFavorabilityLevelChangeEvent extends LivingEvent {

    private final EntityMaid maid;
    private final int oldLevel;
    private final int newLevel;

    public MaidFavorabilityLevelChangeEvent(EntityMaid maid, int oldLevel, int newLevel) {
        super(maid);
        this.maid = maid;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }
}
