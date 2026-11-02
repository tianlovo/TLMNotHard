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
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public String getItemIconName() {
        return "touhou_little_maid:shrine";
    }

    /** metadata0沿原默认北向，1/2/3沿反向玩家yaw；其它值保留为未知 */
    public static int facingDirection(int metadata) {
        return ((metadata & 3) + 2) & 3;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, net.minecraft.entity.EntityLivingBase placer,
        net.minecraft.item.ItemStack stack) {
        if (world.isRemote || placer == null
            || world.getBlock(x, y, z) != this
            || world.getBlockMetadata(x, y, z) > 3
            || !(world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine)
            || shrine.isInvalid()) return;
        int facing = net.minecraft.util.MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + .5) & 3;
        world.setBlockMetadataWithNotify(x, y, z, facing, 2);
    }

    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, net.minecraft.util.AxisAlignedBB mask,
        java.util.List<net.minecraft.util.AxisAlignedBB> boxes, net.minecraft.entity.Entity entity) {
        for (int tier = 0; tier < 3; tier++) {
            var box = collisionBox(x, y, z, tier);
            if (box.intersectsWith(mask)) boxes.add(box);
        }
    }

    @Override
    public net.minecraft.util.MovingObjectPosition collisionRayTrace(World world, int x, int y, int z,
        net.minecraft.util.Vec3 start, net.minecraft.util.Vec3 end) {
        net.minecraft.util.MovingObjectPosition closest = null;
        for (int tier = 0; tier < 3; tier++) {
            var hit = collisionBox(x, y, z, tier).calculateIntercept(start, end);
            if (hit != null
                && (closest == null || start.squareDistanceTo(hit.hitVec) < start.squareDistanceTo(closest.hitVec)))
                closest = new net.minecraft.util.MovingObjectPosition(x, y, z, hit.sideHit, hit.hitVec);
        }
        return closest;
    }

    @Override
    public boolean isSideSolid(net.minecraft.world.IBlockAccess world, int x, int y, int z,
        net.minecraftforge.common.util.ForgeDirection side) {
        return side == net.minecraftforge.common.util.ForgeDirection.DOWN;
    }

    private static net.minecraft.util.AxisAlignedBB collisionBox(int x, int y, int z, int tier) {
        double inset = tier * .125;
        double low = tier * 5.0 / 16.0, high = tier == 2 ? 1 : (tier + 1) * 5.0 / 16.0;
        return net.minecraft.util.AxisAlignedBB
            .getBoundingBox(x + inset, y + low, z + inset, x + 1 - inset, y + high, z + 1 - inset);
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
            var film = shrine.getFilm();
            if (film != null) {
                MaidFilmRecovery.queueOwnedItem(world, film, x + 0.5D, y + 0.5D, z + 0.5D);
                shrine.extractForBreak();
            }
        }
        super.breakBlock(world, x, y, z, block, metadata);
    }
}
