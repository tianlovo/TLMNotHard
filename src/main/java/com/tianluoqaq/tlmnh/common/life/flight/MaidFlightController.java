package com.tianluoqaq.tlmnh.common.life.flight;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleInventory;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;
import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider;
import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider.FlightCapability;

/**
 * 女仆四类飞行与喷气背包三维运动控制器：
 * 支持穿戴于胸甲槽或饰品槽，提供高空防摔悬停与三维跟随飞行主人能力；全程严格零电量、零燃料消耗
 */
public final class MaidFlightController {

    private FlightCapability activeCapability = FlightCapability.NONE;
    private boolean activelyHoveringOrFlying;
    private boolean steeringOwnerFollow;

    public FlightCapability getActiveCapability() {
        return activeCapability;
    }

    public boolean hasFlightCapability() {
        return activeCapability != FlightCapability.NONE;
    }

    public boolean isActivelyHoveringOrFlying() {
        return activelyHoveringOrFlying;
    }

    public boolean isSteeringOwnerFollow() {
        return steeringOwnerFollow;
    }

    public void refreshCapability(EntityMaid maid, FlightEquipmentCompatProvider provider) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return;
        activeCapability = resolveCapability(maid, provider != null ? provider : FlightEquipmentCompatProvider.NOOP);
        if (activeCapability == FlightCapability.NONE || maid.isSitting()
            || maid.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.SLEEPING)
            || maid.isRiding()) {
            activelyHoveringOrFlying = false;
            steeringOwnerFollow = false;
            return;
        }
        if (!maid.onGround) {
            maid.fallDistance = FlightTuning.ZERO_FALL_DISTANCE;
        }
        steeringOwnerFollow = shouldFollowFlyingOwner(maid, maid.getOwner());
    }

    public boolean travel(EntityMaid maid, float strafe, float forward, FlightEquipmentCompatProvider provider) {
        tick(maid, provider);
        if (!activelyHoveringOrFlying && !steeringOwnerFollow) return false;
        if (!steeringOwnerFollow) maid.moveFlying(strafe, forward, FlightTuning.MANUAL_ACCELERATION);
        maid.moveEntity(maid.motionX, maid.motionY, maid.motionZ);
        maid.motionX *= FlightTuning.AIR_DRAG;
        maid.motionZ *= FlightTuning.AIR_DRAG;
        maid.fallDistance = FlightTuning.ZERO_FALL_DISTANCE;
        return true;
    }

    public FlightCapability resolveCapability(EntityMaid maid, FlightEquipmentCompatProvider provider) {
        if (maid == null || provider == null) return FlightCapability.NONE;
        boolean inWater = maid.isInWater();
        ItemStack chest = maid.getEquipmentInSlot(FlightTuning.CHEST_EQUIPMENT_SLOT);
        FlightCapability chestCap = provider.inspectChestArmor(chest, inWater);
        if (chestCap == FlightCapability.CREATIVE_FLIGHT) {
            return FlightCapability.CREATIVE_FLIGHT;
        }
        FlightCapability best = chestCap;
        MaidBaubleInventory baubles = maid.getMaidBaubleInventory();
        int unlocked = MaidBaubleLayout.unlockedSlots(
            maid.getFavorabilityManager()
                .getLevel());
        int limit = Math.min(unlocked, baubles.getSizeInventory());
        for (int i = 0; i < limit; i++) {
            ItemStack b = baubles.getStackInSlot(i);
            if (b == null || b.stackSize <= 0) continue;
            FlightCapability cap = provider.inspectBauble(b, inWater);
            if (cap == FlightCapability.CREATIVE_FLIGHT) return FlightCapability.CREATIVE_FLIGHT;
            if (cap == FlightCapability.SPECIAL_FLIGHT) best = FlightCapability.SPECIAL_FLIGHT;
        }
        return best;
    }

    public void tick(EntityMaid maid, FlightEquipmentCompatProvider provider) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return;
        activeCapability = resolveCapability(maid, provider != null ? provider : FlightEquipmentCompatProvider.NOOP);
        if (activeCapability == FlightCapability.NONE || maid.isSitting()
            || maid.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.SLEEPING)
            || maid.isRiding()) {
            activelyHoveringOrFlying = false;
            steeringOwnerFollow = false;
            return;
        }

        if (!maid.onGround) {
            maid.fallDistance = FlightTuning.ZERO_FALL_DISTANCE;
        }

        EntityLivingBase owner = maid.getOwner();
        if (shouldFollowFlyingOwner(maid, owner)) {
            steerThreeDimensionalFollow(maid, owner, activeCapability);
            steeringOwnerFollow = true;
            activelyHoveringOrFlying = !maid.onGround;
            return;
        }
        steeringOwnerFollow = false;

        if (!maid.onGround) {
            activelyHoveringOrFlying = true;
            if (activeCapability == FlightCapability.CREATIVE_FLIGHT) {
                if (maid.motionY < 0.0D) {
                    maid.motionY = FlightTuning.ZERO_VELOCITY;
                }
            } else if (maid.motionY < FlightTuning.HOVER_FALL_TRIGGER_VY) {
                maid.motionY = FlightTuning.SPECIAL_HOVER_DRIFT_VY;
            }
        } else {
            activelyHoveringOrFlying = false;
        }
    }

    public boolean shouldFollowFlyingOwner(EntityMaid maid, EntityLivingBase owner) {
        if (!hasFlightCapability() || maid.isSitting()
            || maid.getLeashed()
            || maid.isRiding()
            || maid.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.SLEEPING)) return false;
        if (owner == null || !owner.isEntityAlive() || owner.worldObj != maid.worldObj) return false;
        if (maid.getHomeArea()
            .enabled()) return false;
        if (owner instanceof EntityPlayerMP playerMp && playerMp.theItemInWorldManager.getGameType()
            .getID() == FlightTuning.SPECTATOR_GAME_TYPE_ID) {
            return false;
        }
        boolean ownerFlyingCap = owner instanceof EntityPlayer player && player.capabilities.isFlying;
        boolean ownerAirborne = !owner.onGround && hasAirClearanceBelow(owner);
        double dy = Math.abs(owner.posY - maid.posY);
        double distSq = maid.getDistanceSqToEntity(owner);
        if (ownerFlyingCap || ownerAirborne) {
            return distSq > FlightTuning.OWNER_FOLLOW_TRIGGER_DIST_SQ || dy > FlightTuning.OWNER_VERTICAL_GAP_TRIGGER;
        }
        return dy > FlightTuning.OWNER_VERTICAL_GAP_TRIGGER && distSq > FlightTuning.OWNER_FOLLOW_TRIGGER_DIST_SQ;
    }

    private static boolean hasAirClearanceBelow(EntityLivingBase entity) {
        int bx = MathHelper.floor_double(entity.posX);
        int by = MathHelper.floor_double(entity.boundingBox.minY - FlightTuning.OWNER_AIRBORNE_MIN_CLEARANCE);
        int bz = MathHelper.floor_double(entity.posZ);
        return entity.worldObj.isAirBlock(bx, by, bz);
    }

    private static void steerThreeDimensionalFollow(EntityMaid maid, EntityLivingBase owner, FlightCapability cap) {
        maid.getNavigator()
            .clearPathEntity();
        double dx = owner.posX - maid.posX;
        double dz = owner.posZ - maid.posZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double targetX = owner.posX;
        double targetZ = owner.posZ;
        if (horizDist > FlightTuning.MIN_DIRECTION_NORM) {
            targetX -= (dx / horizDist) * FlightTuning.OWNER_TARGET_STANDOFF;
            targetZ -= (dz / horizDist) * FlightTuning.OWNER_TARGET_STANDOFF;
        }
        double targetY = owner.posY;

        double vx = targetX - maid.posX;
        double vy = targetY - maid.posY;
        double vz = targetZ - maid.posZ;
        double horizToAnchor = Math.sqrt(vx * vx + vz * vz);

        if (cap == FlightCapability.CREATIVE_FLIGHT) {
            double desiredVx = horizToAnchor > FlightTuning.MIN_DIRECTION_NORM
                ? (vx / horizToAnchor) * Math.min(FlightTuning.CREATIVE_MAX_HORIZ_SPEED, horizToAnchor)
                : FlightTuning.ZERO_VELOCITY;
            double desiredVz = horizToAnchor > FlightTuning.MIN_DIRECTION_NORM
                ? (vz / horizToAnchor) * Math.min(FlightTuning.CREATIVE_MAX_HORIZ_SPEED, horizToAnchor)
                : FlightTuning.ZERO_VELOCITY;
            double desiredVy = Math
                .max(-FlightTuning.CREATIVE_MAX_VERT_SPEED, Math.min(FlightTuning.CREATIVE_MAX_VERT_SPEED, vy));
            maid.motionX += (desiredVx - maid.motionX) * FlightTuning.CREATIVE_VELOCITY_LERP;
            maid.motionY += (desiredVy - maid.motionY) * FlightTuning.CREATIVE_VELOCITY_LERP;
            maid.motionZ += (desiredVz - maid.motionZ) * FlightTuning.CREATIVE_VELOCITY_LERP;
        } else {
            if (horizToAnchor > FlightTuning.MIN_DIRECTION_NORM) {
                double speed = Math.min(FlightTuning.SPECIAL_MAX_HORIZ_SPEED, horizToAnchor);
                maid.motionX += ((vx / horizToAnchor) * speed - maid.motionX) * FlightTuning.CREATIVE_VELOCITY_LERP;
                maid.motionZ += ((vz / horizToAnchor) * speed - maid.motionZ) * FlightTuning.CREATIVE_VELOCITY_LERP;
            }
            double desiredVy = Math.max(
                -FlightTuning.SPECIAL_MAX_ASCEND_VY,
                Math.min(FlightTuning.SPECIAL_MAX_ASCEND_VY, vy * FlightTuning.CREATIVE_VELOCITY_LERP));
            maid.motionY += (desiredVy - maid.motionY) * FlightTuning.CREATIVE_VELOCITY_LERP;
        }
        maid.fallDistance = FlightTuning.ZERO_FALL_DISTANCE;
        if (dx * dx + dz * dz > FlightTuning.MIN_DIRECTION_NORM) {
            maid.rotationYaw = (float) (Math.atan2(dz, dx) * FlightTuning.YAW_RAD_TO_DEG) - FlightTuning.YAW_OFFSET_DEG;
            maid.renderYawOffset = maid.rotationYaw;
        }
    }
}
