package com.tianluoqaq.tlmnh.common.life.cabinet;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

/** 原零食柜物品说明，仍沿原生ItemBlock放置及同一注册名 */
public final class ItemSnackCabinet extends ItemBlock {

    public ItemSnackCabinet(Block block) {
        super(block);
    }

    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, java.util.List<String> lines, boolean advanced) {
        lines.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocal("block.touhou_little_maid.snack_cabinet.tip"));
    }
}
