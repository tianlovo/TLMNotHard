package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ride.MaidRideBegTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidAwaitTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBedTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBegTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirStopTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidClearHurtTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidClearSleepTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidClimbTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerVehicleTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidHealSelfTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidInteractWithDoor;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidPanicTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidPickupEntitiesTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidRunAwayTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidRunOne;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSnowballTargetTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStartSnowballAttacking;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleMoveBlockTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleUseTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUpdateActivityFromSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

/** 原Brain首版保留活动的开发组件；完整忠实模式的原版对照准入仍待验收 */
public final class ModernFollowCoreComponent implements MaidPolicyRuntime.AfterNavigationActions {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final MaidActivityState activities;
    private final FollowCoreLookDriver look = new FollowCoreLookDriver();
    private final FollowCoreMoveDriver move = new FollowCoreMoveDriver();
    private final MaidFollowOwnerTask follow = new MaidFollowOwnerTask(.5F, 2);
    private final MaidFollowOwnerVehicleTask vehicle = new MaidFollowOwnerVehicleTask(.5F, 2);
    private final MaidHealSelfTask heal = new MaidHealSelfTask();
    private final MaidWorkMealTask workMeal = new MaidWorkMealTask(), rideWorkMeal = new MaidWorkMealTask();
    private final MaidBreathAirTask breath = new MaidBreathAirTask();
    private final MaidBreathAirStopTask breathStop = new MaidBreathAirStopTask();
    private final MaidSwimJumpTask swimJump = new MaidSwimJumpTask(.8F);
    private final MaidClimbTask climb = new MaidClimbTask();
    private final MaidPickupEntitiesTask pickup = new MaidPickupEntitiesTask(.6F);
    private final MaidPanicTask panic = new MaidPanicTask();
    private final MaidAwaitTask await = new MaidAwaitTask();
    private final MaidClearHurtTask clearHurt = new MaidClearHurtTask();
    private final MaidRunAwayTask runAway = new MaidRunAwayTask(.7F, false);
    private final MaidUpdateActivityFromSchedule schedule = new MaidUpdateActivityFromSchedule();
    private final MaidClearSleepTask clearSleep = new MaidClearSleepTask();
    private final MaidModernCombat combat = new MaidModernCombat();
    private final MaidAbsentContentChecks absentContent = new MaidAbsentContentChecks();
    private MaidInteractWithDoor doors = new MaidInteractWithDoor();
    private MaidModernRanged rangedWork = new MaidModernRanged(false), rangedRide = new MaidModernRanged(true);
    private MaidRunOne idleRandom = new MaidRunOne(true), workRandom = new MaidRunOne(true),
        restRandom = new MaidRunOne(true), rideWorkRandom = new MaidRunOne(false), rideIdleLook = new MaidRunOne(false);
    private final MaidStartSnowballAttacking snowFind = new MaidStartSnowballAttacking();
    private MaidSnowballTargetTask snowThrow = new MaidSnowballTargetTask(50);
    private boolean snowRunning;
    private long snowDeadline;
    private boolean refreshing;
    private long refreshes;
    private final MaidBedTask bed = new MaidBedTask(.6F, 2);
    private final MaidBegTask idleBeg = new MaidBegTask(), workBeg = new MaidBegTask();
    private final MaidRideBegTask rideIdleBeg = new MaidRideBegTask(), rideWorkBeg = new MaidRideBegTask();
    private boolean idleBegRunning, workBegRunning, rideIdleBegRunning, rideWorkBegRunning;
    private final MaidStealEdibleMoveBlockTask idleStealMove = new MaidStealEdibleMoveBlockTask(.6F),
        workStealMove = new MaidStealEdibleMoveBlockTask(.6F);
    private final MaidStealEdibleUseTask idleStealUse = new MaidStealEdibleUseTask(2),
        workStealUse = new MaidStealEdibleUseTask(2);
    private final com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos schedulePos = new com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos();
    private final java.util.Map<String, MaidActivityState.Activity> priority99 = new java.util.HashMap<>();
    private final java.util.Map<String, MaidActivityState.Activity> priority5 = new java.util.HashMap<>();
    private final java.util.Map<String, MaidActivityState.Activity> priority20 = new java.util.HashMap<>();
    private boolean climbRunning;
    private long climbDeadline;
    private FollowCoreLookDriver.State lookState = new FollowCoreLookDriver.State();
    private FollowMoveState moveState = new FollowMoveState();
    private boolean followRunning, vehicleRunning, ticking, hasTick;
    private long lastWorldTick;
    private int lastEntityTick;

