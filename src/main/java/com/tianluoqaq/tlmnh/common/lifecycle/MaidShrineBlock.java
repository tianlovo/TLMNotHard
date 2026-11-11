/*
 * Interaction adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: block/BlockShrine.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 单槽神龛：持片装入，潜行空手取回，空手复活 */
public final class MaidShrineBlock extends BlockContainer {

    public MaidShrineBlock() {
        super(Material.wood);
        setBlockName(MaidLifecycleIds.ID_TLMNH_SHRINE);
        setBlockTextureName(MaidLifecycleIds.ID_PLANKS_OAK);
        setHardness(MaidLifecycleTuning.MIDPOINT_DIVISOR_F);
        setResistance(MaidLifecycleTuning.SHRINE_BLOCK_SET_RESISTANCE_FIRST_FACTOR);
        setStepSound(soundTypeWood);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new MaidShrineTile();
    }

    @Override
    public int getRenderType() {
        return MaidLifecycleIds.CUSTOM_BLOCK_RENDER_TYPE;
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
    @SideOnly(Side.CLIENT)
    public String getItemIconName() {
        return MaidLifecycleIds.ID_TOUHOU_LITTLE_MAID_SHRINE;
    }

    /** metadata0沿原默认北向，1/2/3沿反向玩家yaw；其它值保留为未知 */
    public static int facingDirection(int metadata) {
        return ((metadata & MaidLifecycleTuning.TARGET_SIZE_THRESHOLD) + MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM)
            & MaidLifecycleTuning.TARGET_SIZE_THRESHOLD;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        if (world.isRemote || placer == null
            || world.getBlock(x, y, z) != this
            || world.getBlockMetadata(x, y, z) > MaidLifecycleTuning.TARGET_SIZE_THRESHOLD
            || !(world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine)
            || shrine.isInvalid()) return;
        int facing = MathHelper.floor_double(
            placer.rotationYaw * MaidLifecycleTuning.SHRINE_BLOCK_FACING / MaidLifecycleTuning.FULL_TURN_DEGREES_F
                + MaidLifecycleTuning.DEATH_INGRESS_RUN_FACTOR)
            & MaidLifecycleTuning.TARGET_SIZE_THRESHOLD;
        world.setBlockMetadataWithNotify(x, y, z, facing, MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM);
    }

    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB mask, List<AxisAlignedBB> boxes,
        Entity entity) {
        for (int tier = 0; tier < MaidLifecycleTuning.TARGET_SIZE_THRESHOLD; tier++) {
            var box = collisionBox(x, y, z, tier);
            if (box.intersectsWith(mask)) boxes.add(box);
        }
    }

    @Override
    public MovingObjectPosition collisionRayTrace(World world, int x, int y, int z, Vec3 start, Vec3 end) {
        MovingObjectPosition closest = null;
        for (int tier = 0; tier < MaidLifecycleTuning.TARGET_SIZE_THRESHOLD; tier++) {
            var hit = collisionBox(x, y, z, tier).calculateIntercept(start, end);
            if (hit != null
                && (closest == null || start.squareDistanceTo(hit.hitVec) < start.squareDistanceTo(closest.hitVec)))
                closest = new MovingObjectPosition(x, y, z, hit.sideHit, hit.hitVec);
        }
        return closest;
    }

    @Override
    public boolean isSideSolid(IBlockAccess world, int x, int y, int z, ForgeDirection side) {
        return side == ForgeDirection.DOWN;
    }

    private static AxisAlignedBB collisionBox(int x, int y, int z, int tier) {
        double inset = tier * MaidLifecycleTuning.SHRINE_BLOCK_INSET;
        double low = tier * MaidLifecycleTuning.SHRINE_BLOCK_LOW / MaidLifecycleTuning.SHRINE_BLOCK_LOW_2,
            high = tier == MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM
                ? MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                : (tier + 1) * MaidLifecycleTuning.SHRINE_BLOCK_LOW / MaidLifecycleTuning.SHRINE_BLOCK_LOW_2;
        return AxisAlignedBB.getBoundingBox(x + inset, y + low, z + inset, x + 1 - inset, y + high, z + 1 - inset);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) return true;
        if (!MaidLifecycleService.canUse(player) || player.getDistanceSq(
            x + MaidLifecycleTuning.CENTER_OFFSET_D,
            y + MaidLifecycleTuning.CENTER_OFFSET_D,
            z + MaidLifecycleTuning.CENTER_OFFSET_D) > MaidLifecycleTuning.LIFECYCLE_SERVICE_DOMAIN_FACTOR) return true;
        if (!(world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine)) return false;
        var held = player.getCurrentEquippedItem();
        if (player.isSneaking()) {
            if (held != null) return true;
            if (MaidShrineTransfer.transfer(player, shrine, false)) {
                CompatServices.worldContent()
                    .playShrineFrameSound(world, x, y, z, false);
            }
            return true;
        }
        if (held != null) {
            if (MaidShrineTransfer.transfer(player, shrine, true)) {
                CompatServices.worldContent()
                    .playShrineFrameSound(world, x, y, z, true);
            } else MaidLifecycleService.tell(player, MaidLifecycleService.Result.INVALID_CREDENTIAL);
            return true;
        }
        MaidLifecycleService.tell(player, MaidLifecycleService.resurrect(player, shrine));
        return true;
    }

    @Override
    public void onBlockPreDestroy(World world, int x, int y, int z, int metadata) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine) prepareReturn(shrine);
        super.onBlockPreDestroy(world, x, y, z, metadata);
    }

    private static void prepareReturn(MaidShrineTile shrine) {
        var items = new ArrayList<ItemStack>();
        var film = shrine.getFilm();
        if (film != null) items.add(film);
        MaidTileReturns.prepare(shrine, items);
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof MaidShrineTile shrine) {
            prepareReturn(shrine);
            MaidTileReturns.beforeClear(shrine);
            var planned = MaidShrineTransfer.snapshot(shrine);
            planned.removeTag(MaidLifecyclePart2NbtKeys.KEY_STORAGE_ITEM);
            shrine.extractForBreak();
            MaidTileReturns.cleared(shrine, planned);
        }
        super.breakBlock(world, x, y, z, block, metadata);
    }
}
