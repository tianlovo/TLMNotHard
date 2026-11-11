package com.tianluoqaq.tlmnh.common.life.cabinet;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原零食柜物品说明，仍沿原生ItemBlock放置及同一注册名 */
public final class ItemSnackCabinet extends ItemBlock {

    public ItemSnackCabinet(Block block) {
        super(block);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        lines.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocal("block.touhou_little_maid.snack_cabinet.tip"));
    }
}
