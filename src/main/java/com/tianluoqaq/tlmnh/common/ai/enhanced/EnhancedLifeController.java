package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.BlockBed;
import net.minecraft.tileentity.TileEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent.Action;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeSensor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;

/** 增强策略只读候选与实际生活动作；动作共享平台物品提交，不借用旧root */
public final class EnhancedLifeController {

    private static final int SCAN_INTERVAL = 20, MAX_BED_APPROACH_TICKS = 200, BED_RETRY_TICKS = 40;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidLifeSensor sensor;
    private List<MaidLifeCandidate> observations = Collections.emptyList();
    private MaidPolicyContext.Token observedToken;
    private boolean observedRest;
    private long nextScan, nextSnack, bedSelectedAt, nextBedAttempt;
    private TileEntityMaidBed reservedBed;
    private int bites, placements, sleepEntries;

    public EnhancedLifeController(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
        sensor = new MaidLifeSensor(maid);
    }

    public void offer(MaidIntentFrame frame, MaidPolicyContext context) {
        if (!permitted()) return;
        long now = maid.worldObj.getTotalWorldTime();
        boolean rest = MaidLifeRules.isRest(maid.getSchedule(), maid.worldObj.getWorldTime());
        if (reservedBed != null && !actor.isSleeping() && now - bedSelectedAt > MAX_BED_APPROACH_TICKS) {
            cancel();
            nextBedAttempt = now + BED_RETRY_TICKS;
        }
        if (rest && now < nextBedAttempt || !rest && now < nextSnack) return;
        if (observedToken != context.getToken() || observedRest != rest || now >= nextScan) {
            observations = sensor.scan(context, rest);
            observedToken = context.getToken();
            observedRest = rest;
            nextScan = now + SCAN_INTERVAL;
        }
        if (reservedBed != null && rest && legalBed(reservedBed)) {
            offer(frame, observation(reservedBed, context), Action.BED);
            return;
        }
        for (var observation : observations) {
            var tile = sensor.resolve(observation, context);
            if (rest) {
                if (tile instanceof TileEntityMaidBed bed && legalBed(bed)) offer(frame, observation, Action.BED);
            } else if (tile != null) {
                var point = snackPoint(tile);
                if (!actor.withinLifeRestriction(point) || !actor.workPointNearOwner(point)) continue;
                if (actor.shouldStealCake(point)) offer(frame, observation, Action.STEAL);
                else if (actor.shouldPlaceCake(point, actor.firstCakeStack())) offer(frame, observation, Action.PLACE);
            }
        }
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
        if (tile == null) return;
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
                bedSelectedAt = maid.worldObj.getTotalWorldTime();
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
            actor.setPositionWalkAndLook(point, .6F, 1);
            return;
        }
        actor.stopNavigation();
        actor.eraseWalkTarget();
        actor.setPathMemory(null);
        boolean used = stealing ? actor.consumeCake(point) : actor.placeCake(point);
        if (!actor.isCurrent() || !used) return;
        nextSnack = now + MaidLifeRules.SNACK_INTERVAL;
        if (stealing) {
            bites++;
            actor.rewardSteal();
        } else placements++;
        actor.swingMainHand();
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
        if (reservedBed != null) reservedBed.release(maid);
        reservedBed = null;
        observations = Collections.emptyList();
        observedToken = null;
        nextScan = 0;
        sensor.clear();
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
}
