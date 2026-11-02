package com.tianluoqaq.tlmnh.common.life;

import java.util.Random;

import net.minecraft.block.BlockBed;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.life.bed.MaidBedColor;
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
        var held = player.getCurrentEquippedItem();
        var color = MaidBedColor.fromDye(held);
        if (color == null) return false;
        int metadata = world.getBlockMetadata(x, y, z);
        if (!isBlockHeadOfBed(metadata)) {
            int direction = metadata & 3;
            x += field_149981_a[direction][0];
            z += field_149981_a[direction][1];
        }
        if (!world.blockExists(x, y, z) || !(world.getTileEntity(x, y, z) instanceof TileEntityMaidBed bed)
            || !bed.complete()) return false;
        if (bed.getColor() == null) {
            if (!world.isRemote) {
                player.addChatMessage(new ChatComponentTranslation("message.tlmnh.maid_bed.unreadable_color"));
                com.tianluoqaq.tlmnh.TLMNotHard.LOG.warn(
                    "Unrecognized saved maid-bed color; dye refused at {},{},{}",
                    x,
                    y,
                    z,
                    new IllegalStateException("Saved BedColor is not a supported integer color"));
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
        return isBlockHeadOfBed(metadata) ? null : MaidLifeContent.maidBedItem;
    }

    @Override
    public Item getItem(World world, int x, int y, int z) {
        return MaidLifeContent.maidBedItem;
    }
}
