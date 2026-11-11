package com.tianluoqaq.tlmnh.client.render.attachment;

import org.joml.Matrix4f;

/** 原版附件坐标与原生平台物品坐标的显式桥接，不推进模型姿态 */
public final class MaidHeldItemTransforms {

    public enum Side {

        LEFT(AttachmentRenderLayout.UNSET_OR_NOT_FOUND),
        RIGHT(AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);

        private final int sign;

        Side(int sign) {
            this.sign = sign;
        }
    }

    public enum Profile {
        BLOCK_3D,
        BOW,
        FULL_3D,
        FLAT,
        SHIELD_LEFT
    }

    private MaidHeldItemTransforms() {}

    public static Matrix4f bedrockTail(Side side, boolean positioningBone) {
        return new Matrix4f().rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM))
            .rotateY(radians(AttachmentRenderLayout.HEAD_ITEM_FUNC_152674_A_FIFTH_PARAM))
            .translate(positioningBone ? AttachmentRenderLayout.PUT_INITIAL_VALUE : side.sign / AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_X, AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Y, positioningBone ? AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_OFFSET_Y_2 : AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Z);
    }

    public static Matrix4f geckoModelBase() {
        return new Matrix4f().translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_SCALE_X, AttachmentRenderLayout.PUT_INITIAL_VALUE)
            .scale(AttachmentRenderLayout.UNSET_OR_NOT_FOUND, AttachmentRenderLayout.UNSET_OR_NOT_FOUND, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
    }

    public static Matrix4f geckoTail() {
        return new Matrix4f().translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_FIXED_ICON_BRIDGE_OFFSET_Y_2, AttachmentRenderLayout.HELD_ITEM_MATRIX4F_OFFSET_Z)
            .rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM));
    }

    /** 1.20.1 ItemInHandLayer的标准手部基准，不包含物品模型display变换 */
    public static Matrix4f modernHandBasis(Side side) {
        return new Matrix4f().rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM))
            .rotateY(radians(AttachmentRenderLayout.HEAD_ITEM_FUNC_152674_A_FIFTH_PARAM))
            .translate(side.sign / AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_X, AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Y, AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Z_2);
    }

    /** 移植坐标合同，不能据此宣称两版本物品模型外观完全等价 */
    public static Matrix4f bridge(Side side, Profile profile, boolean rotateFull3D) {
        return modernHandBasis(side).invert()
            .mul(nativeArmEntry(side, profile, rotateFull3D));
    }

    /** native进入ItemRenderer之前的矩阵，ItemRenderer自己的helper变换仍正常执行 */
    public static Matrix4f nativeArmEntry(Side side, Profile profile, boolean rotateFull3D) {
        if (profile == Profile.SHIELD_LEFT) {
            if (side != Side.LEFT) throw new IllegalArgumentException(AttachmentRenderDiagnostics.MSG_SHIELD_LEFT_PROFILE_REQUIRES_THE);
            // BG第三人称副手盾入口，不能叠加Backhand通用左手镜像
            return new Matrix4f().translate(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_X / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_X / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM)
                .scale(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_Y / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM)
                .translate(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_X_2 / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y_2 / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Z / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM)
                .rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_2))
                .rotateY(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_3))
                .rotateZ(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_4));
        }
        // 普通副手以实际手部局部原点对称，不复制玩家pivot抵消或玩家事件
        var matrix = new Matrix4f();
        if (side == Side.LEFT) matrix.scale(AttachmentRenderLayout.UNSET_OR_NOT_FOUND, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
        matrix.translate(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Z / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_X / AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM);
        switch (profile) {
            case BLOCK_3D -> matrix.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y_3, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Z_2)
                .rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_5))
                .rotateY(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_6))
                .scale(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X_2, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X_2, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_Z);
            case BOW -> matrix.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Y, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Z_3)
                .rotateY(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_7))
                .scale(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X_3, AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Z_2, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X_3)
                .rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_8))
                .rotateY(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_6));
            case FULL_3D -> {
                if (rotateFull3D) matrix.rotateZ(radians(AttachmentRenderLayout.HEAD_ITEM_FUNC_152674_A_FIFTH_PARAM))
                    .translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y_4, AttachmentRenderLayout.PUT_INITIAL_VALUE);
                matrix.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y_3, AttachmentRenderLayout.PUT_INITIAL_VALUE)
                    .scale(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X_3, AttachmentRenderLayout.HELD_ITEM_RENDER_OFFSET_Z_2, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_X_3)
                    .rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_8))
                    .rotateY(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_6));
            }
            case FLAT -> matrix.translate(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_X_3, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Y_3, AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_OFFSET_Z_4)
                .scale(AttachmentRenderLayout.HELD_ITEM_NATIVE_ARM_ENTRY_SCALE_Z)
                .rotateZ(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_9))
                .rotateX(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM))
                .rotateZ(radians(AttachmentRenderLayout.HELD_ITEM_RADIANS_FIRST_PARAM_5));
            default -> throw new IllegalArgumentException(AttachmentRenderDiagnostics.MSG_UNKNOWN_NATIVE_ITEM_PROFILE);
        }
        return matrix;
    }

    public static boolean finite(Matrix4f matrix) {
        for (float value : matrix.get(new float[AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM])) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
