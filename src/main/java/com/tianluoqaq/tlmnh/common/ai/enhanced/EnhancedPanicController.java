package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Vec3;
import com.tianluoqaq.tlmnh.common.ai.faithful.perception.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 增强安全决策独立消费真实伤害/可见威胁，受伤事实本身不提供逃离坐标 */
public final class EnhancedPanicController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedAiIds.ID_PANIC);

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
            if (++checked > EnhancedAiTuning.MAX_CANDIDATES) break;
            if (visibleThreat(candidate)
                && (nearest == null || maid.getDistanceSqToEntity(candidate) < maid.getDistanceSqToEntity(nearest)))
                nearest = candidate;
        }
        if (nearest == null && maid.getMaidLastDamageSource() == null) return false;
        frame.offerTyped(
            ID,
            Group.DANGER,
            Kind.PANIC,
            EnhancedAiIds.ID_PANIC,
            EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP,
            nearest,
            this::execute);
        return true;
    }

    private boolean permitted() {
        return actor.isCurrent() && maid.getTask() == MaidTask.IDLE
            && (!maid.getHomeArea()
                .enabled() || maid.getHomeArea()
                    .sameDimension());
    }

    private boolean visibleThreat(EntityLivingBase candidate) {
        return candidate instanceof EntityCreeper && candidate.worldObj == maid.worldObj
            && candidate.dimension == maid.dimension
            && candidate.isEntityAlive()
            && maid.worldObj.getEntityByID(candidate.getEntityId()) == candidate
            && maid.getDistanceSqToEntity(candidate) < EnhancedAiTuning.DANGER_DISTANCE_SQUARED
            && maid.canEntityBeSeen(candidate);
    }

    public void execute(EntityLivingBase observed) {
        if (!permitted()) return;
        long now = maid.worldObj.getTotalWorldTime();
        if (now < lastTick || threat != observed) {
            destination = null;
            nextSample = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
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
        if (destination != null && (!legalDestination(destination) || maid.getDistanceSq(
            destination.x + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
            destination.y,
            destination.z + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE)
            < EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP)) {
            destination = null;
        }
        if (now >= nextSample) {
            nextSample = now + EnhancedAiTuning.RETRY_TICKS_2;
            samples++;
            var sampled = actor.findEnhancedLandAway(
                EnhancedAiTuning.ENHANCED_PANIC_FIND_ENHANCED_LAND_AWAY_FIRST_PARAM,
                EnhancedAiTuning.ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_SECOND_PARAM,
                new Vec3(threat.posX, threat.posY, threat.posZ));
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
        else actor.setPositionWalkAndLook(
            destination,
            EnhancedAiTuning.ENHANCED_HOME_SET_POSITION_WALK_AND_LOOK_SECOND_FACT,
            EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE);
    }

    private boolean legalDestination(Point point) {
        return maid.worldObj.blockExists(point.x, point.y, point.z)
            && maid.worldObj.blockExists(point.x, point.y + 1, point.z)
            && maid.getHomeArea()
                .withinRestriction(point.x, point.y, point.z)
            && actor.isEnhancedLandDestination(point)
            && threat.getDistanceSq(
                point.x + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
                point.y,
                point.z + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE) > maid.getDistanceSqToEntity(threat);
    }

    public void onDeselected(TransitionReason reason) {
        cancel();
    }

    public void cancel() {
        threat = null;
        destination = null;
        nextSample = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
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
