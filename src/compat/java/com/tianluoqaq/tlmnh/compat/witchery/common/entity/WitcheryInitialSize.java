package com.tianluoqaq.tlmnh.compat.witchery.common.entity;

import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;

/** Witchery出生事件的两项原始尺寸初始化，不调用包含其他玩法的完整事件处理器 */
public final class WitcheryInitialSize {

    private WitcheryInitialSize() {}

    /** 已核witchery-1.7.10-0.24.1的GenericEvents.onEntityJoinWorld字节码0–43；其余ForgeData原样保留 */
    public static void prepare(EntityMaid maid, UUID actor) {
        if (!Loader.isModLoaded(WitcheryEntityIds.ID_WITCHERY)) return;
        if (!IntegrationGuard.available(Side.SERVER, WitcheryEntityIds.KEY))
            throw new IllegalStateException(WitcheryEntityDiagnostics.MSG_WITCHERY_INITIAL_SIZE_CONTRACT_IS);
        try {
            if (maid.worldObj == null || maid.worldObj.isRemote
                || !Float.isFinite(maid.width)
                || !Float.isFinite(maid.height)
                || maid.width <= 0
                || maid.height <= 0)
                throw new IllegalStateException(WitcheryEntityDiagnostics.MSG_UNKNOWN_MAID_DIMENSIONS_BEFORE_WITCHERY);
            if (!DependencyFingerprint.mod(WitcheryEntityIds.ID_WITCHERY)
                .endsWith(WitcheryEntityIds.REVIEWED_ARTIFACT_REVIEWED + WitcheryEntityIds.SOURCE_SHA256))
                throw new IllegalStateException(
                    WitcheryEntityDiagnostics.MSG_UNVERIFIED_WITCHERY_INITIAL_SIZE_PROVIDER);
            // 原提供方每次Join都覆盖这两float；在写前快照之前执行同一已核初始化
            var data = maid.getEntityData();
            data.setFloat(WitcheryEntityNbtKeys.KEY_WITC_INITIAL_WIDTH, maid.width);
            data.setFloat(WitcheryEntityNbtKeys.KEY_WITC_INITIAL_HEIGHT, maid.height);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, WitcheryEntityIds.KEY, failure, actor);
            throw new IllegalStateException(
                WitcheryEntityDiagnostics.MSG_CANNOT_PREPARE_VERIFIED_WITCHERY_DIMENSIONS,
                failure);
        }
    }
}
