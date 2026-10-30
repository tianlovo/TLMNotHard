/*
 * Includes adaptations of authors_and_credits:animation/arm.js and float.js
 * TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6, TartaricAcid
 * This file is provided under CC BY-NC-SA 4.0
 * See licenses/TouhouLittleMaid-CC-BY-NC-SA-4.0.txt and SOURCE_PROVENANCE.md
 */
package com.tianluoqaq.tlmnh.client.render;

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
        if (choice.isGecko()) throw new IllegalArgumentException("Expected Bedrock animation list");
        for (var id : choice.getAnimations()) animations.add(resolve(id));
    }

    public static IAnimation<MaidAnimationState> resolve(ResourceLocation id) {
        if (id.equals(new ResourceLocation("authors_and_credits:animation/arm.js"))) {
            return BuiltinBedrockAnimations::arms;
        }
        if (id.equals(new ResourceLocation("authors_and_credits:animation/float.js"))) {
            return BuiltinBedrockAnimations::floating;
        }
        var animation = InnerAnimation.get(id);
        if (animation == null) throw new IllegalArgumentException("Unsupported builtin animation " + id);
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
                0.0625F,
                state,
                parts);
        });
    }

    /** 固定资源 authors_and_credits:animation/arm.js，来源摘要由资产清单保留 */
    private static void arms(float limbSwing, float limbSwingAmount, float age, float yaw, float pitch, float scale,
        MaidAnimationState maid, Map<String, ModelRendererWrapper> parts) {
        double f1 = 1 - Math.pow(1 - maid.attackAnim, 4);
        double f2 = Math.sin(f1 * Math.PI);
        double f3 = Math.sin(maid.attackAnim * Math.PI) * -0.7 * 0.75;
        for (boolean left : new boolean[] { true, false }) {
            var arm = parts.get(left ? "armLeft" : "armRight");
            if (arm == null) continue;
            arm.setRotateAngleX(arm.getInitRotateAngleX());
            arm.setRotateAngleY(0);
            arm.setRotateAngleZ(
                (float) (left ? Math.cos(age * 0.05) * 0.05 - 0.4 : -Math.cos(age * 0.05) * 0.05 + 0.4));
            if (maid.attackAnim > 0 && maid.swingLeft == left) {
                arm.setRotateAngleX((float) (arm.getRotateAngleX() - (f2 * 1.2 + f3)));
                arm.setRotateAngleZ((float) (arm.getRotateAngleZ() + Math.sin(maid.attackAnim * Math.PI) * -0.4));
            }
        }
    }

    /** 固定资源 authors_and_credits:animation/float.js */
    private static void floating(float limbSwing, float limbSwingAmount, float age, float yaw, float pitch, float scale,
        MaidAnimationState maid, Map<String, ModelRendererWrapper> parts) {
        var leg = required(parts, "floatLeg");
        var left = required(parts, "left");
        var right = required(parts, "right");
        var armLeft = required(parts, "armLeft");
        var armRight = required(parts, "armRight");
        leg.setRotateAngleX((float) (leg.getInitRotateAngleX() + Math.atan(limbSwingAmount) * 1.25));
        if (maid.sitting) {
            armLeft.setRotateAngleX(armLeft.getInitRotateAngleX() - 1);
            armLeft.setRotateAngleY(armLeft.getInitRotateAngleY() + 0.75F);
            armRight.setRotateAngleX(armRight.getInitRotateAngleX() - 0.5F);
            armRight.setRotateAngleY(armRight.getInitRotateAngleY() - 0.25F);
            armRight.setRotateAngleZ(armRight.getInitRotateAngleZ() + 0.25F);
            left.setRotateAngleZ(left.getInitRotateAngleZ() + 0.25F);
            left.setRotateAngleX(left.getInitRotateAngleX() + 0.3F);
            right.setRotateAngleZ(right.getInitRotateAngleZ() - 0.5F);
        } else {
            left.setRotateAngleX(left.getInitRotateAngleX());
            left.setRotateAngleZ((float) (left.getInitRotateAngleZ() + Math.atan(limbSwingAmount) * 0.75));
            right.setRotateAngleZ((float) (right.getInitRotateAngleZ() - Math.atan(limbSwingAmount) * 0.75));
        }
        GlWrapper.translate(0, maid.sleeping ? 0.5 : 0.1 * Math.sin(age * 0.075) - 0.1, 0);
    }

    private static ModelRendererWrapper required(Map<String, ModelRendererWrapper> parts, String name) {
        var part = parts.get(name);
        if (part == null) throw new IllegalArgumentException("Missing builtin animation bone " + name);
        return part;
    }
}
