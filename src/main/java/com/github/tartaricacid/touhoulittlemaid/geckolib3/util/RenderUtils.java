/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/util/RenderUtils.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.util;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.ILocationBone;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;

public final class RenderUtils {

    public static void translateMatrixToBone(LegacyPoseStack poseStack, ILocationBone bone) {
        poseStack.translate(-bone.getPositionX() / 16f, bone.getPositionY() / 16f, bone.getPositionZ() / 16f);
    }

    public static void rotateMatrixAroundBone(LegacyPoseStack poseStack, ILocationBone bone) {
        if (bone.getRotationZ() != 0.0F || bone.getRotationY() != 0.0F || bone.getRotationX() != 0.0F) {
            poseStack
                .mulPose(new Quaternionf().rotateZYX(bone.getRotationZ(), bone.getRotationY(), bone.getRotationX()));
        }
    }

    /**
     * 如果缩放全为 0，则返回 true
     */
    public static boolean scaleMatrixForBone(LegacyPoseStack poseStack, ILocationBone bone) {
        float scaleX = bone.getScaleX();
        float scaleY = bone.getScaleY();
        float scaleZ = bone.getScaleZ();
        poseStack.scale(scaleX, scaleY, scaleZ);
        return scaleX == 0 && scaleY == 0 && scaleZ == 0;
    }

    public static void translateToPivotPoint(LegacyPoseStack poseStack, ILocationBone bone) {
        poseStack.translate(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f);
    }

    public static void translateAwayFromPivotPoint(LegacyPoseStack poseStack, ILocationBone bone) {
        poseStack.translate(-bone.getPivotX() / 16f, -bone.getPivotY() / 16f, -bone.getPivotZ() / 16f);
    }

    public static void translateAndRotateMatrixForBone(LegacyPoseStack poseStack, ILocationBone bone) {
        translateToPivotPoint(poseStack, bone);
        rotateMatrixAroundBone(poseStack, bone);
    }

    /**
     * 如果缩放为 0，则返回 true
     */
    public static boolean prepMatrixForBone(LegacyPoseStack poseStack, ILocationBone bone) {
        translateMatrixToBone(poseStack, bone);
        translateToPivotPoint(poseStack, bone);
        rotateMatrixAroundBone(poseStack, bone);
        boolean scaleAllIsZero = scaleMatrixForBone(poseStack, bone);
        translateAwayFromPivotPoint(poseStack, bone);
        return scaleAllIsZero;
    }

    public static Matrix4f invertAndMultiplyMatrices(Matrix4f baseMatrix, Matrix4f inputMatrix) {
        inputMatrix = new Matrix4f(inputMatrix);
        inputMatrix.invert();
        inputMatrix.mul(baseMatrix);
        return inputMatrix;
    }

    public static boolean prepMatrixForLocator(LegacyPoseStack poseStack,
        List<? extends ILocationBone> locatorHierarchy) {
        boolean scaleCheck = false;
        for (int i = 0; i < locatorHierarchy.size() - 1; i++) {
            boolean result = RenderUtils.prepMatrixForBone(poseStack, locatorHierarchy.get(i));
            if (result) {
                scaleCheck = true;
            }
        }
        ILocationBone lastBone = locatorHierarchy.get(locatorHierarchy.size() - 1);
        RenderUtils.translateMatrixToBone(poseStack, lastBone);
        RenderUtils.translateToPivotPoint(poseStack, lastBone);
        RenderUtils.rotateMatrixAroundBone(poseStack, lastBone);
        RenderUtils.scaleMatrixForBone(poseStack, lastBone);
        return scaleCheck;
    }
}
