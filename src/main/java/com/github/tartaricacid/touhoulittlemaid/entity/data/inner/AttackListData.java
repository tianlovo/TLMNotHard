/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/data/inner/AttackListData.java, MIT
 * Adaptation: Java 8 class and native ResourceLocation; persistence is supplied by the entity platform boundary
 */
package com.github.tartaricacid.touhoulittlemaid.entity.data.inner;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;

public final class AttackListData {

    private final Map<ResourceLocation, MonsterType> attackGroups;

    public AttackListData(Map<ResourceLocation, MonsterType> attackGroups) {
        this.attackGroups = Objects.requireNonNull(attackGroups, "attackGroups");
    }

    public Map<ResourceLocation, MonsterType> attackGroups() {
        return attackGroups;
    }

    public static AttackListData empty() {
        return new AttackListData(new HashMap<>());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AttackListData data && attackGroups.equals(data.attackGroups);
    }

    @Override
    public int hashCode() {
        return attackGroups.hashCode();
    }

    @Override
    public String toString() {
        return "AttackListData[attackGroups=" + attackGroups + "]";
    }
}
