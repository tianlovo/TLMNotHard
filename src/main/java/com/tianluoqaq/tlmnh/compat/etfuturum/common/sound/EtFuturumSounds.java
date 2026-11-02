package com.tianluoqaq.tlmnh.compat.etfuturum.common.sound;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** EFR的现代声音资源域由其Reference维护，不复制Mojang音频或固化资源版本 */
public final class EtFuturumSounds {

    private static String namespace;

    private EtFuturumSounds() {}

    public static String barrel(boolean opening) {
        return IntegrationGuard.call(
            ExternalFeature.CABINET_SOUNDS,
            () -> { return soundNamespace() + (opening ? ":block.barrel.open" : ":block.barrel.close"); },
            () -> opening ? "random.chestopen" : "random.chestclosed");
    }

    /** 物品转移已完成；声音回调失败只静音并熔断，不能重播交付或再调用失败的世界接口 */
    public static void shrineFrame(net.minecraft.world.World world, int x, int y, int z, boolean inserted) {
        IntegrationGuard.call(ExternalFeature.SHRINE_SOUNDS, () -> {
            if (world.isRemote) return false;
            String sound = soundNamespace()
                + (inserted ? ":entity.item_frame.add_item" : ":entity.item_frame.remove_item");
            world.playSoundEffect(x + .5, y + .5, z + .5, sound, 1, 1);
            return true;
        }, () -> false);
    }

    private static String soundNamespace() throws ReflectiveOperationException {
        if (namespace == null) {
            Object value = Class.forName("ganymedes01.etfuturum.lib.Reference")
                .getField("MCAssetVer")
                .get(null);
            if (!(value instanceof String text) || !text.matches("[a-z0-9_.-]+"))
                throw new IllegalStateException("EFR modern sound resource namespace changed");
            namespace = text;
        }
        return namespace;
    }
}
