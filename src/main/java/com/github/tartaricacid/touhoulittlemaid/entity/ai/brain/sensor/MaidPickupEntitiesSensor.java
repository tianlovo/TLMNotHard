/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/sensor/MaidPickupEntitiesSensor.java, MIT
 * Adaptation: native entity query and guarded component memory; outer Sensor cadence remains 30 ticks
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.sensor;

import java.util.ArrayList;
import java.util.Comparator;

import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;

public final class MaidPickupEntitiesSensor {

    public void doTick(EntityMaid maid, MaidModernPerception memory) {
        if (!maid.isTamed()) return;
        var entities = new ArrayList<Entity>();
        for (Object value : maid.worldObj.getEntitiesWithinAABB(Entity.class, memory.pickupSearchDimension())) {
            var entity = (Entity) value;
            if (entity.isEntityAlive()) entities.add(entity);
        }
        entities.sort(Comparator.comparingDouble(maid::getDistanceSqToEntity));
        float radius = maid.getHomeArea()
            .restrictionRadius();
        var accepted = new ArrayList<Entity>();
        for (var entity : entities) {
            if (maid.canPickup(entity, true) && maid.getDistanceSqToEntity(entity) < (radius + 1) * (radius + 1)
                && maid.getHomeArea()
                    .withinRestriction(
                        MathHelper.floor_double(entity.posX),
                        MathHelper.floor_double(entity.posY),
                        MathHelper.floor_double(entity.posZ))
                && maid.canEntityBeSeen(entity)) accepted.add(entity);
        }
        memory.setPickupEntities(accepted);
    }
}
