/*
 * Interaction adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: block/BlockShrine.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 单槽神龛：持片装入，潜行空手取回，空手复活 */
public final class MaidShrineBlock extends BlockContainer {

    public MaidShrineBlock() {
        super(Material.wood);
        setBlockName("tlmnh.shrine");
        setBlockTextureName("planks_oak");
        setHardness(2.0F);
        setResistance(3.0F);
        setStepSound(soundTypeWood);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new MaidShrineTile();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) return true;
        if (!MaidLifecycleService.canUse(player) || player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > 64.0D)
            return true;
        if (!(world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine)) return false;
        var held = player.getCurrentEquippedItem();
        if (player.isSneaking()) {
            if (held != null) return true;
            var film = shrine.extract();
            if (film != null) {
                player.inventory.setInventorySlotContents(player.inventory.currentItem, film);
                player.inventory.markDirty();
            }
            return true;
        }
        if (held != null) {
            if (shrine.insert(held)) {
                // block callback不经过ItemInWorldManager的创造栈数量恢复分支
                player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                player.inventory.markDirty();
            } else MaidLifecycleService.tell(player, MaidLifecycleService.Result.INVALID_CREDENTIAL);
            return true;
        }
        MaidLifecycleService.tell(player, MaidLifecycleService.resurrect(player, shrine));
        return true;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine) {
            var film = shrine.extractForBreak();
            if (film != null) {
                MaidFilmRecovery.returnFromShrine(world, film, x + 0.5D, y + 0.5D, z + 0.5D);
            }
        }
        super.breakBlock(world, x, y, z, block, metadata);
    }
}
