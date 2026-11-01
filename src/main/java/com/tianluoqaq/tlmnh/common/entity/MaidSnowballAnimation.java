package com.tianluoqaq.tlmnh.common.entity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 原拾取雪球动画的非存档状态；原1750毫秒时长，实体状态包只同步触发 */
public final class MaidSnowballAnimation {

    public static final byte STATUS = 47;
    public static final long DURATION_MILLIS = 1750;
    private boolean pickingUp;
    private long startedAt = -1;
    private int sequence;

    public void start(EntityMaid maid) {
        pickingUp = true;
        startedAt = System.currentTimeMillis();
        sequence++;
        if (!maid.worldObj.isRemote) maid.worldObj.setEntityState(maid, STATUS);
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
        startedAt = -1;
    }
}
