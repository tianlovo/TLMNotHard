/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/control/MaidMoveControl.java, MIT
 * Adaptation: native move-helper lifecycle and target collision capabilities
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.control;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidMovementAngles;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType;

public final class MaidMoveControl extends EntityMoveHelper {

    private enum Operation {
        WAIT,
        MOVE_TO,
        STRAFE,
        JUMPING
    }

    private final EntityMaid maid;
    private double x, y, z, speed;
    private float forwards, right;
    private Operation operation = Operation.WAIT;

    public MaidMoveControl(EntityMaid maid) {
        super(maid);
        this.maid = maid;
    }

    @Override
    public boolean isUpdating() {
        return operation == Operation.MOVE_TO;
    }

    @Override
    public double getSpeed() {
        return speed;
    }

    @Override
    public void setMoveTo(double x, double y, double z, double speed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.speed = speed;
        if (operation != Operation.JUMPING) operation = Operation.MOVE_TO;
    }

    public void strafe(float forwards, float right) {
        operation = Operation.STRAFE;
        this.forwards = forwards;
        this.right = right;
        speed = .25;
    }

    @Override
    public void onUpdateMoveHelper() {
        // 原型低氧路线有独立 travel 执行器，不能在水面附近重新驱动旧导航目标
        if (maid.getBrainCoordinator()
            .getBreathController()
            .isSwimming()) {
            maid.setAIMoveSpeed(0);
            return;
        }
        if (maid.getAquaticController()
            .getManager()
            .wantToSwim()) {
            if (maid.isInsideOfMaterial(net.minecraft.block.material.Material.water) && maid.getNavigator()
                .noPath()) {
                maid.setAIMoveSpeed(0);
                return;
            }
            double dx = x - maid.posX, dy = y - maid.posY, dz = z - maid.posZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance == 0) {
                maid.setAIMoveSpeed(0);
                return;
            }
            turn((float) (Math.toDegrees(MaidMovementAngles.atan2(dz, dx)) - 90));
            maid.renderYawOffset = maid.rotationYaw;
            float movement = (float) (speed * maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .getAttributeValue());
            var target = maid.getAquaticController()
                .getManager()
                .getSwimTarget();
            if (target != null) maid.getLookHelper()
                .setLookPosition(target.x + .5, target.y + .5, target.z + .5, 10, 40);
            maid.setAIMoveSpeed(movement * 3);
            maid.motionX += movement * dx * .005;
            maid.motionY += movement * dy / distance * .25;
            maid.motionZ += movement * dz * .005;
        } else if (operation == Operation.MOVE_TO) {
            operation = Operation.WAIT;
            double dx = x - maid.posX, dy = y - maid.posY, dz = z - maid.posZ;
            if (dx * dx + dy * dy + dz * dz < 2.5e-7) {
                maid.setMoveForward(0);
                return;
            }
            turn((180F / (float) Math.PI) * (float) MaidMovementAngles.atan2(dz, dx) - 90);
            maid.setAIMoveSpeed(
                (float) (speed * maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue()));
            int bx = MathHelper.floor_double(maid.posX), by = MathHelper.floor_double(maid.posY),
                bz = MathHelper.floor_double(maid.posZ);
            var block = maid.worldObj.getBlock(bx, by, bz);
            block.setBlockBoundsBasedOnState(maid.worldObj, bx, by, bz);
            var box = block.getCollisionBoundingBoxFromPool(maid.worldObj, bx, by, bz);
            boolean forbidden = block instanceof BlockDoor || block instanceof BlockFence
                || block.isLadder(maid.worldObj, bx, by, bz, maid);
            if (maid.stepHeight < dy && dx * dx + dz * dz < Math.max(1, maid.width)
                || box != null && maid.posY < box.maxY && !forbidden) {
                maid.getJumpHelper()
                    .setJumping();
                operation = Operation.JUMPING;
            }
        } else if (operation == Operation.JUMPING && maid.isInWater()) operation = Operation.WAIT;
        else if (operation == Operation.STRAFE) {
            float movement = (float) speed * (float) maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .getAttributeValue();
            float scale = movement / Math.max(1, (float) Math.sqrt(forwards * forwards + right * right));
            float sin = MathHelper.sin(maid.rotationYaw * ((float) Math.PI / 180)),
                cos = MathHelper.cos(maid.rotationYaw * ((float) Math.PI / 180));
            float relativeX = forwards * scale * cos - right * scale * sin,
                relativeZ = right * scale * cos + forwards * scale * sin;
            if (maid.getNavigator() instanceof MaidPathNavigator navigator && navigator.evaluator()
                .cellType(
                    MathHelper.floor_double(maid.posX + relativeX),
                    MathHelper.floor_double(maid.posY),
                    MathHelper.floor_double(maid.posZ + relativeZ))
                != MaidPathType.WALKABLE) {
                forwards = 1;
                right = 0;
            }
            maid.setAIMoveSpeed(movement);
            maid.moveForward = forwards;
            maid.moveStrafing = right;
            operation = Operation.WAIT;
        } else if (operation == Operation.JUMPING) {
            maid.setAIMoveSpeed(
                (float) (speed * maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue()));
            if (maid.onGround) operation = Operation.WAIT;
        } else maid.setMoveForward(0);
    }

    private void turn(float target) {
        float delta = MathHelper.wrapAngleTo180_float(target - maid.rotationYaw);
        float result = maid.rotationYaw + Math.max(-90, Math.min(90, delta));
        if (result < 0) result += 360;
        else if (result > 360) result -= 360;
        maid.rotationYaw = result;
    }
}
