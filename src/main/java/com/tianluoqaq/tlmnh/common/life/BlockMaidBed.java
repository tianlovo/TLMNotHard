package com.tianluoqaq.tlmnh.common.life;

import java.util.Random;

import net.minecraft.block.BlockBed;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 女仆专用双格床，首批复用原生床的可见外观，不调用玩家睡眠或爆炸分支 */
public final class BlockMaidBed extends BlockBed {

    public BlockMaidBed() {
        setBlockName("tlmnh.maid_bed");
        setBlockTextureName("bed");
        setHardness(0.2F);
        setStepSound(soundTypeWood);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
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
        return isBlockHeadOfBed(metadata) ? null : MaidLifeContent.maidBedItem;
    }

    @Override
    public Item getItem(World world, int x, int y, int z) {
        return MaidLifeContent.maidBedItem;
    }
}
