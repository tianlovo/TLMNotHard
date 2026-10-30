package com.tianluoqaq.tlmnh.client.render.molang;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.MathHelper;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 从 RC2 的实际实体读取一帧，动画计时由调用方的独立时间轴传入 */
@SideOnly(Side.CLIENT)
public final class MaidMolangFrames {

    private MaidMolangFrames() {}

    public static MolangQueryFrame capture(EntityMaid maid, float partialTick, double seekTicks, float netHeadYaw,
        float headPitch) {
        var frame = new MolangQueryFrame();
        frame.partialTick = Math.max(0, Math.min(1, partialTick));
        frame.lifetime = seekTicks / 20.0;
        frame.netHeadYaw = netHeadYaw;
        frame.headPitch = headPitch;
        frame.bodyYaw = MathHelper.wrapDegrees(
            maid.prevRenderYawOffset + (maid.renderYawOffset - maid.prevRenderYawOffset) * frame.partialTick);
        frame.health = maid.getHealth();
        frame.maxHealth = maid.getMaxHealth();
        frame.groundSpeed = 20 * (float) Math.sqrt((float) (maid.motionX * maid.motionX + maid.motionZ * maid.motionZ));
        frame.verticalSpeed = 20 * (float) (maid.posY - maid.prevPosY);
        frame.yawSpeed = 20 * (maid.rotationYaw - maid.prevRotationYaw);
        frame.inWater = maid.isInWater();
        frame.inWaterOrRain = maid.isWet();
        frame.jumping = maid.ridingEntity == null && !maid.onGround && !frame.inWater;
        frame.sneaking = maid.onGround && maid.isSneaking();
        frame.previousX = maid.prevPosX;
        frame.previousY = maid.prevPosY;
        frame.previousZ = maid.prevPosZ;
        frame.x = maid.posX;
        frame.y = maid.posY;
        frame.z = maid.posZ;
        equipment(frame, Slot.MAINHAND, maid.getHeldItem());
        equipment(frame, Slot.OFFHAND, maid.getOffhandItem());
        equipment(frame, Slot.HEAD, maid.getEquipmentInSlot(4));
        equipment(frame, Slot.CHEST, maid.getEquipmentInSlot(3));
        equipment(frame, Slot.LEGS, maid.getEquipmentInSlot(2));
        equipment(frame, Slot.FEET, maid.getEquipmentInSlot(1));
        return frame;
    }

    private static void equipment(MolangQueryFrame frame, Slot slot, ItemStack stack) {
        if (stack != null && stack.stackSize > 0) {
            String id = Item.itemRegistry.getNameForObject(stack.getItem());
            if (id != null) frame.setEquipment(slot, id, stack.getMaxDamage(), stack.getItemDamage());
        }
    }
}
