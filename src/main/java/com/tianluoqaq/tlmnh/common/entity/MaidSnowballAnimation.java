package com.tianluoqaq.tlmnh.common.entity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 原拾取雪球动画的非存档状态；原1750毫秒时长，实体状态包只同步触发 */
public final class MaidSnowballAnimation {

    private boolean pickingUp;
    private long startedAt = MaidEntityTuning.UNSET_OR_NOT_FOUND;
    private int sequence;

    public void start(EntityMaid maid) {
        pickingUp = true;
        startedAt = System.currentTimeMillis();
        sequence++;
        if (!maid.worldObj.isRemote) maid.worldObj.setEntityState(maid, MaidEntityTuning.STATUS);
    }

    public boolean pickingUp() {
        return pickingUp;
    }

    public long startedAt() {
        return startedAt;
    }

    public int sequence() {
        return sequence;
    }

    public void clear() {
        pickingUp = false;
        startedAt = MaidEntityTuning.UNSET_OR_NOT_FOUND;
    }
}
