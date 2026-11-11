package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.Objects;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;

/** 原型的可见 IMob 生活中断信号，不等于原版仅苦力怕的 panic 传感器 */
public final class MaidDangerSensor {

    private final EntityMaid maid;
    private final MaidTelemetryChannel telemetry;
    private final MaidDangerState state = new MaidDangerState();
    private long frame, nextScan;

    public MaidDangerSensor(EntityMaid maid, MaidTelemetryChannel telemetry) {
        this.maid = Objects.requireNonNull(maid, MaidPerceptionIds.ID_MAID);
        this.telemetry = Objects.requireNonNull(telemetry, MaidPerceptionIds.ID_TELEMETRY);
    }

    public void beginFrame(MaidPolicyContext context, long frame) {
        if (context == null || !context.getMaidId()
            .equals(maid.getUniqueID())) {
            clear();
            return;
        }
        if (state.beginFrame(maid.worldObj, context, maid.worldObj.getTotalWorldTime()) || frame < this.frame)
            nextScan = MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
        this.frame = frame;
    }

    /** UNKNOWN 保留原型无明确威胁时允许生活的规则，不把它当作完整阴性观测 */
    public boolean hasKnownDanger() {
        if (maid.hurtTime > 0 || maid.getBrainCoordinator()
            .hasRecentHurt()) return true;
        if (!state.isFrame(maid.worldObj, maid.worldObj.getTotalWorldTime()) || maid.worldObj.isRemote) return false;
        if (frame >= nextScan) {
            nextScan = frame + MaidPerceptionTuning.SCAN_INTERVAL;
            scan();
        }
        var observation = state.getObservation();
        if (state.getResult() == MaidDangerState.Result.VISIBLE_HOSTILE
            && (!(observation.getTarget() instanceof EntityLivingBase entity) || !entity.isEntityAlive()
                || entity.isDead
                || entity.worldObj != maid.worldObj
                || maid.worldObj.getEntityByID(entity.getEntityId()) != entity
                || !entity.getUniqueID()
                    .equals(observation.getTargetId())))
            state.discardTarget();
        return state.getResult() == MaidDangerState.Result.VISIBLE_HOSTILE;
    }

    private void scan() {
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
        var candidates = maid.worldObj.getEntitiesWithinAABB(
            IMob.class,
            maid.boundingBox.expand(
                MaidPerceptionTuning.DANGER_SENSOR_EXPAND_FIRST_PARAM,
                MaidPerceptionTuning.DANGER_SENSOR_EXPAND_SECOND_PARAM,
                MaidPerceptionTuning.DANGER_SENSOR_EXPAND_FIRST_PARAM));
        int size = candidates.size(), start = state.scanStart(size),
            inspected = MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
        EntityLivingBase found = null;
        while (inspected < size && inspected < MaidPerceptionTuning.SCAN_BUDGET) {
            var value = candidates.get((int) (((long) start + inspected) % size));
            inspected++;
            boolean accepted = value instanceof EntityLivingBase entity && entity instanceof IMob
                && entity.isEntityAlive()
                && maid.getEntitySenses()
                    .canSee(entity);
            if (value instanceof EntityLivingBase entity) telemetry
                .candidate(MaidPerceptionIds.ID_DANGER, entity, accepted, MaidPerceptionIds.ID_VISIBLE_HOSTILE_FILTER);
            if (accepted) {
                found = (EntityLivingBase) value;
                break;
            }
        }
        state.recordScan(size, inspected, found == null ? null : found.getUniqueID(), found);
        telemetry.scan(
            MaidPerceptionIds.ID_DANGER,
            size,
            inspected,
            MaidPerceptionIds.ID_NATIVE_AABB_RETURNED_LIST_PARTIAL + (size > inspected)
                + MaidPerceptionIds.ID_START
                + start
                + MaidPerceptionIds.ID_RESULT
                + state.getResult());
        if (recording) telemetry.work(MaidPerceptionIds.ID_DANGER, WorkComponent.SENSOR, System.nanoTime() - started);
    }

    public void clear() {
        state.clear();
        nextScan = MaidPerceptionTuning.DANGER_SENSOR_NEXT_SCAN;
    }
}
