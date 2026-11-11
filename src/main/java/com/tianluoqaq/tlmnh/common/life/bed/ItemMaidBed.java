package com.tianluoqaq.tlmnh.common.life.bed;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原生 ItemBed 把方块写死为 vanilla bed，因此保留独立的双格放置入口 */
public final class ItemMaidBed extends Item {

    public ItemMaidBed() {
        setUnlocalizedName(MaidBedIds.ID_TLMNH_MAID_BED);
        setTextureName(MaidBedIds.ID_TOUHOU_LITTLE_MAID_MAID_BED);
        setMaxStackSize(MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP);
        setCreativeTab(CreativeTabs.tabDecorations);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        var color = MaidBedColorData.decode(MaidBedColorData.read(stack.getTagCompound()));
        String text = color == null
            ? StatCollector.translateToLocal(MaidBedIds.I18N_TOOLTIP_TLMNH_MAID_BED_UNKNOWN_COLOR)
            : StatCollector.translateToLocalFormatted(
                MaidBedIds.I18N_TOOLTIP_TLMNH_MAID_BED_COLOR,
                StatCollector.translateToLocal(MaidBedIds.ID_COLOR_TLMNH_MAID_BED + color.resourceName));
        lines.add(EnumChatFormatting.GRAY + text);
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (side != MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP || stack == null || stack.stackSize <= 0) return false;
        return MaidBedPlacement.place(stack, player, world, x, y + 1, z, side);
    }
}
