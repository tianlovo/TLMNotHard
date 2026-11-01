package com.tianluoqaq.tlmnh.common.ai.enhanced;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

/** 无活路线且头部出水时提供被动跳跃；由增强root调度，不运行F的Swim Behavior */
public final class EnhancedBuoyancyController {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private long eligibleFrames, requests;

    public EnhancedBuoyancyController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    public void tick() {
        if (!actor.canBrainMoving() || !actor.navigationIsDone()) return;
        var aquatic = maid.getAquaticController();
        if (aquatic.underWater()
            || !(aquatic.inWater() && aquatic.waterHeight() > aquatic.fluidJumpThreshold() || aquatic.inLava())
            || !actor.isCurrent()) return;
        eligibleFrames++;
        if (maid.getRNG()
            .nextFloat() < .8F && actor.isCurrent()) {
            aquatic.requestJump();
            requests++;
        }
    }

    public long eligibleFrames() {
        return eligibleFrames;
    }

    public long requests() {
        return requests;
    }
}
