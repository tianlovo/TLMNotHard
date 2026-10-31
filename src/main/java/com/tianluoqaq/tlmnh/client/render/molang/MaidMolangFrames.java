package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.Locale;

import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.MathHelper;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import mods.battlegear2.api.shield.IShield;

/** 从 RC2 的实际实体读取一帧，动画计时由调用方的独立时间轴传入 */
@SideOnly(Side.CLIENT)
public final class MaidMolangFrames {

    private MaidMolangFrames() {}

    public static MolangQueryFrame capture(EntityMaid maid, float partialTick, double seekTicks, float netHeadYaw,
        float headPitch) {
        var frame = new MolangQueryFrame();
        frame.partialTick = Math.max(0, Math.min(1, partialTick));
        frame.lifetime = seekTicks / 20.0;
        // ModelBase给出实体角度；上游Gecko renderer会先wrap/clamp相对yaw，再将两轴取负
        frame.netHeadYaw = -Math.max(-85F, Math.min(85F, MathHelper.wrapDegrees(netHeadYaw)));
        frame.headPitch = -headPitch;
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
        frame.begging = maid.hasPose(MaidPose.BEGGING);
        frame.sitting = maid.isSitting();
        frame.foodLevel = maid.getHunger();
        double blink = (seekTicks + Math.abs(
            maid.getUniqueID()
                .getLeastSignificantBits() % 10))
            % 90;
        frame.closeEyes = maid.hasPose(MaidPose.SLEEPING) || (85 < blink && blink < 90);
        frame.viewYaw = (float) (maid.prevRotationYaw
            + MathHelper.wrapDegrees(maid.rotationYaw - maid.prevRotationYaw) * frame.partialTick);
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
        var chest = frame.equipment(Slot.CHEST);
        frame.hasElytra = chest != null && "etfuturum:elytra".equals(chest.itemId);
        return frame;
    }

    private static void equipment(MolangQueryFrame frame, Slot slot, ItemStack stack) {
        if (stack != null && stack.stackSize > 0) {
            String id = Item.itemRegistry.getNameForObject(stack.getItem());
            if (id != null) frame.setEquipment(
                slot,
                id,
                stack.getMaxDamage(),
                stack.getItemDamage(),
                classify(stack),
                stack.getItemUseAction()
                    .name()
                    .toLowerCase(Locale.ROOT));
        }
    }

    private static String classify(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof IShield) return "shield";
        if (item instanceof ItemSword) return "sword";
        if (item instanceof ItemAxe) return "axe";
        if (item instanceof ItemPickaxe) return "pickaxe";
        if (item instanceof ItemSpade) return "shovel";
        if (item instanceof ItemHoe) return "hoe";
        if (item instanceof ItemPotion && ItemPotion.isSplash(stack.getItemDamage())) return "throwable_potion";
        return "";
    }
}
