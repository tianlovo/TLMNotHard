package com.tianluoqaq.tlmnh.client.render;

import org.joml.Matrix4f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原版头部附件入口，复用本体姿态，不再次计算动画或猜测缺失定位骨 */
@SideOnly(Side.CLIENT)
public final class MaidHeadAttachment {

    private MaidHeadAttachment() {}

    public static Matrix4f capture(BuiltinMaidModel model) {
        if (!model.getChoice()
            .isShowCustomHead()) return null;
        var poses = new LegacyPoseStack();
        if (model.getBedrock() != null) {
            var head = model.getBedrock()
                .getModelMap()
                .get("head");
            if (head == null) return null;
            model.getBedrockPose()
                .applyTransform(poses);
            // 原Layer只定位head这个part，不累积父骨骼或part scale
            head.translateAndRotate(poses);
        } else {
            var chain = model.getGecko()
                .headBones();
            if (chain.isEmpty()) return null;
            poses.last()
                .pose()
                .mul(MaidHeldItemTransforms.geckoModelBase());
            if (RenderUtils.prepMatrixForLocator(poses, chain)) return null;
        }
        var matrix = poses.last()
            .pose();
        return MaidHeldItemTransforms.finite(matrix) && matrix.determinant() != 0 ? new Matrix4f(matrix) : null;
    }
}
