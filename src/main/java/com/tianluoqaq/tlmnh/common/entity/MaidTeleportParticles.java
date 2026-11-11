package com.tianluoqaq.tlmnh.common.entity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;

/** 固定现代LivingEntity事件46的128粒子；只由客户端实体事件调用，不消费服务端随机流 */
public final class MaidTeleportParticles {

    private MaidTeleportParticles() {}

    public static void show(EntityMaid maid) {
        if (!maid.worldObj.isRemote) return;
        var random = maid.getRNG();
        for (int index = 0; index < MaidEntityTuning.POSE_SWINGING_ARMS_FIRST_PARAM; index++) {
            double fraction = index / MaidEntityTuning.TELEPORT_PARTICLES_FRACTION;
            float vx = (random.nextFloat() - MaidEntityTuning.TELEPORT_PARTICLES_SHOW_VX_OFFSET)
                * MaidEntityTuning.ENTITY_POSE_SLEEPING_FIRST_FACTOR;
            float vy = (random.nextFloat() - MaidEntityTuning.TELEPORT_PARTICLES_SHOW_VX_OFFSET)
                * MaidEntityTuning.ENTITY_POSE_SLEEPING_FIRST_FACTOR;
            float vz = (random.nextFloat() - MaidEntityTuning.TELEPORT_PARTICLES_SHOW_VX_OFFSET)
                * MaidEntityTuning.ENTITY_POSE_SLEEPING_FIRST_FACTOR;
            double x = maid.prevPosX + fraction * (maid.posX - maid.prevPosX)
                + (random.nextDouble() - MaidEntityTuning.TELEPORT_PARTICLES_SHOW_FACTOR) * maid.width
                    * MaidEntityTuning.POSE_BEGGING_FIRST_PARAM;
            double y = maid.prevPosY + fraction * (maid.posY - maid.prevPosY) + random.nextDouble() * maid.height;
            double z = maid.prevPosZ + fraction * (maid.posZ - maid.prevPosZ)
                + (random.nextDouble() - MaidEntityTuning.TELEPORT_PARTICLES_SHOW_FACTOR) * maid.width
                    * MaidEntityTuning.POSE_BEGGING_FIRST_PARAM;
            maid.worldObj.spawnParticle(MaidEntityIds.ID_PORTAL, x, y, z, vx, vy, vz);
        }
        if (DevelopmentFlags.isDevCommandsEnabled()) ModContext.LOG
            .info(MaidEntityDiagnostics.MSG_TLMNH_RESTRICTION105_EVENT46_UUID_PARTICLES, maid.getUniqueID());
    }
}
