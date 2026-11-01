/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/sensor/MaidNearestLivingEntitySensor.java, MIT
 * Adaptation: 1.7 entity query and guarded component memory instead of vanilla Brain
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.sensor;

import java.util.ArrayList;
import java.util.Comparator;

import net.minecraft.entity.EntityLivingBase;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;

public final class MaidNearestLivingEntitySensor {

    public void doTick(EntityMaid maid, MaidModernPerception memory) {
        var nearby = new ArrayList<EntityLivingBase>();
        for (Object candidate : maid.worldObj.getEntitiesWithinAABB(EntityLivingBase.class, memory.searchDimension())) {
            var entity = (EntityLivingBase) candidate;
            if (entity != maid && entity.isEntityAlive()) nearby.add(entity);
        }
        nearby.sort(Comparator.comparingDouble(maid::getDistanceSqToEntity));
        memory.setNearby(nearby);
    }
}
