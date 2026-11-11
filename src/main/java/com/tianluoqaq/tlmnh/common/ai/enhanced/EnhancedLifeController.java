package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.BlockBed;
import net.minecraft.tileentity.TileEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.EnhancedSupportTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidDomainGuard;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidTargetCooldowns;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidSensingSession;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeIntent.Action;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.life.MaidLifeTuning;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;

/** 增强策略只读候选与实际生活动作；动作共享平台物品提交，不借用旧root */
public final class EnhancedLifeController {

    public static final MaidBehaviorId ID = MaidBehaviorId.builtin(EnhancedAiIds.ID_LIFE_CARE);

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidLifeSensor sensor;
    private final MaidSensingSession sensing;
    private List<MaidLifeCandidate> observations = Collections.emptyList();
    private MaidPolicyContext.Token observedToken;
    private boolean observedRest;
    private long nextScan, nextSnack, approachStartedAt, abandonedCandidates;
    private final MaidTargetCooldowns<TileEntity> failedCandidates = MaidTargetCooldowns
        .forIdentityClearOnFull(EnhancedAiTuning.MAX_FAILED_CANDIDATES);
    private TileEntity approachedCandidate;
    private TileEntityMaidBed reservedBed;
    private int bites, placements, sleepEntries;
    private long scans;

    public EnhancedLifeController(EntityMaid maid, ModernFollowCoreActor actor, MaidSensingSession sensing) {
        this.maid = maid;
        this.actor = actor;
        sensor = new MaidLifeSensor(maid);
        this.sensing = sensing;
    }

    public void offer(MaidIntentFrame frame, MaidPolicyContext context) {
        if (!permitted()) {
            sensing.cancel(MaidSensingBudget.Kind.LIFE);
            return;
        }
        long now = maid.worldObj.getTotalWorldTime();
        if (failedCandidates.advanceTick(now)) {
            nextScan = nextSnack = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
            approachStartedAt = now;
        }
        failedCandidates.purgeExpired(now, (tile, entry) -> tile.isInvalid() || tile.getWorldObj() != maid.worldObj);
        boolean rest = MaidLifeRules.isRest(maid.getSchedule(), maid.worldObj.getWorldTime());
        int approachLimit = approachedCandidate instanceof TileEntityMaidBed ? EnhancedAiTuning.MAX_BED_APPROACH_TICKS
            : EnhancedAiTuning.MAX_SNACK_APPROACH_TICKS;
        if (approachedCandidate != null && !actor.isSleeping() && now - approachStartedAt > approachLimit) {
            failedCandidates.record(approachedCandidate, now + EnhancedAiTuning.CANDIDATE_RETRY_TICKS);
            abandonedCandidates++;
            cancel();
        }
        if (!rest && now < nextSnack) {
            sensing.cancel(MaidSensingBudget.Kind.LIFE);
            return;
        }
        if (observedToken != context.getToken() || observedRest != rest) {
            observations = Collections.emptyList();
            sensor.clear();
            observedToken = context.getToken();
            observedRest = rest;
            nextScan = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
        }
        if (reservedBed != null && rest && legalBed(reservedBed)) {
            sensing.cancel(MaidSensingBudget.Kind.LIFE);
            offer(frame, observation(reservedBed, context), Action.BED, context);
            return;
        }
        if (now >= nextScan && sensing.acquire(MaidSensingBudget.Kind.LIFE)) {
            observations = sensor.scan(context, rest);
            scans++;
            nextScan = now + EnhancedAiTuning.SCAN_INTERVAL;
        }
        // 接近中的合法柜保留到完成或本次期限，失败候选冷却过期不会抢走正在走的路线
        if (!rest && approachedCandidate != null) for (var observation : observations) {
            var tile = sensor.resolve(observation, context);
            if (tile == approachedCandidate && !failedCandidates.contains(tile)
                && offerSnack(frame, observation, tile, context)) return;
        }
        for (var observation : observations) {
            var tile = sensor.resolve(observation, context);
            if (failedCandidates.contains(tile)) continue;
            if (rest) {
                if (tile instanceof TileEntityMaidBed bed && legalBed(bed))
                    offer(frame, observation, Action.BED, context);
            } else if (tile != null) offerSnack(frame, observation, tile, context);
        }
    }

