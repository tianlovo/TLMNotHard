package com.tianluoqaq.tlmnh.compat.witchery.common.entity;

import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;

/** Witchery出生事件的两项原始尺寸初始化，不调用包含其他玩法的完整事件处理器 */
public final class WitcheryInitialSize {

    private static final String KEY = "witchery/initial_size";
    private static final String SOURCE_SHA256 = "68c331f624cea6c0827a31e96a6dce2e75461227c42da70f11ba765a8c6a8cec";

    private WitcheryInitialSize() {}

    /** 已核witchery-1.7.10-0.24.1的GenericEvents.onEntityJoinWorld字节码0–43；其余ForgeData原样保留 */
    public static void prepare(EntityMaid maid, UUID actor) {
        if (!Loader.isModLoaded("witchery")) return;
        if (!IntegrationGuard.available(Side.SERVER, KEY)) throw new IllegalStateException(
            "Witchery initial-size contract is unavailable; source credential remains intact");
        try {
            if (maid.worldObj == null || maid.worldObj.isRemote
                || !Float.isFinite(maid.width)
                || !Float.isFinite(maid.height)
                || maid.width <= 0
                || maid.height <= 0)
                throw new IllegalStateException("Unknown maid dimensions before Witchery initialization");
            if (!DependencyFingerprint.mod("witchery")
                .endsWith(":sha256:" + SOURCE_SHA256))
                throw new IllegalStateException("Unverified Witchery initial-size provider; no maid will be published");
            // 原提供方每次Join都覆盖这两float；在写前快照之前执行同一已核初始化
            var data = maid.getEntityData();
            data.setFloat("WITCInitialWidth", maid.width);
            data.setFloat("WITCInitialHeight", maid.height);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, KEY, failure, actor);
            throw new IllegalStateException(
                "Cannot prepare verified Witchery dimensions; original source is retained",
                failure);
        }
    }
}
