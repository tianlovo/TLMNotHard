package com.tianluoqaq.tlmnh.common.interaction;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 原版randomRestoreHealth的服务端适配，自动进食另由物品使用动作处理 */
public final class MaidSelfCare {

    private MaidSelfCare() {}

    public static void tick(EntityMaid maid) {
        if (maid.worldObj.isRemote || !maid.isEntityAlive() || maid.getHealth() >= maid.getMaxHealth()) return;
        if (maid.getRNG()
            .nextFloat() < .0025F) {
            maid.heal(1);
            maid.worldObj.setEntityState(
                maid,
                (byte) (100 + maid.getRNG()
                    .nextInt(3)));
        }
    }
}
