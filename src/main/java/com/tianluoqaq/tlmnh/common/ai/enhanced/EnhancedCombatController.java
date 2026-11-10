package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatObservation;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession.Phase;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 一个战斗会话内部切武器与动作，实体级节拍和已接受命中不随取消重建 */
public final class EnhancedCombatController {

    private static final int REPLAN_TICKS = 4, FAILED_APPROACH_TICKS = 200, RETRY_TICKS = 100;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final CombatPlanner planner = new CombatPlanner();
    private final Map<UUID, Failure> failures = new LinkedHashMap<>();
    private EntityLivingBase ownedTarget;
    private Point lastSeen;
    private CombatWorkQuota quota;
    private CombatPlan lastPlan;
    private CombatSnapshot lastSnapshot;
    private CombatPlan.Result lastResult;
    private long lastFullAt = Long.MIN_VALUE, lastOfferAt = Long.MIN_VALUE, attacks, releases, abandonedTargets;
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
        if (now < lastOfferAt) {
            failures.clear();
            lastFullAt = Long.MIN_VALUE;
        }
        lastOfferAt = now;
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
        failures.entrySet()
            .removeIf(
                entry -> entry.getValue()
                    .expired(observation.lastSeen(entry.getKey()), now));
        if (ownedTarget != null && approachFailed(now)) {
            Vec position = observation.lastSeen(ownedTarget.getUniqueID());
            if (position != null) {
                if (failures.size() >= 32) failures.remove(
                    failures.keySet()
                        .iterator()
                        .next());
                failures.put(ownedTarget.getUniqueID(), new Failure(position, now + RETRY_TICKS));
                abandonedTargets++;
            }
        }
        for (EntityLivingBase target : observation.targets()) {
            if (!knownTarget(target) || failures.containsKey(target.getUniqueID())) continue;
            Vec seen = observation.lastSeen(target.getUniqueID());
            if (seen == null) continue;
            double distance = Math.sqrt(maid.getDistanceSq(seen.x, seen.y, seen.z));
            double utility = 1 / (1 + distance);
            if (target == maid.getAITarget()) utility += .10;
            if (target == ownedTarget) utility += .015;
            frame.offer(
                Group.COMBAT,
                Kind.COMBAT,
                target.getUniqueID()
                    .toString(),
                utility,
                target);
        }
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
            if (knownTarget(target) && !failures.containsKey(target.getUniqueID())) return true;
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
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid();
        var owner = actor.getOwner();
        return owner != null && !owner.isDeadOrDying()
            && !owner.isSpectator()
            && owner.getWorldIdentity() == actor.getWorldIdentity()
            && actor.closerThan(owner, 16);
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
        var home = maid.getHomeArea();
        if (home.enabled()) return home.contains(seen.x, seen.y, seen.z);
        var owner = maid.getOwner();
        return owner != null && owner.getDistanceSq(seen.x, seen.y, seen.z) <= 256;
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
        long hits = actor.meleeNativeAccepted(), shots = actor.rangedBow()
            .getShots();
        CombatPlan.Result result;
        try (var measurement = CombatEffectPilotSession.measure(maid, Phase.SUBMIT)) {
            result = actor.tryApplyCombatPlan(lastPlan);
        }
        if (trial != null) trial.applied(result, quota);
        lastResult = result;
        attacks += Math.max(0, actor.meleeNativeAccepted() - hits);
        releases += Math.max(
            0,
            actor.rangedBow()
                .getShots() - shots);
        if (result == CombatPlan.Result.STALE) staleDecisions++;
        if (result == CombatPlan.Result.FINISHED)
            planner.finishAttack(lastPlan, new Vec(maid.posX, maid.boundingBox.minY, maid.posZ), now);
    }

    /** 原Enhanced根调用形状保留，模式现在由会话选定武器决定 */
    public void execute(EntityLivingBase selected, boolean ignoredBowMode) {
        execute(selected);
    }

    public void cancel() {
        cancel(false);
    }

    public void cancel(boolean preserveRequest) {
        EntityLivingBase previous = ownedTarget;
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

    private static final class Failure {

        final Vec position;
        final long retryAt;

        Failure(Vec position, long retryAt) {
            this.position = position;
            this.retryAt = retryAt;
        }

        boolean expired(Vec seen, long now) {
            return now >= retryAt || seen == null || seen.distance(position) > 2;
        }
    }
}
