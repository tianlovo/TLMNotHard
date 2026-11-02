package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.block.BlockBed;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

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
        y++;
        int direction = MathHelper.floor_double(player.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int headX = x + BlockBed.field_149981_a[direction][0];
        int headZ = z + BlockBed.field_149981_a[direction][1];
        if (!world.blockExists(x, y, z) || !world.blockExists(headX, y, headZ)
            || !player.canPlayerEdit(x, y, z, side, stack)
            || !player.canPlayerEdit(headX, y, headZ, side, stack)
            || !world.isAirBlock(x, y, z)
            || !world.isAirBlock(headX, y, headZ)
            || !World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)
            || !World.doesBlockHaveSolidTopSurface(world, headX, y - 1, headZ)) return false;
        if (world.isRemote) return true;
        var bed = MaidLifeContent.maidBed;
        // 两段完整后再通知邻居，避免只放下床尾时被原生邻居检查立即移除
        if (!world.setBlock(x, y, z, bed, direction, 2)) return false;
        if (!world.setBlock(headX, y, headZ, bed, direction | 8, 2)) {
            world.setBlockToAir(x, y, z);
            return false;
        }
        if (!(world.getTileEntity(headX, y, headZ) instanceof TileEntityMaidBed tile)
            || !tile.initializeItemColor(stack)) {
            // 放置尚未扣物；只撤销本次仍然存在的两半，不调用掉落路径
            if (world.getBlock(headX, y, headZ) == bed)
                world.setBlock(headX, y, headZ, net.minecraft.init.Blocks.air, 0, 2);
            if (world.getBlock(x, y, z) == bed) world.setBlock(x, y, z, net.minecraft.init.Blocks.air, 0, 2);
            return false;
        }
        world.notifyBlocksOfNeighborChange(x, y, z, bed);
        world.notifyBlocksOfNeighborChange(headX, y, headZ, bed);
        if (!player.capabilities.isCreativeMode) stack.stackSize--;
        return true;
    }
}
