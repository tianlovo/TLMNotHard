package com.tianluoqaq.tlmnh.common.ai;

import java.util.function.BooleanSupplier;

import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 原版空闲任务的苦力怕逃离和受伤活动中断，移动与取消由同一动作所有者管理 */
public final class MaidPanicController {

    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final BooleanSupplier suspended;
    private final BooleanSupplier recentlyHurt;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner(true);
    private EntityCreeper threat;
    private MaidActionRun run;
    private PathEntity ownedPath;
    private long nextScan, nextPath, lastTime = Long.MIN_VALUE;
    private String status = MaidAiIds.ID_IDLE;

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
            nextScan = MaidAiTuning.NAVIGATION_INITIAL_VALUE;
        }
        if (now >= nextScan) {
            nextScan = now + MaidAiTuning.SCAN_INTERVAL;
            scan();
        }
        if (threat == null && !recentlyHurt.getAsBoolean()) {
            clearControl();
            setStatus(MaidAiIds.ID_IDLE);
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
                .cancelUse(MaidAiIds.ID_PANIC);
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) {
            clearControl();
            return true;
        }
        // 原版仅以 NEAREST_HOSTILE 选择逃离点，HURT_BY 自身不泄露攻击者位置
        if (threat == null) {
            if (ownedPath != null && MaidPathOwnership.owns(
                maid.getNavigator()
                    .getPath(),
                ownedPath))
                maid.getNavigator()
                    .clearPathEntity();
            ownedPath = null;
            setStatus(MaidAiIds.ID_HURT_PAUSE);
            return true;
        }
        if (ownedPath != null && MaidPathOwnership.owns(
            maid.getNavigator()
                .getPath(),
            ownedPath) && !ownedPath.isFinished() && away(ownedPath)) {
            setStatus(MaidAiIds.ID_RETREATING);
            return true;
        }
        if (now < nextPath) return true;
        nextPath = now + MaidAiTuning.PATH_RETRY;
        if (ownedPath != null && MaidPathOwnership.owns(
            maid.getNavigator()
                .getPath(),
            ownedPath))
            maid.getNavigator()
                .clearPathEntity();
        ownedPath = null;
        int radius = maid.getHomeArea()
            .enabled()
                ? maid.getHomeArea()
                    .radius()
                : MaidHomeConfig.workRange;
        if (!loadedCandidates(radius)) {
            setStatus(MaidAiIds.ID_WAITING_FOR_CHUNKS);
            return true;
        }
        var danger = Vec3.createVectorHelper(threat.posX, threat.posY, threat.posZ);
        for (int i = 0; i < MaidAiTuning.ATTEMPTS; i++) {
            var point = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
                maid,
                radius,
                MaidAiTuning.BRAIN_COORDINATOR_SET_MUTEX_BITS_FIRST_PARAM,
                danger);
            if (point == null || !maid.getHomeArea()
                .contains(point.xCoord, point.yCoord, point.zCoord)) continue;
            var created = planner.create(
                maid,
                Math.floor(point.xCoord),
                Math.floor(point.yCoord),
                Math.floor(point.zCoord),
                MaidAiTuning.SPEED);
            if (created.preflightFailure != null && created.preflightFailure.isDeferred()) {
                nextPath = now + 1;
                setStatus(MaidAiIds.ID_NAVIGATION_PENDING);
                return true;
            }
            if (created.path == null || created.path.getCurrentPathLength() == 0 || !away(created.path)) continue;
            if (!validThreat() || !runner.isOwned(run, maid.worldObj, maid.getTask())) {
                clearControl();
                return true;
            }
            var installed = planner.install(maid, created);
            if (installed.result.isDeferred()) {
                nextPath = now + 1;
                setStatus(MaidAiIds.ID_NAVIGATION_PENDING);
                return true;
            }
            if (installed.result == MaidFollowController.PathResult.STARTED) {
                ownedPath = installed.installedPath;
                setStatus(MaidAiIds.ID_RETREATING);
                return true;
            }
        }
        setStatus(MaidAiIds.ID_NO_RETREAT_PATH);
        return true;
    }

    private boolean away(PathEntity path) {
        if (threat == null || path.getFinalPathPoint() == null) return false;
        var end = path.getFinalPathPoint();
        double x = end.xCoord + MaidAiTuning.HOME_CONTROLLER_DISTANCE - maid.posX,
            z = end.zCoord + MaidAiTuning.HOME_CONTROLLER_DISTANCE - maid.posZ;
        double dx = threat.posX - maid.posX, dz = threat.posZ - maid.posZ;
        return x * dx + z * dz < 0;
    }

    private void scan() {
        var returned = maid.worldObj.getEntitiesWithinAABB(
            EntityCreeper.class,
            maid.boundingBox.expand(
                MaidAiTuning.LEGACY_BRAIN_EXPAND_SECOND_PARAM,
                MaidAiTuning.LEGACY_BRAIN_EXPAND_SECOND_PARAM,
                MaidAiTuning.LEGACY_BRAIN_EXPAND_SECOND_PARAM));
        EntityCreeper nearest = null;
        double distance = MaidAiTuning.DANGER_RANGE_SQ;
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
            .scan(
                MaidAiIds.ID_PANIC_CREEPER,
                returned.size(),
                returned.size(),
                MaidAiIds.ID_NEAREST_VISIBLE_WITHIN_STRICT_8_BLOCKS);
    }

    private boolean validThreat() {
        return threat != null && threat.worldObj == maid.worldObj
            && threat.isEntityAlive()
            && !threat.isDead
            && maid.worldObj.getEntityByID(threat.getEntityId()) == threat;
    }

    private boolean loadedCandidates(int radius) {
        int x = MathHelper.floor_double(maid.posX), z = MathHelper.floor_double(maid.posZ);
        for (int cx = (x - radius) >> MaidAiTuning.CHUNK_SECTION_SHIFT; cx
            <= (x + radius) >> MaidAiTuning.CHUNK_SECTION_SHIFT; cx++)
            for (int cz = (z - radius) >> MaidAiTuning.CHUNK_SECTION_SHIFT; cz
                <= (z + radius) >> MaidAiTuning.CHUNK_SECTION_SHIFT; cz++)
                if (!maid.worldObj.getChunkProvider()
                    .chunkExists(cx, cz)) return false;
        return true;
    }

    private void setStatus(String value) {
        if (status.equals(value)) return;
        status = value;
        maid.getBrainCoordinator()
            .getTelemetry()
            .state(MaidAiIds.ID_PANIC, threat == null ? "" : MaidTelemetryChannel.id(threat), value);
    }

    private void clearControl() {
        var stopped = run;
        run = null;
        if (stopped != null) stopped.cancel();
        if (ownedPath != null && MaidPathOwnership.owns(
            maid.getNavigator()
                .getPath(),
            ownedPath))
            maid.getNavigator()
                .clearPathEntity();
        ownedPath = null;
        runner.release(stopped);
    }

    public void cancel() {
        clearControl();
        threat = null;
        nextScan = nextPath = MaidAiTuning.NAVIGATION_INITIAL_VALUE;
        setStatus(MaidAiIds.ID_IDLE);
    }
}
