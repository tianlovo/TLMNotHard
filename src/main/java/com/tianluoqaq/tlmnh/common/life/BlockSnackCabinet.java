package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 供蛋糕摆放与偷吃的最小柜体，暂用原生木板外观 */
public final class BlockSnackCabinet extends BlockContainer {

    public BlockSnackCabinet() {
        super(Material.wood);
        setBlockName("tlmnh.snack_cabinet");
        setBlockTextureName("planks_oak");
        setHardness(2.0F);
        setResistance(3.0F);
        setStepSound(soundTypeWood);
        setCreativeTab(CreativeTabs.tabDecorations);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntitySnackCabinet();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        var held = player.getCurrentEquippedItem();
        if (side == 1 && held != null && held.getItem() instanceof ItemBlock) return false;
        // 原生蛋糕物品不是 ItemBlock，也要把柜面点击交给其放置逻辑
        if (side == 1 && held != null && held.getItem() == net.minecraft.init.Items.cake) return false;
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet
            && cabinet.isUseableByPlayer(player)) {
            if (player.isSneaking() && cabinet.recovery()
                .size() > 0) {
                cabinet.recovery()
                    .recoverOne(player);
                cabinet.markDirty();
            } else player.displayGUIChest(cabinet);
        }
        return true;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block old, int metadata) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet) {
            cabinet.recovery()
                .depositAll(world, x + 0.5, y + 0.5, z + 0.5);
            for (int slot = 0; slot < cabinet.getSizeInventory(); slot++) {
                var stack = cabinet.getStackInSlot(slot);
                if (stack != null) com.tianluoqaq.tlmnh.common.lifecycle.MaidFilmRecovery
                    .queueOwnedItem(world, stack, x + 0.5, y + 0.5, z + 0.5);
                cabinet.setInventorySlotContents(slot, null);
            }
        }
        super.breakBlock(world, x, y, z, old, metadata);
    }
}
