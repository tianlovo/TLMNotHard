package com.tianluoqaq.tlmnh.compat.etfuturum.common.sound;

import java.lang.invoke.MethodHandle;

import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;

/** 读取 Et Futurum Requiem 的现代音效命名空间；未装该模组时平滑回退到原版箱子音效 */
public final class EtFuturumSounds {

    private static volatile String namespace;
    private static volatile boolean resolved;

    private EtFuturumSounds() {}

    public static String barrel(boolean opening) {
        String fallback = opening ? "random.chestopen" : "random.chestclosed";
        return IntegrationGuard.call(ExternalFeature.CABINET_SOUNDS, () -> {
            String domain = soundNamespace();
            if (domain == null) return fallback;
            return domain + (opening ? ":block.barrel.open" : ":block.barrel.close");
        }, () -> fallback);
    }

    /** 神龛放取胶片后的点缀音效；未装 EFR 或音效熔断时静默跳过，不影响已完成的物品转移 */
    public static void shrineFrame(World world, int x, int y, int z, boolean inserted) {
        IntegrationGuard.call(ExternalFeature.SHRINE_SOUNDS, () -> {
            if (world.isRemote) return false;
            String domain = soundNamespace();
            if (domain == null) return false;
            String sound = domain + (inserted ? ":entity.item_frame.add_item" : ":entity.item_frame.remove_item");
            world.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, sound, 1, 1);
            return true;
        }, () -> false);
    }

    private static String soundNamespace() {
        if (resolved) return namespace;
        synchronized (EtFuturumSounds.class) {
            if (resolved) return namespace;
            MethodHandle getter = OptionalApi.findStaticGetter(
                KnownModIds.ET_FUTURUM,
                "ganymedes01.etfuturum.lib.Reference",
                "MCAssetVer",
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
                throw new IllegalStateException("Failed to read EFR sound resource namespace", failure);
            }
            if (text == null || !text.matches("[a-z0-9_.-]+"))
                throw new IllegalStateException("EFR modern sound resource namespace changed");
            namespace = text;
            resolved = true;
            return text;
        }
    }
}
