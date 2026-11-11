package com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.Entity;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirStopTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingSession;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

/** 增强呼吸独立处理离座与移动，手部被其他动作持有不阻止安全找空气 */
public final class EnhancedBreathingController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedLocomotionIds.ID_BREATHING);

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidSensingSession sensing;
    private final EnhancedNavigationController navigation;
    private final BooleanSupplier airRecoveryFallback;
    private final MaidBreathAirStopTask stop = new MaidBreathAirStopTask();
    private Entity attemptedMount;
    private boolean attemptedSit;
    private long nextRelease, releases;
    private List<Point> candidates = new ArrayList<>();
    private int candidateIndex;
    private Point searchOrigin;
    private long nextSearch, nextNearbySearch, lastTick = Long.MIN_VALUE;

    public EnhancedBreathingController(EntityMaid maid, ModernFollowCoreActor actor, MaidSensingSession sensing,
        EnhancedNavigationController navigation, BooleanSupplier airRecoveryFallback) {
        this.maid = maid;
        this.actor = actor;
        this.sensing = sensing;
        this.navigation = navigation;
        this.airRecoveryFallback = Objects
            .requireNonNull(airRecoveryFallback, EnhancedLocomotionIds.ID_AIRRECOVERYFALLBACK);
    }

    public boolean lowAir() {
        return actor.airSupply() < EnhancedLocomotionTuning.MIN_AIR_SUPPLY_THRESHOLD && !actor.hasBreathingEffect()
            && !actor.hasDrownBauble();
    }

    public void stopIfRecovered() {
        if (stop.checkExtraStartConditions(actor)) stop.start(actor);
    }

    public void offer(MaidIntentFrame frame, boolean lowAirNow) {
        stopIfRecovered();
        if (lowAirNow || actor.isGoingToBreath() || actor.isEatBreatheItem()) {
            frame.offerTyped(
                ID,
                Group.EMERGENCY,
                Kind.BREATHE,
                EnhancedLocomotionIds.ID_AIR,
                EnhancedLocomotionTuning.OFFER_TYPED_SINGLE_UNIT,
                null,
                ignored -> execute());
        }
    }

    public void execute() {
        if (actor.hasBreathingEffect() || actor.hasDrownBauble() || actor.isEatBreatheItem()) return;
        if (actor.airSupply() >= EnhancedLocomotionTuning.MIN_AIR_SUPPLY_THRESHOLD && !actor.isGoingToBreath()) return;
        if (!actor.isUsingItem() && actor.tryEatBreatheItem()) return;
        if (!actor.isCurrent()) return;
        if (actor.airSupply() < EnhancedLocomotionTuning.MIN_AIR_SUPPLY_THRESHOLD) {
            actor.clearSleepingIfPresent();
            if (!actor.isCurrent()) return;
            if (EnhancedRideController.stationary(maid)) {
                long now = actor.worldGameTime();
                if (now >= nextRelease || attemptedMount != maid.ridingEntity || attemptedSit != maid.isSitting()) {
                    attemptedMount = maid.ridingEntity;
                    attemptedSit = maid.isSitting();
                    nextRelease = now + EnhancedLocomotionTuning.ENHANCED_BREATHING_NEXT_RELEASE;
                    actor.leaveMountOrSitForDanger();
                    if (!actor.isCurrent()) return;
                    if (attemptedMount != maid.ridingEntity || attemptedSit != maid.isSitting()) releases++;
                }
            }
        }
        if (!actor.canBrainMoving()) return;
        var target = actor.isGoingToBreath() ? actor.walkTargetPosition() : null;
        boolean failed = navigation.outcome() == EnhancedNavigationController.Outcome.UNREACHABLE
            || navigation.outcome() == EnhancedNavigationController.Outcome.RECOVERY_WAIT;
        if (target != null && actor.givesAir(target) && !failed) return;
        if (target != null) {
            actor.setGoingToBreath(false);
            actor.eraseWalkTarget();
        }
        long now = actor.worldGameTime();
        var origin = actor.airSearchPosition();
        if (now < lastTick || searchOrigin != null && distanceSquared(searchOrigin, origin) > 0) {
            candidates.clear();
            candidateIndex = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
            nextSearch = nextNearbySearch = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
        }
        lastTick = now;
        if (candidates.isEmpty() || now >= nextNearbySearch) {
            if (now < nextSearch || !actor.canScanForAir()) return;
            var found = actor.findAirWithBfs(EnhancedLocomotionTuning.ENHANCED_BREATHING_FIND_AIR_WITH_BFS_FIRST_PARAM);
            if (!actor.isCurrent()) return;
            var next = new ArrayList<Point>();
            if (found.isPresent()) add(next, found.get());
            if (candidates.isEmpty()) {
                int sea = actor.seaLevel() + 1;
                var center = new Point(origin.x, sea, origin.z);
                if (actor.givesAir(center)) add(next, center);
                for (int z = origin.z - EnhancedLocomotionTuning.ENHANCED_BREATHING_EXECUTE_Z_OFFSET; z
                    <= origin.z + EnhancedLocomotionTuning.ENHANCED_BREATHING_EXECUTE_Z_OFFSET; z++)
                    for (int x = origin.x - EnhancedLocomotionTuning.ENHANCED_BREATHING_EXECUTE_Z_OFFSET; x
                        <= origin.x + EnhancedLocomotionTuning.ENHANCED_BREATHING_EXECUTE_Z_OFFSET; x++) {
                            var p = new Point(x, sea, z);
                            if (actor.givesAir(p)) add(next, p);
                        }
            } else {
                // 重试附近空气时保留尚未检查的回退点，不能每帧从海平面第一个点重来
                for (int i = candidateIndex; i < candidates.size(); i++) add(next, candidates.get(i));
            }
            candidates = next;
            candidateIndex = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
            searchOrigin = origin;
            nextNearbySearch = now + EnhancedLocomotionTuning.NEARBY_RETRY_TICKS;
        }
        while (candidateIndex < candidates.size()) {
            var p = candidates.get(candidateIndex);
            if (!actor.givesAir(p)) {
                candidateIndex++;
                continue;
            }
            var position = new BlockPos(p.x, p.y, p.z);
            var result = actor.requestEnhancedPath(position);
            if (!actor.isCurrent()) return;
            switch (result.result) {
                case WAITING_BUDGET, NOT_NAVIGABLE -> {
                    return;
                }
                case UNREACHABLE -> candidateIndex++;
                case FOUND -> {
                    candidateIndex++;
                    if (!result.path.canReach()) continue;
                    actor.setGoingToBreath(true);
                    actor.setAirWalkAndLook(
                        p,
                        EnhancedLocomotionTuning.ENHANCED_BREATHING_SET_AIR_WALK_AND_LOOK_SECOND_FACT,
                        EnhancedLocomotionTuning.OFFER_TYPED_SINGLE_UNIT);
                    navigation.acceptPreflight(position, result, now);
                    return;
                }
            }
        }
        candidates.clear();
        candidateIndex = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
        nextSearch = now + EnhancedLocomotionTuning.EXHAUSTED_RETRY_TICKS;
        if (airRecoveryFallback.getAsBoolean() && actor.isCurrent()) {
            actor.setGoingToBreath(false);
            actor.eraseTargetPos();
        }
    }

    private static double distanceSquared(Point a, Point b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void add(List<Point> points, Point candidate) {
        for (var p : points) if (distanceSquared(p, candidate) == 0) return;
        points.add(candidate);
    }

    public void onDeselected(TransitionReason reason) {
        cancel();
    }

    public void cancel() {
        attemptedMount = null;
        attemptedSit = false;
        nextRelease = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
        candidates.clear();
        candidateIndex = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
        searchOrigin = null;
        nextSearch = nextNearbySearch = EnhancedLocomotionTuning.ENHANCED_BREATHING_CANDIDATE_INDEX;
        lastTick = Long.MIN_VALUE;
        if (sensing != null) sensing.cancel(MaidSensingBudget.Kind.AIR);
    }

    public long emergencyReleases() {
        return releases;
    }
}
