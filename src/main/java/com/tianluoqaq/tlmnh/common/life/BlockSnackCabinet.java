package com.tianluoqaq.tlmnh.common.life;

import java.util.ArrayList;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTileReturns;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.RecoveryStore;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 供蛋糕摆放与偷吃的最小27格柜体，原模型与玻璃罩由TESR显示 */
public final class BlockSnackCabinet extends BlockContainer {

    public static final int TYPE_NONE = 0, TYPE_FULL = 1, TYPE_HALF = 2;

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
    public int getRenderType() {
        return -1;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public boolean isSideSolid(IBlockAccess world, int x, int y, int z, ForgeDirection side) {
        // 原noOcclusion不改变柜体的完整碰撞/承重，旧平台需独立声明实心面
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public String getItemIconName() {
        return "touhou_little_maid:snack_cabinet";
    }

    /** 旧柜metadata0没有外观方向，沿原默认北向；低两位记录玩家放置yaw */
    public static int facingDirection(int metadata) {
        return ((metadata & 3) + 2) & 3;
    }

    public static int cabinetType(int metadata) {
        return (metadata >>> 2) & 3;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        if (world.isRemote || world.getBlock(x, y, z) != this
            || !(world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet)
            || cabinet.isInvalid()) return;
        int previous = world.getBlockMetadata(x, y, z);
        if (cabinetType(previous) > TYPE_HALF) return;
        if (placer != null) {
            int facing = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + .5) & 3;
            world.setBlockMetadataWithNotify(x, y, z, (previous & 12) | facing, 2);
        }
        if (stack != null && stack.hasDisplayName() && world.getTileEntity(x, y, z) == cabinet)
            cabinet.setCustomInventoryName(stack.getDisplayName());
        refreshDisplay(world, x, y, z);
    }

    @Override
    public boolean hasComparatorInputOverride() {
        return true;
    }

    @Override
    public int getComparatorInputOverride(World world, int x, int y, int z, int side) {
        return world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet && !cabinet.isInvalid()
            ? Container.calcRedstoneFromInventory(cabinet)
            : 0;
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        refreshDisplay(world, x, y, z);
    }

    void refreshDisplay(World world, int x, int y, int z) {
        if (world.isRemote || world.getBlock(x, y, z) != this
            || !world.blockExists(x, y + 1, z)
            || world.getBlock(x, y + 1, z) != Blocks.cake
            || !(world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet)
            || cabinet.isInvalid()) return;
        int metadata = world.getBlockMetadata(x, y, z);
        // 原updateShape仅在命中食物标签时切换罩型，移走蛋糕后保留原罩型
        if (cabinetType(metadata) <= TYPE_HALF && cabinetType(metadata) != TYPE_FULL)
            world.setBlockMetadataWithNotify(x, y, z, (metadata & 3) | (TYPE_FULL << 2), 2);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        var held = player.getCurrentEquippedItem();
        if (side == 1 && held != null && held.getItem() instanceof ItemBlock) return false;
        // 原生蛋糕物品不是 ItemBlock，也要把柜面点击交给其放置逻辑
        if (side == 1 && held != null && held.getItem() == Items.cake) return false;
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
    public void onBlockPreDestroy(World world, int x, int y, int z, int metadata) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet)
            prepareReturn(cabinet);
        super.onBlockPreDestroy(world, x, y, z, metadata);
    }

    private static void prepareReturn(TileEntitySnackCabinet cabinet) {
        var items = new ArrayList<ItemStack>();
        for (int slot = 0; slot < cabinet.getSizeInventory(); slot++) {
            var stack = cabinet.getStackInSlot(slot);
            if (stack != null) items.add(stack);
        }
        var recovery = cabinet.recovery()
            .write();
        for (int i = 0; i < recovery.tagCount(); i++) {
            var stack = ProtectedItemCodec.decode(recovery.getCompoundTagAt(i));
            if (stack == null) throw new IllegalStateException("Empty recovery contents retained");
            items.add(stack);
        }
        MaidTileReturns.prepare(cabinet, items);
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block old, int metadata) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TileEntitySnackCabinet cabinet) {
            prepareReturn(cabinet);
            MaidTileReturns.beforeClear(cabinet);
            var planned = MaidShrineTransfer.snapshot(cabinet);
            planned.setTag("Items", new NBTTagList());
            planned.setTag(RecoveryStore.TAG, new NBTTagList());
            cabinet.clearForReturn();
            MaidTileReturns.cleared(cabinet, planned);
        }
        if (!world.isRemote) world.func_147453_f(x, y, z, this);
        super.breakBlock(world, x, y, z, old, metadata);
    }
}
