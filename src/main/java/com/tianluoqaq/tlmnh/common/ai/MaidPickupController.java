package com.tianluoqaq.tlmnh.common.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidOwnerSensor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 参考上游30tick拾取感知和可达性筛选，跟随/战斗/生活优先，移动仍由同一动作执行器授权 */
public final class MaidPickupController {

    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final Supplier<EntityPlayer> findOwner;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner(true);
    private MaidActionRun run;
    private PathEntity ownedPath;
    private Entity target;
    private World observedWorld;
    private UUID ignored;
    private long nextScan, nextPath, expires, ignoreUntil;
    private double plannedX, plannedY, plannedZ;
    private boolean stopping, waitingPath;
    private Entity preferredIntent;
    private long nextIntentScan;
    private long lastIntentTime = Long.MIN_VALUE;

    /** 发现不创建路径或认领动作，执行端仍重检当前实体与主人的边界 */
    public List<Entity> prepareIntents() {
        long now = maid.worldObj.getTotalWorldTime();
        if (now < lastIntentTime) nextIntentScan = MaidAiTuning.NAVIGATION_INITIAL_VALUE;
        lastIntentTime = now;
        var home = maid.getHomeArea();
        if (!movementAllowed()) return Collections.emptyList();
        EntityPlayer owner = home.enabled() ? null : findOwner.get();
        if (!home.enabled() && (!MaidOwnerSensor.isValid(maid, owner)
            || maid.getDistanceSqToEntity(owner) > MaidAiTuning.START_DISTANCE_SQ)) return Collections.emptyList();
        if (target != null) {
            if (now >= expires && !waitingPath) {
                ignore(target, now);
                return Collections.emptyList();
            }
            return eligible(target, owner, now) ? Collections.singletonList(target) : Collections.emptyList();
        }
        if (now < nextIntentScan) return Collections.emptyList();
        nextIntentScan = now + MaidAiTuning.SCAN_INTERVAL_2;
        return candidates(owner, now);
    }

    public boolean tickSelected(Entity selected) {
        if (target != null && target != selected) cancel();
        preferredIntent = selected;
        try {
            return tick(true);
        } finally {
            preferredIntent = null;
        }
    }

    private boolean movementAllowed() {
        var home = maid.getHomeArea();
        return !maid.worldObj.isRemote && maid.isEntityAlive()
            && maid.isTamed()
            && maid.isPickup()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && maid.riddenByEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && maid.getAttackTarget() == null
            && (!home.enabled() || home.sameDimension() && home.containsMaid() && home.activity() != Activity.REST);
    }

    public MaidPickupController(EntityMaid maid, MaidActionRunner runner, Supplier<EntityPlayer> findOwner) {
        this.maid = maid;
        this.runner = runner;
        this.findOwner = findOwner;
    }

    public boolean tick(boolean permitted) {
        if (stopping) return false;
        if (observedWorld != maid.worldObj) {
            cancel();
            observedWorld = maid.worldObj;
            nextScan = MaidAiTuning.NAVIGATION_INITIAL_VALUE;
            ignored = null;
        }
        long now = maid.worldObj.getTotalWorldTime();
        var home = maid.getHomeArea();
        if (!permitted || !movementAllowed()) {
            cancel();
            return false;
        }
        EntityPlayer owner = home.enabled() ? null : findOwner.get();
        if (!home.enabled() && (!MaidOwnerSensor.isValid(maid, owner)
            || maid.getDistanceSqToEntity(owner) > MaidAiTuning.START_DISTANCE_SQ)) {
            cancel();
            return false;
        }
        if (waitingPath) expires++;
        if (target != null && (now >= expires || !eligible(target, owner, now)
            || !runner.isOwned(run, maid.worldObj, maid.getTask()))) {
            if (now >= expires) ignore(target, now);
            cancel();
        }
        if (target == null) {
            if (now < nextScan && preferredIntent == null) return false;
            nextScan = now + MaidAiTuning.SCAN_INTERVAL_2;
            if (!select(owner, now)) return false;
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) {
            cancel();
            return false;
        }
        maid.getLookHelper()
            .setLookPosition(
                target.posX,
                target.posY,
                target.posZ,
                MaidAiTuning.HOME_CONTROLLER_TICK_PARAM,
                maid.getVerticalFaceSpeed());
        boolean moved = target.getDistanceSq(plannedX, plannedY, plannedZ) > MaidAiTuning.PICKUP_CONTROLLER_TICK_FACTOR;
        if (now >= nextPath && (waitingPath || moved
            || maid.getNavigator()
                .noPath())) {
            nextPath = now + MaidAiTuning.HOME_CONTROLLER_TICK_PARAM;
            var result = pathTo(target);
            waitingPath = result.isDeferred();
            if (waitingPath) nextPath = now + 1;
            else if (result != PathResult.STARTED) {
                ignore(target, now);
                cancel();
                return false;
            }
        }
        return true;
    }

    private boolean eligible(Entity entity, EntityPlayer owner, long now) {
        if (entity == null || entity.worldObj != maid.worldObj
            || entity.isDead
            || entity.getUniqueID()
                .equals(ignored) && now < ignoreUntil
            || !maid.getHomeArea()
                .contains(entity.posX, entity.posY, entity.posZ)
            || owner != null && owner.getDistanceSqToEntity(entity) > MaidAiTuning.START_DISTANCE_SQ) return false;
        return maid.canPickup(entity, true) && maid.getEntitySenses()
            .canSee(entity);
    }

