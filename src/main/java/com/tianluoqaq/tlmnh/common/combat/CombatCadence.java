package com.tianluoqaq.tlmnh.common.combat;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 生产攻击的实体级世界时钟；取消会话、换目标和暂停GUI均不重置 */
public final class CombatCadence {

    public static final String TAG = "TLMNHCombatCadence";
    private static final int VERSION = 1;
    private final EntityMaid maid;
    private long remaining, lastObserved;
    private boolean initialized, deathFrozen, committed;
    private NBTBase retained;

    public CombatCadence(EntityMaid maid) {
        this.maid = java.util.Objects.requireNonNull(maid, "maid");
    }

    public boolean ready() {
        observe();
        return retained == null && !deathFrozen && remaining == 0;
    }

    /** 在Pre/伤害/发射入口之前占用，回调取消行为也不能退回本次节拍 */
    public boolean commit(long cooldownTicks) {
        if (maid.worldObj == null || maid.worldObj.isRemote || !ready() || deathFrozen) return false;
        remaining = Math.max(1, cooldownTicks);
        committed = true;
        return true;
    }

    public long remainingTicks() {
        observe();
        return remaining;
    }

    public boolean hasPersistentState() {
        return committed || remaining > 0 || retained != null;
    }

    public boolean formatSupported() {
        return retained == null;
    }

    /** 死亡快照可能跨tick确认；冻结相同尸体的剩余量，避免完整源快照自行漂移 */
    public void freezeForDeath() {
        observe();
        deathFrozen = true;
    }

    private void observe() {
        if (maid.worldObj == null) return;
        long now = Math.max(0, maid.worldObj.getTotalWorldTime());
        if (!initialized) {
            initialized = true;
        } else if (!deathFrozen && now >= lastObserved) {
            long elapsed = now - lastObserved;
            remaining = elapsed >= remaining ? 0 : remaining - elapsed;
        }
        // 回退时保留剩余量并以新时钟重新计期，不补攻，也不等待旧绝对期限追上
        lastObserved = now;
    }

    public void save(NBTTagCompound root) {
        if (retained != null) {
            root.setTag(TAG, retained.copy());
            return;
        }
        observe();
        var data = new NBTTagCompound();
        data.setInteger("Version", VERSION);
        data.setLong("Remaining", remaining);
        root.setTag(TAG, data);
    }

    public void load(NBTTagCompound root) {
        if (maid.getCombatHitReceipt()
            .activeCall())
            throw new IllegalStateException("Combat cadence cannot be replaced during an active attack call");
        retained = null;
        remaining = 0;
        initialized = false;
        deathFrozen = false;
        if (root.hasKey(TAG)) {
            var raw = root.getTag(TAG);
            if (!(raw instanceof NBTTagCompound data) || !data.hasKey("Version", 3)
                || data.getInteger("Version") != VERSION
                || !data.hasKey("Remaining", 4)
                || data.getLong("Remaining") < 0
                || data.func_150296_c()
                    .size() != 2) {
                retained = raw.copy();
            } else remaining = data.getLong("Remaining");
        }
        observe();
        if (maid.getHealth() <= 0) deathFrozen = true;
    }
}
