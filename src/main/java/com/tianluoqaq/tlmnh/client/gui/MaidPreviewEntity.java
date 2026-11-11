package com.tianluoqaq.tlmnh.client.gui;

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
        if (!world.isRemote) throw new IllegalArgumentException("Client preview only");
        setModelId(model);
        onGround = true;
        // 原版详情页明确清零头角，未注册预览不会经自然tick消除旧版构造器的随机朝向
        rotationYawHead = prevRotationYawHead = 0;
    }

    @Override
    public int getBrightnessForRender(float partialTick) {
        return 0xF000F0;
    }

    @Override
    public float getBrightness(float partialTick) {
        return 1;
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
        if (!DevelopmentFlags.isDevCommandsEnabled()) throw new IllegalStateException("Development preview required");
        var action = PreviewAction.valueOf(name.toUpperCase(Locale.ROOT));
        previewPoses.clear();
        setSitting(false);
        mountEntity(null);
        for (int slot = 0; slot < 5; slot++) setCurrentItemOrArmor(slot, null);
        setOffhandItem(null);
        rotationYawHead = prevRotationYawHead = rotationPitch = prevRotationPitch = 0;
        rotationYaw = prevRotationYaw = renderYawOffset = prevRenderYawOffset = 0;
        swingProgress = prevSwingProgress = 0;
        swingProgressInt = 0;
        isSwingInProgress = false;
        hurtTime = maxHurtTime = 0;
        developmentAction = action;
        actionTicks = 0;
        switch (action) {
            case LOOK_LEFT, LOOK_RIGHT -> {
                rotationYawHead = prevRotationYawHead = action == PreviewAction.LOOK_LEFT ? -35 : 35;
                rotationPitch = prevRotationPitch = 10;
            }
            case ATTACK -> setCurrentItemOrArmor(0, new ItemStack(Items.diamond_sword));
            case AIM -> {
                setCurrentItemOrArmor(0, new ItemStack(Items.bow));
                setPose(MaidPose.USING_ITEM, true);
                setPose(MaidPose.AIMING_BOW, true);
            }
            case EAT -> {
                setCurrentItemOrArmor(0, new ItemStack(Items.bread));
                setPose(MaidPose.USING_ITEM, true);
            }
            case SLEEP -> setPose(MaidPose.SLEEPING, true);
            case HURT -> hurtTime = maxHurtTime = 10;
            default -> {}
        }
    }

    public void advanceDevelopmentAction() {
        if (developmentAction == null) return;
        actionTicks++;
        if (developmentAction == PreviewAction.ATTACK) {
            prevSwingProgress = swingProgress;
            if (actionTicks % 12 == 1) swingItem();
            updateArmSwingProgress();
        }
        if (developmentAction == PreviewAction.HURT) hurtTime = 6;
    }

    public String getDevelopmentAction() {
        return developmentAction == null ? "none"
            : developmentAction.name()
                .toLowerCase(Locale.ROOT);
    }

    public int getActionTicks() {
        return actionTicks;
    }
}
