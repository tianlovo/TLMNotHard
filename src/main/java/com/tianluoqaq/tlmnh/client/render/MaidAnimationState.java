package com.tianluoqaq.tlmnh.client.render;

import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 一次绘制的输入快照，动画只读取这些值，不操作世界或改变服务端状态 */
@SideOnly(Side.CLIENT)
public final class MaidAnimationState {

    public final UUID uuid;
    public boolean sleeping, sitting, passenger, begging, usingItem, usingOffhand, swingLeft, aimingBow;
    public boolean mainHandPresent, helmet, chestPlate, leggings, boots, hurt;
    public float attackAnim, health = 20, maxHealth = 20;
    public int dimension;
    public long dayTime;
    public String task = "idle";

    public MaidAnimationState(UUID uuid) {
        this.uuid = java.util.Objects.requireNonNull(uuid, "uuid");
    }

    public static MaidAnimationState capture(EntityMaid maid, float partialTick) {
        var state = new MaidAnimationState(maid.getUniqueID());
        state.sleeping = maid.hasPose(MaidPose.SLEEPING);
        state.begging = maid.hasPose(MaidPose.BEGGING);
        state.usingItem = maid.hasPose(MaidPose.USING_ITEM);
        state.usingOffhand = maid.hasPose(MaidPose.USING_OFFHAND);
        state.swingLeft = maid.hasPose(MaidPose.SWING_OFFHAND);
        state.aimingBow = maid.hasPose(MaidPose.AIMING_BOW);
        state.sitting = maid.isSitting();
        state.passenger = maid.ridingEntity != null;
        state.attackAnim = maid.getSwingProgress(partialTick);
        state.mainHandPresent = maid.getHeldItem() != null;
        state.helmet = maid.getEquipmentInSlot(4) != null;
        state.chestPlate = maid.getEquipmentInSlot(3) != null;
        state.leggings = maid.getEquipmentInSlot(2) != null;
        state.boots = maid.getEquipmentInSlot(1) != null;
        state.hurt = maid.hurtTime > 0;
        state.health = maid.getHealth();
        state.maxHealth = maid.getMaxHealth();
        state.dimension = maid.dimension;
        state.dayTime = Math.floorMod(maid.worldObj.getWorldTime(), 24000L);
        var id = maid.getTask()
            .getId();
        state.task = id.substring(id.indexOf(':') + 1);
        return state;
    }
}
