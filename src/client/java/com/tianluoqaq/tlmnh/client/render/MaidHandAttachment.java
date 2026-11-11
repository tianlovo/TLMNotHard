package com.tianluoqaq.tlmnh.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Matrix4f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Side;

import cpw.mods.fml.relauncher.SideOnly;

/** 读取本体已经完成的姿态，不再次执行动画，也不包含实体外层缩放 */
@SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
public final class MaidHandAttachment {

    public enum Status {
        READY,
        MISSING,
        ZERO_SCALE,
        NON_FINITE,
        DEGENERATE
    }

    public static final class Attachment {

        public final Status status;
        public final Side side;
        public final String modelId, backend;
        public final List<String> boneChain;
        private final float[] sourceMatrix;

        private Attachment(BuiltinMaidModel model, Side side, Status status, List<String> bones, Matrix4f matrix) {
            this.status = status;
            this.side = side;
            modelId = model.getChoice()
                .getId();
            backend = model.getGecko() == null ? "BEDROCK" : "GECKO";
            boneChain = Collections.unmodifiableList(new ArrayList<>(bones));
            sourceMatrix = status == Status.NON_FINITE ? null : matrix.get(new float[16]);
        }

        public Matrix4f getSourceMatrix() {
            if (sourceMatrix == null) throw new IllegalStateException("Non-finite hand attachment matrix");
            return new Matrix4f().set(sourceMatrix);
        }

        public float[] getSourceValues() {
            return sourceMatrix == null ? null : sourceMatrix.clone();
        }
    }

    private MaidHandAttachment() {}

    public static Attachment capture(BuiltinMaidModel model, Side side) {
        var poses = new LegacyPoseStack();
        var bones = new ArrayList<String>();
        boolean skipped = false;
        if (model.getBedrock() != null) {
            var parts = model.getBedrock()
                .getModelMap();
            String name = side == Side.LEFT ? "armLeft" : "armRight";
            var arm = parts.get(name);
            if (arm == null) return new Attachment(
                model,
                side,
                Status.MISSING,
                bones,
                poses.last()
                    .pose());
            model.getBedrockPose()
                .applyTransform(poses);
            bones.add(name);
            // 上游只定位这一个part，不含父链、part scale或additionalQuaternion
            arm.translateAndRotate(poses);
            String positioningName = name + "PositioningBone";
            var positioning = parts.get(positioningName);
            if (positioning != null) {
                positioning.translateAndRotate(poses);
                bones.add(positioningName);
            }
            poses.last()
                .pose()
                .mul(MaidHeldItemTransforms.bedrockTail(side, positioning != null));
        } else {
            var geo = model.getGecko();
            var chain = side == Side.LEFT ? geo.leftHandBones() : geo.rightHandBones();
            if (chain.isEmpty()) return new Attachment(
                model,
                side,
                Status.MISSING,
                bones,
                poses.last()
                    .pose());
            for (var bone : chain) bones.add(bone.getName());
            poses.last()
                .pose()
                .mul(MaidHeldItemTransforms.geckoModelBase());
            skipped = RenderUtils.prepMatrixForLocator(poses, chain);
            poses.last()
                .pose()
                .mul(MaidHeldItemTransforms.geckoTail());
        }
        var matrix = poses.last()
            .pose();
        Status status = skipped ? Status.ZERO_SCALE
            : !MaidHeldItemTransforms.finite(matrix) ? Status.NON_FINITE
                : matrix.determinant() == 0 ? Status.DEGENERATE : Status.READY;
        return new Attachment(model, side, status, bones, matrix);
    }
}
