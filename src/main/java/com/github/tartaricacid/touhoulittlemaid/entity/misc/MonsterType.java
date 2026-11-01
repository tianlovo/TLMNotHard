/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/misc/MonsterType.java, MIT
 * Adaptation: native 1.7 chat component; serialized names replace the modern Codec boundary
 */
package com.github.tartaricacid.touhoulittlemaid.entity.misc;

import java.util.Locale;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

public enum MonsterType {

    FRIENDLY,
    NEUTRAL,
    HOSTILE;

    private final IChatComponent component;

    MonsterType() {
        component = new ChatComponentTranslation("gui.touhou_little_maid.monster_type." + getSerializedName());
    }

    public MonsterType getPrevious() {
        return values()[Math.floorMod(ordinal() - 1, values().length)];
    }

    public MonsterType getNext() {
        return values()[(ordinal() + 1) % values().length];
    }

    public IChatComponent getComponent() {
        return component;
    }

    public String getSerializedName() {
        return name().toLowerCase(Locale.ENGLISH);
    }
}
