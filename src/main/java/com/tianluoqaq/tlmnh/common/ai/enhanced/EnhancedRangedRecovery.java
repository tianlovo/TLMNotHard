package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.combat.BallisticVector;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;

/** 已确认拒发后的短期让位与局部换位；导航仍由完整N原有预算入口执行 */
final class EnhancedRangedRecovery {

    private static final int RETRY_TICKS = 100, PROBE_TICKS = 10, POINT_TICKS = 20, ROUTE_TICKS = 40;
    private static final int[][] OFFSETS = { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 }, { 2, 2 }, { -2, 2 }, { 2, -2 },
        { -2, -2 } };
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final Map<EntityLivingBase, MaidBowAttack.Refusal> failures = new IdentityHashMap<>();
    private EntityLivingBase recovering;
    private Point point;
    private int candidate;
    private long lastTick = Long.MIN_VALUE, nextProbe, nextPoint, routeAt;
    private long refusals, probes, moves;
    private MaidBowAttack.Status outcome = MaidBowAttack.Status.IDLE;

    EnhancedRangedRecovery(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    void refresh(long now) {
        if (now < lastTick) {
            failures.clear();
            cancel();
        }
        lastTick = now;
        failures.entrySet()
            .removeIf(
                e -> now < e.getValue().at || now - e.getValue().at >= RETRY_TICKS
                    || !e.getValue()
                        .matches(maid, e.getKey())
                    || !e.getValue()
                        .environmentUnchanged());
        if (recovering != null && !failures.containsKey(recovering)) cancel();
    }

    boolean held(EntityLivingBase target) {
        return failures.containsKey(target);
    }

    void rejected(EntityLivingBase target) {
        var refusal = actor.rangedBow()
            .lastRefusal();
        if (refusal == null || !refusal.matches(maid, target)) return;
        if (failures.size() >= 32) failures.clear();
        failures.put(target, refusal);
        refusals++;
        outcome = refusal.reason;
        if (recovering != target) {
            cancel();
            recovering = target;
        }
        nextProbe = actor.worldGameTime() + PROBE_TICKS;
    }

    /** true表示本tick只恢复/等待，不能继续拉弓；false表示回到正常攻击入口 */
    boolean execute(EntityLivingBase target, boolean stationary, BooleanSupplier permitted) {
        if (!held(target)) {
            cancel();
            return false;
        }
        if (recovering != target) {
            cancel();
            recovering = target;
        }
        long now = actor.worldGameTime();
        if (now >= nextProbe) {
            nextProbe = now + PROBE_TICKS;
            outcome = preview(target, maid.posX, maid.posY, maid.posZ, permitted);
            if (!actor.isCurrent() || !permitted.getAsBoolean()) {
                cancel();
                return true;
            }
            if (outcome == MaidBowAttack.Status.READY) {
                failures.remove(target);
                actor.eraseWalkTarget();
                cancel();
                return false;
            }
            if (outcome != MaidBowAttack.Status.BLOCKED && outcome != MaidBowAttack.Status.NO_SOLUTION) {
                actor.eraseWalkTarget();
                point = null;
                return true;
            }
        }
        if (outcome != MaidBowAttack.Status.BLOCKED && outcome != MaidBowAttack.Status.NO_SOLUTION
            && outcome != MaidBowAttack.Status.READY) {
            actor.eraseWalkTarget();
            point = null;
            return true;
        }
        if (stationary || !actor.canBrainMoving() || maid.getLeashed() || maid.isInWater()) {
            actor.eraseWalkTarget();
            point = null;
            return true;
        }
        if (point != null) {
            if (!actor.isEnhancedLandDestination(point) || maid.getDistanceSq(point.x + .5, point.y, point.z + .5) < .75
                || now - routeAt >= ROUTE_TICKS) {
                actor.eraseWalkTarget();
                point = null;
            } else {
                actor.setPositionWalkAndLook(point, .6F, 0);
                return true;
            }
        }
        if (now < nextPoint) return true;
        nextPoint = now + POINT_TICKS;
        // 每次最多两个当前落点，不扫描整个区域；实际A*仍由原navigation.tick统一预算
        for (int checked = 0; checked < 2 && candidate < OFFSETS.length * 3; checked++, candidate++) {
            int[] offset = OFFSETS[candidate % OFFSETS.length];
            int dy = candidate / OFFSETS.length == 0 ? 0 : candidate / OFFSETS.length == 1 ? 1 : -1;
            var proposed = new Point(
                MathHelper.floor_double(maid.posX) + offset[0],
                MathHelper.floor_double(maid.boundingBox.minY) + dy,
                MathHelper.floor_double(maid.posZ) + offset[1]);
            if (!actor.isEnhancedLandDestination(proposed)) continue;
            outcome = preview(target, proposed.x + .5, proposed.y, proposed.z + .5, permitted);
            if (!actor.isCurrent() || !permitted.getAsBoolean()) {
                cancel();
                return true;
            }
            if (outcome == MaidBowAttack.Status.READY) {
                candidate++;
                point = proposed;
                routeAt = now;
                moves++;
                actor.setPositionWalkAndLook(point, .6F, 0);
                return true;
            }
            if (outcome != MaidBowAttack.Status.BLOCKED && outcome != MaidBowAttack.Status.NO_SOLUTION) return true;
        }
        return true;
    }

    private MaidBowAttack.Status preview(EntityLivingBase target, double x, double y, double z,
        BooleanSupplier permitted) {
        probes++;
        return actor.rangedBow()
            .preview(target, new BallisticVector(x, y + maid.getEyeHeight() - .1, z), permitted);
    }

    void cancel() {
        recovering = null;
        point = null;
        candidate = 0;
        nextProbe = nextPoint = routeAt = 0;
    }

    long refusals() {
        return refusals;
    }

    long probes() {
        return probes;
    }

    long moves() {
        return moves;
    }

    MaidBowAttack.Status outcome() {
        return outcome;
    }
}
