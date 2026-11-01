package com.tianluoqaq.tlmnh.common.ai;

import java.util.function.BooleanSupplier;

import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 原版空闲任务的苦力怕逃离和受伤活动中断，移动与取消由同一动作所有者管理 */
public final class MaidPanicController {

    private static final int SCAN_INTERVAL = 20, PATH_RETRY = 20, ATTEMPTS = 10;
    private static final double DANGER_RANGE_SQ = 64, SPEED = .7;
    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final BooleanSupplier suspended;
    private final BooleanSupplier recentlyHurt;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private EntityCreeper threat;
    private MaidActionRun run;
    private PathEntity ownedPath;
    private long nextScan, nextPath, lastTime = Long.MIN_VALUE;
    private String status = "idle";

    public MaidPanicController(EntityMaid maid, MaidActionRunner runner, BooleanSupplier suspended,
        BooleanSupplier recentlyHurt) {
        this.maid = maid;
        this.runner = runner;
        this.suspended = suspended;
        this.recentlyHurt = recentlyHurt;
    }

    public String getStatus() {
        return status;
    }

    public EntityCreeper getThreat() {
        return threat;
    }

    public boolean tick() {
        if (maid.worldObj.isRemote || !maid.isEntityAlive()
            || suspended.getAsBoolean()
            || maid.getTask() != MaidTask.IDLE) {
            cancel();
            return false;
        }
        long now = maid.worldObj.getTotalWorldTime();
        if (now < lastTime) cancel();
        lastTime = now;
        if (threat != null && !validThreat()) {
            threat = null;
            nextScan = 0;
        }
        if (now >= nextScan) {
            nextScan = now + SCAN_INTERVAL;
            scan();
        }
        if (threat == null && !recentlyHurt.getAsBoolean()) {
            clearControl();
            setStatus("idle");
            return false;
        }
        if (run == null || !runner.isOwned(run, maid.worldObj, maid.getTask())) {
            var proposed = runner.newRun(maid.worldObj, maid.getTask());
            if (proposed == null || !runner.claim(MaidActionRunner.Owner.PANIC, proposed, this::cancel)) return true;
            run = proposed;
            if (threat != null) {
                maid.setSitting(false);
                maid.setPose(MaidPose.SLEEPING, false);
                if (maid.ridingEntity != null) maid.mountEntity(null);
            }
            maid.getShieldController()
                .cancelUse("panic");
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) {
            clearControl();
            return true;
        }
        // 原版仅以 NEAREST_HOSTILE 选择逃离点，HURT_BY 自身不泄露攻击者位置
        if (threat == null) {
            if (ownedPath != null && maid.getNavigator()
                .getPath() == ownedPath) maid.getNavigator()
                    .clearPathEntity();
            ownedPath = null;
            setStatus("hurt_pause");
            return true;
        }
        if (ownedPath != null && maid.getNavigator()
            .getPath() == ownedPath && !ownedPath.isFinished() && away(ownedPath)) {
            setStatus("retreating");
            return true;
        }
        if (now < nextPath) return true;
        nextPath = now + PATH_RETRY;
        if (ownedPath != null && maid.getNavigator()
            .getPath() == ownedPath) maid.getNavigator()
                .clearPathEntity();
        ownedPath = null;
        int radius = maid.getHomeArea()
            .enabled()
                ? maid.getHomeArea()
                    .radius()
                : MaidHomeConfig.workRange;
        if (!loadedCandidates(radius)) {
            setStatus("waiting_for_chunks");
            return true;
        }
        var danger = Vec3.createVectorHelper(threat.posX, threat.posY, threat.posZ);
        for (int i = 0; i < ATTEMPTS; i++) {
            var point = RandomPositionGenerator.findRandomTargetBlockAwayFrom(maid, radius, 7, danger);
            if (point == null || !maid.getHomeArea()
                .contains(point.xCoord, point.yCoord, point.zCoord)) continue;
            var created = planner
                .create(maid, Math.floor(point.xCoord), Math.floor(point.yCoord), Math.floor(point.zCoord), SPEED);
            if (created.path == null || created.path.getCurrentPathLength() == 0 || !away(created.path)) continue;
            if (!validThreat() || !runner.isOwned(run, maid.worldObj, maid.getTask())) {
                clearControl();
                return true;
            }
            var installed = planner.install(maid, created);
            if (installed.result == MaidFollowController.PathResult.STARTED) {
                ownedPath = installed.installedPath;
                setStatus("retreating");
                return true;
            }
        }
        setStatus("no_retreat_path");
        return true;
    }

    private boolean away(PathEntity path) {
        if (threat == null || path.getFinalPathPoint() == null) return false;
        var end = path.getFinalPathPoint();
        double x = end.xCoord + .5 - maid.posX, z = end.zCoord + .5 - maid.posZ;
        double dx = threat.posX - maid.posX, dz = threat.posZ - maid.posZ;
        return x * dx + z * dz < 0;
    }

    private void scan() {
        var returned = maid.worldObj.getEntitiesWithinAABB(EntityCreeper.class, maid.boundingBox.expand(8, 8, 8));
        EntityCreeper nearest = null;
        double distance = DANGER_RANGE_SQ;
        for (Object value : returned)
            if (value instanceof EntityCreeper candidate && candidate.isEntityAlive() && !candidate.isDead) {
                double actual = maid.getDistanceSqToEntity(candidate);
                if (actual < distance && maid.getEntitySenses()
                    .canSee(candidate)) {
                    nearest = candidate;
                    distance = actual;
                }
            }
        threat = nearest;
        maid.getBrainCoordinator()
            .getTelemetry()
            .scan("panic_creeper", returned.size(), returned.size(), "nearest_visible_within_strict_8_blocks");
    }

    private boolean validThreat() {
        return threat != null && threat.worldObj == maid.worldObj
            && threat.isEntityAlive()
            && !threat.isDead
            && maid.worldObj.getEntityByID(threat.getEntityId()) == threat;
    }

    private boolean loadedCandidates(int radius) {
        int x = MathHelper.floor_double(maid.posX), z = MathHelper.floor_double(maid.posZ);
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++)
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) if (!maid.worldObj.getChunkProvider()
                .chunkExists(cx, cz)) return false;
        return true;
    }

    private void setStatus(String value) {
        if (status.equals(value)) return;
        status = value;
        maid.getBrainCoordinator()
            .getTelemetry()
            .state("panic", threat == null ? "" : MaidTelemetryChannel.id(threat), value);
    }

    private void clearControl() {
        var stopped = run;
        run = null;
        if (stopped != null) stopped.cancel();
        if (ownedPath != null && maid.getNavigator()
            .getPath() == ownedPath) maid.getNavigator()
                .clearPathEntity();
        ownedPath = null;
        runner.release(stopped);
    }

    public void cancel() {
        clearControl();
        threat = null;
        nextScan = nextPath = 0;
        setStatus("idle");
    }
}
