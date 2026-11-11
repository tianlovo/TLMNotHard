/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemFilm.java, MIT; see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: existing server-ledger credential shape replaces upstream embedded maid data
 */
package com.tianluoqaq.tlmnh.common.lifecycle.item;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 沿原胶片恒常闪光与空数据提示，凭证及掉落保全仍沿原StoredMaidItem实现 */
public final class MaidFilmItem extends MaidLifecycleItems.StoredMaidItem {

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasEffect(ItemStack stack) {
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        if (MaidLifecycleItems.token(stack) == null) lines.add(
            EnumChatFormatting.DARK_RED
                + StatCollector.translateToLocal("tooltips.touhou_little_maid.film.no_data.desc"));
    }
}
