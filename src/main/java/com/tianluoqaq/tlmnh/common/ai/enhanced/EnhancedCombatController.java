package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatObservation;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatRangePolicy;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidDomainGuard;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidTargetCooldowns;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession.Phase;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 一个战斗会话内部切武器与动作，实体级节拍和已接受命中不随取消重建 */
public final class EnhancedCombatController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin("unified_combat");
    private static final int REPLAN_TICKS = 4, FAILED_APPROACH_TICKS = 200, RETRY_TICKS = 100, MAX_FAILED_TARGETS = 32;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final CombatPlanner planner = new CombatPlanner();
    private final MaidTargetCooldowns<UUID> failures = MaidTargetCooldowns.forUuidFifo(MAX_FAILED_TARGETS);
    private EntityLivingBase ownedTarget;
    private Point lastSeen;
    private CombatWorkQuota quota;
    private CombatPlan lastPlan;
    private CombatSnapshot lastSnapshot;
    private CombatPlan.Result lastResult;
    private long lastFullAt = Long.MIN_VALUE, attacks, releases, abandonedTargets;
    private long fullDecisions, lightDecisions, staleDecisions;
    private long bodyServices;
    private com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatFullServiceReceipt.Status lastServiceResult = com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatFullServiceReceipt.Status.NOT_GRANTED;

    public EnhancedCombatController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception) {
        this.maid = maid;
        this.actor = actor;
    }

    public void offer(MaidIntentFrame frame) {
        try (var measurement = CombatEffectPilotSession.measure(maid, Phase.OFFER)) {
            offerCurrent(frame);
        }
    }

    private void offerCurrent(MaidIntentFrame frame) {
        if (!permitted()) {
            CombatWorldBudget.cancel(maid.worldObj, maid.getUniqueID());
            return;
        }
        long now = actor.worldGameTime();
        if (failures.advanceTick(now)) {
            lastFullAt = Long.MIN_VALUE;
        }
        CombatObservation observation = actor.combatObservation();
        boolean due = lastFullAt == Long.MIN_VALUE || now - lastFullAt >= REPLAN_TICKS;
        if (observation.readiness() != null && observation.readiness().tick == now) reportPreparation(observation, now);
        quota = CombatWorldBudget
            .heartbeat(maid.worldObj, maid.getUniqueID(), now, due && hasServiceTarget(observation));
        observation.observe(maid, quota);
        reportPreparation(observation, now);
        if (due && hasServiceTarget(observation))
            quota = CombatWorldBudget.heartbeat(maid.worldObj, maid.getUniqueID(), now, true);
        observation.observe(maid, quota);
        reportPreparation(observation, now);
        if (due && bodyServicePrepared(observation))
            quota = CombatWorldBudget.heartbeat(maid.worldObj, maid.getUniqueID(), now, true);
        failures.purgeExpired(now, (id, entry) -> {
            Vec seen = observation.lastSeen(id);
            return seen == null || Math.sqrt(entry.distanceSq(seen.x, seen.y, seen.z)) > 2;
        });
        if (ownedTarget != null && approachFailed(now)) {
            Vec position = observation.lastSeen(ownedTarget.getUniqueID());
            if (position != null) {
                failures.record(ownedTarget.getUniqueID(), now + RETRY_TICKS, position.x, position.y, position.z, null);
                abandonedTargets++;
            }
        }
        CombatRangePolicy rangePolicy = observation.rangePolicy();
        var owner = maid.getOwner();
        // 友方贴身偏置只服务纯近战争抢，不干扰已具备可用弓箭与弹药时的远程目标仲裁
        boolean meleeContention = observation.supportedMelee()
            && !(observation.supportedBow() && observation.observedAmmunition(now));
        List<OfferCandidate> candidates = new ArrayList<>();
        for (EntityLivingBase target : observation.targets()) {
            UUID id = target.getUniqueID();
            if (!knownTarget(target) || failures.contains(id)) continue;
            Vec seen = observation.lastSeen(id);
            if (seen == null) continue;
            boolean ownerHurt = owner != null && target == owner.getAITarget();
            boolean maidHurt = target == maid.getAITarget();
            candidates.add(
                new OfferCandidate(
                    target,
                    id,
                    seen,
                    CombatRangePolicy.urgency(ownerHurt, maidHurt),
                    ownerHurt,
                    maidHurt,
                    rangePolicy.isMeleeUnproductive(id, now),
                    meleeContention
                        && observation.hasObservedFriendNear(maid, seen, CombatRangePolicy.FRIEND_MELEE_RADIUS_SQ),
                    canProgressAgainst(observation, target, seen, now)));
        }
        for (OfferCandidate current : candidates) {
            int bestAlternativeUrgency = -1;
            boolean uncontestedAlternative = false;
            for (OfferCandidate other : candidates) {
                if (other == current || other.unproductive || !other.progressable) continue;
                bestAlternativeUrgency = Math.max(bestAlternativeUrgency, other.urgency);
                if (!other.friendEngaged) uncontestedAlternative = true;
            }
            // 仅当存在同级或更高防卫紧迫度且可推进的替代目标时退让，避免被仲裁滞回锁在无效目标
            boolean yieldUnproductive = CombatRangePolicy
                .shouldYieldUnproductive(current.urgency, current.unproductive, bestAlternativeUrgency);
            if (yieldUnproductive) continue;
            double distance = Math.sqrt(maid.getDistanceSq(current.seen.x, current.seen.y, current.seen.z));
            double utility = CombatRangePolicy.score(
                distance,
                current.ownerHurt,
                current.maidHurt,
                current.target == ownedTarget,
                current.friendEngaged && uncontestedAlternative);
            frame.offerTyped(
                ID,
                Group.COMBAT,
                Kind.COMBAT,
                current.id.toString(),
                utility,
                current.target,
                this::execute);
        }
    }

    private boolean canProgressAgainst(CombatObservation observation, EntityLivingBase target, Vec seen, long now) {
        if (target == ownedTarget && actor.getCantReachSince() != null) return false;
        if (!EnhancedRideController.stationary(maid)) return true;
        if (!observation.visible(target.getUniqueID())) return false;
        double distanceSq = maid.getDistanceSq(seen.x, seen.y, seen.z);
        boolean bowInRange = observation.supportedBow() && observation.observedAmmunition(now)
            && distanceSq <= MaidBowAttack.RANGE_SQ;
        if (bowInRange) return true;
        if (!observation.supportedMelee()) return false;
        double reach = MaidCombatAttributes.entityReach(maid) + maid.getFavorabilityManager()
            .getAttackDistancePlusByPoint(maid.getFavorability());
        return distanceSq <= reach * reach;
    }

    private void reportPreparation(CombatObservation observation, long now) {
        CombatWorldBudget.prepared(
            maid.worldObj,
            maid.getUniqueID(),
            now,
            bodyServicePrepared(observation),
            observation.oldestCoverage(),
            observation.friendRefreshDebt(),
            observation.knownFriends());
    }

    private boolean bodyServicePrepared(CombatObservation observation) {
        return observation.bodyPreparationReady() && hasServiceTarget(observation);
    }

    private boolean hasServiceTarget(CombatObservation observation) {
        for (EntityLivingBase target : observation.targets())
            if (knownTarget(target) && !failures.contains(target.getUniqueID())) return true;
        return false;
    }

    private boolean approachFailed(long now) {
        Long since = actor.getCantReachSince();
        return since != null && now >= since && now - since > FAILED_APPROACH_TICKS;
    }

    private boolean permitted() {
        if (!actor.isCurrent() || !maid.isUnifiedCombat()
            || maid.getTask() != MaidTask.ATTACK
            || !maid.isTamed()
            || maid.getLeashed()
            || actor.isSleeping()
            || !actor.canBrainMoving() && !EnhancedRideController.stationary(maid)
            || maid.getHomeArea()
                .activity() != Activity.WORK)
            return false;
        return MaidDomainGuard.isMaidWithinDomain(maid, actor, MaidDomainGuard.DEFAULT_OWNER_DOMAIN_RADIUS);
    }

    /** 身份与存活可重验；隐藏目标的活动边界只用最后可见位置 */
    private boolean knownTarget(EntityLivingBase target) {
        if (target == null || target == maid
            || target.worldObj != maid.worldObj
            || target.dimension != maid.dimension
            || target.isDead
            || !target.isEntityAlive()
            || maid.worldObj.getEntityByID(target.getEntityId()) != target) return false;
        Vec seen = actor.combatObservation()
            .lastSeen(target.getUniqueID());
        if (seen == null) return false;
        return actor.combatObservation()
            .targetInDomain(maid, target.getUniqueID(), seen);
    }

    public void execute(EntityLivingBase selected) {
        if (!permitted() || !knownTarget(selected)) return;
        long now = actor.worldGameTime();
        if (quota == null || quota.tick != now)
            quota = CombatWorldBudget.heartbeat(maid.worldObj, maid.getUniqueID(), now, true);
        EntityLivingBase before = ownedTarget;
        if (maid.getModernCombatMemory()
            .target() != selected) {
            if (!actor.setAttackTarget(actor.combatTargetView(selected)) || !actor.isCurrent()) return;
        }
        // 重定向只采用事件后的真实目标；未观测重定向先收集，不能偷读墙后坐标
        ownedTarget = maid.getModernCombatMemory()
            .target();
        if (!actor.isCurrent() || ownedTarget == null) return;
        if (!knownTarget(ownedTarget)) {
            ownedTarget = null;
            return;
        }
        actor.combatObservation()
            .rangePolicy()
            .select(ownedTarget.getUniqueID());
        actor.combatObservation()
            .fullGranted(quota);
        CombatSnapshot snapshot;
        try (var measurement = CombatEffectPilotSession.measure(maid, Phase.SNAPSHOT)) {
            snapshot = actor.readonlyCombatSnapshot(ownedTarget, quota);
        }
        if (snapshot == null || !knownTarget(ownedTarget)) return;
        lastSnapshot = snapshot;
        if (before != ownedTarget) actor.eraseCantReachSince();
        Vec seen = actor.combatObservation()
            .lastSeen(ownedTarget.getUniqueID());
        if (seen != null) lastSeen = new Point(
            MathHelper.floor_double(seen.x),
            MathHelper.floor_double(seen.y),
            MathHelper.floor_double(seen.z));
        var trial = CombatEffectPilotSession.current(maid);
        lastPlan = trial == null ? planner.decide(snapshot, quota) : trial.decide(snapshot, quota, planner);
        actor.combatObservation()
            .completeForecast(now, lastPlan.simulated);
        lastServiceResult = actor.combatObservation()
            .serviceResult(now);
        if (lastPlan.simulated) fullDecisions++;
        else lightDecisions++;
        // 真实capture才结算，同一tick不因身体策略未simulated而重复使用服务
        if (quota.full && actor.combatObservation()
            .settleService(now)) {
            lastFullAt = now;
            if (!lastPlan.simulated) bodyServices++;
            CombatWorldBudget.completed(maid.worldObj, maid.getUniqueID(), now);
        }
        UUID attemptedTarget = ownedTarget.getUniqueID();
        boolean cadenceWasReady = maid.getCombatCadence()
            .ready();
        long attempts = actor.meleeAttempts();
        long hits = actor.meleeNativeAccepted(), shots = actor.rangedBow()
            .getShots();
        CombatPlan.Result result;
        try (var measurement = CombatEffectPilotSession.measure(maid, Phase.SUBMIT)) {
            result = actor.tryApplyCombatPlan(lastPlan);
        }
        if (trial != null) trial.applied(result, quota);
        lastResult = result;
        long accepted = Math.max(0, actor.meleeNativeAccepted() - hits);
        attacks += accepted;
        if (actor.isCurrent()) {
            boolean rejectedMelee = accepted == 0 && actor.meleeAttempts() > attempts
                && cadenceWasReady
                && !maid.getCombatCadence()
                    .ready();
            if (accepted > 0 || rejectedMelee) {
                long committedCooldown = accepted > 0 ? 0
                    : maid.getCombatCadence()
                        .remainingTicks();
                actor.combatObservation()
                    .rangePolicy()
                    .recordMeleeOutcome(attemptedTarget, now, accepted > 0, committedCooldown);
            }
        }
        releases += Math.max(
            0,
            actor.rangedBow()
                .getShots() - shots);
        if (result == CombatPlan.Result.STALE) staleDecisions++;
        if (result == CombatPlan.Result.FINISHED)
            planner.finishAttack(lastPlan, new Vec(maid.posX, maid.boundingBox.minY, maid.posZ), now);
    }

    public void onTargetChanged() {
        cancel(true);
    }

    public void onDeselected(TransitionReason reason) {
        boolean preserveObservation = reason != TransitionReason.BRAIN_INVALIDATED && maid.isUnifiedCombat()
            && maid.getTask() == MaidTask.ATTACK;
        cancel(preserveObservation);
        if (!preserveObservation && actor.isCurrent()) {
            actor.combatObservation()
                .clear();
        }
    }

    public void cancel() {
        cancel(false);
        if (actor.isCurrent()) {
            actor.combatObservation()
                .clear();
        }
    }

    public void cancel(boolean preserveRequest) {
        EntityLivingBase previous = ownedTarget;
        actor.cancelCombatTargetRetention();
        ownedTarget = null;
        lastSeen = null;
        lastPlan = null;
        lastSnapshot = null;
        lastResult = null;
        lastFullAt = Long.MIN_VALUE;
        planner.finishDecision();
        if (!preserveRequest) CombatWorldBudget.cancel(maid.worldObj, maid.getUniqueID());
        if (actor.isCurrent()) actor.cancelUnifiedCombat("combat_session_cancelled");
        if (previous != null && maid.getModernCombatMemory()
            .target() == previous) maid.getModernCombatMemory()
                .target(null);
    }

    public EntityLivingBase target() {
        return ownedTarget;
    }

    public Point lastSeenPosition() {
        return lastSeen;
    }

    public long attacks() {
        return attacks;
    }

    public long releases() {
        return releases;
    }

    public long abandonedTargets() {
        return abandonedTargets;
    }

    public long rangedRefusals() {
        return actor.rangedBow()
            .getRejectedShots();
    }

    public long rangedRecoveryProbes() {
        return fullDecisions;
    }

    public long rangedRecoveryMoves() {
        return lightDecisions;
    }

    public MaidBowAttack.Status rangedRecoveryOutcome() {
        return actor.rangedBow()
            .getStatus();
    }

    public CombatPlan lastPlan() {
        return lastPlan;
    }

    public CombatSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public CombatPlan.Result lastResult() {
        return lastResult;
    }

    public long bodyServices() {
        return bodyServices;
    }

    public com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatFullServiceReceipt.Status lastServiceResult() {
        return lastServiceResult;
    }

    public long fullDecisions() {
        return fullDecisions;
    }

    public long lightDecisions() {
        return lightDecisions;
    }

    public long staleDecisions() {
        return staleDecisions;
    }

    private static final class OfferCandidate {

        final EntityLivingBase target;
        final UUID id;
        final Vec seen;
        final int urgency;
        final boolean ownerHurt, maidHurt, unproductive, friendEngaged, progressable;

        OfferCandidate(EntityLivingBase target, UUID id, Vec seen, int urgency, boolean ownerHurt, boolean maidHurt,
            boolean unproductive, boolean friendEngaged, boolean progressable) {
            this.target = target;
            this.id = id;
            this.seen = seen;
            this.urgency = urgency;
            this.ownerHurt = ownerHurt;
            this.maidHurt = maidHurt;
            this.unproductive = unproductive;
            this.friendEngaged = friendEngaged;
            this.progressable = progressable;
        }
    }
}
