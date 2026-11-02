package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.BlockBed;
import net.minecraft.tileentity.TileEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent.Action;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingSession;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;

/** 增强策略只读候选与实际生活动作；动作共享平台物品提交，不借用旧root */
public final class EnhancedLifeController {

    private static final int SCAN_INTERVAL = 20, MAX_BED_APPROACH_TICKS = 200, MAX_SNACK_APPROACH_TICKS = 160,
        CANDIDATE_RETRY_TICKS = 100, MAX_FAILED_CANDIDATES = 32;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidLifeSensor sensor;
    private final MaidSensingSession sensing;
    private List<MaidLifeCandidate> observations = Collections.emptyList();
    private MaidPolicyContext.Token observedToken;
    private boolean observedRest;
    private long nextScan, nextSnack, approachStartedAt, lastOfferTick = Long.MIN_VALUE, abandonedCandidates;
    private final Map<TileEntity, Long> failedCandidates = new IdentityHashMap<>();
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
        if (now < lastOfferTick) {
            failedCandidates.clear();
            nextScan = nextSnack = 0;
            approachStartedAt = now;
        }
        lastOfferTick = now;
        failedCandidates.entrySet()
            .removeIf(
                entry -> now >= entry.getValue() || entry.getKey()
                    .isInvalid()
                    || entry.getKey()
                        .getWorldObj() != maid.worldObj);
        boolean rest = MaidLifeRules.isRest(maid.getSchedule(), maid.worldObj.getWorldTime());
        int approachLimit = approachedCandidate instanceof TileEntityMaidBed ? MAX_BED_APPROACH_TICKS
            : MAX_SNACK_APPROACH_TICKS;
        if (approachedCandidate != null && !actor.isSleeping() && now - approachStartedAt > approachLimit) {
            if (failedCandidates.size() >= MAX_FAILED_CANDIDATES) failedCandidates.clear();
            failedCandidates.put(approachedCandidate, now + CANDIDATE_RETRY_TICKS);
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
            nextScan = 0;
        }
        if (reservedBed != null && rest && legalBed(reservedBed)) {
            sensing.cancel(MaidSensingBudget.Kind.LIFE);
            offer(frame, observation(reservedBed, context), Action.BED);
            return;
        }
        if (now >= nextScan && sensing.acquire(MaidSensingBudget.Kind.LIFE)) {
            observations = sensor.scan(context, rest);
            scans++;
            nextScan = now + SCAN_INTERVAL;
        }
        // 接近中的合法柜保留到完成或本次期限，失败候选冷却过期不会抢走正在走的路线
        if (!rest && approachedCandidate != null) for (var observation : observations) {
            var tile = sensor.resolve(observation, context);
            if (tile == approachedCandidate && !failedCandidates.containsKey(tile)
                && offerSnack(frame, observation, tile)) return;
        }
        for (var observation : observations) {
            var tile = sensor.resolve(observation, context);
            if (failedCandidates.containsKey(tile)) continue;
            if (rest) {
                if (tile instanceof TileEntityMaidBed bed && legalBed(bed)) offer(frame, observation, Action.BED);
            } else if (tile != null) offerSnack(frame, observation, tile);
        }
    }

    private boolean offerSnack(MaidIntentFrame frame, MaidLifeCandidate observation, TileEntity tile) {
        var point = snackPoint(tile);
        if (!actor.withinLifeRestriction(point) || !actor.workPointNearOwner(point)) return false;
        if (actor.shouldStealCake(point)) offer(frame, observation, Action.STEAL);
        else if (actor.shouldPlaceCake(point, actor.firstCakeStack())) offer(frame, observation, Action.PLACE);
        else return false;
        return true;
    }

    private void offer(MaidIntentFrame frame, MaidLifeCandidate observation, Action action) {
        double distance = maid.getDistanceSq(observation.getX() + .5, observation.getY() + .5, observation.getZ() + .5);
        var intent = new MaidLifeIntent(action, observation, distance);
        frame.offer(
            action == Action.BED ? Group.CARE : Group.AUTONOMOUS,
            action == Action.BED ? Kind.SLEEP : Kind.SNACK,
            observation.getDimension() + ":"
                + observation.getX()
                + ":"
                + observation.getY()
                + ":"
                + observation.getZ()
                + ":"
                + action,
            1 / (1 + Math.sqrt(distance)),
            intent);
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
        if (tile == null || failedCandidates.containsKey(tile)) return;
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
            if (actor.distanceToBlockCenterSquared(point) < 4) {
                actor.stopNavigation();
                actor.eraseWalkTarget();
                actor.setPathMemory(null);
                actor.sleepAt(point);
                if (actor.isCurrent() && actor.isSleeping()) sleepEntries++;
            } else actor.setPositionWalkAndLook(point, .6F, 1);
            return;
        }
        long now = maid.worldObj.getTotalWorldTime();
        if (now < nextSnack) return;
        var point = snackPoint(tile);
        if (!actor.withinLifeRestriction(point) || !actor.workPointNearOwner(point)) return;
        boolean stealing = intent.getAction() == Action.STEAL;
        if (!(stealing ? actor.shouldStealCake(point) : actor.shouldPlaceCake(point, actor.firstCakeStack()))) return;
        if (actor.distanceToBlockCenterSquared(point) > 4) {
            beginApproach(tile);
            actor.setPositionWalkAndLook(point, .6F, 1);
            return;
        }
        actor.stopNavigation();
        actor.eraseWalkTarget();
        actor.setPathMemory(null);
        boolean used = stealing ? actor.consumeCake(point) : actor.placeCake(point);
        if (!actor.isCurrent() || !used) return;
        approachedCandidate = null;
        nextSnack = now + MaidLifeRules.SNACK_INTERVAL;
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
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid();
        var owner = actor.getOwner();
        return owner != null && !owner.isDeadOrDying()
            && !owner.isSpectator()
            && owner.getWorldIdentity() == actor.getWorldIdentity()
            && actor.closerThan(owner, 12);
    }

    private boolean legalBed(TileEntityMaidBed bed) {
        if (bed == null || bed.isInvalid()
            || bed.getWorldObj() != maid.worldObj
            || !maid.worldObj.blockExists(bed.xCoord, bed.yCoord, bed.zCoord)
            || maid.worldObj.getTileEntity(bed.xCoord, bed.yCoord, bed.zCoord) != bed
            || !bed.complete()
            || !bed.available(maid)
            || !actor.withinLifeRestriction(new Point(bed.xCoord, bed.yCoord, bed.zCoord))) return false;
        int direction = maid.worldObj.getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 3;
        for (int part = 0; part < 2; part++) {
            int x = bed.xCoord - BlockBed.field_149981_a[direction][0] * part;
            int z = bed.zCoord - BlockBed.field_149981_a[direction][1] * part;
            for (int dy = 1; dy <= 2; dy++) if (!maid.worldObj.blockExists(x, bed.yCoord + dy, z)
                || !maid.worldObj.isAirBlock(x, bed.yCoord + dy, z)) return false;
        }
        return true;
    }

    private static Point snackPoint(TileEntity tile) {
        return new Point(tile.xCoord, tile.yCoord + 1, tile.zCoord);
    }

    /** 不访问失效actor，不恢复已提交物品；床租约只按实际持有身份释放 */
    public void cancel() {
        var bed = reservedBed;
        reservedBed = null;
        approachedCandidate = null;
        observations = Collections.emptyList();
        observedToken = null;
        nextScan = 0;
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
