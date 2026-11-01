package com.tianluoqaq.tlmnh.common.protection;

import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.relauncher.Side;

/** 单个女仆的AI世界边界，保留物品所有权和原生物理，不尝试替换或修补外部API */
public final class MaidBehaviorProtection {

    private final EntityMaid maid;
    private UUID identity;
    private String key;
    private boolean stopped;

    public MaidBehaviorProtection(EntityMaid maid) {
        this.maid = maid;
    }

    public void run(Runnable operation) {
        if (maid.worldObj == null || maid.worldObj.isRemote || stopped) return;
        if (!maid.getUniqueID()
            .equals(identity)) {
            identity = maid.getUniqueID();
            key = ExternalFeature.BEHAVIOR_RUNTIME.id + "/" + identity;
        }
        if (!IntegrationGuard.available(Side.SERVER, ExternalFeature.BEHAVIOR_RUNTIME)
            || !IntegrationGuard.available(Side.SERVER, key)) {
            stop(null);
            return;
        }
        try {
            operation.run();
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            stop(failure);
        }
    }

    private void stop(Throwable failure) {
        stopped = true;
        try {
            maid.getBrainCoordinator()
                .stopBehaviorForProtection();
        } catch (Exception | LinkageError | AssertionError cleanup) {
            IntegrationGuard.throwIfFatal(cleanup);
            if (failure == null) failure = cleanup;
            else if (failure != cleanup) failure.addSuppressed(cleanup);
        }
        if (failure != null) IntegrationGuard.report(Side.SERVER, key, failure, owner(failure));
    }

    private UUID owner(Throwable primary) {
        try {
            String owner = maid.func_152113_b();
            return owner == null || owner.isEmpty() ? null : UUID.fromString(owner);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return null;
        } catch (Exception | LinkageError | AssertionError metadata) {
            IntegrationGuard.throwIfFatal(metadata);
            if (primary != metadata) primary.addSuppressed(metadata);
            return null;
        }
    }
}
