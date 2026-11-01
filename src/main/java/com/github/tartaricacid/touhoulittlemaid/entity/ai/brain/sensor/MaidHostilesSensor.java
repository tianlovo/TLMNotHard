/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/sensor/MaidHostilesSensor.java, MIT
 * Adaptation: guarded component memory and the target version's vanilla creeper type
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.sensor;

import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;

public final class MaidHostilesSensor {

    public void doTick(EntityMaid maid, MaidModernPerception memory) {
        var visible = memory.visible();
        memory.setNearestHostile(
            visible == null ? null
                : visible.findAll(e -> e.getClass() == EntityCreeper.class && e.getDistanceSqToEntity(maid) < 64)
                    .stream()
                    .min(
                        (a, b) -> MathHelper
                            .floor_double(a.getDistanceSqToEntity(maid) - b.getDistanceSqToEntity(maid)))
                    .orElse(null));
    }
}
