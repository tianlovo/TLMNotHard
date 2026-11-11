package com.tianluoqaq.tlmnh.client.gui.overlay;

/** 原版准星交互提示的显示准入；提示不执行交互或承诺服务端会接受动作 */
public final class MaidInteractionTipsRules {

    private MaidInteractionTipsRules() {}

    public enum Tip {

        GOLDEN_APPLE(ClientOverlayIds.ID_GOLDEN_APPLE, ClientOverlayIds.KEY_ENABLE_GOLDEN_APPLE_TIP),
        POTION(ClientOverlayIds.ID_POTION, ClientOverlayIds.KEY_ENABLE_POTION_TIP),
        MILK_BUCKET(ClientOverlayIds.ID_MILK_BUCKET, ClientOverlayIds.KEY_ENABLE_MILK_BUCKET_TIP),
        GLASS_BOTTLE(ClientOverlayIds.ID_GLASS_BOTTLE, ClientOverlayIds.KEY_ENABLE_GLASS_BOTTLE_TIP),
        NAME_TAG(ClientOverlayIds.ID_NAME_TAG, ClientOverlayIds.KEY_ENABLE_NAME_TAG_TIP),
        SADDLE(ClientOverlayIds.ID_SADDLE, ClientOverlayIds.KEY_ENABLE_SADDLE_TIP),
        EMPTY_SLAB(ClientOverlayIds.I18N_OVERLAY_TLMNH_EMPTY_SLAB_TIPS, ClientOverlayIds.KEY_ENABLE_EMPTY_SLAB_TIP);

        public final String translationKey;
        public final String configKey;

        Tip(String name, String configKey) {
            this.translationKey = name.startsWith(ClientOverlayIds.I18N_OVERLAY) ? name : ClientOverlayIds.I18N_OVERLAY_TOUHOU_LITTLE_MAID + name + ClientOverlayIds.ID_TIPS;
            this.configKey = configKey;
        }
    }

    public static boolean visible(boolean firstPerson, boolean hudVisible, boolean spectator, boolean maidAlive,
        boolean owned, boolean mainHand, boolean sameVehicle) {
        return firstPerson && hudVisible && !spectator && maidAlive && owned && mainHand && !sameVehicle;
    }
}
