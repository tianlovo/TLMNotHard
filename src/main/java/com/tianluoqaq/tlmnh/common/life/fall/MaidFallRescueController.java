package com.tianluoqaq.tlmnh.common.life.fall;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.life.MaidSurvivalInventory;
import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider;
import com.tianluoqaq.tlmnh.common.life.fall.spi.FallRescueBoatCompatProvider;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

/**
 * 女仆高处坠落自主落地水与落地船自救控制器：
 * 主世界等允许水体维度优先放落地水并在着陆稳定后收回水桶；
 * 下界、外星真空或无水桶时切换为贴地放船（原版船与 Et Futurum 全木种新船），免伤着陆后无损收船入包
 */
public final class MaidFallRescueController {

    private boolean waterLeaseActive;
    private int waterDim;
    private int waterX;
    private int waterY;
    private int waterZ;
    private int waterTicksRemaining;

    private boolean boatLeaseActive;
    private int boatTicksRemaining;
    private ItemStack savedBoatItem;
    private Entity activeRescueBoat;
    private UUID boatId;
    private int boatDimension;

    public void tick(EntityMaid maid, boolean hasActiveFlight, SpecialArmorCompatProvider armorCompat,
        SpaceSurvivalCompatProvider spaceCompat, FallRescueBoatCompatProvider boatCompat) {
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || maid.getCombatHitReceipt()
                .blocksTransfer())
            return;
        FallRescueBoatCompatProvider activeBoatCompat = boatCompat != null ? boatCompat
            : FallRescueBoatCompatProvider.NOOP;

        if (waterLeaseActive) {
            tickWaterRecovery(maid);
        }
        if (boatLeaseActive) {
            tickBoatRecovery(maid, activeBoatCompat);
        }

        if (waterLeaseActive || boatLeaseActive || hasActiveFlight) return;
        if (maid.onGround || maid.isInWater() || maid.handleLavaMovement() || maid.isOnLadder() || maid.isRiding()) {
            return;
        }
        if (maid.fallDistance < FallRescueTuning.MIN_TRIGGER_FALL_DISTANCE
            || maid.motionY > FallRescueTuning.TRIGGER_MAX_VY) {
            return;
        }

        ItemStack boots = maid.getEquipmentInSlot(FallRescueTuning.BOOTS_EQUIPMENT_SLOT);
        if (armorCompat != null && armorCompat.canBootsFullyAbsorbFall(maid, boots, maid.fallDistance)) {
            return;
        }

        World world = maid.worldObj;
        int bx = MathHelper.floor_double(maid.posX);
        int bz = MathHelper.floor_double(maid.posZ);
        int footY = MathHelper.floor_double(maid.boundingBox.minY);
        double surfaceY = findSolidSurfaceBelow(world, bx, footY, bz);
        if (surfaceY < 0.0D) return;
        int groundY = MathHelper.floor_double(surfaceY - FallRescueTuning.SURFACE_EPSILON);

        int placeY = groundY + 1;
        double heightAboveSurface = maid.boundingBox.minY - surfaceY;
        double dynamicWindow = Math.max(
            FallRescueTuning.MIN_WATER_WINDOW_BLOCKS,
            -maid.motionY * FallRescueTuning.VY_WINDOW_MULTIPLIER + FallRescueTuning.VY_WINDOW_BASE);
        if (heightAboveSurface > dynamicWindow) return;

        Block aboveBlock = world.getBlock(bx, placeY, bz);
        if (aboveBlock.getMaterial() == Material.water) {
            maid.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;
            return;
        }

        if (canPlaceRescueWater(world, bx, placeY, bz, spaceCompat) && tryDeployWaterBucket(maid, bx, placeY, bz)) {
            return;
        }

