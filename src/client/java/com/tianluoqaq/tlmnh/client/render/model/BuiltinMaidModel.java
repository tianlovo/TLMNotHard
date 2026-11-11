package com.tianluoqaq.tlmnh.client.render.model;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.Entity;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.client.render.animation.BedrockPose;
import com.tianluoqaq.tlmnh.client.render.animation.BuiltinBedrockAnimations;
import com.tianluoqaq.tlmnh.client.render.animation.BuiltinGeckoPlayer;
import com.tianluoqaq.tlmnh.client.render.animation.MaidAnimationState;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.buffer.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangFrames;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 将两种几何坐标接入旧版 ModelBase，每个实例持有自己的可变骨骼姿态 */
@SideOnly(Side.CLIENT)
public final class BuiltinMaidModel extends ModelBase {

    private static final IGeoRenderer<EntityMaid> GECKO_RENDERER = new IGeoRenderer<>() {};
    private final BuiltinModelCatalog.Choice choice;
    private final AbstractBedrockEntityModel<EntityMaid> bedrock;
    private final AnimatedGeoModel gecko;
    private final BedrockPose bedrockPose;
    private final BuiltinBedrockAnimations bedrockAnimations;
    private final BuiltinGeckoPlayer geckoPlayer;
    private String posedActor;
    private int posedTick;
    private float posedPartialTick;
    private long poseSerial;
    private boolean completedBodyPose;

    public BuiltinMaidModel(BuiltinModelCatalog.Choice choice, AbstractBedrockEntityModel<EntityMaid> bedrock,
        AnimatedGeoModel gecko, AnimationFile animations) {
        this.choice = choice;
        this.bedrock = bedrock;
        this.gecko = gecko;
        if ((bedrock == null) == (gecko == null))
            throw new IllegalArgumentException(BuiltinModelDiagnostics.MSG_EXPECTED_ONE_MODEL_GEOMETRY);
        bedrockPose = bedrock == null ? null : new BedrockPose(bedrock);
        bedrockAnimations = bedrock == null ? null : new BuiltinBedrockAnimations(choice);
        geckoPlayer = gecko == null ? null
            : new BuiltinGeckoPlayer(
                gecko,
                animations,
                ThreadLocalRandom.current()
                    .nextLong());
    }

    public BuiltinModelCatalog.Choice getChoice() {
        return choice;
    }

    public AbstractBedrockEntityModel<EntityMaid> getBedrock() {
        return bedrock;
    }

    public AnimatedGeoModel getGecko() {
        return gecko;
    }

    public BedrockPose getBedrockPose() {
        return bedrockPose;
    }

    public BuiltinGeckoPlayer getGeckoPlayer() {
        return geckoPlayer;
    }

    public void beginRenderFrame() {
        completedBodyPose = false;
    }

    public boolean hasCompletedPoseFor(EntityMaid maid) {
        return completedBodyPose && maid.ticksExisted == posedTick
            && maid.getUniqueID()
                .toString()
                .equals(posedActor);
    }

    public long getPoseSerial() {
        return poseSerial;
    }

    public float getPosedPartialTick() {
        return posedPartialTick;
    }

