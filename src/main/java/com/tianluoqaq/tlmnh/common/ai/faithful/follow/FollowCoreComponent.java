package com.tianluoqaq.tlmnh.common.ai.faithful.follow;

import java.util.Objects;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveState.Status;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

/** after-native开发组件：原look/move/follow两pass，无手工导航tick，不代表完整F或自然Brain */
public final class FollowCoreComponent implements MaidPolicyRuntime.AfterNavigationActions {

    private final EntityMaid maid;
    private final DryPlatformFollowActor actor;
    private final FollowCoreLookDriver look = new FollowCoreLookDriver();
    private final FollowCoreMoveDriver move = new FollowCoreMoveDriver();
    private final MaidFollowOwnerTask follow = new MaidFollowOwnerTask(
        FaithfulFollowTuning.HALF_FACTOR_F,
        FaithfulFollowTuning.FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM);
    private FollowCoreLookDriver.State lookState = new FollowCoreLookDriver.State();
    private FollowMoveState moveState = new FollowMoveState();
    private boolean followRunning, ticking, hasTick;
    private long followEndTimestamp, lastWorldTick;
    private int lastEntityTick;

    public FollowCoreComponent(EntityMaid maid, DryPlatformFollowActor.Contract contract) {
        this.maid = Objects.requireNonNull(maid, FaithfulFollowIds.ID_MAID);
        actor = new DryPlatformFollowActor(maid, contract);
    }

    @Override
    public void tick(Token token) {
        if (ticking) throw new DryPlatformFollowActor.OutsideContractException(FaithfulFollowIds.ID_COMPONENT_REENTRY);
        if (maid.worldObj == null)
            throw new DryPlatformFollowActor.OutsideContractException(FaithfulFollowIds.ID_WORLD_MISSING);
        var runtime = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        if (hasTick && lastWorldTick == maid.worldObj.getTotalWorldTime() && lastEntityTick == maid.ticksExisted)
            throw new DryPlatformFollowActor.OutsideContractException(FaithfulFollowIds.ID_DUPLICATE_COMPONENT_TICK);
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
                followEndTimestamp = time + FaithfulFollowTuning.FOLLOW_CORE_FOLLOW_END_TIMESTAMP
                    + actor.nextWorldInt(FaithfulFollowTuning.NEXT_WORLD_INT_SINGLE_UNIT);
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

    public DryPlatformFollowActor getActor() {
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
