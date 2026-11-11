package com.tianluoqaq.tlmnh.client.gui.overlay;

/** 原版准星交互提示的显示准入；提示不执行交互或承诺服务端会接受动作 */
public final class MaidInteractionTipsRules {

    private MaidInteractionTipsRules() {}

    public enum Tip {

        GOLDEN_APPLE("golden_apple", "EnableGoldenAppleTip"),
        POTION("potion", "EnablePotionTip"),
        MILK_BUCKET("milk_bucket", "EnableMilkBucketTip"),
        GLASS_BOTTLE("glass_bottle", "EnableGlassBottleTip"),
        NAME_TAG("name_tag", "EnableNameTagTip"),
        SADDLE("saddle", "EnableSaddleTip"),
        EMPTY_SLAB("overlay.tlmnh.empty_slab.tips", "EnableEmptySlabTip");

        public final String translationKey;
        public final String configKey;

        Tip(String name, String configKey) {
            this.translationKey = name.startsWith("overlay.") ? name : "overlay.touhou_little_maid." + name + ".tips";
            this.configKey = configKey;
        }
    }

    public static boolean visible(boolean firstPerson, boolean hudVisible, boolean spectator, boolean maidAlive,
        boolean owned, boolean mainHand, boolean sameVehicle) {
        return firstPerson && hudVisible && !spectator && maidAlive && owned && mainHand && !sameVehicle;
    }
}
