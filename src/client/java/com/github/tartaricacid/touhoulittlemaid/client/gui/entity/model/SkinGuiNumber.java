/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/model/SkinGuiNumber.java, MIT
 * Adaptation: fixed builtin pack metadata, same seven tabs and eleven-column geometry
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model;

import java.util.List;

import com.tianluoqaq.tlmnh.client.gui.BuiltinMaidPacks.Pack;

public final class SkinGuiNumber {

    private final List<Pack> packs;

    public SkinGuiNumber(List<Pack> packs) {
        this.packs = packs;
    }

    public int getPageSize() {
        return (packs.size() - 1) / 7 + 1;
    }

    public int getTabIndex(int pack) {
        return pack % 7;
    }

    public int getTabSize(int pack) {
        return pack < (getPageSize() - 1) * 7 ? 7 : (packs.size() - 1) % 7 + 1;
    }

    public int tabToPackIndex(int tab, int page) {
        return Math.max(0, Math.min(packs.size() - 1, page * 7 + tab));
    }

    public int modelFromIndex(int row) {
        return 11 * row;
    }
}
