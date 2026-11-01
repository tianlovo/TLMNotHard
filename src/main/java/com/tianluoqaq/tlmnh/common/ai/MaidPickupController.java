package com.tianluoqaq.tlmnh.common.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidOwnerSensor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 参考上游30tick拾取感知和可达性筛选，跟随/战斗/生活优先，移动仍由同一动作执行器授权 */
public final class MaidPickupController {

    private static final int SCAN_INTERVAL = 30, CANDIDATE_BUDGET = 64, PATH_CANDIDATES = 3;
    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final Supplier<EntityPlayer> findOwner;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private MaidActionRun run;
    private PathEntity ownedPath;
    private Entity target;
    private World observedWorld;
    private UUID ignored;
    private long nextScan, nextPath, expires, ignoreUntil;
    private double plannedX, plannedY, plannedZ;
    private boolean stopping;

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
            nextScan = 0;
            ignored = null;
        }
        long now = maid.worldObj.getTotalWorldTime();
        var home = maid.getHomeArea();
        if (!permitted || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || !maid.isTamed()
            || !maid.isPickup()
            || maid.isSitting()
            || maid.ridingEntity != null
            || maid.riddenByEntity != null
            || maid.getLeashed()
            || maid.hasPose(MaidPose.SLEEPING)
            || maid.getBrainCoordinator()
                .isSuspended()
            || maid.getAttackTarget() != null
            || home.enabled() && (!home.sameDimension() || !home.containsMaid() || home.activity() == Activity.REST)) {
            cancel();
            return false;
        }
        EntityPlayer owner = home.enabled() ? null : findOwner.get();
        if (!home.enabled() && (!MaidOwnerSensor.isValid(maid, owner)
            || maid.getDistanceSqToEntity(owner) > MaidFollowController.START_DISTANCE_SQ)) {
            cancel();
            return false;
        }
        if (target != null && (now >= expires || !eligible(target, owner, now)
            || !runner.isOwned(run, maid.worldObj, maid.getTask()))) {
            if (now >= expires) ignore(target, now);
            cancel();
        }
        if (target == null) {
            if (now < nextScan) return false;
            nextScan = now + SCAN_INTERVAL;
            if (!select(owner, now)) return false;
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) {
            cancel();
            return false;
        }
        maid.getLookHelper()
            .setLookPosition(target.posX, target.posY, target.posZ, 20, maid.getVerticalFaceSpeed());
        boolean moved = target.getDistanceSq(plannedX, plannedY, plannedZ) > 0.25;
        if (now >= nextPath && (moved || maid.getNavigator()
            .noPath())) {
            nextPath = now + 20;
            if (!pathTo(target)) {
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
            || owner != null && owner.getDistanceSqToEntity(entity) > MaidFollowController.START_DISTANCE_SQ)
            return false;
        return maid.canPickup(entity, true) && maid.getEntitySenses()
            .canSee(entity);
    }

    private boolean select(EntityPlayer owner, long now) {
        int radius = maid.getHomeArea()
            .enabled()
                ? Math.min(
                    16,
                    maid.getHomeArea()
                        .radius())
                : 8;
        var returned = maid.worldObj
            .getEntitiesWithinAABBExcludingEntity(maid, maid.boundingBox.expand(radius, 4, radius));
        var candidates = new ArrayList<Entity>();
        var nearest = Comparator.comparingDouble((Entity entity) -> maid.getDistanceSqToEntity(entity));
        int inspected = 0;
        for (Object value : returned) {
            if (inspected++ >= CANDIDATE_BUDGET) break;
            if (!(value instanceof Entity entity) || !eligible(entity, owner, now)) continue;
            candidates.add(entity);
            candidates.sort(nearest);
            if (candidates.size() > PATH_CANDIDATES) candidates.remove(PATH_CANDIDATES);
        }
        maid.getBrainCoordinator()
            .getTelemetry()
            .scan(
                "pickup_candidates",
                returned.size(),
                Math.min(inspected, CANDIDATE_BUDGET),
                "bounded_scan;partial=" + (returned.size() > CANDIDATE_BUDGET));
        for (var candidate : candidates) {
            if (pathTo(candidate)) {
                target = candidate;
                expires = now + 120;
                nextPath = now + 20;
                return true;
            }
            ignore(candidate, now);
            cancel();
        }
        return false;
    }

    private boolean pathTo(Entity entity) {
        var proposed = run == null ? runner.newRun(maid.worldObj, maid.getTask()) : run;
        if (proposed == null || !proposed.isCurrent(maid.worldObj, maid.getTask())) return false;
        // 原生整数重载先加0.5再减实体半宽，直接传掉落物所在格
        var created = planner
            .create(maid, Math.floor(entity.posX), Math.floor(entity.boundingBox.minY), Math.floor(entity.posZ), 0.6);
        var path = created.path;
        if (path == null || path.getCurrentPathLength() == 0 || !proposed.isCurrent(maid.worldObj, maid.getTask()))
            return false;
        var end = path.getFinalPathPoint();
        double dx = end.xCoord + 0.5 - entity.posX, dz = end.zCoord + 0.5 - entity.posZ;
        if (dx * dx + dz * dz > 0.5 || Math.abs(end.yCoord - entity.boundingBox.minY) > 1) return false;
        if (run == null) {
            if (!runner.claim(Owner.PICKUP, proposed, this::cancel)) return false;
            run = proposed;
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) return false;
        var installed = planner.install(maid, created);
        if (installed.result != PathResult.STARTED) return false;
        ownedPath = installed.installedPath;
        plannedX = entity.posX;
        plannedY = entity.posY;
        plannedZ = entity.posZ;
        return runner.isOwned(run, maid.worldObj, maid.getTask());
    }

    private void ignore(Entity entity, long now) {
        ignored = entity.getUniqueID();
        ignoreUntil = now + 100;
    }

    public void cancel() {
        if (stopping) return;
        stopping = true;
        var stopped = run;
        try {
            if (stopped != null) stopped.cancel();
            if (ownedPath != null && maid.getNavigator()
                .getPath() == ownedPath) maid.getNavigator()
                    .clearPathEntity();
        } finally {
            target = null;
            ownedPath = null;
            run = null;
            nextPath = 0;
            runner.release(stopped);
            stopping = false;
        }
    }

    public Entity getTarget() {
        return target;
    }
}
