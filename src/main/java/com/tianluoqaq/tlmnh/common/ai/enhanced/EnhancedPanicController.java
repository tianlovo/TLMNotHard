package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 增强安全决策独立消费真实伤害/可见威胁，受伤事实本身不提供逃离坐标 */
public final class EnhancedPanicController {

    private static final int MAX_CANDIDATES = 64, RETRY_TICKS = 20;
    private static final double DANGER_DISTANCE_SQUARED = 64;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private EntityLivingBase threat;
    private Point destination;
    private long nextSample, lastTick = Long.MIN_VALUE, samples;

    public EnhancedPanicController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
    }

    public boolean offer(MaidIntentFrame frame) {
        if (!permitted()) return false;
        EntityLivingBase nearest = visibleThreat(perception.nearestHostile()) ? perception.nearestHostile() : null;
        int checked = 0;
        for (var candidate : perception.nearby()) {
            if (++checked > MAX_CANDIDATES) break;
            if (visibleThreat(candidate)
                && (nearest == null || maid.getDistanceSqToEntity(candidate) < maid.getDistanceSqToEntity(nearest)))
                nearest = candidate;
        }
        if (nearest == null && maid.getMaidLastDamageSource() == null) return false;
        frame.offer(Group.DANGER, Kind.PANIC, "panic", 1, nearest);
        return true;
    }

    private boolean permitted() {
        return actor.isCurrent() && maid.isTamed()
            && maid.getTask() == MaidTask.IDLE
            && (!maid.getHomeArea()
                .enabled() || maid.getHomeArea()
                    .sameDimension());
    }

    private boolean visibleThreat(EntityLivingBase candidate) {
        return candidate instanceof EntityCreeper && candidate.worldObj == maid.worldObj
            && candidate.dimension == maid.dimension
            && candidate.isEntityAlive()
            && maid.worldObj.getEntityByID(candidate.getEntityId()) == candidate
            && maid.getDistanceSqToEntity(candidate) < DANGER_DISTANCE_SQUARED
            && maid.canEntityBeSeen(candidate);
    }

    public void execute(EntityLivingBase observed) {
        if (!permitted()) return;
        long now = maid.worldObj.getTotalWorldTime();
        if (now < lastTick || threat != observed) {
            destination = null;
            nextSample = 0;
        }
        lastTick = now;
        threat = observed != null && visibleThreat(observed) ? observed : null;
        actor.clearSleepingIfPresent();
        if (!actor.isCurrent()) return;
        if (threat == null) {
            destination = null;
            actor.eraseWalkTarget();
            actor.eraseLookTarget();
            return;
        }
        actor.leaveMountOrSitForDanger();
        if (!actor.isCurrent()) return;
        if (!actor.canBrainMoving()) {
            destination = null;
            actor.eraseWalkTarget();
            return;
        }
        if (destination != null && (!legalDestination(destination)
            || maid.getDistanceSq(destination.x + .5, destination.y, destination.z + .5) < 1)) {
            destination = null;
        }
        if (now >= nextSample) {
            nextSample = now + RETRY_TICKS;
            samples++;
            var sampled = actor.findLandAway(10, 3, new Vec3(threat.posX, threat.posY, threat.posZ));
            if (!actor.isCurrent()) return;
            if (sampled != null) {
                var point = new Point(
                    MathHelper.floor_double(sampled.x),
                    MathHelper.floor_double(sampled.y),
                    MathHelper.floor_double(sampled.z));
                if (legalDestination(point)) destination = point;
            }
        }
        if (destination == null) actor.eraseWalkTarget();
        else actor.setPositionWalkAndLook(destination, .7F, 0);
    }

    private boolean legalDestination(Point point) {
        return maid.worldObj.blockExists(point.x, point.y, point.z)
            && maid.worldObj.blockExists(point.x, point.y + 1, point.z)
            && maid.getHomeArea()
                .withinRestriction(point.x, point.y, point.z)
            && threat.getDistanceSq(point.x + .5, point.y, point.z + .5) > maid.getDistanceSqToEntity(threat);
    }

    public void cancel() {
        threat = null;
        destination = null;
        nextSample = 0;
        lastTick = Long.MIN_VALUE;
    }

    public EntityLivingBase threat() {
        return threat;
    }

    public Point destination() {
        return destination;
    }

    public long samples() {
        return samples;
    }
}
