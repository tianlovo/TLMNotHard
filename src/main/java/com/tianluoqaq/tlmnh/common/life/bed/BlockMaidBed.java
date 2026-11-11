package com.tianluoqaq.tlmnh.common.life.bed;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 女仆专用双格床，外观由床头TESR负责，不调用玩家睡眠或爆炸分支 */
public final class BlockMaidBed extends BlockBed {

    public BlockMaidBed() {
        setBlockName(MaidBedIds.ID_TLMNH_MAID_BED);
        setBlockTextureName(MaidBedIds.ID_BED);
        setHardness(MaidBedLayout.BLOCK_BED_SET_HARDNESS_FIRST_FACTOR);
        setStepSound(soundTypeWood);
    }

    @Override
    public int getRenderType() {
        return MaidBedIds.CUSTOM_BLOCK_RENDER_TYPE;
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        var held = player.getCurrentEquippedItem();
        var color = MaidBedColor.fromDye(held);
        if (color == null) return false;
        int metadata = world.getBlockMetadata(x, y, z);
        if (!isBlockHeadOfBed(metadata)) {
            int direction = metadata & MaidBedLayout.LOW_TWO_BITS_MASK;
            x += field_149981_a[direction][MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE];
            z += field_149981_a[direction][MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP];
        }
        if (!world.blockExists(x, y, z) || !(world.getTileEntity(x, y, z) instanceof TileEntityMaidBed bed))
            return false;
        if (bed.itemDataRetained()) {
            tellRetained(player, bed);
            return false;
        }
        if (!bed.complete()) return false;
        if (bed.getColor() == null) {
            if (!world.isRemote) {
                player.addChatMessage(
                    new ChatComponentTranslation(MaidBedIds.I18N_MESSAGE_TLMNH_MAID_BED_UNREADABLE_COLOR));
                ModContext.LOG.warn(
                    MaidBedDiagnostics.MSG_UNRECOGNIZED_SAVED_MAID_BED_COLOR,
                    x,
                    y,
                    z,
                    new IllegalStateException(MaidBedDiagnostics.MSG_SAVED_BEDCOLOR_IS_NOT_SUPPORTED));
            }
            return false;
        }
        if (bed.getColor() == color) return false;
        if (world.isRemote) return true;
        if (!bed.setColor(color)) return false;
        if (!player.capabilities.isCreativeMode) held.stackSize--;
        return true;
    }

    @Override
    public boolean isBed(IBlockAccess world, int x, int y, int z, EntityLivingBase entity) {
        return entity instanceof EntityMaid;
    }

    @Override
    public boolean hasTileEntity(int metadata) {
        return isBlockHeadOfBed(metadata);
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        return isBlockHeadOfBed(metadata) ? new TileEntityMaidBed() : null;
    }

    @Override
    public Item getItemDropped(int metadata, Random random, int fortune) {
        return isBlockHeadOfBed(metadata) ? MaidLifeContent.maidBedItem : null;
    }

    private TileEntityMaidBed head(World world, int x, int y, int z, int metadata) {
        if (!isBlockHeadOfBed(metadata)) {
            x += field_149981_a[metadata & MaidBedLayout.LOW_TWO_BITS_MASK][MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE];
            z += field_149981_a[metadata & MaidBedLayout.LOW_TWO_BITS_MASK][MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP];
        }
        return world.blockExists(x, y, z) && world.getBlock(x, y, z) == this
            && world.getTileEntity(x, y, z) instanceof TileEntityMaidBed bed ? bed : null;
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        var drops = new ArrayList<ItemStack>();
        if (isBlockHeadOfBed(metadata)) {
            var bed = head(world, x, y, z, metadata);
            if (bed != null && !bed.dropQueued() && !bed.placementPending()) drops.add(bed.coloredItem());
        }
        return drops;
    }

    @Override
    public void dropBlockAsItemWithChance(World world, int x, int y, int z, int metadata, float chance, int fortune) {
        if (world.isRemote || world.restoringBlockSnapshots) return;
        // 不能调用BlockBed父实现，它只允许床尾掉落；仍沿用Block的Forge事件及概率流程
        var items = getDrops(world, x, y, z, metadata, fortune);
        float admitted = ForgeEventFactory
            .fireBlockHarvesting(items, world, this, x, y, z, metadata, fortune, chance, false, harvesters.get());
        for (var stack : items) if (world.rand.nextFloat() <= admitted) dropBlockAsItem(world, x, y, z, stack);
    }