    private boolean select(EntityPlayer owner, long now) {
        var options = preferredIntent == null ? candidates(owner, now)
            : eligible(preferredIntent, owner, now) ? Collections.singletonList(preferredIntent)
                : Collections.<Entity>emptyList();
        for (var candidate : options) {
            var result = pathTo(candidate);
            if (result == PathResult.STARTED || result.isDeferred()) {
                target = candidate;
                expires = now + MaidAiTuning.PICKUP_CONTROLLER_EXPIRES;
                waitingPath = result.isDeferred();
                nextPath = now + (waitingPath ? MaidAiTuning.MAX_PERSISTENT_READ_DEPTH_THRESHOLD
                    : MaidAiTuning.HOME_CONTROLLER_TICK_PARAM);
                return true;
            }
            ignore(candidate, now);
            cancel();
        }
        return false;
    }

    private List<Entity> candidates(EntityPlayer owner, long now) {
        int radius = maid.getHomeArea()
            .enabled()
                ? Math.min(
                    MaidAiTuning.HOME_CONTROLLER_MIN_SECOND_PARAM,
                    maid.getHomeArea()
                        .radius())
                : MaidAiTuning.LEGACY_BRAIN_EXPAND_SECOND_PARAM;
        var returned = maid.worldObj.getEntitiesWithinAABBExcludingEntity(
            maid,
            maid.boundingBox.expand(radius, MaidAiTuning.CHUNK_SECTION_SHIFT, radius));
        var candidates = new ArrayList<Entity>();
        var nearest = Comparator.comparingDouble((Entity entity) -> maid.getDistanceSqToEntity(entity));
        int inspected = 0;
        for (Object value : returned) {
            if (inspected++ >= MaidAiTuning.CANDIDATE_BUDGET) break;
            if (!(value instanceof Entity entity) || !eligible(entity, owner, now)) continue;
            candidates.add(entity);
            candidates.sort(nearest);
            if (candidates.size() > MaidAiTuning.PATH_CANDIDATES) candidates.remove(MaidAiTuning.PATH_CANDIDATES);
        }
        maid.getBrainCoordinator()
            .getTelemetry()
            .scan(
                MaidAiIds.ID_PICKUP_CANDIDATES,
                returned.size(),
                Math.min(inspected, MaidAiTuning.CANDIDATE_BUDGET),
                MaidAiIds.ID_BOUNDED_SCAN_PARTIAL + (returned.size() > MaidAiTuning.CANDIDATE_BUDGET));
        return candidates;
    }

    private PathResult pathTo(Entity entity) {
        var proposed = run == null ? runner.newRun(maid.worldObj, maid.getTask()) : run;
        if (proposed == null || !proposed.isCurrent(maid.worldObj, maid.getTask())) return PathResult.STALE_REQUEST;
        // 原生整数重载先加0.5再减实体半宽，直接传掉落物所在格
        var created = planner.create(
            maid,
            Math.floor(entity.posX),
            Math.floor(entity.boundingBox.minY),
            Math.floor(entity.posZ),
            MaidAiTuning.PICKUP_CONTROLLER_CREATE_FIFTH_FACTOR);
        if (created.preflightFailure != null && created.preflightFailure.isDeferred()) {
            if (run == null) {
                if (!runner.claim(Owner.PICKUP, proposed, this::cancel)) return PathResult.STALE_REQUEST;
                run = proposed;
            }
            return created.preflightFailure;
        }
        var path = created.path;
        if (!proposed.isCurrent(maid.worldObj, maid.getTask())) return PathResult.STALE_REQUEST;
        if (path == null || path.getCurrentPathLength() == 0) return PathResult.UNREACHABLE;
        var end = path.getFinalPathPoint();
        double dx = end.xCoord + MaidAiTuning.CENTER_OFFSET_D - entity.posX,
            dz = end.zCoord + MaidAiTuning.CENTER_OFFSET_D - entity.posZ;
        if (dx * dx + dz * dz > MaidAiTuning.CENTER_OFFSET_D
            || Math.abs(end.yCoord - entity.boundingBox.minY) > MaidAiTuning.MAX_PERSISTENT_READ_DEPTH_THRESHOLD)
            return PathResult.UNREACHABLE;
        if (run == null) {
            if (!runner.claim(Owner.PICKUP, proposed, this::cancel)) return PathResult.STALE_REQUEST;
            run = proposed;
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) return PathResult.STALE_REQUEST;
        var installed = planner.install(maid, created);
        if (installed.result != PathResult.STARTED) return installed.result;
        ownedPath = installed.installedPath;
        plannedX = entity.posX;
        plannedY = entity.posY;
        plannedZ = entity.posZ;
        return runner.isOwned(run, maid.worldObj, maid.getTask()) ? PathResult.STARTED : PathResult.STALE_REQUEST;
    }

    private void ignore(Entity entity, long now) {
        ignored = entity.getUniqueID();
        ignoreUntil = now + MaidAiTuning.LEGACY_BRAIN_IGNORE_UNTIL;
    }

    public void cancel() {
        if (stopping) return;
        stopping = true;
        var stopped = run;
        try {
            if (stopped != null) stopped.cancel();
            if (ownedPath != null && MaidPathOwnership.owns(
                maid.getNavigator()
                    .getPath(),
                ownedPath))
                maid.getNavigator()
                    .clearPathEntity();
        } finally {
            target = null;
            ownedPath = null;
            run = null;
            nextPath = MaidAiTuning.NAVIGATION_INITIAL_VALUE;
            nextIntentScan = MaidAiTuning.NAVIGATION_INITIAL_VALUE;
            lastIntentTime = Long.MIN_VALUE;
            waitingPath = false;
            runner.release(stopped);
            stopping = false;
        }
    }

    public Entity getTarget() {
        return target;
    }

    public boolean isWaitingForPath() {
        return waitingPath;
    }
}