    public void setupPose(MaidAnimationState state, float limbSwing, float limbSwingAmount, float ageInTicks,
        float netHeadYaw, float headPitch) {
        if (bedrockAnimations != null)
            bedrockAnimations.apply(bedrockPose, state, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
    }

    @Override
    public void render(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch, float scale) {
        float partialTick = Math.max(
            BuiltinModelLayout.MAX_INITIAL_VALUE,
            Math.min(BuiltinModelLayout.MIN_SINGLE_UNIT, ageInTicks - entity.ticksExisted));
        boolean devActive = ClientDevelopmentAccess.isActive();
        var devHooks = ClientDevelopmentAccess.hooks();
        if (entity instanceof EntityMaid maid) {
            try {
                var state = MaidAnimationState.capture(maid, partialTick);
                if (geckoPlayer == null) {
                    setupPose(state, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
                    if (devActive) devHooks.headAfterBedrock(this, maid, netHeadYaw, headPitch, partialTick);
                } else {
                    boolean paused = Minecraft.getMinecraft()
                        .isGamePaused();
                    double seekTicks = geckoPlayer.advanceClock(ageInTicks, paused);
                    var frame = MaidMolangFrames.capture(maid, partialTick, seekTicks, netHeadYaw, headPitch);
                    if (devActive) devHooks.headBeforePlayer(this, maid, frame, netHeadYaw, headPitch);
                    boolean applied = geckoPlayer.apply(state, frame, limbSwing, limbSwingAmount, partialTick, paused);
                    if (devActive)
                        devHooks.headAfterPlayer(this, maid, frame, netHeadYaw, headPitch, partialTick, applied);
                }
            } catch (RuntimeException error) {
                if (devActive) devHooks.headFailed(maid, error);
                throw error;
            }
        }
        if (devActive && entity instanceof EntityMaid maid) devHooks.gpuMatrixAfterPose(this, maid, partialTick);
        var consumer = TessellatorVertexConsumer.INSTANCE;
        int light = entity.getBrightnessForRender(partialTick);
        if (devActive) devHooks.submissionBegin(this, entity, light);
        boolean completed = false;
        try {
            consumer.begin();
            try {
                renderGeometry(new LegacyPoseStack(), consumer, light);
            } finally {
                consumer.end();
            }
            completed = true;
        } finally {
            if (devActive) devHooks.submissionFinish(completed);
        }
        if (entity instanceof EntityMaid maid) {
            posedActor = maid.getUniqueID()
                .toString();
            posedTick = maid.ticksExisted;
            posedPartialTick = partialTick;
            if (poseSerial < Long.MAX_VALUE) poseSerial++;
            completedBodyPose = true;
        }
    }

    /** 输出位于 ModelBase 坐标系的顶点，供实体本体、预览与 CPU 检查共用 */
    public void renderGeometry(LegacyPoseStack poses, LegacyVertexConsumer consumer, int light) {
        if (bedrock != null) {
            poses.pushPose();
            try {
                bedrockPose.applyTransform(poses);
                bedrock.renderToBuffer(
                    poses,
                    consumer,
                    light,
                    BuiltinModelLayout.MAX_INITIAL_VALUE,
                    BuiltinModelLayout.MIN_SINGLE_UNIT,
                    BuiltinModelLayout.MIN_SINGLE_UNIT,
                    BuiltinModelLayout.MIN_SINGLE_UNIT,
                    BuiltinModelLayout.MIN_SINGLE_UNIT);
            } finally {
                poses.popPose();
            }
            return;
        }
        poses.pushPose();
        try {
            // ModelBase 的脚位于 Y=24/16，Gecko 位于 Y=0，且 X/Y 轴方向相反
            poses.translate(
                BuiltinModelLayout.MAX_INITIAL_VALUE,
                BuiltinModelLayout.MODEL_RENDER_GEOMETRY_OFFSET_Y,
                BuiltinModelLayout.MAX_INITIAL_VALUE);
            poses.scale(
                BuiltinModelLayout.UNSET_OR_NOT_FOUND,
                BuiltinModelLayout.UNSET_OR_NOT_FOUND,
                BuiltinModelLayout.MIN_SINGLE_UNIT);
            GECKO_RENDERER.render(
                gecko,
                poses,
                consumer,
                light,
                BuiltinModelLayout.MAX_INITIAL_VALUE,
                BuiltinModelLayout.MIN_SINGLE_UNIT,
                BuiltinModelLayout.MIN_SINGLE_UNIT,
                BuiltinModelLayout.MIN_SINGLE_UNIT,
                BuiltinModelLayout.MIN_SINGLE_UNIT);
        } finally {
            poses.popPose();
        }
    }
}
