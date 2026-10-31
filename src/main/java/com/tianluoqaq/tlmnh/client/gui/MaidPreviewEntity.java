package com.tianluoqaq.tlmnh.client.gui;

import java.util.EnumSet;

import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 仅供详情页的未注册实体，预览姿态不经过真实女仆的服务端同步 */
@SideOnly(Side.CLIENT)
public final class MaidPreviewEntity extends EntityMaid {

    private final EnumSet<MaidPose> previewPoses = EnumSet.noneOf(MaidPose.class);

    public MaidPreviewEntity(World world, String model) {
        super(world);
        if (!world.isRemote) throw new IllegalArgumentException("Client preview only");
        setModelId(model);
        onGround = true;
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
}
