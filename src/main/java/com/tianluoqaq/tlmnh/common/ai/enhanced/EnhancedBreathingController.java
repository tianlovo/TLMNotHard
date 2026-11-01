package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirStopTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 增强呼吸独立处理离座与移动，手部被其他动作持有不阻止安全找空气 */
public final class EnhancedBreathingController {

    private static final int NEARBY_RETRY_TICKS = 10, EXHAUSTED_RETRY_TICKS = 20;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final EnhancedNavigationController navigation;
    private final EnhancedFollowController follow;
    private final MaidBreathAirStopTask stop = new MaidBreathAirStopTask();
    private Entity attemptedMount;
    private boolean attemptedSit;
    private long nextRelease, releases;
    private List<Point> candidates = new ArrayList<>();
    private int candidateIndex;
    private Point searchOrigin;
    private long nextSearch, nextNearbySearch, lastTick = Long.MIN_VALUE;

    public EnhancedBreathingController(EntityMaid maid, ModernFollowCoreActor actor,
        EnhancedNavigationController navigation, EnhancedFollowController follow) {
        this.maid = maid;
        this.actor = actor;
        this.navigation = navigation;
        this.follow = follow;
    }

    public void stopIfRecovered() {
        if (stop.checkExtraStartConditions(actor)) stop.start(actor);
    }

    public void execute() {
        if (actor.hasBreathingEffect() || actor.hasDrownBauble() || actor.isEatBreatheItem()) return;
        if (actor.airSupply() >= 100 && !actor.isGoingToBreath()) return;
        if (!actor.isUsingItem() && actor.tryEatBreatheItem()) return;
        if (!actor.isCurrent()) return;
        if (actor.airSupply() < 100) {
            actor.clearSleepingIfPresent();
            if (!actor.isCurrent()) return;
            if (EnhancedRideController.stationary(maid)) {
                long now = actor.worldGameTime();
                if (now >= nextRelease || attemptedMount != maid.ridingEntity || attemptedSit != maid.isSitting()) {
                    attemptedMount = maid.ridingEntity;
                    attemptedSit = maid.isSitting();
                    nextRelease = now + 20;
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
            candidateIndex = 0;
            nextSearch = nextNearbySearch = 0;
        }
        lastTick = now;
        if (candidates.isEmpty() || now >= nextNearbySearch) {
            if (now < nextSearch || !actor.canScanForAir()) return;
            var found = actor.findAirWithBfs(16);
            if (!actor.isCurrent()) return;
            var next = new ArrayList<Point>();
            if (found.isPresent()) add(next, found.get());
            if (candidates.isEmpty()) {
                int sea = actor.seaLevel() + 1;
                var center = new Point(origin.x, sea, origin.z);
                if (actor.givesAir(center)) add(next, center);
                for (int z = origin.z - 2; z <= origin.z + 2; z++) for (int x = origin.x - 2; x <= origin.x + 2; x++) {
                    var p = new Point(x, sea, z);
                    if (actor.givesAir(p)) add(next, p);
                }
            } else {
                // 重试附近空气时保留尚未检查的回退点，不能每帧从海平面第一个点重来
                for (int i = candidateIndex; i < candidates.size(); i++) add(next, candidates.get(i));
            }
            candidates = next;
            candidateIndex = 0;
            searchOrigin = origin;
            nextNearbySearch = now + NEARBY_RETRY_TICKS;
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
                    actor.setAirWalkAndLook(p, .5F, 1);
                    navigation.acceptPreflight(position, result, now);
                    return;
                }
            }
        }
        candidates.clear();
        candidateIndex = 0;
        nextSearch = now + EXHAUSTED_RETRY_TICKS;
        if (follow.recoverForAir() && actor.isCurrent()) {
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

    public void cancel() {
        attemptedMount = null;
        attemptedSit = false;
        nextRelease = 0;
        candidates.clear();
        candidateIndex = 0;
        searchOrigin = null;
        nextSearch = nextNearbySearch = 0;
        lastTick = Long.MIN_VALUE;
    }

    public long emergencyReleases() {
        return releases;
    }
}
