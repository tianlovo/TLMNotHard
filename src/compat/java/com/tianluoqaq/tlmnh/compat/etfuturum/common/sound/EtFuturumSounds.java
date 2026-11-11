package com.tianluoqaq.tlmnh.compat.etfuturum.common.sound;

import java.lang.invoke.MethodHandle;

import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;

/** 读取 Et Futurum Requiem 的现代音效命名空间；未装该模组时平滑回退到原版箱子音效 */
public final class EtFuturumSounds {

    private static volatile String namespace;
    private static volatile boolean resolved;

    private EtFuturumSounds() {}

    public static String barrel(boolean opening) {
        String fallback = opening ? EtFuturumSoundIds.ID_RANDOM_CHESTOPEN : EtFuturumSoundIds.ID_RANDOM_CHESTCLOSED;
        return IntegrationGuard.call(ExternalFeature.CABINET_SOUNDS, () -> {
            String domain = soundNamespace();
            if (domain == null) return fallback;
            return domain + (opening ? EtFuturumSoundIds.ID_BLOCK_BARREL_OPEN : EtFuturumSoundIds.ID_BLOCK_BARREL_CLOSE);
        }, () -> fallback);
    }

    /** 神龛放取胶片后的点缀音效；未装 EFR 或音效熔断时静默跳过，不影响已完成的物品转移 */
    public static void shrineFrame(World world, int x, int y, int z, boolean inserted) {
        IntegrationGuard.call(ExternalFeature.SHRINE_SOUNDS, () -> {
            if (world.isRemote) return false;
            String domain = soundNamespace();
            if (domain == null) return false;
            String sound = domain + (inserted ? EtFuturumSoundIds.ID_ENTITY_ITEM_FRAME_ADD_ITEM : EtFuturumSoundIds.ID_ENTITY_ITEM_FRAME_REMOVE_ITEM);
            world.playSoundEffect(x + EtFuturumSoundTuning.CENTER_OFFSET_D, y + EtFuturumSoundTuning.CENTER_OFFSET_D, z + EtFuturumSoundTuning.CENTER_OFFSET_D, sound, EtFuturumSoundTuning.PLAY_SOUND_EFFECT_SINGLE_UNIT, EtFuturumSoundTuning.PLAY_SOUND_EFFECT_SINGLE_UNIT);
            return true;
        }, () -> false);
    }

    private static String soundNamespace() {
        if (resolved) return namespace;
        synchronized (EtFuturumSounds.class) {
            if (resolved) return namespace;
            MethodHandle getter = OptionalApi.findStaticGetter(
                KnownModIds.ET_FUTURUM,
                EtFuturumSoundReflectionSpec.TARGET_CLASS_REFERENCE,
                EtFuturumSoundIds.KEY_MC_ASSET_VER,
                String.class);
            if (getter == null) {
                resolved = true;
                return null;
            }
            String text;
            try {
                text = (String) getter.invokeExact();
            } catch (RuntimeException | Error direct) {
                throw direct;
            } catch (Throwable failure) {
                throw new IllegalStateException(EtFuturumSoundDiagnostics.MSG_FAILED_TO_READ_EFR_SOUND, failure);
            }
            if (text == null || !text.matches(EtFuturumSoundIds.ID_A_Z0_9))
                throw new IllegalStateException(EtFuturumSoundDiagnostics.MSG_EFR_MODERN_SOUND_RESOURCE_NAMESPACE);
            namespace = text;
            resolved = true;
            return text;
        }
    }
}
