package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.EnumMap;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;

/** 原Brain的单个非CORE活动与日程节流；行为仍按原优先级开始/运行，不因活动切换强停全部行为 */
public final class MaidActivityState {

    public enum Activity {
        IDLE,
        WORK,
        REST,
        RIDE_IDLE,
        RIDE_WORK,
        RIDE_REST,
        PANIC
    }

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final EnumMap<Activity, MaidLifeRules.Activity> scheduleCaches = new EnumMap<>(Activity.class);
    private Activity active = Activity.IDLE;
    private long lastScheduleUpdate = -9999, transitions;
    private boolean initialized;

    public MaidActivityState(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
    }

    public void initialize() {
        guard();
        if (!initialized) {
            initialized = true;
            updateFromSchedule();
        }
    }

    public boolean canPanic() {
        guard();
        return maid.getTask() == MaidTask.IDLE;
    }

    public boolean hurtOrHostile() {
        guard();
        return perception.hurtBy() != null || perception.nearestHostile() != null;
    }

    public void enterPanic() {
        guard();
        if (active != Activity.PANIC) {
            actor.setPathMemory(null);
            actor.eraseWalkTarget();
            actor.eraseLookTarget();
        }
        setActive(Activity.PANIC);
    }

    public void clearHurtMemories() {
        guard();
        perception.setHurtBy(null);
        perception.setHurtByEntity(null);
    }

    public void startScheduleTask() {
        guard();
        long time = actor.worldGameTime();
        if (time - lastScheduleUpdate > 20) {
            var scheduled = MaidLifeRules.activity(maid.getSchedule(), maid.worldObj.getWorldTime());
            var cached = scheduleCaches.get(active);
            if (cached == null) scheduleCaches.put(active, scheduled);
            if (cached != null && cached != scheduled && actor.isHomeModeEnable() && actor.canBrainMoving()) {
                scheduleCaches.put(active, scheduled);
                actor.applyScheduleRestriction();
                actor.setPositionWalkAndLook(actor.restrictionCenter(), .7F, 3);
            }
        }
        updateFromSchedule();
    }

    public void updateFromSchedule() {
        guard();
        long time = actor.worldGameTime();
        if (time - lastScheduleUpdate <= 20) return;
        lastScheduleUpdate = time;
        var scheduled = MaidLifeRules.activity(maid.getSchedule(), maid.worldObj.getWorldTime());
        boolean riding = maid.isSitting() || maid.ridingEntity != null;
        var next = switch (scheduled) {
            case WORK -> riding ? Activity.RIDE_WORK : Activity.WORK;
            case IDLE -> riding ? Activity.RIDE_IDLE : Activity.IDLE;
            case REST -> riding ? Activity.RIDE_REST : Activity.REST;
        };
        if (riding && active != next) {
            actor.setPathMemory(null);
            actor.eraseWalkTarget();
        }
        setActive(next);
    }

    private void setActive(Activity next) {
        if (active != next) {
            active = next;
            transitions++;
        }
    }

    public Activity active() {
        guard();
        return active;
    }

    public long lastScheduleUpdate() {
        guard();
        return lastScheduleUpdate;
    }

    public long transitions() {
        guard();
        return transitions;
    }

    public void reset() {
        active = Activity.IDLE;
        lastScheduleUpdate = -9999;
        initialized = false;
        scheduleCaches.clear();
    }

    private void guard() {
        if (!actor.isCurrent()) throw new IllegalStateException("Expired activity frame");
    }
}
