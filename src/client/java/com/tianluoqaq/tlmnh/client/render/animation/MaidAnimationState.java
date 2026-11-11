package com.tianluoqaq.tlmnh.client.render.animation;

import com.tianluoqaq.tlmnh.common.entity.MaidEntityTuning;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.gui.preview.MaidPreviewSeat;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidSnowballAnimation;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 一次绘制的输入快照，动画只读取这些值，不操作世界或改变服务端状态 */
@SideOnly(Side.CLIENT)
public final class MaidAnimationState {

    public final UUID uuid;
    public boolean sleeping, sitting, passenger, begging, usingItem, usingOffhand, swingLeft, aimingBow;
    public boolean mainHandPresent, helmet, chestPlate, leggings, boots, hurt;
    public boolean onGround = true, sprinting, climbing, swimming, dead, swinging;
    public String vehicleId = "", passengerId = "";
    public int swingTick;
    public boolean pickingUpSnowball, snowballAnimationExpired;
    public double verticalSpeed;
    public float attackAnim, health = AnimationLayout.GECKO_PLAYER_LIFETIME, maxHealth = AnimationLayout.GECKO_PLAYER_LIFETIME;
    public int dimension;
    public long dayTime;
    public String task = AnimationIds.ID_IDLE;

    public MaidAnimationState(UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, AnimationIds.ID_UUID);
    }

    public static MaidAnimationState capture(EntityMaid maid, float partialTick) {
        var state = new MaidAnimationState(maid.getUniqueID());
        state.sleeping = maid.hasPose(MaidPose.SLEEPING);
        state.begging = maid.hasPose(MaidPose.BEGGING);
        var snowball = maid.getSnowballAnimation();
        if (!state.begging && snowball.pickingUp()
            && System.currentTimeMillis() - snowball.startedAt() > MaidEntityTuning.DURATION_MILLIS) {
            snowball.clear();
            state.snowballAnimationExpired = true;
        }
        state.pickingUpSnowball = snowball.pickingUp();
        state.usingItem = maid.hasPose(MaidPose.USING_ITEM);
        state.usingOffhand = maid.hasPose(MaidPose.USING_OFFHAND);
        state.swingLeft = maid.hasPose(MaidPose.SWING_OFFHAND);
        state.aimingBow = maid.hasPose(MaidPose.AIMING_BOW);
        state.sitting = maid.isSitting();
        state.passenger = maid.ridingEntity != null;
        state.attackAnim = maid.getSwingProgress(partialTick);
        state.mainHandPresent = maid.getHeldItem() != null;
        state.helmet = maid.getEquipmentInSlot(AnimationLayout.BEDROCK_ANIMATIONS_POW_SECOND_PARAM) != null;
        state.chestPlate = maid.getEquipmentInSlot(AnimationLayout.ANIMATION_STATE_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM) != null;
        state.leggings = maid.getEquipmentInSlot(AnimationLayout.GECKO_PLAYER_ADD_SECOND_PARAM) != null;
        state.boots = maid.getEquipmentInSlot(AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F1_OFFSET) != null;
        state.hurt = maid.hurtTime > 0;
        state.onGround = maid.onGround;
        state.sprinting = maid.isSprinting();
        state.climbing = maid.isOnLadder();
        state.swimming = maid.hasPose(MaidPose.SWIMMING);
        state.dead = maid.getHealth() <= 0 || maid.isDead;
        state.swinging = maid.isSwingInProgress;
        state.swingTick = maid.swingProgressInt;
        state.verticalSpeed = AnimationLayout.GECKO_PLAYER_LIFETIME * (maid.posY - maid.prevPosY);
        state.vehicleId = animationEntityId(maid.ridingEntity);
        state.passengerId = animationEntityId(maid.riddenByEntity);
        state.health = maid.getHealth();
        state.maxHealth = maid.getMaxHealth();
        state.dimension = maid.dimension;
        state.dayTime = Math.floorMod(maid.worldObj.getWorldTime(), AnimationLayout.ANIMATION_STATE_FLOOR_MOD_SECOND_LIMIT);
        var animationTask = maid.getTask();
        if (maid.isUnifiedCombat() && animationTask == MaidTask.ATTACK
            && maid.getHeldItem() != null
            && maid.getHeldItem()
                .getItem() == Items.bow)
            animationTask = MaidTask.RANGED_ATTACK;
        var id = animationTask.getId();
        state.task = id.substring(id.indexOf(':') + 1);
        return state;
    }

    private static String animationEntityId(Entity entity) {
        if (entity instanceof MaidPreviewSeat) return AnimationIds.ID_TOUHOU_LITTLE_MAID_CHAIR;
        if (entity instanceof EntityPlayer) return AnimationIds.ID_MINECRAFT_PLAYER;
        if (entity instanceof EntityBoat) return AnimationIds.ID_MINECRAFT_BOAT;
        if (entity instanceof EntityMinecart) return AnimationIds.ID_MINECRAFT_MINECART;
        if (entity instanceof EntityHorse) return AnimationIds.ID_MINECRAFT_HORSE;
        if (entity instanceof EntityMaid) return AnimationIds.ID_TOUHOU_LITTLE_MAID_MAID;
        return "";
    }
}
