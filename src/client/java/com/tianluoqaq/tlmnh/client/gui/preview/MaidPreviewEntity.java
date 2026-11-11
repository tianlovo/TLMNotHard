package com.tianluoqaq.tlmnh.client.gui.preview;

import java.util.EnumSet;
import java.util.Locale;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 仅供详情页的未注册实体，预览姿态不经过真实女仆的服务端同步 */
@SideOnly(Side.CLIENT)
public final class MaidPreviewEntity extends EntityMaid {

    private final EnumSet<MaidPose> previewPoses = EnumSet.noneOf(MaidPose.class);
    private PreviewAction developmentAction;
    private int actionTicks;

    private enum PreviewAction {
        IDLE,
        LOOK_LEFT,
        LOOK_RIGHT,
        ATTACK,
        AIM,
        EAT,
        SLEEP,
        WAKE,
        HURT
    }

    public MaidPreviewEntity(World world, String model) {
        super(world);
        if (!world.isRemote) throw new IllegalArgumentException(ClientPreviewDiagnostics.MSG_CLIENT_PREVIEW_ONLY);
        setModelId(model);
        onGround = true;
        // 原版详情页明确清零头角，未注册预览不会经自然tick消除旧版构造器的随机朝向
        rotationYawHead = prevRotationYawHead = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
    }

    @Override
    public int getBrightnessForRender(float partialTick) {
        return ClientPreviewLayout.RGB_COLOR_F000F0;
    }

    @Override
    public float getBrightness(float partialTick) {
        return ClientPreviewLayout.PREVIEW_ENTITY_GET_BRIGHTNESS_UNIT_STEP;
    }

    @Override
    public boolean hasPose(MaidPose pose) {
        return previewPoses != null && previewPoses.contains(pose);
    }

    @Override
    public void setPose(MaidPose pose, boolean active) {
        if (previewPoses == null) return;
        if (active) previewPoses.add(pose);
        else previewPoses.remove(pose);
    }

    /** 受控输入只属于未登记的预览实体，各动作独立开始并沿生产动画读取字段绘制 */
    public void setDevelopmentAction(String name) {
        if (!DevelopmentFlags.isDevCommandsEnabled()) throw new IllegalStateException(ClientPreviewDiagnostics.MSG_DEVELOPMENT_PREVIEW_REQUIRED);
        var action = PreviewAction.valueOf(name.toUpperCase(Locale.ROOT));
        previewPoses.clear();
        setSitting(false);
        mountEntity(null);
        for (int slot = 0; slot < ClientPreviewLayout.MIN_SLOT_THRESHOLD; slot++) setCurrentItemOrArmor(slot, null);
        setOffhandItem(null);
        rotationYawHead = prevRotationYawHead = rotationPitch = prevRotationPitch = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
        rotationYaw = prevRotationYaw = renderYawOffset = prevRenderYawOffset = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
        swingProgress = prevSwingProgress = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
        swingProgressInt = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
        isSwingInProgress = false;
        hurtTime = maxHurtTime = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
        developmentAction = action;
        actionTicks = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD;
        switch (action) {
            case LOOK_LEFT, LOOK_RIGHT -> {
                rotationYawHead = prevRotationYawHead = action == PreviewAction.LOOK_LEFT ? ClientPreviewLayout.PREVIEW_ENTITY_SET_DEVELOPMENT_ACTION_PARAM : ClientPreviewLayout.PREVIEW_ENTITY_SET_DEVELOPMENT_ACTION_PARAM_2;
                rotationPitch = prevRotationPitch = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_PITCH;
            }
            case ATTACK -> setCurrentItemOrArmor(ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD, new ItemStack(Items.diamond_sword));
            case AIM -> {
                setCurrentItemOrArmor(ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD, new ItemStack(Items.bow));
                setPose(MaidPose.USING_ITEM, true);
                setPose(MaidPose.AIMING_BOW, true);
            }
            case EAT -> {
                setCurrentItemOrArmor(ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_YAW_HEAD, new ItemStack(Items.bread));
                setPose(MaidPose.USING_ITEM, true);
            }
            case SLEEP -> setPose(MaidPose.SLEEPING, true);
            case HURT -> hurtTime = maxHurtTime = ClientPreviewLayout.PREVIEW_ENTITY_PREV_ROTATION_PITCH;
            default -> {}
        }
    }

    public void advanceDevelopmentAction() {
        if (developmentAction == null) return;
        actionTicks++;
        if (developmentAction == PreviewAction.ATTACK) {
            prevSwingProgress = swingProgress;
            if (actionTicks % ClientPreviewLayout.PREVIEW_ENTITY_ADVANCE_DEVELOPMENT_ACTION_PARAM == ClientPreviewLayout.PREVIEW_ENTITY_GET_BRIGHTNESS_UNIT_STEP) swingItem();
            updateArmSwingProgress();
        }
        if (developmentAction == PreviewAction.HURT) hurtTime = ClientPreviewLayout.PREVIEW_ENTITY_HURT_TIME;
    }

    public String getDevelopmentAction() {
        return developmentAction == null ? ClientPreviewIds.ID_NONE
            : developmentAction.name()
                .toLowerCase(Locale.ROOT);
    }

    public int getActionTicks() {
        return actionTicks;
    }
}
