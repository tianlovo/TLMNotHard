package com.tianluoqaq.tlmnh.common.interaction;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 原版randomRestoreHealth的服务端适配，自动进食另由物品使用动作处理 */
public final class MaidSelfCare {

    private MaidSelfCare() {}

    public static void tick(EntityMaid maid) {
        if (maid.worldObj.isRemote || !maid.isEntityAlive() || maid.getHealth() >= maid.getMaxHealth()) return;
        if (maid.getRNG()
            .nextFloat() < MaidInteractionTuning.MIN_NEXT_FLOAT_THRESHOLD) {
            maid.heal(MaidInteractionTuning.CONSUME_SINGLE_UNIT);
            maid.worldObj.setEntityState(
                maid,
                (byte) (MaidInteractionTuning.INTERACTIONS_POTION_EFFECT_SECOND_PARAM_2 + maid.getRNG()
                    .nextInt(MaidInteractionTuning.TARGET_GET_ID_THRESHOLD)));
        }
    }
}
