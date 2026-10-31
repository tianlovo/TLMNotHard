/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/DefaultMaidSoundPack.java, MIT
 * Adaptation: unchanged builtin IDs and initial probability
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

public final class DefaultMaidSoundPack {

    public static final String DEFAULT_SOUND_PACK_ID = "touhou_little_maid";
    public static final String PECO_SOUND_PACK_ID = "littlemaid_peco";
    private static final double PECO_CHANCE = 0.75;

    private DefaultMaidSoundPack() {}

    public static String getInitSoundPackId() {
        return Math.random() < PECO_CHANCE ? PECO_SOUND_PACK_ID : DEFAULT_SOUND_PACK_ID;
    }
}