    private boolean offerSnack(MaidIntentFrame frame, MaidLifeCandidate observation, TileEntity tile,
        MaidPolicyContext context) {
        var point = snackPoint(tile);
        if (!actor.withinLifeRestriction(point) || !actor.workPointNearOwner(point)) return false;
        if (actor.shouldStealCake(point)) offer(frame, observation, Action.STEAL, context);
        else if (actor.shouldPlaceCake(point, actor.firstCakeStack())) offer(frame, observation, Action.PLACE, context);
        else return false;
        return true;
    }

    private void offer(MaidIntentFrame frame, MaidLifeCandidate observation, Action action, MaidPolicyContext context) {
        double distance = maid.getDistanceSq(
            observation.getX() + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
            observation.getY() + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE,
            observation.getZ() + EnhancedAiTuning.ENHANCED_LIFE_DISTANCE);
        var intent = new MaidLifeIntent(action, observation, distance);
        frame.offerTyped(
            ID,
            action == Action.BED ? Group.CARE : Group.AUTONOMOUS,
            action == Action.BED ? Kind.SLEEP : Kind.SNACK,
            observation.getDimension() + EnhancedAiIds.COLON_DELIMITER
                + observation.getX()
                + EnhancedAiIds.COLON_DELIMITER
                + observation.getY()
                + EnhancedAiIds.COLON_DELIMITER
                + observation.getZ()
                + EnhancedAiIds.COLON_DELIMITER
                + action,
            EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP
                / (EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP + Math.sqrt(distance)),
            intent,
            selected -> execute(selected, context));
    }

    private MaidLifeCandidate observation(TileEntityMaidBed bed, MaidPolicyContext context) {
        return new MaidLifeCandidate(
            MaidLifeCandidate.Kind.BED,
            maid.dimension,
            bed.xCoord,
            bed.yCoord,
            bed.zCoord,
            maid.worldObj.getTotalWorldTime(),
            maid.worldObj,
            context,
            bed);
    }

    public void execute(MaidLifeIntent intent, MaidPolicyContext context) {
        if (!permitted() || intent == null) return;
        var tile = sensor.resolve(intent.getCandidate(), context);
        if (tile == null || failedCandidates.contains(tile)) return;
        boolean rest = MaidLifeRules.isRest(maid.getSchedule(), maid.worldObj.getWorldTime());
        if ((intent.getAction() == Action.BED) != rest) return;
        if (intent.getAction() == Action.BED) {
            if (!(tile instanceof TileEntityMaidBed bed) || !legalBed(bed) || !bed.claim(maid)) return;
            if (!actor.isCurrent()) {
                bed.release(maid);
                return;
            }
            if (reservedBed != bed) {
                if (reservedBed != null) reservedBed.release(maid);
                reservedBed = bed;
                beginApproach(bed);
            }
            if (actor.isSleeping()) return;
            var point = new Point(bed.xCoord, bed.yCoord, bed.zCoord);
            if (actor.distanceToBlockCenterSquared(point) < EnhancedAiTuning.ENHANCED_HOME_RECOVERY_DISTANCE) {
                actor.stopNavigation();
                actor.eraseWalkTarget();
                actor.setPathMemory(null);
                actor.sleepAt(point);
                if (actor.isCurrent() && actor.isSleeping()) sleepEntries++;
            } else actor.setPositionWalkAndLook(
                point,
                EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
                EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP);
            return;
        }
        long now = maid.worldObj.getTotalWorldTime();
        if (now < nextSnack) return;
        var point = snackPoint(tile);
        if (!actor.withinLifeRestriction(point) || !actor.workPointNearOwner(point)) return;
        boolean stealing = intent.getAction() == Action.STEAL;
        if (!(stealing ? actor.shouldStealCake(point) : actor.shouldPlaceCake(point, actor.firstCakeStack()))) return;
        if (actor.distanceToBlockCenterSquared(point) > EnhancedAiTuning.ENHANCED_HOME_RECOVERY_DISTANCE) {
            beginApproach(tile);
            actor.setPositionWalkAndLook(
                point,
                EnhancedAiTuning.ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S,
                EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP);
            return;
        }
        actor.stopNavigation();
        actor.eraseWalkTarget();
        actor.setPathMemory(null);
        boolean used = stealing ? actor.consumeCake(point) : actor.placeCake(point);
        if (!actor.isCurrent() || !used) return;
        approachedCandidate = null;
        nextSnack = now + MaidLifeTuning.SNACK_INTERVAL;
        if (stealing) {
            bites++;
            actor.rewardSteal();
        } else placements++;
        actor.swingMainHand();
    }