    @Override
    protected void dropBlockAsItem(World world, int x, int y, int z, ItemStack stack) {
        if (world.isRemote || world.restoringBlockSnapshots
            || !world.getGameRules()
                .getGameRuleBooleanValue(MaidBedIds.ID_DOTILEDROPS))
            return;
        if (!captureDrops.get() && stack != null
            && stack.getItem() == MaidLifeContent.maidBedItem
            && world.getTileEntity(x, y, z) instanceof TileEntityMaidBed bed) {
            bed.queueItemDrop(stack);
            return;
        }
        super.dropBlockAsItem(world, x, y, z, stack);
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        int metadata = world.getBlockMetadata(x, y, z), direction = metadata & MaidBedLayout.LOW_TWO_BITS_MASK;
        int sign = isBlockHeadOfBed(metadata) ? MaidBedLayout.UNSET_OR_NOT_FOUND
            : MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP;
        int otherX = x + sign * field_149981_a[direction][MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE],
            otherZ = z + sign * field_149981_a[direction][MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP];
        if (!world.blockExists(otherX, y, otherZ) || world.getBlock(otherX, y, otherZ) == this) return;
        if (isBlockHeadOfBed(metadata)) {
            var bed = head(world, x, y, z, metadata);
            if (bed != null && bed.itemDataRetained()) return;
            dropBlockAsItem(world, x, y, z, metadata, MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE);
        }
        world.setBlockToAir(x, y, z);
    }

    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        var bed = head(world, x, y, z, world.getBlockMetadata(x, y, z));
        if (bed != null && bed.itemDataRetained()) {
            tellRetained(player, bed);
            return false;
        }
        // 原生采集先删方块再求掉落；保留Tile直到带色物品已提交
        return willHarvest || super.removedByPlayer(world, player, x, y, z, false);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int metadata) {
        var bed = head(world, x, y, z, metadata);
        if (bed != null && bed.itemDataRetained()) {
            tellRetained(player, bed);
            return;
        }
        super.harvestBlock(world, player, x, y, z, metadata);
        // 不放finally：提交抛异常时保留仍持有原数据的床头
        world.setBlockToAir(x, y, z);
    }

    @Override
    public void onBlockHarvested(World world, int x, int y, int z, int metadata, EntityPlayer player) {
        var bed = head(world, x, y, z, metadata);
        if (world.isRemote || !player.capabilities.isCreativeMode || bed != null && bed.itemDataRetained()) return;
        int sign = isBlockHeadOfBed(metadata) ? MaidBedLayout.UNSET_OR_NOT_FOUND
            : MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP;
        int otherX = x + sign
            * field_149981_a[metadata & MaidBedLayout.LOW_TWO_BITS_MASK][MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE],
            otherZ = z + sign
                * field_149981_a[metadata & MaidBedLayout.LOW_TWO_BITS_MASK][MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP];
        if (world.blockExists(otherX, y, otherZ) && world.getBlock(otherX, y, otherZ) == this) world.setBlock(
            otherX,
            y,
            otherZ,
            Blocks.air,
            MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE,
            MaidBedLayout.BLOCK_BED_SET_BLOCK_SIXTH_PARAM);
    }

    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World world, int x, int y, int z, EntityPlayer player) {
        var bed = head(world, x, y, z, world.getBlockMetadata(x, y, z));
        return bed == null || bed.dropQueued() || bed.placementPending() ? null : bed.coloredItem();
    }

    private static void tellRetained(EntityPlayer player, TileEntityMaidBed bed) {
        if (player != null && !player.worldObj.isRemote) player.addChatMessage(
            new ChatComponentTranslation(
                bed.placementPending() ? MaidBedIds.I18N_MESSAGE_TLMNH_MAID_BED_UNCOMMITTED_PLACEMENT
                    : MaidBedIds.I18N_MESSAGE_TLMNH_MAID_BED_TRANSFER_RETAINED));
    }

    @Override
    public Item getItem(World world, int x, int y, int z) {
        return MaidLifeContent.maidBedItem;
    }
}
