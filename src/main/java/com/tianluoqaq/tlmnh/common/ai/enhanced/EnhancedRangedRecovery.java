package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.ballistics.BallisticVector;

/** 已确认拒发后的短期让位与局部换位；导航仍由完整N原有预算入口执行 */
final class EnhancedRangedRecovery {

    private static final int[][] OFFSETS = {
        { EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM, EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE },
        { EnhancedAiTuning.ENHANCED_RANGED_DOMAIN_PARAM, EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE },
        { EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE, EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM },
        { EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE, EnhancedAiTuning.ENHANCED_RANGED_DOMAIN_PARAM },
        { EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM,
            EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM },
        { EnhancedAiTuning.ENHANCED_RANGED_DOMAIN_PARAM, EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM },
        { EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM, EnhancedAiTuning.ENHANCED_RANGED_DOMAIN_PARAM },
        { EnhancedAiTuning.ENHANCED_RANGED_DOMAIN_PARAM, EnhancedAiTuning.ENHANCED_RANGED_DOMAIN_PARAM } };
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
                e -> now < e.getValue().at || now - e.getValue().at >= EnhancedAiTuning.RETRY_TICKS
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
        if (failures.size() >= EnhancedAiTuning.MAX_SIZE_THRESHOLD) failures.clear();
        failures.put(target, refusal);
        refusals++;
        outcome = refusal.reason;
        if (recovering != target) {
            cancel();
            recovering = target;
        }
        nextProbe = actor.worldGameTime() + EnhancedAiTuning.PROBE_TICKS;
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
            nextProbe = now + EnhancedAiTuning.PROBE_TICKS;
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
            if (!actor.isEnhancedLandDestination(point)
                || maid.getDistanceSq(
                    point.x + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
                    point.y,
                    point.z + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE) < EnhancedAiTuning.ENHANCED_RANGED_EXECUTE_FACTOR
                || now - routeAt >= EnhancedAiTuning.ROUTE_TICKS) {
                actor.eraseWalkTarget();
                point = null;
            } else {
                actor.setPositionWalkAndLook(
                    point,
                    EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
                    EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE);
                return true;
            }
        }
        if (now < nextPoint) return true;
        nextPoint = now + EnhancedAiTuning.POINT_TICKS;
        // 每次最多两个当前落点，不扫描整个区域；实际A*仍由原navigation.tick统一预算
        for (int checked = 0; checked < EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM
            && candidate < OFFSETS.length
                * EnhancedAiTuning.ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_SECOND_PARAM; checked++, candidate++) {
            int[] offset = OFFSETS[candidate % OFFSETS.length];
            int dy = candidate / OFFSETS.length == 0 ? EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE
                : candidate / OFFSETS.length == EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP
                    ? EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP
                    : EnhancedAiTuning.UNSET_OR_NOT_FOUND;
            var proposed = new Point(
                MathHelper.floor_double(maid.posX) + offset[EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE],
                MathHelper.floor_double(maid.boundingBox.minY) + dy,
                MathHelper.floor_double(maid.posZ) + offset[EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP]);
            if (!actor.isEnhancedLandDestination(proposed)) continue;
            outcome = preview(
                target,
                proposed.x + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
                proposed.y,
                proposed.z + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
                permitted);
            if (!actor.isCurrent() || !permitted.getAsBoolean()) {
                cancel();
                return true;
            }
            if (outcome == MaidBowAttack.Status.READY) {
                candidate++;
                point = proposed;
                routeAt = now;
                moves++;
                actor.setPositionWalkAndLook(
                    point,
                    EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
                    EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE);
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
            .preview(
                target,
                new BallisticVector(
                    x,
                    y + maid.getEyeHeight() - EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_WALK_FACTOR,
                    z),
                permitted);
    }

    void cancel() {
        recovering = null;
        point = null;
        candidate = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
        nextProbe = nextPoint = routeAt = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
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