        if (heightAboveSurface
            <= Math.max(FallRescueTuning.MAX_BOAT_WINDOW_BLOCKS, -maid.motionY + FallRescueTuning.BOAT_VY_PADDING)) {
            tryDeployRescueBoat(maid, surfaceY, activeBoatCompat);
        }
    }

    private boolean tryDeployWaterBucket(EntityMaid maid, int x, int placeY, int z) {
        World world = maid.worldObj;
        ItemStack source = MaidSurvivalInventory
            .extract(maid, stack -> isStackItem(stack, Items.water_bucket), FallRescueTuning.SINGLE_STACK, true);
        if (source == null || !mayEdit(maid, x, placeY, z, source)) return false;
        var snapshot = BlockSnapshot.getBlockSnapshot(world, x, placeY, z);
        if (!world.setBlock(
            x,
            placeY,
            z,
            Blocks.flowing_water,
            FallRescueTuning.ZERO_META,
            FallRescueTuning.BLOCK_UPDATE_FLAGS)) return false;
        EntityPlayer owner = (EntityPlayer) maid.getOwner();
        if (ForgeEventFactory.onPlayerBlockPlace(owner, snapshot, ForgeDirection.UP)
            .isCanceled() || world.getBlock(x, placeY, z) != Blocks.flowing_water) {
            if (world.getBlock(x, placeY, z) == Blocks.flowing_water) snapshot.restore(true, false);
            return false;
        }
        ItemStack consumed = MaidSurvivalInventory
            .extract(maid, stack -> isStackItem(stack, Items.water_bucket), FallRescueTuning.SINGLE_STACK, false);
        if (consumed == null) {
            if (world.getBlock(x, placeY, z) == Blocks.flowing_water) snapshot.restore(true, false);
            return false;
        }
        returnToInventory(maid, new ItemStack(Items.bucket));
        maid.swingItem();
        maid.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;

        waterLeaseActive = true;
        waterDim = maid.dimension;
        waterX = x;
        waterY = placeY;
        waterZ = z;
        waterTicksRemaining = FallRescueTuning.WATER_PICKUP_LEASE_TICKS;
        return true;
    }

    private void tickWaterRecovery(EntityMaid maid) {
        waterTicksRemaining--;
        World world = maid.worldObj;
        if (maid.dimension != waterDim) {
            waterLeaseActive = false;
            return;
        }
        if (maid.isInWater()) {
            maid.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;
        }
        int elapsed = FallRescueTuning.WATER_PICKUP_LEASE_TICKS - waterTicksRemaining;
        boolean settled = elapsed >= FallRescueTuning.WATER_MIN_SETTLE_TICKS && (maid.onGround || maid.isInWater());
        if (!settled && waterTicksRemaining > 0) {
            return;
        }

        Block block = world.getBlock(waterX, waterY, waterZ);
        int meta = world.getBlockMetadata(waterX, waterY, waterZ);
        if ((block == Blocks.flowing_water || block == Blocks.water) && meta == 0) {
            var bucket = MaidSurvivalInventory
                .extract(maid, stack -> isStackItem(stack, Items.bucket), FallRescueTuning.SINGLE_STACK, true);
            if (bucket != null && mayEdit(maid, waterX, waterY, waterZ, bucket)
                && world.setBlockToAir(waterX, waterY, waterZ)) {
                var consumed = MaidSurvivalInventory
                    .extract(maid, stack -> isStackItem(stack, Items.bucket), FallRescueTuning.SINGLE_STACK, false);
                if (consumed != null) returnToInventory(maid, new ItemStack(Items.water_bucket));
                else world.setBlock(waterX, waterY, waterZ, block, meta, FallRescueTuning.BLOCK_UPDATE_FLAGS);
                maid.swingItem();
            }
        }
        waterLeaseActive = false;
    }

    private boolean tryDeployRescueBoat(EntityMaid maid, double surfaceY, FallRescueBoatCompatProvider boatCompat) {
        var available = MaidSurvivalInventory
            .extract(maid, stack -> isBoatStack(stack, boatCompat), FallRescueTuning.SINGLE_STACK, true);
        if (available == null || !mayEdit(
            maid,
            MathHelper.floor_double(maid.posX),
            MathHelper.floor_double(surfaceY),
            MathHelper.floor_double(maid.posZ),
            available)) return false;
        ItemStack singleBoat = MaidSurvivalInventory
            .extract(maid, stack -> isBoatStack(stack, boatCompat), FallRescueTuning.SINGLE_STACK, false);
        if (singleBoat == null) return false;
        double spawnX = maid.posX;
        double spawnY = surfaceY + FallRescueTuning.BOAT_SPAWN_ABOVE_TOP_OFFSET;
        double spawnZ = maid.posZ;

        Entity spawned;
        if (singleBoat.getItem() == Items.boat && !boatCompat.handlesVanillaBoatReplacement()) {
            EntityBoat vanillaBoat = new EntityBoat(maid.worldObj, spawnX, spawnY, spawnZ);
            vanillaBoat.rotationYaw = maid.rotationYaw;
            vanillaBoat.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;
            if (!maid.worldObj.spawnEntityInWorld(vanillaBoat)) {
                returnToInventory(maid, singleBoat);
                return false;
            }
            maid.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;
            maid.mountEntity(vanillaBoat);
            spawned = vanillaBoat;
        } else {
            spawned = boatCompat
                .spawnAndMountCustomBoat(maid.worldObj, maid, singleBoat, spawnX, spawnY, spawnZ, maid.rotationYaw);
            if (spawned == null) {
                returnToInventory(maid, singleBoat);
                return false;
            }
        }

        boatId = spawned.getUniqueID();
        boatDimension = maid.dimension;
        maid.swingItem();
        maid.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;

        boatLeaseActive = true;
        boatTicksRemaining = FallRescueTuning.BOAT_DISMOUNT_SETTLE_TICKS;
        savedBoatItem = singleBoat;
        activeRescueBoat = spawned;
        return true;
    }

    private void tickBoatRecovery(EntityMaid maid, FallRescueBoatCompatProvider boatCompat) {
        maid.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;
        if (activeRescueBoat != null) {
            activeRescueBoat.fallDistance = FallRescueTuning.ZERO_FALL_DISTANCE;
        }
        boatTicksRemaining--;
        if (boatTicksRemaining > 0) return;

        Entity boat = activeRescueBoat;
        if (boat == null && boatId != null && maid.dimension == boatDimension) {
            for (Object value : maid.worldObj.loadedEntityList) {
                if (value instanceof Entity entity && boatId.equals(entity.getUniqueID())) {
                    boat = entity;
                    break;
                }
            }
        }
        boolean discarded = false;
        if (boat != null && !boat.isDead
            && boat.worldObj == maid.worldObj
            && boatId != null
            && boatId.equals(boat.getUniqueID())) {
            if (boat instanceof EntityBoat) {
                if (boat.riddenByEntity == null || boat.riddenByEntity == maid) {
                    if (maid.ridingEntity == boat) maid.mountEntity(null);
                    boat.setDead();
                    discarded = boat.isDead;
                }
            } else discarded = boatCompat.dismountAndDiscardCustomBoat(maid, boat) && boat.isDead;
        }
        if (discarded && savedBoatItem != null && savedBoatItem.stackSize > 0)
            returnToInventory(maid, savedBoatItem.copy());
        boatLeaseActive = false;
        savedBoatItem = null;
        activeRescueBoat = null;
        boatId = null;
    }

    public void finishBeforeTransfer(EntityMaid maid, FallRescueBoatCompatProvider provider) {
        if (!boatLeaseActive || maid.worldObj.isRemote
            || maid.getCombatHitReceipt()
                .blocksTransfer())
            return;
        boatTicksRemaining = FallRescueTuning.ZERO_META;
        tickBoatRecovery(maid, provider);
    }

    public void readFromNBT(NBTTagCompound root) {
        waterLeaseActive = false;
        boatLeaseActive = false;
        savedBoatItem = null;
        activeRescueBoat = null;
        boatId = null;
        if (root == null || !root.hasKey(FallRescueNbtKeys.ROOT_TAG, FallRescueTuning.NBT_TAG_COMPOUND)) return;
        NBTTagCompound sub = root.getCompoundTag(FallRescueNbtKeys.ROOT_TAG);
        if (sub.getBoolean(FallRescueNbtKeys.WATER_ACTIVE)) {
            waterLeaseActive = true;
            waterDim = sub.getInteger(FallRescueNbtKeys.WATER_DIM);
            waterX = sub.getInteger(FallRescueNbtKeys.WATER_X);
            waterY = sub.getInteger(FallRescueNbtKeys.WATER_Y);
            waterZ = sub.getInteger(FallRescueNbtKeys.WATER_Z);
            waterTicksRemaining = Math
                .max(FallRescueTuning.SINGLE_STACK, sub.getInteger(FallRescueNbtKeys.WATER_TICKS));
        }
        if (sub.getBoolean(FallRescueNbtKeys.BOAT_ACTIVE)
            && sub.hasKey(FallRescueNbtKeys.BOAT_ITEM, FallRescueTuning.NBT_TAG_COMPOUND)) {
            savedBoatItem = ProtectedItemCodec.decode(sub.getCompoundTag(FallRescueNbtKeys.BOAT_ITEM));
            if (savedBoatItem != null && sub.hasKey(FallRescueNbtKeys.BOAT_UUID_MOST, FallRescueTuning.NBT_TAG_LONG)
                && sub.hasKey(FallRescueNbtKeys.BOAT_UUID_LEAST, FallRescueTuning.NBT_TAG_LONG)) {
                boatId = new UUID(
                    sub.getLong(FallRescueNbtKeys.BOAT_UUID_MOST),
                    sub.getLong(FallRescueNbtKeys.BOAT_UUID_LEAST));
                boatDimension = sub.getInteger(FallRescueNbtKeys.BOAT_DIM);
                boatLeaseActive = true;
                boatTicksRemaining = Math
                    .max(FallRescueTuning.SINGLE_STACK, sub.getInteger(FallRescueNbtKeys.BOAT_TICKS));
            }
        }
    }

    public void writeToNBT(NBTTagCompound root) {
        if (root == null || (!waterLeaseActive && !boatLeaseActive)) return;
        NBTTagCompound sub = new NBTTagCompound();
        if (waterLeaseActive) {
            sub.setBoolean(FallRescueNbtKeys.WATER_ACTIVE, true);
            sub.setInteger(FallRescueNbtKeys.WATER_DIM, waterDim);
            sub.setInteger(FallRescueNbtKeys.WATER_X, waterX);
            sub.setInteger(FallRescueNbtKeys.WATER_Y, waterY);
            sub.setInteger(FallRescueNbtKeys.WATER_Z, waterZ);
            sub.setInteger(FallRescueNbtKeys.WATER_TICKS, waterTicksRemaining);
        }
        if (boatLeaseActive && savedBoatItem != null) {
            sub.setLong(FallRescueNbtKeys.BOAT_UUID_MOST, boatId.getMostSignificantBits());
            sub.setLong(FallRescueNbtKeys.BOAT_UUID_LEAST, boatId.getLeastSignificantBits());
            sub.setInteger(FallRescueNbtKeys.BOAT_DIM, boatDimension);
            sub.setBoolean(FallRescueNbtKeys.BOAT_ACTIVE, true);
            sub.setInteger(FallRescueNbtKeys.BOAT_TICKS, boatTicksRemaining);
            sub.setTag(FallRescueNbtKeys.BOAT_ITEM, ProtectedItemCodec.encode(savedBoatItem));
        }
        root.setTag(FallRescueNbtKeys.ROOT_TAG, sub);
    }

    private static void returnToInventory(EntityMaid maid, ItemStack stack) {
        var remainder = maid.getMaidInventory()
            .insertAvailable(stack, false);
        if (remainder != null) maid.entityDropItem(remainder, FallRescueTuning.DROP_HEIGHT_OFFSET);
    }

    private static boolean mayEdit(EntityMaid maid, int x, int y, int z, ItemStack item) {
        return maid.getOwner() instanceof EntityPlayer owner && owner.worldObj == maid.worldObj
            && maid.worldObj.blockExists(x, y, z)
            && maid.worldObj.canMineBlock(owner, x, y, z)
            && owner.canPlayerEdit(x, y, z, FallRescueTuning.PLACE_SIDE_UP, item);
    }

    private static boolean canPlaceRescueWater(World world, int x, int placeY, int z,
        SpaceSurvivalCompatProvider spaceCompat) {
        if (world.provider != null && world.provider.isHellWorld) return false;
        if (spaceCompat != null && spaceCompat.isAvailable()) {
            SpaceSurvivalCompatProvider.AtmosphereSample sample = spaceCompat.sampleAtmosphere(world);
            if (sample.galacticraftDimension && (!sample.breathableAtmosphere || sample.noAtmosphericCombustion)) {
                return false;
            }
        }
        if (world.getTileEntity(x, placeY, z) != null) return false;
        Block existing = world.getBlock(x, placeY, z);
        return world.isAirBlock(x, placeY, z) || existing.getMaterial() == Material.vine
            || existing.getMaterial() == Material.plants
            || existing.getMaterial() == Material.snow;
    }

    private static double findSolidSurfaceBelow(World world, int x, int startY, int z) {
        int minY = Math.max(FallRescueTuning.MIN_WORLD_Y, startY - FallRescueTuning.MAX_SCAN_BLOCKS_DOWN);
        for (int y = startY; y >= minY; y--) {
            Block block = world.getBlock(x, y, z);
            if (block.getMaterial()
                .blocksMovement()) {
                var box = block.getCollisionBoundingBoxFromPool(world, x, y, z);
                if (box != null) {
                    return box.maxY;
                }
            }
        }
        return FallRescueTuning.NOT_FOUND_SLOT;
    }

    private static boolean isBoatStack(ItemStack stack, FallRescueBoatCompatProvider boatCompat) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        return stack.getItem() == Items.boat || boatCompat.isCustomBoatItem(stack);
    }

    private static boolean isStackItem(ItemStack stack, net.minecraft.item.Item target) {
        return stack != null && stack.stackSize > 0 && stack.getItem() == target;
    }
}
