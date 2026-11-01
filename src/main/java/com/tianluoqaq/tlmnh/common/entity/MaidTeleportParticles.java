package com.tianluoqaq.tlmnh.common.entity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 固定现代LivingEntity事件46的128粒子；只由客户端实体事件调用，不消费服务端随机流 */
public final class MaidTeleportParticles {

    private MaidTeleportParticles() {}

    public static void show(EntityMaid maid) {
        if (!maid.worldObj.isRemote) return;
        var random = maid.getRNG();
        for (int index = 0; index < 128; index++) {
            double fraction = index / 127.0D;
            float vx = (random.nextFloat() - .5F) * .2F;
            float vy = (random.nextFloat() - .5F) * .2F;
            float vz = (random.nextFloat() - .5F) * .2F;
            double x = maid.prevPosX + fraction * (maid.posX - maid.prevPosX)
                + (random.nextDouble() - .5) * maid.width * 2;
            double y = maid.prevPosY + fraction * (maid.posY - maid.prevPosY) + random.nextDouble() * maid.height;
            double z = maid.prevPosZ + fraction * (maid.posZ - maid.prevPosZ)
                + (random.nextDouble() - .5) * maid.width * 2;
            maid.worldObj.spawnParticle("portal", x, y, z, vx, vy, vz);
        }
        if (Boolean.getBoolean("tlmnh.devCommands")) com.tianluoqaq.tlmnh.TLMNotHard.LOG
            .info("TLMNH_RESTRICTION105_EVENT46 uuid={} particles=128", maid.getUniqueID());
    }
}
