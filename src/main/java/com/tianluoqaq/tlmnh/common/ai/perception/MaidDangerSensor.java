package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.Objects;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;

/** 原型的可见 IMob 生活中断信号，不等于原版仅苦力怕的 panic 传感器 */
public final class MaidDangerSensor {

    private static final int SCAN_INTERVAL = 10;
    private final EntityMaid maid;
    private final MaidTelemetryChannel telemetry;
    private final MaidDangerState state = new MaidDangerState();
    private long frame, nextScan;

    public MaidDangerSensor(EntityMaid maid, MaidTelemetryChannel telemetry) {
        this.maid = Objects.requireNonNull(maid, "maid");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public void beginFrame(MaidPolicyContext context, long frame) {
        if (context == null || !context.getMaidId()
            .equals(maid.getUniqueID())) {
            clear();
            return;
        }
        if (state.beginFrame(maid.worldObj, context, maid.worldObj.getTotalWorldTime()) || frame < this.frame)
            nextScan = 0;
        this.frame = frame;
    }

    /** UNKNOWN 保留原型无明确威胁时允许生活的规则，不把它当作完整阴性观测 */
    public boolean hasKnownDanger() {
        if (maid.hurtTime > 0) return true;
        if (!state.isFrame(maid.worldObj, maid.worldObj.getTotalWorldTime()) || maid.worldObj.isRemote) return false;
        if (frame >= nextScan) {
            nextScan = frame + SCAN_INTERVAL;
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
        long started = recording ? System.nanoTime() : 0;
        var candidates = maid.worldObj.getEntitiesWithinAABB(IMob.class, maid.boundingBox.expand(8, 4, 8));
        int size = candidates.size(), start = state.scanStart(size), inspected = 0;
        EntityLivingBase found = null;
        while (inspected < size && inspected < MaidDangerState.SCAN_BUDGET) {
            var value = candidates.get((int) (((long) start + inspected) % size));
            inspected++;
            boolean accepted = value instanceof EntityLivingBase entity && entity instanceof IMob
                && entity.isEntityAlive()
                && maid.getEntitySenses()
                    .canSee(entity);
            if (value instanceof EntityLivingBase entity)
                telemetry.candidate("danger", entity, accepted, "visible_hostile_filter");
            if (accepted) {
                found = (EntityLivingBase) value;
                break;
            }
        }
        state.recordScan(size, inspected, found == null ? null : found.getUniqueID(), found);
        telemetry.scan(
            "danger",
            size,
            inspected,
            "native_aabb_returned_list;partial=" + (size > inspected)
                + ";start="
                + start
                + ";result="
                + state.getResult());
        if (recording) telemetry.work("danger", WorkComponent.SENSOR, System.nanoTime() - started);
    }

    public void clear() {
        state.clear();
        nextScan = 0;
    }
}