    private void beginApproach(TileEntity tile) {
        if (approachedCandidate == tile) return;
        approachedCandidate = tile;
        approachStartedAt = maid.worldObj.getTotalWorldTime();
    }

    private boolean permitted() {
        if (!actor.isCurrent() || !maid.isTamed()
            || maid.isSitting()
            || maid.ridingEntity != null
            || maid.riddenByEntity != null
            || maid.getLeashed()
            || maid.hurtTime > 0
            || maid.isBurning()
            || maid.isInWater()
            || maid.getAttackTarget() != null
            || maid.getItemUseState()
                .peek() != null)
            return false;
        return MaidDomainGuard.isMaidWithinDomain(maid, actor, EnhancedSupportTuning.LIFE_OWNER_DOMAIN_RADIUS);
    }

    private boolean legalBed(TileEntityMaidBed bed) {
        if (bed == null || bed.isInvalid()
            || bed.getWorldObj() != maid.worldObj
            || !maid.worldObj.blockExists(bed.xCoord, bed.yCoord, bed.zCoord)
            || maid.worldObj.getTileEntity(bed.xCoord, bed.yCoord, bed.zCoord) != bed
            || !bed.complete()
            || !bed.available(maid)
            || !actor.withinLifeRestriction(new Point(bed.xCoord, bed.yCoord, bed.zCoord))) return false;
        int direction = maid.worldObj.getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord)
            & EnhancedAiTuning.ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_SECOND_PARAM;
        for (int part = 0; part < EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM; part++) {
            int x = bed.xCoord
                - BlockBed.field_149981_a[direction][EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE] * part;
            int z = bed.zCoord
                - BlockBed.field_149981_a[direction][EnhancedAiTuning.ENHANCED_AMBIENT_OFFER_UNIT_STEP] * part;
            for (int dy = 1; dy <= EnhancedAiTuning.ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM; dy++)
                if (!maid.worldObj.blockExists(x, bed.yCoord + dy, z)
                    || !maid.worldObj.isAirBlock(x, bed.yCoord + dy, z)) return false;
        }
        return true;
    }

    private static Point snackPoint(TileEntity tile) {
        return new Point(tile.xCoord, tile.yCoord + 1, tile.zCoord);
    }

    public void onTargetChanged() {
        var bed = reservedBed;
        reservedBed = null;
        approachedCandidate = null;
        if (bed != null) bed.release(maid);
    }

    public void onDeselected(TransitionReason reason) {
        cancel();
    }

    /** 不访问失效actor，不恢复已提交物品；床租约只按实际持有身份释放 */
    public void cancel() {
        var bed = reservedBed;
        reservedBed = null;
        approachedCandidate = null;
        observations = Collections.emptyList();
        observedToken = null;
        nextScan = EnhancedAiTuning.ENHANCED_AMBIENT_NEXT_CHOICE;
        sensor.clear();
        sensing.cancel(MaidSensingBudget.Kind.LIFE);
        if (bed != null) bed.release(maid);
    }

    public int bites() {
        return bites;
    }

    public int placements() {
        return placements;
    }

    public int sleepEntries() {
        return sleepEntries;
    }

    public long scanCount() {
        return scans;
    }

    public long abandonedCandidates() {
        return abandonedCandidates;
    }
}
