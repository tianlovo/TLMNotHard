package com.tianluoqaq.tlmnh.common.life.bed;

import java.util.UUID;

/** 床位租约不写入存档，卸载或重启后重新争取，不让旧 UUID 永久霸床 */
public final class MaidBedLease {

    private UUID owner;
    private long expires;

    public boolean claim(UUID maid, long tick) {
        if (!available(maid, tick)) return false;
        owner = maid;
        expires = tick + MaidBedLayout.BED_LEASE_EXPIRES;
        return true;
    }

    public boolean available(UUID maid, long tick) {
        return maid != null && (owner == null || owner.equals(maid) || tick >= expires);
    }

    public boolean isOwnedBy(UUID maid, long tick) {
        return owner != null && owner.equals(maid) && tick < expires;
    }

    public boolean release(UUID maid) {
        if (owner == null || !owner.equals(maid)) return false;
        clear();
        return true;
    }

    public boolean expired(long tick) {
        return owner != null && tick >= expires;
    }

    public void clear() {
        owner = null;
        expires = MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE;
    }
}
