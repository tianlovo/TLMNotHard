package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState.Status;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

/** after-native开发组件：原look/move/follow两pass，无手工导航tick，不代表完整F或自然Brain */
public final class FollowCoreComponent implements MaidPolicyRuntime.AfterNavigationActions {

    private final EntityMaid maid;
    private final Rc2FollowCoreActor actor;
    private final FollowCoreLookDriver look = new FollowCoreLookDriver();
    private final FollowCoreMoveDriver move = new FollowCoreMoveDriver();
    private final MaidFollowOwnerTask follow = new MaidFollowOwnerTask(0.5F, 2);
    private FollowCoreLookDriver.State lookState = new FollowCoreLookDriver.State();
    private FollowMoveState moveState = new FollowMoveState();
    private boolean followRunning, ticking, hasTick;
    private long followEndTimestamp, lastWorldTick;
    private int lastEntityTick;

    public FollowCoreComponent(EntityMaid maid, Rc2FollowCoreActor.Contract contract) {
        this.maid = Objects.requireNonNull(maid, "maid");
        actor = new Rc2FollowCoreActor(maid, contract);
    }

    @Override
    public void tick(Token token) {
        if (ticking) throw new Rc2FollowCoreActor.OutsideContractException("component_reentry");
        if (maid.worldObj == null) throw new Rc2FollowCoreActor.OutsideContractException("world_missing");
        var runtime = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        if (hasTick && lastWorldTick == maid.worldObj.getTotalWorldTime() && lastEntityTick == maid.ticksExisted)
            throw new Rc2FollowCoreActor.OutsideContractException("duplicate_component_tick");
        ticking = true;
        try {
            actor.beginFrame(runtime, token);
            long time = actor.worldGameTime();
            lastWorldTick = time;
            lastEntityTick = maid.ticksExisted;
            hasTick = true;
            // Brain先尝试启动全部STOPPED行为：look0、move2、follow3
            if (lookState.getStatus() == FollowCoreLookDriver.Status.STOPPED) look.tryStart(actor, lookState, time);
            if (moveState.getStatus() == Status.STOPPED) move.tryStart(actor, moveState, time);
            if (!followRunning && actor.isWalkTargetRegistered() && follow.checkExtraStartConditions(actor)) {
                followRunning = true;
                followEndTimestamp = time + 60 + actor.nextWorldInt(1);
                follow.start(actor);
                if (actor.consumeFollowStop()) followRunning = false;
            }
            // 再按同样优先序tick运行行为；Follow默认canStillUse=false
            if (lookState.getStatus() == FollowCoreLookDriver.Status.RUNNING) look.tickOrStop(actor, lookState, time);
            if (moveState.getStatus() == Status.RUNNING) move.tickOrStop(actor, moveState, time);
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
            lookState = new FollowCoreLookDriver.State();
            moveState = new FollowMoveState();
            followRunning = false;
        }
    }

    public Rc2FollowCoreActor getActor() {
        return actor;
    }

    public FollowCoreLookDriver.State getLookState() {
        return lookState;
    }

    public FollowMoveState getMoveState() {
        return moveState;
    }

    public long getFollowEndTimestamp() {
        return followEndTimestamp;
    }
}
