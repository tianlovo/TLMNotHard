/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidPickupEntitiesTask.java, MIT
 * Adaptation: guarded modern actor/typed memories; real task-local BFS and dynamic entity targets
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public final class MaidPickupEntitiesTask {

    private final float speedModifier;

    public MaidPickupEntitiesTask(float speedModifier) {
        this.speedModifier = speedModifier;
    }

    public boolean checkExtraStartConditions(EntityMaid maid, ModernFollowCoreActor actor) {
        return maid.isTamed() && actor.canBrainMoving() && maid.isPickup();
    }

    public void start(EntityMaid maid, MaidModernPerception memory, ModernFollowCoreActor actor) {
        var entities = memory.pickupEntities();
        try (var map = actor.openPickupSearch()) {
            for (var entity : entities) {
                var pos = new Point(
                    MathHelper.floor_double(entity.posX),
                    MathHelper.floor_double(entity.posY),
                    MathHelper.floor_double(entity.posZ));
                if (maid.getHomeArea()
                    .contains(pos.x, pos.y, pos.z) && entity.isEntityAlive()
                    && !entity.isInWater()
                    && map.canPathReach(pos)) {
                    actor.setEntityWalkAndLook(entity, speedModifier, 0);
                    break;
                }
            }
        }
    }
}