    public ModernFollowCoreComponent(EntityMaid maid) {
        this.maid = java.util.Objects.requireNonNull(maid, "maid");
        actor = new ModernFollowCoreActor(maid);
        perception = new MaidModernPerception(maid, actor::isCurrent);
        actor.bindPerception(perception);
        activities = new MaidActivityState(maid, actor, perception);
        // 原Activity.hashCode为name.hashCode；保持原HashMap组顺序与注册顺序
        priority99.put("core", null);
        priority99.put("tlm_ride_idle", MaidActivityState.Activity.RIDE_IDLE);
        priority99.put("tlm_ride_work", MaidActivityState.Activity.RIDE_WORK);
        priority99.put("tlm_ride_rest", MaidActivityState.Activity.RIDE_REST);
        priority99.put("idle", MaidActivityState.Activity.IDLE);
        priority99.put("work", MaidActivityState.Activity.WORK);
        priority99.put("rest", MaidActivityState.Activity.REST);
        priority5.put("tlm_ride_work", MaidActivityState.Activity.RIDE_WORK);
        priority5.put("tlm_ride_idle", MaidActivityState.Activity.RIDE_IDLE);
        priority5.put("idle", MaidActivityState.Activity.IDLE);
        priority5.put("work", MaidActivityState.Activity.WORK);
        priority5.put("rest", MaidActivityState.Activity.REST);
        priority5.put("panic", MaidActivityState.Activity.PANIC);
        priority20.put("tlm_ride_work", MaidActivityState.Activity.RIDE_WORK);
        priority20.put("idle", MaidActivityState.Activity.IDLE);
        priority20.put("work", MaidActivityState.Activity.WORK);
        priority20.put("rest", MaidActivityState.Activity.REST);
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
            activities.initialize();
            actor.maintainSleep();
            if (!actor.canBrainMoving()) {
                actor.stopNavigation();
                actor.eraseWalkTarget();
                actor.setPathMemory(null);
                moveState = new FollowMoveState();
            }
            // 原Brain先启动全部STOPPED行为，再按look0、move2、follow3运行
            swimJump.tryStart(maid.getAquaticController(), time);
            if (!climbRunning && climb.checkExtraStartConditions(actor)) {
                climbRunning = true;
                climbDeadline = time + 60 + actor.nextWorldInt(1);
                climb.start(actor);
            }
            if (breath.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                breath.start(actor);
                if (!actor.isCurrent()) return;
            }
            if (breathStop.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                breathStop.start(actor);
            }
            if (lookState.getStatus() == FollowCoreLookDriver.Status.STOPPED) look.tryStart(actor, lookState, time);
            actor.nextWorldInt(1);
            panic.start(activities);
            actor.nextWorldInt(1);
            await.start(actor);
            doors.trigger(actor);
            if (!actor.isCurrent()) return;
            if (moveState.getStatus() == FollowMoveState.Status.STOPPED) move.tryStart(actor, moveState, time);
            if (!followRunning && actor.isWalkTargetRegistered() && follow.checkExtraStartConditions(actor)) {
                followRunning = true;
                actor.nextWorldInt(1); // 默认Behavior 60 + nextInt(1)，本任务canStillUse=false
                follow.start(actor);
                if (actor.consumeFollowStop()) followRunning = false;
            }
            if (vehicle.checkExtraStartConditions(actor)) {
                vehicleRunning = true;
                actor.nextWorldInt(1);
                vehicle.start(actor);
                if (!actor.isCurrent()) return;
            }
            if (heal.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                heal.start(actor);
                if (!actor.isCurrent()) return;
            }
            // 原Brain只在活动组入口检查active；ClearHurt切换活动后同组后项仍会继续尝试启动
            if (activities.active() == MaidActivityState.Activity.RIDE_IDLE && !rideIdleBegRunning
                && rideIdleBeg.checkExtraStartConditions(actor)) {
                rideIdleBegRunning = true;
                actor.nextWorldInt(1);
            }
            for (var group : priority5.values()) {
                if (activities.active() != group) continue;
                if (group == MaidActivityState.Activity.IDLE) {
                    if (!idleBegRunning && idleBeg.checkExtraStartConditions(actor)) {
                        idleBegRunning = true;
                        actor.nextWorldInt(1);
                    }
                } else if (group == MaidActivityState.Activity.RIDE_IDLE) {
                    absentContent.visitHomeMeal(actor::nextWorldInt);
                } else if (group == MaidActivityState.Activity.REST) {
                    if (!actor.hasLifeWalkTarget() && actor.workTarget() == null
                        && bed.checkExtraStartConditions(actor)) {
                        actor.nextWorldInt(1);
                        bed.start(actor);
                        if (!actor.isCurrent()) return;
                    }
                } else if (group == MaidActivityState.Activity.WORK || group == MaidActivityState.Activity.RIDE_WORK) {
                    if (maid.getTask() == com.tianluoqaq.tlmnh.common.entity.MaidTask.ATTACK) {
                        combat.startTasks(actor, group == MaidActivityState.Activity.RIDE_WORK);
                        if (!actor.isCurrent()) return;
                    } else if (maid.getTask() == com.tianluoqaq.tlmnh.common.entity.MaidTask.RANGED_ATTACK) {
                        (group == MaidActivityState.Activity.RIDE_WORK ? rangedRide : rangedWork)
                            .startTasks(actor, actor.hasVisibleLivingMemory());
                        if (!actor.isCurrent()) return;
                    }
                } else {
                    actor.nextWorldInt(1);
                    clearHurt.start(activities);
                    var danger = actor.dangerPosition();
                    if (runAway.checkExtraStartConditions(actor, danger)) {
                        actor.nextWorldInt(1);
                        runAway.start(actor, actor.dangerPosition());
                    }
                }
            }
            if (activities.active() == MaidActivityState.Activity.RIDE_IDLE) rideIdleLook.tryStart(actor, true);
            if (activities.active() == MaidActivityState.Activity.IDLE) absentContent
                .visitFindHomeMeal(!actor.hasLifeWalkTarget() && actor.workTarget() == null, actor::nextWorldInt);
            if (activities.active() == MaidActivityState.Activity.WORK
                && maid.getTask() == com.tianluoqaq.tlmnh.common.entity.MaidTask.IDLE
                && actor.attackTarget() == null
                && snowFind.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                snowFind.start(actor);
            }
            if (activities.active() == MaidActivityState.Activity.WORK && !workBegRunning
                && workBeg.checkExtraStartConditions(actor)) {
                workBegRunning = true;
                actor.nextWorldInt(1);
            }
            if (activities.active() == MaidActivityState.Activity.RIDE_WORK && !rideWorkBegRunning
                && rideWorkBeg.checkExtraStartConditions(actor)) {
                rideWorkBegRunning = true;
                actor.nextWorldInt(1);
            }
            if (activities.active() == MaidActivityState.Activity.WORK
                && maid.getTask() == com.tianluoqaq.tlmnh.common.entity.MaidTask.IDLE
                && !snowRunning
                && snowThrow.checkExtraStartConditions(actor)) {
                snowRunning = true;
                snowDeadline = time + 1200 + actor.nextWorldInt(1);
                snowThrow.start(actor);
                if (!actor.isCurrent()) return;
            }
            if (activities.active() == MaidActivityState.Activity.IDLE)
                absentContent.visitJoy(!actor.hasLifeWalkTarget() && actor.workTarget() == null, actor::nextWorldInt);
            var meal = activities.active() == MaidActivityState.Activity.WORK ? workMeal
                : activities.active() == MaidActivityState.Activity.RIDE_WORK ? rideWorkMeal : null;
            if (meal != null && meal.checkExtraStartConditions(actor)) {
                actor.nextWorldInt(1);
                meal.start(actor);
                if (!actor.isCurrent()) return;
            }
            var stealMove = activities.active() == MaidActivityState.Activity.IDLE ? idleStealMove
                : activities.active() == MaidActivityState.Activity.WORK ? workStealMove : null;
            if (stealMove != null) {
                if (!actor.hasLifeWalkTarget() && actor.workTarget() == null
                    && stealMove.checkExtraStartConditions(actor)) {
                    actor.nextWorldInt(1);
                    stealMove.start(actor);
                    if (!actor.isCurrent()) return;
                }
                var use = stealMove == idleStealMove ? idleStealUse : workStealUse;
                if (actor.workTarget() != null && actor.edibleAction() != null
                    && use.checkExtraStartConditions(actor)) {
                    actor.nextWorldInt(1);
                    use.start(actor);
                    if (!actor.isCurrent()) return;
                }
            }
            if (!perception.pickupEntities()
                .isEmpty() && actor.getWalkTarget() == null && pickup.checkExtraStartConditions(maid, actor)) {
                actor.nextWorldInt(1);
                pickup.start(maid, perception, actor);
            }
            for (var group : priority20.values()) {
                if (activities.active() == group) randomFor(group).tryStart(
                    actor,
                    group == MaidActivityState.Activity.REST || group == MaidActivityState.Activity.RIDE_WORK
                        || !actor.isGoingToBreath());
            }
            for (var entry : priority99.entrySet()) {
                if (entry.getValue() == null) {
                    actor.nextWorldInt(1);
                    clearSleep.start(activities, actor);
                } else if (activities.active() == entry.getValue()) {
                    actor.nextWorldInt(1);
                    schedule.start(activities);
                }
            }
            swimJump.tickOrStop(maid.getAquaticController(), time);
            if (climbRunning) {
                if (time > climbDeadline || !climb.canStillUse(actor)) {
                    climbRunning = false;
                    climb.stop(actor);
                } else climb.tick(actor);
            }
            if (lookState.getStatus() == FollowCoreLookDriver.Status.RUNNING) look.tickOrStop(actor, lookState, time);
            if (moveState.getStatus() == FollowMoveState.Status.RUNNING) move.tickOrStop(actor, moveState, time);
            // 原Brain的已运行行为跨活动保留，直到自身canStillUse失败；乞食不超时
            if (rideIdleBegRunning) {
                if (rideIdleBeg.canStillUse(actor)) rideIdleBeg.tick(actor);
                else {
                    rideIdleBegRunning = false;
                    rideIdleBeg.stop(actor);
                }
            }
            combat.runShield(actor);
            rangedWork.runTasks(actor);
            if (!actor.isCurrent()) return;
            rangedRide.runTasks(actor);
            if (!actor.isCurrent()) return;
            if (idleBegRunning) {
                if (idleBeg.canStillUse(actor)) idleBeg.tick(actor);
                else {
                    idleBegRunning = false;
                    idleBeg.stop(actor);
                }
            }
            rideIdleLook.tickOrStop(actor);
            if (workBegRunning) {
                if (workBeg.canStillUse(actor)) workBeg.tick(actor);
                else {
                    workBegRunning = false;
                    workBeg.stop(actor);
                }
            }
            if (rideWorkBegRunning) {
                if (rideWorkBeg.canStillUse(actor)) rideWorkBeg.tick(actor);
                else {
                    rideWorkBegRunning = false;
                    rideWorkBeg.stop(actor);
                }
            }
            if (snowRunning) {
                if (time > snowDeadline || !snowThrow.canStillUse(actor)) {
                    snowRunning = false;
                    snowThrow.stop(actor);
                } else snowThrow.tick(actor);
                if (!actor.isCurrent()) return;
            }
            for (var group : priority20.values()) randomFor(group).tickOrStop(actor);
            if (followRunning) followRunning = false;
            if (vehicleRunning) {
                vehicle.stop();
                vehicleRunning = false;
            }
        } finally {
            ticking = false;
        }
    }

    /** 对应stopAll + copyWithoutBehaviors；停止副作用决定擦哪些记忆，其余对象原样保留 */
    public void refreshBrain(MaidPolicyRuntime runtime, Token token) {
        if (refreshing) throw new IllegalStateException("Reentrant modern Brain refresh");
        refreshing = true;
        try {
            actor.beginEntityFrame(runtime, token);
            long time = actor.worldGameTime();
            if (climbRunning) climb.stop(actor);
            if (lookState.getStatus() == FollowCoreLookDriver.Status.RUNNING) look.doStop(actor, lookState, time);
            if (moveState.getStatus() == FollowMoveState.Status.RUNNING) move.doStop(actor, moveState, time);
            if (idleBegRunning) idleBeg.stop(actor);
            if (workBegRunning) workBeg.stop(actor);
            if (rideIdleBegRunning) rideIdleBeg.stop(actor);
            if (rideWorkBegRunning) rideWorkBeg.stop(actor);
            combat.stop(actor);
            rangedWork.stop(actor);
            rangedRide.stop(actor);
            if (snowRunning) snowThrow.stop(actor);
            if (vehicleRunning) vehicle.stop();
            swimJump.cancel();
            maid.getAquaticController()
                .clearPendingJump();
            heal.reset();
            absentContent.reset();
            workMeal.reset();
            rideWorkMeal.reset();
            bed.reset();
            idleStealMove.reset();
            workStealMove.reset();
            idleRandom = new MaidRunOne(true);
            workRandom = new MaidRunOne(true);
            restRandom = new MaidRunOne(true);
            rideWorkRandom = new MaidRunOne(false);
            rideIdleLook = new MaidRunOne(false);
            rangedWork = new MaidModernRanged(false);
            rangedRide = new MaidModernRanged(true);
            snowThrow = new MaidSnowballTargetTask(50);
            snowRunning = climbRunning = followRunning = vehicleRunning = false;
            idleBegRunning = workBegRunning = rideIdleBegRunning = rideWorkBegRunning = false;
            lookState = new FollowCoreLookDriver.State();
            moveState = new FollowMoveState();
            doors = new MaidInteractWithDoor();
            perception.refreshSensors();
            activities.reset();
            activities.initialize();
            refreshes++;
            actor.invalidateBehaviorViews();
        } finally {
            refreshing = false;
        }
    }

    public long refreshes() {
        return refreshes;
    }

    @Override
    public void cancel() {
        try {
            actor.cancelOwnedResources();
        } finally {
            doors = new MaidInteractWithDoor();
            perception.clear();
            swimJump.cancel();
            maid.getAquaticController()
                .clearPendingJump();
            activities.reset();
            heal.reset();
            absentContent.reset();
            workMeal.reset();
            rideWorkMeal.reset();
            bed.reset();
            idleStealMove.reset();
            workStealMove.reset();
            for (var group : priority20.values()) randomFor(group).cancel();
            rideIdleLook.cancel();
            snowRunning = false;
            snowThrow.cancelUseState();
            rangedWork.reset();
            rangedRide.reset();
            idleBegRunning = workBegRunning = rideIdleBegRunning = rideWorkBegRunning = false;
            lookState = new FollowCoreLookDriver.State();
            moveState = new FollowMoveState();
            followRunning = false;
            vehicleRunning = false;
            vehicle.stop();
            climbRunning = false;
        }
    }

    @Override
    public void afterLivingUpdate(Token token) {
        actor.afterLivingFrame(token);
        schedulePos.tick(actor);
    }

    public ModernFollowCoreActor getActor() {
        return actor;
    }

    private MaidRunOne randomFor(MaidActivityState.Activity activity) {
        return switch (activity) {
            case IDLE -> idleRandom;
            case WORK -> workRandom;
            case REST -> restRandom;
            case RIDE_WORK -> rideWorkRandom;
            default -> throw new IllegalArgumentException("No random activity " + activity);
        };
    }

    public MaidModernPerception getPerception() {
        return perception;
    }

    public MaidActivityState getActivities() {
        return activities;
    }

    public FollowCoreLookDriver.State getLookState() {
        return lookState;
    }

    public FollowMoveState getMoveState() {
        return moveState;
    }
}
