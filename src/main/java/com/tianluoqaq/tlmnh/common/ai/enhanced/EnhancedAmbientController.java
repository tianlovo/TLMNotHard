package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedNavigationController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedRideController;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowOwnerActor.Owner;
import com.tianluoqaq.tlmnh.common.ai.faithful.perception.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingSession;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Intent;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 无其他活动时才选择短程闲逛或观察；请求食物只表现意图，不取走主人手中物品 */
public final class EnhancedAmbientController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedAiIds.ID_AMBIENT);

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final MaidSensingSession sensing;
    private final EnhancedNavigationController navigation;
    private Point destination;
    private EntityLivingBase watching;
    private long until, nextChoice, lastTick = Long.MIN_VALUE;
    private long samples, walkStarts, lookStarts;
    private boolean preferLook = true;

    public EnhancedAmbientController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception,
        MaidSensingSession sensing, EnhancedNavigationController navigation) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
        this.sensing = sensing;
        this.navigation = navigation;
    }

    public void offer(MaidIntentFrame frame) {
        long now = actor.worldGameTime();
        if (now < lastTick) {
            clearPlan();
            nextChoice = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
        }
        lastTick = now;
        if (frame.hasOffers() || !permitted()) {
            sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
            return;
        }
        if (watching != null || destination != null) {
            boolean legal = now < until && (watching != null ? legalLook(watching)
                : legalWalk(destination)
                    && actor.distanceToBlockCenterSquared(destination)
                        > EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP
                    && navigation.outcome() != EnhancedNavigationController.Outcome.UNREACHABLE
                    && navigation.outcome() != EnhancedNavigationController.Outcome.RECOVERY_WAIT);
            if (legal) {
                offerPlan(frame);
                return;
            }
            clearPlan();
            nextChoice = now + EnhancedAiTuning.CHOICE_INTERVAL;
        }
        if (now < nextChoice) {
            sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
            return;
        }
        if (!sensing.acquire(MaidSensingBudget.Kind.AMBIENT)) return;
        nextChoice = now + EnhancedAiTuning.CHOICE_INTERVAL;
        var visible = perception.visible();
        var target = visible == null ? null : visible.findClosest(this::legalLook);
        // 可见对象存在时轮换观察和闲逛，避免随机抽样长期遗漏一种表现
        if (target != null && preferLook) {
            preferLook = false;
            offerObserve(frame, target);
            return;
        }
        samples++;
        var point = actor.sampleEnhancedLand(
            EnhancedAiTuning.ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_FIRST_PARAM,
            EnhancedAiTuning.ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_SECOND_PARAM);
        if (point != null) {
            var block = new Point(
                MathHelper.floor_double(point.x),
                MathHelper.floor_double(point.y),
                MathHelper.floor_double(point.z));
            if (legalWalk(block)
                && actor.distanceToBlockCenterSquared(block) > EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP) {
                preferLook = true;
                offerWalk(frame, block);
                return;
            }
        }
        if (target != null) offerObserve(frame, target);
    }

    private void offerPlan(MaidIntentFrame frame) {
        if (watching != null) offerObserve(frame, watching);
        else offerWalk(frame, destination);
    }

    public boolean offerBeg(MaidIntentFrame frame) {
        if (frame.hasOffers()) return false;
        boolean wanted = temptingOwner() != null;
        if (wanted && permitted()) {
            frame.offerTyped(
                ID,
                Group.AMBIENT,
                Kind.BEG,
                EnhancedAiIds.ID_OWNER,
                EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP,
                null,
                ignored -> executeBeg());
        }
        return wanted;
    }

    private void offerObserve(MaidIntentFrame frame, EntityLivingBase target) {
        frame.offerTyped(
            ID,
            Group.AMBIENT,
            Kind.OBSERVE,
            target.getUniqueID()
                .toString(),
            EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_OBSERVE_FACTOR,
            target,
            this::executeObserve);
    }

    private void offerWalk(MaidIntentFrame frame, Point point) {
        frame.offerTyped(
            ID,
            Group.AMBIENT,
            Kind.WANDER,
            point.x + EnhancedAiIds.COLON_DELIMITER + point.y + EnhancedAiIds.COLON_DELIMITER + point.z,
            EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_WALK_FACTOR,
            point,
            this::executeWander);
    }

    private boolean permitted() {
        return actor.canBrainMoving() && !EnhancedRideController.stationary(maid)
            && !maid.getLeashed()
            && !actor.isSleeping()
            && !maid.isInWater()
            && maid.getItemUseState()
                .peek() == null;
    }

    private Owner temptingOwner() {
        if (maid.getHomeArea()
            .activity() == Activity.REST) return null;
        var owner = actor.visibleOwnerNow(EnhancedAiTuning.ENHANCED_AMBIENT_VISIBLE_OWNER_NOW_FIRST_PARAM);
        var player = maid.getOwner();
        return owner != null && player != null
            && actor.ownerHoldsTemptation(owner)
            && maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(player.posX),
                    MathHelper.floor_double(player.posY),
                    MathHelper.floor_double(player.posZ)) ? owner : null;
    }

    private boolean legalLook(EntityLivingBase target) {
        return target != maid && target != maid.riddenByEntity
            && target.worldObj == maid.worldObj
            && target.isEntityAlive()
            && maid.worldObj.loadedEntityList.contains(target)
            && (target instanceof EntityPlayer || target instanceof EntityMaid
                || target instanceof EntityWolf
                || target instanceof EntityOcelot)
            && (!(target instanceof EntityPlayer player) || !player.isPlayerSleeping()
                && (!(player instanceof EntityPlayerMP mp) || mp.theItemInWorldManager.getGameType()
                    .getID() != EnhancedAiTuning.ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_SECOND_PARAM))
            && maid.getDistanceSqToEntity(target) < EnhancedAiTuning.ENHANCED_AMBIENT_LEGAL_LOOK_PARAM
            && maid.getHomeArea()
                .withinRestriction(
                    MathHelper.floor_double(target.posX),
                    MathHelper.floor_double(target.posY),
                    MathHelper.floor_double(target.posZ))
            && maid.canEntityBeSeen(target);
    }

    private boolean legalWalk(Point point) {
        return actor.isEnhancedLandDestination(point);
    }

    public void executeBeg() {
        if (!permitted()) return;
        var owner = temptingOwner();
        if (owner == null) return;
        actor.setBegging(true);
        if (!actor.closerThan(owner, EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM))
            actor.setWalkAndLookTargetMemories(
                owner,
                EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
                EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM);
        else {
            actor.eraseWalkTarget();
            actor.lookAtOwner(owner);
        }
    }

    public void executeObserve(EntityLivingBase target) {
        if (!permitted() || !legalLook(target)) return;
        if (watching != target) {
            watching = target;
            until = actor.worldGameTime() + EnhancedAiTuning.LOOK_TICKS;
            lookStarts++;
        }
        actor.lookAtAmbientEntity(target);
    }

    public void executeWander(Point point) {
        if (!permitted() || !legalWalk(point)) return;
        if (!point.equals(destination)) {
            destination = point;
            until = actor.worldGameTime() + EnhancedAiTuning.WALK_TICKS;
            walkStarts++;
        }
        actor.setPositionWalkAndLook(
            point,
            EnhancedAiTuning.ENHANCED_AMBIENT_SET_POSITION_WALK_AND_LOOK_SECOND_F,
            EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE);
    }

    public void execute(Intent intent) {
        switch (intent.kind) {
            case BEG -> executeBeg();
            case OBSERVE -> executeObserve((EntityLivingBase) intent.payload);
            case WANDER -> executeWander((Point) intent.payload);
            default -> throw new IllegalArgumentException(EnhancedAiDiagnostics.MSG_AMBIENT_INTENT_REQUIRED);
        }
    }

    private void clearPlan() {
        destination = null;
        watching = null;
        until = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
    }

    public void onTargetChanged() {
        clearPlan();
    }

    public void onDeselected(TransitionReason reason) {
        cancel();
    }

    /** 无token时只释放本实例计划和排队身份；实际LOOK/PATH/姿态由actor所有者清理 */
    public void cancel() {
        clearPlan();
        sensing.cancel(MaidSensingBudget.Kind.AMBIENT);
    }

    public Point destination() {
        return destination;
    }

    public EntityLivingBase watching() {
        return watching;
    }

    public long samples() {
        return samples;
    }

    public long walkStarts() {
        return walkStarts;
    }

    public long lookStarts() {
        return lookStarts;
    }
}
