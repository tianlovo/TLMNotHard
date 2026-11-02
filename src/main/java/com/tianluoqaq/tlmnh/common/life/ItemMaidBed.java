package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** 原生 ItemBed 把方块写死为 vanilla bed，因此保留独立的双格放置入口 */
public final class ItemMaidBed extends Item {

    public ItemMaidBed() {
        setUnlocalizedName("tlmnh.maid_bed");
        setTextureName("touhou_little_maid:maid_bed");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabDecorations);
    }

    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, java.util.List<String> lines, boolean advanced) {
        var color = com.tianluoqaq.tlmnh.common.life.bed.MaidBedColorData
            .decode(com.tianluoqaq.tlmnh.common.life.bed.MaidBedColorData.read(stack.getTagCompound()));
        String text = color == null
            ? net.minecraft.util.StatCollector.translateToLocal("tooltip.tlmnh.maid_bed.unknown_color")
            : net.minecraft.util.StatCollector.translateToLocalFormatted(
                "tooltip.tlmnh.maid_bed.color",
                net.minecraft.util.StatCollector.translateToLocal("color.tlmnh.maid_bed." + color.resourceName));
        lines.add(net.minecraft.util.EnumChatFormatting.GRAY + text);
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (side != 1 || stack == null || stack.stackSize <= 0) return false;
        return MaidBedPlacement.place(stack, player, world, x, y + 1, z, side);
    }
}
