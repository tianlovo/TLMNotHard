/*
 * Includes adaptations of authors_and_credits:animation/arm.js and float.js
 * TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6, TartaricAcid
 * This file is provided under CC BY-NC-SA 4.0
 * See licenses/TouhouLittleMaid-CC-BY-NC-SA-4.0.txt and SOURCE_PROVENANCE.md
 */
package com.tianluoqaq.tlmnh.client.render.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.IAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.InnerAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.GlWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只解析内置目录的动画标识；两份原脚本按原公式执行，不加载脚本引擎 */
@SideOnly(Side.CLIENT)
public final class BuiltinBedrockAnimations {

    static {
        InnerAnimation.init();
    }

    private final List<IAnimation<MaidAnimationState>> animations = new ArrayList<>();

    public BuiltinBedrockAnimations(BuiltinModelCatalog.Choice choice) {
        if (choice.isGecko())
            throw new IllegalArgumentException(AnimationDiagnostics.MSG_EXPECTED_BEDROCK_ANIMATION_LIST);
        for (var id : choice.getAnimations()) animations.add(resolve(id));
    }

    public static IAnimation<MaidAnimationState> resolve(ResourceLocation id) {
        if (id.equals(new ResourceLocation(AnimationIds.ID_AUTHORS_AND_CREDITS_ANIMATION_ARM_JS))) {
            return BuiltinBedrockAnimations::arms;
        }
        if (id.equals(new ResourceLocation(AnimationIds.ID_AUTHORS_AND_CREDITS_ANIMATION_FLOAT_JS))) {
            return BuiltinBedrockAnimations::floating;
        }
        var animation = InnerAnimation.get(id);
        if (animation == null)
            throw new IllegalArgumentException(AnimationDiagnostics.MSG_UNSUPPORTED_BUILTIN_ANIMATION + id);
        return animation;
    }

    public void apply(BedrockPose pose, MaidAnimationState state, float limbSwing, float limbSwingAmount,
        float ageInTicks, float netHeadYaw, float headPitch) {
        pose.animate(parts -> {
            for (var animation : animations) animation.setRotationAngles(
                limbSwing,
                limbSwingAmount,
                ageInTicks,
                netHeadYaw,
                headPitch,
                AnimationLayout.BEDROCK_ANIMATIONS_DOMAIN_FACTOR,
                state,
                parts);
        });
    }

    /** 固定资源 authors_and_credits:animation/arm.js，来源摘要由资产清单保留 */
    private static void arms(float limbSwing, float limbSwingAmount, float age, float yaw, float pitch, float scale,
        MaidAnimationState maid, Map<String, ModelRendererWrapper> parts) {
        double f1 = AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F1_OFFSET - Math.pow(
            AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F1_OFFSET - maid.attackAnim,
            AnimationLayout.BEDROCK_ANIMATIONS_POW_SECOND_PARAM);
        double f2 = Math.sin(f1 * Math.PI);
        double f3 = Math.sin(maid.attackAnim * Math.PI) * AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F3_OFFSET
            * AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F3_OFFSET_2;
        for (boolean left : new boolean[] { true, false }) {
            var arm = parts.get(left ? AnimationIds.ID_ARMLEFT : AnimationIds.ID_ARMRIGHT);
            if (arm == null) continue;
            arm.setRotateAngleX(arm.getInitRotateAngleX());
            arm.setRotateAngleY(AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE);
            arm.setRotateAngleZ(
                (float) (left
                    ? Math.cos(age * AnimationLayout.BEDROCK_ANIMATIONS_COS_FIRST_FACTOR)
                        * AnimationLayout.BEDROCK_ANIMATIONS_COS_FIRST_FACTOR
                        - AnimationLayout.BEDROCK_ANIMATIONS_DOMAIN_FACTOR_2
                    : -Math.cos(age * AnimationLayout.BEDROCK_ANIMATIONS_COS_FIRST_FACTOR)
                        * AnimationLayout.BEDROCK_ANIMATIONS_COS_FIRST_FACTOR
                        + AnimationLayout.BEDROCK_ANIMATIONS_DOMAIN_FACTOR_2));
            if (maid.attackAnim > 0 && maid.swingLeft == left) {
                arm.setRotateAngleX(
                    (float) (arm.getRotateAngleX()
                        - (f2 * AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_X_FIRST_FACTOR + f3)));
                arm.setRotateAngleZ(
                    (float) (arm.getRotateAngleZ() + Math.sin(maid.attackAnim * Math.PI)
                        * AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_Z_FIRST_FACTOR));
            }
        }
    }

    /** 固定资源 authors_and_credits:animation/float.js */
    private static void floating(float limbSwing, float limbSwingAmount, float age, float yaw, float pitch, float scale,
        MaidAnimationState maid, Map<String, ModelRendererWrapper> parts) {
        var leg = required(parts, AnimationIds.ID_FLOATLEG);
        var left = required(parts, AnimationIds.ID_LEFT);
        var right = required(parts, AnimationIds.ID_RIGHT);
        var armLeft = required(parts, AnimationIds.ID_ARMLEFT);
        var armRight = required(parts, AnimationIds.ID_ARMRIGHT);
        leg.setRotateAngleX(
            (float) (leg.getInitRotateAngleX()
                + Math.atan(limbSwingAmount) * AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_X_FIRST_FACTOR_2));
        if (maid.sitting) {
            armLeft.setRotateAngleX(armLeft.getInitRotateAngleX() - 1);
            armLeft.setRotateAngleY(
                armLeft.getInitRotateAngleY() + AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_Y_FIRST_FACTOR);
            armRight.setRotateAngleX(armRight.getInitRotateAngleX() - AnimationLayout.CENTER_OFFSET_F);
            armRight.setRotateAngleY(
                armRight.getInitRotateAngleY() - AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_Y_FIRST_FACTOR_2);
            armRight.setRotateAngleZ(
                armRight.getInitRotateAngleZ() + AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_Y_FIRST_FACTOR_2);
            left.setRotateAngleZ(
                left.getInitRotateAngleZ() + AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_Y_FIRST_FACTOR_2);
            left.setRotateAngleX(
                left.getInitRotateAngleX() + AnimationLayout.BEDROCK_ANIMATIONS_SET_ROTATE_ANGLE_X_FIRST_FACTOR_3);
            right.setRotateAngleZ(right.getInitRotateAngleZ() - AnimationLayout.CENTER_OFFSET_F);
        } else {
            left.setRotateAngleX(left.getInitRotateAngleX());
            left.setRotateAngleZ(
                (float) (left.getInitRotateAngleZ()
                    + Math.atan(limbSwingAmount) * AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F3_OFFSET_2));
            right.setRotateAngleZ(
                (float) (right.getInitRotateAngleZ()
                    - Math.atan(limbSwingAmount) * AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F3_OFFSET_2));
        }
        GlWrapper.translate(
            AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE,
            maid.sleeping ? AnimationLayout.HALF_FACTOR_D
                : AnimationLayout.BEDROCK_ANIMATIONS_RENDER_OFFSET_Y
                    * Math.sin(age * AnimationLayout.BEDROCK_ANIMATIONS_SIN_FIRST_FACTOR)
                    - AnimationLayout.BEDROCK_ANIMATIONS_RENDER_OFFSET_Y,
            AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE);
    }

    private static ModelRendererWrapper required(Map<String, ModelRendererWrapper> parts, String name) {
        var part = parts.get(name);
        if (part == null)
            throw new IllegalArgumentException(AnimationDiagnostics.MSG_MISSING_BUILTIN_ANIMATION_BONE + name);
        return part;
    }
}
