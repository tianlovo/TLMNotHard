package com.tianluoqaq.tlmnh.client.render;

import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.Entity;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
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

    BuiltinMaidModel(BuiltinModelCatalog.Choice choice, AbstractBedrockEntityModel<EntityMaid> bedrock,
        AnimatedGeoModel gecko) {
        this.choice = choice;
        this.bedrock = bedrock;
        this.gecko = gecko;
        if ((bedrock == null) == (gecko == null)) throw new IllegalArgumentException("Expected one model geometry");
        bedrockPose = bedrock == null ? null : new BedrockPose(bedrock);
        bedrockAnimations = bedrock == null ? null : new BuiltinBedrockAnimations(choice);
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

    public void setupPose(MaidAnimationState state, float limbSwing, float limbSwingAmount, float ageInTicks,
        float netHeadYaw, float headPitch) {
        if (bedrockAnimations != null)
            bedrockAnimations.apply(bedrockPose, state, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
    }

    @Override
    public void render(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch, float scale) {
        float partialTick = Math.max(0, Math.min(1, ageInTicks - entity.ticksExisted));
        if (entity instanceof EntityMaid maid) setupPose(
            MaidAnimationState.capture(maid, partialTick),
            limbSwing,
            limbSwingAmount,
            ageInTicks,
            netHeadYaw,
            headPitch);
        var consumer = TessellatorVertexConsumer.INSTANCE;
        consumer.begin();
        try {
            renderGeometry(new LegacyPoseStack(), consumer, entity.getBrightnessForRender(partialTick));
        } finally {
            consumer.end();
        }
    }

    /** 输出位于 ModelBase 坐标系的顶点，供实体本体、预览与 CPU 检查共用 */
    public void renderGeometry(LegacyPoseStack poses, LegacyVertexConsumer consumer, int light) {
        if (bedrock != null) {
            poses.pushPose();
            try {
                bedrockPose.applyTransform(poses);
                bedrock.renderToBuffer(poses, consumer, light, 0, 1, 1, 1, 1);
            } finally {
                poses.popPose();
            }
            return;
        }
        poses.pushPose();
        try {
            // ModelBase 的脚位于 Y=24/16，Gecko 位于 Y=0，且 X/Y 轴方向相反
            poses.translate(0, 1.5, 0);
            poses.scale(-1, -1, 1);
            GECKO_RENDERER.render(gecko, poses, consumer, light, 0, 1, 1, 1, 1);
        } finally {
            poses.popPose();
        }
    }
}
