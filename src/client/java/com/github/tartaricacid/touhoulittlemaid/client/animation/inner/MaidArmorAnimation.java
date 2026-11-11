/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/inner/MaidArmorAnimation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: legacy resource IDs and per-frame state input; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.inner;

import static com.github.tartaricacid.touhoulittlemaid.client.animation.inner.InnerAnimation.INNER_ANIMATION;

import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.tianluoqaq.tlmnh.client.render.MaidAnimationState;

public final class MaidArmorAnimation {

    public static void init() {
        INNER_ANIMATION
            .put(new ResourceLocation("touhou_little_maid:animation/maid/default/armor/default.js"), getArmorDefault());
        INNER_ANIMATION
            .put(new ResourceLocation("touhou_little_maid:animation/maid/default/armor/reverse.js"), getArmorReverse());

    }

    public static IAnimation<MaidAnimationState> getArmorDefault() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState maid,
                Map<String, ModelRendererWrapper> modelMap) {

                ModelRendererWrapper helmet = modelMap.get("helmet");
                ModelRendererWrapper chestPlate = modelMap.get("chestPlate");
                ModelRendererWrapper chestPlateLeft = modelMap.get("chestPlateLeft");
                ModelRendererWrapper chestPlateMiddle = modelMap.get("chestPlateMiddle");
                ModelRendererWrapper chestPlateRight = modelMap.get("chestPlateRight");
                ModelRendererWrapper leggings = modelMap.get("leggings");
                ModelRendererWrapper leggingsLeft = modelMap.get("leggingsLeft");
                ModelRendererWrapper leggingsMiddle = modelMap.get("leggingsMiddle");
                ModelRendererWrapper leggingsRight = modelMap.get("leggingsRight");
                ModelRendererWrapper bootsLeft = modelMap.get("bootsLeft");
                ModelRendererWrapper bootsRight = modelMap.get("bootsRight");

                if (helmet != null) {
                    helmet.setHidden(!maid.helmet);
                }
                if (chestPlate != null) {
                    chestPlate.setHidden(!maid.chestPlate);
                }
                if (chestPlateLeft != null) {
                    chestPlateLeft.setHidden(!maid.chestPlate);
                }
                if (chestPlateMiddle != null) {
                    chestPlateMiddle.setHidden(!maid.chestPlate);
                }
                if (chestPlateRight != null) {
                    chestPlateRight.setHidden(!maid.chestPlate);
                }
                if (leggings != null) {
                    leggings.setHidden(!maid.leggings);
                }
                if (leggingsLeft != null) {
                    leggingsLeft.setHidden(!maid.leggings);
                }
                if (leggingsMiddle != null) {
                    leggingsMiddle.setHidden(!maid.leggings);
                }
                if (leggingsRight != null) {
                    leggingsRight.setHidden(!maid.leggings);
                }
                if (bootsLeft != null) {
                    bootsLeft.setHidden(!maid.boots);
                }
                if (bootsRight != null) {
                    bootsRight.setHidden(!maid.boots);
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getArmorReverse() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState maid,
                Map<String, ModelRendererWrapper> modelMap) {

                ModelRendererWrapper reverseHelmet = modelMap.get("_helmet");
                ModelRendererWrapper reverseChestPlate = modelMap.get("_chestPlate");
                ModelRendererWrapper reverseChestPlateLeft = modelMap.get("_chestPlateLeft");
                ModelRendererWrapper reverseChestPlateMiddle = modelMap.get("_chestPlateMiddle");
                ModelRendererWrapper reverseChestPlateRight = modelMap.get("_chestPlateRight");
                ModelRendererWrapper reverseLeggings = modelMap.get("_leggings");
                ModelRendererWrapper reverseLeggingsLeft = modelMap.get("_leggingsLeft");
                ModelRendererWrapper reverseLeggingsMiddle = modelMap.get("_leggingsMiddle");
                ModelRendererWrapper reverseLeggingsRight = modelMap.get("_leggingsRight");
                ModelRendererWrapper reverseBootsLeft = modelMap.get("_bootsLeft");
                ModelRendererWrapper reverseBootsRight = modelMap.get("_bootsRight");

                if (reverseHelmet != null) {
                    reverseHelmet.setHidden(maid.helmet);
                }
                if (reverseChestPlate != null) {
                    reverseChestPlate.setHidden(maid.chestPlate);
                }
                if (reverseChestPlateLeft != null) {
                    reverseChestPlateLeft.setHidden(maid.chestPlate);
                }
                if (reverseChestPlateMiddle != null) {
                    reverseChestPlateMiddle.setHidden(maid.chestPlate);
                }
                if (reverseChestPlateRight != null) {
                    reverseChestPlateRight.setHidden(maid.chestPlate);
                }
                if (reverseLeggings != null) {
                    reverseLeggings.setHidden(maid.leggings);
                }
                if (reverseLeggingsLeft != null) {
                    reverseLeggingsLeft.setHidden(maid.leggings);
                }
                if (reverseLeggingsMiddle != null) {
                    reverseLeggingsMiddle.setHidden(maid.leggings);
                }
                if (reverseLeggingsRight != null) {
                    reverseLeggingsRight.setHidden(maid.leggings);
                }
                if (reverseBootsLeft != null) {
                    reverseBootsLeft.setHidden(maid.boots);
                }
                if (reverseBootsRight != null) {
                    reverseBootsRight.setHidden(maid.boots);
                }
            }
        };
    }

}
