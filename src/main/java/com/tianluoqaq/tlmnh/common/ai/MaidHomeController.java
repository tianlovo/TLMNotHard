package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.pathfinding.PathEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 家园范围外或日程切换后走回当前活动点，使用原生导航，不加载区块或跨维度传送 */
public final class MaidHomeController {

    public enum Status {
        IDLE,
        RETURNING,
        BLOCKED,
        WRONG_DIMENSION,
        PATH_UNAVAILABLE
    }

    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private MaidActionRun run;
    private PathEntity ownedPath;
    private long tick, nextPath;
    private boolean requested, returning, stopping;
    private Status status = Status.IDLE;

    public MaidHomeController(EntityMaid maid, MaidActionRunner runner) {
        this.maid = maid;
        this.runner = runner;
    }

    public void requestReturn() {
        requested = true;
    }

    public void tick() {
        if (stopping) return;
        tick++;
        var home = maid.getHomeArea();
        if (!home.enabled() || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || !maid.isTamed()
            || maid.getBrainCoordinator()
                .isSuspended()
            || maid.isSitting()
            || maid.ridingEntity != null
            || maid.riddenByEntity != null
            || maid.getLeashed()
            || maid.hasPose(MaidPose.SLEEPING)) {
            cancel();
            status = Status.BLOCKED;
            return;
        }
        if (!home.sameDimension()) {
            cancel();
            status = Status.WRONG_DIMENSION;
            return;
        }
        var center = home.center();
        double distance = maid.getDistanceSq(center.x + 0.5, center.y, center.z + 0.5);
        if (home.containsMaid() && (distance <= 9 || !returning && !requested)) {
            cancel();
            return;
        }
        if (run == null) {
            var next = runner.newRun(maid.worldObj, maid.getTask());
            if (!runner.claim(Owner.HOME, next, this::cancel)) return;
            run = next;
            returning = true;
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) {
            cancel();
            return;
        }
        maid.getLookHelper()
            .setLookPosition(
                center.x + 0.5,
                center.y + maid.getEyeHeight(),
                center.z + 0.5,
                20,
                maid.getVerticalFaceSpeed());
        status = Status.RETURNING;
        if (tick < nextPath) return;
        nextPath = tick + 40;
        double factor = Math.min(1, 16 / Math.sqrt(distance));
        var result = planner.request(
            maid,
            maid.posX + (center.x + 0.5 - maid.posX) * factor,
            maid.posY + (center.y - maid.posY) * factor,
            maid.posZ + (center.z + 0.5 - maid.posZ) * factor,
            0.7);
        if (result == PathResult.STARTED) ownedPath = maid.getNavigator()
            .getPath();
        else status = Status.PATH_UNAVAILABLE;
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
            ownedPath = null;
            run = null;
            requested = returning = false;
            nextPath = 0;
            status = Status.IDLE;
            runner.release(stopped);
            stopping = false;
        }
    }

    public Status getStatus() {
        return status;
    }
}
