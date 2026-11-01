package com.tianluoqaq.tlmnh.common.ai.enhanced;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidClimbTask;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;

/** 增强攀爬只在当前自有活路线与实际梯子上运行，不借用F的Behavior调度 */
public final class EnhancedClimbController {

    private final ModernFollowCoreActor actor;
    private final MaidClimbTask action = new MaidClimbTask();
    private boolean running;

    public EnhancedClimbController(ModernFollowCoreActor actor) {
        this.actor = actor;
    }

    public void tick() {
        if (!actor.canBrainMoving() || !action.checkExtraStartConditions(actor)) {
            if (running) action.stop(actor);
            running = false;
            return;
        }
        var path = actor.climbPath();
        if (path == null || path.done()) {
            if (running) action.stop(actor);
            running = false;
            return;
        }
        if (!running) {
            running = true;
            action.start(actor);
        }
        if (actor.isCurrent()) action.tick(actor);
    }

    public void cancel() {
        running = false;
    }
}
