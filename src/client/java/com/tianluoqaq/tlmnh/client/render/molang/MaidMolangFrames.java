package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.Locale;

import net.minecraft.entity.ai.attributes.BaseAttributeMap;
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
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 从 native 的实际实体读取一帧，动画计时由调用方的独立时间轴传入 */
@SideOnly(Side.CLIENT)
public final class MaidMolangFrames {

    private MaidMolangFrames() {}

    public static MolangQueryFrame capture(EntityMaid maid, float partialTick, double seekTicks, float netHeadYaw,
        float headPitch) {
        var frame = new MolangQueryFrame();
        frame.partialTick = Math
            .max(MolangRenderLayout.MAX_INITIAL_VALUE, Math.min(MolangRenderLayout.MIN_SINGLE_UNIT, partialTick));
        frame.lifetime = seekTicks / MolangRenderLayout.MOLANG_FRAMES_LIFETIME;
        // ModelBase给出实体角度；上游Gecko renderer会先wrap/clamp相对yaw，再将两轴取负
        frame.netHeadYaw = -Math.max(
            MolangRenderLayout.MOLANG_FRAMES_MAX_FIRST_FACTOR,
            Math.min(MolangRenderLayout.MOLANG_FRAMES_MIN_FIRST_FACTOR, MathHelper.wrapDegrees(netHeadYaw)));
        frame.headPitch = -headPitch;
        frame.bodyYaw = MathHelper.wrapDegrees(
            maid.prevRenderYawOffset + (maid.renderYawOffset - maid.prevRenderYawOffset) * frame.partialTick);
        frame.health = maid.getHealth();
        frame.maxHealth = maid.getMaxHealth();
        frame.groundSpeed = MolangRenderLayout.MOLANG_FRAMES_GROUND_SPEED
            * (float) Math.sqrt((float) (maid.motionX * maid.motionX + maid.motionZ * maid.motionZ));
        frame.verticalSpeed = MolangRenderLayout.MOLANG_FRAMES_GROUND_SPEED * (float) (maid.posY - maid.prevPosY);
        frame.yawSpeed = MolangRenderLayout.MOLANG_FRAMES_GROUND_SPEED * (maid.rotationYaw - maid.prevRotationYaw);
        frame.inWater = maid.isInWater();
        frame.inWaterOrRain = maid.isWet();
        frame.jumping = maid.ridingEntity == null && !maid.onGround && !frame.inWater;
        frame.sneaking = maid.onGround && maid.isSneaking();
        frame.begging = maid.hasPose(MaidPose.BEGGING);
        frame.sitting = maid.isSitting();
        frame.foodLevel = foodLevel(maid.getAttributeMap());
        double blink = (seekTicks + Math.abs(
            maid.getUniqueID()
                .getLeastSignificantBits() % MolangRenderLayout.MOLANG_FRAMES_DOMAIN_PARAM))
            % MolangRenderLayout.MOLANG_FRAMES_DOMAIN_PARAM_2;
        frame.closeEyes = maid.hasPose(MaidPose.SLEEPING) || (MolangRenderLayout.MOLANG_FRAMES_DOMAIN_PARAM_3 < blink
            && blink < MolangRenderLayout.MOLANG_FRAMES_DOMAIN_PARAM_2);
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
        equipment(
            frame,
            Slot.HEAD,
            maid.getEquipmentInSlot(MolangRenderLayout.MOLANG_FRAMES_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM));
        equipment(
            frame,
            Slot.CHEST,
            maid.getEquipmentInSlot(MolangRenderLayout.MOLANG_FRAMES_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM_2));
        equipment(
            frame,
            Slot.LEGS,
            maid.getEquipmentInSlot(MolangRenderLayout.MOLANG_FRAMES_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM_3));
        equipment(frame, Slot.FEET, maid.getEquipmentInSlot(MolangRenderLayout.MIN_SINGLE_UNIT));
        var chest = frame.equipment(Slot.CHEST);
        frame.hasElytra = chest != null && CompatServices.worldContent()
            .isElytraItem(chest.itemId);
        return frame;
    }

    /** 原YSMBinding读取MAID_HUNGER属性，默认20；持久字段MaidHunger不是该动画查询 */
    public static int foodLevel(BaseAttributeMap attributes) {
        var attribute = attributes.getAttributeInstance(MaidCombatAttributes.MAID_HUNGER);
        return attribute == null ? MolangRenderLayout.MOLANG_FRAMES_GROUND_SPEED : (int) attribute.getAttributeValue();
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
        if (MaidShieldItems.isShield(stack)) return MolangRenderIds.ID_SHIELD;
        if (item instanceof ItemSword) return MolangRenderIds.ID_SWORD;
        if (item instanceof ItemAxe) return MolangRenderIds.ID_AXE;
        if (item instanceof ItemPickaxe) return MolangRenderIds.ID_PICKAXE;
        if (item instanceof ItemSpade) return MolangRenderIds.ID_SHOVEL;
        if (item instanceof ItemHoe) return MolangRenderIds.ID_HOE;
        if (item instanceof ItemPotion && ItemPotion.isSplash(stack.getItemDamage()))
            return MolangRenderIds.ID_THROWABLE_POTION;
        return "";
    }
}
