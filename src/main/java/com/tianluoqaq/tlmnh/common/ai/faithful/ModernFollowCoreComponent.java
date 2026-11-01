package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirStopTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidClimbTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

/** 真实现代导航上的Follow/Move/Look核心，尚不包含完整Brain活动/感知/工作任务 */
public final class ModernFollowCoreComponent implements MaidPolicyRuntime.AfterNavigationActions {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final FollowCoreLookDriver look = new FollowCoreLookDriver();
    private final FollowCoreMoveDriver move = new FollowCoreMoveDriver();
    private final MaidFollowOwnerTask follow = new MaidFollowOwnerTask(.5F, 2);
    private final MaidBreathAirTask breath = new MaidBreathAirTask();
    private final MaidBreathAirStopTask breathStop = new MaidBreathAirStopTask();
    private final MaidClimbTask climb = new MaidClimbTask();
    private boolean climbRunning;
    private long climbDeadline;
    private FollowCoreLookDriver.State lookState = new FollowCoreLookDriver.State();
    private FollowMoveState moveState = new FollowMoveState();
    private boolean followRunning, ticking, hasTick;
    private long lastWorldTick;
    private int lastEntityTick;

    public ModernFollowCoreComponent(EntityMaid maid) {
        this.maid = java.util.Objects.requireNonNull(maid, "maid");
        actor = new ModernFollowCoreActor(maid);
        perception = new MaidModernPerception(maid, actor::isCurrent);
    }

    @Override
    public void tick(Token token) {
        if (ticking
            || hasTick && lastWorldTick == maid.worldObj.getTotalWorldTime() && lastEntityTick == maid.ticksExisted)
            throw new IllegalStateException("Duplicate or reentrant modern core tick");
        ticking = true;
        try {
            actor.beginFrame(
                maid.getBrainCoordinator()
                    .peekPolicyRuntime(),
                token);
            long time = actor.worldGameTime();
            lastWorldTick = time;
            lastEntityTick = maid.ticksExisted;
            hasTick = true;
            perception.tick();
            if (!actor.canBrainMoving()) {
                actor.stopNavigation();
                actor.eraseWalkTarget();
                actor.setPathMemory(null);
                moveState = new FollowMoveState();
            }
            // 原Brain先启动全部STOPPED行为，再按look0、move2、follow3运行
            if (!climbRunning && climb.checkExtraStartConditions(actor)) {
                climbRunning = true;
                climbDeadline = time + 60 + actor.nextWorldInt(1);
                climb.start(actor);
            }
            if (breath.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                breath.start(actor);
            }
            if (breathStop.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                breathStop.start(actor);
            }
            if (lookState.getStatus() == FollowCoreLookDriver.Status.STOPPED) look.tryStart(actor, lookState, time);
            if (moveState.getStatus() == FollowMoveState.Status.STOPPED) move.tryStart(actor, moveState, time);
            if (!followRunning && actor.isWalkTargetRegistered() && follow.checkExtraStartConditions(actor)) {
                followRunning = true;
                actor.nextWorldInt(1); // 默认Behavior 60 + nextInt(1)，本任务canStillUse=false
                follow.start(actor);
                if (actor.consumeFollowStop()) followRunning = false;
            }
            if (climbRunning) {
                if (time > climbDeadline || !climb.canStillUse(actor)) {
                    climbRunning = false;
                    climb.stop(actor);
                } else climb.tick(actor);
            }
            if (lookState.getStatus() == FollowCoreLookDriver.Status.RUNNING) look.tickOrStop(actor, lookState, time);
            if (moveState.getStatus() == FollowMoveState.Status.RUNNING) move.tickOrStop(actor, moveState, time);
            if (followRunning) followRunning = false;
        } finally {
            ticking = false;
        }
    }

    @Override
    public void cancel() {
        try {
            actor.cancelOwnedResources();
        } finally {
            perception.clear();
            lookState = new FollowCoreLookDriver.State();
            moveState = new FollowMoveState();
            followRunning = false;
            climbRunning = false;
        }
    }

    public ModernFollowCoreActor getActor() {
        return actor;
    }

    public MaidModernPerception getPerception() {
        return perception;
    }

    public FollowCoreLookDriver.State getLookState() {
        return lookState;
    }

    public FollowMoveState getMoveState() {
        return moveState;
    }
}
