package com.tianluoqaq.tlmnh.client.render;

import org.joml.Matrix4f;

/** 原版附件坐标与RC2物品坐标的显式桥接，不推进模型姿态 */
public final class MaidHeldItemTransforms {

    public enum Side {

        LEFT(-1),
        RIGHT(1);

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
        return new Matrix4f().rotateX(radians(-90))
            .rotateY(radians(180))
            .translate(positioningBone ? 0 : side.sign / 16F, .125F, positioningBone ? -.0625F : -.525F);
    }

    public static Matrix4f geckoModelBase() {
        return new Matrix4f().translate(0, 1.5F, 0)
            .scale(-1, -1, 1);
    }

    public static Matrix4f geckoTail() {
        return new Matrix4f().translate(0, -.0625F, -.1F)
            .rotateX(radians(-90));
    }

    /** 1.20.1 ItemInHandLayer的标准手部基准，不包含物品模型display变换 */
    public static Matrix4f modernHandBasis(Side side) {
        return new Matrix4f().rotateX(radians(-90))
            .rotateY(radians(180))
            .translate(side.sign / 16F, .125F, -.625F);
    }

    /** 移植坐标合同，不能据此宣称两版本物品模型外观完全等价 */
    public static Matrix4f bridge(Side side, Profile profile, boolean rotateFull3D) {
        return modernHandBasis(side).invert()
            .mul(nativeArmEntry(side, profile, rotateFull3D));
    }

    /** RC2进入ItemRenderer之前的矩阵，ItemRenderer自己的helper变换仍正常执行 */
    public static Matrix4f nativeArmEntry(Side side, Profile profile, boolean rotateFull3D) {
        if (profile == Profile.SHIELD_LEFT) {
            if (side != Side.LEFT) throw new IllegalArgumentException("Shield-left profile requires the left hand");
            // BG第三人称副手盾入口，不能叠加Backhand通用左手镜像
            return new Matrix4f().translate(1F / 16, 7F / 16, 1F / 16)
                .scale(10F / 16, -10F / 16, 10F / 16)
                .translate(8F / 16, -11F / 16, -1F / 16)
                .rotateX(radians(-10))
                .rotateY(radians(-45))
                .rotateZ(radians(25));
        }
        // 普通副手以实际手部局部原点对称，不复制玩家pivot抵消或玩家事件
        var matrix = new Matrix4f();
        if (side == Side.LEFT) matrix.scale(-1, 1, 1);
        matrix.translate(-1F / 16, 7F / 16, 1F / 16);
        switch (profile) {
            case BLOCK_3D -> matrix.translate(0, .1875F, -.3125F)
                .rotateX(radians(20))
                .rotateY(radians(45))
                .scale(-.375F, -.375F, .375F);
            case BOW -> matrix.translate(0, .125F, .3125F)
                .rotateY(radians(-20))
                .scale(.625F, -.625F, .625F)
                .rotateX(radians(-100))
                .rotateY(radians(45));
            case FULL_3D -> {
                if (rotateFull3D) matrix.rotateZ(radians(180))
                    .translate(0, -.125F, 0);
                matrix.translate(0, .1875F, 0)
                    .scale(.625F, -.625F, .625F)
                    .rotateX(radians(-100))
                    .rotateY(radians(45));
            }
            case FLAT -> matrix.translate(.25F, .1875F, -.1875F)
                .scale(.375F)
                .rotateZ(radians(60))
                .rotateX(radians(-90))
                .rotateZ(radians(20));
            default -> throw new IllegalArgumentException("Unknown native item profile");
        }
        return matrix;
    }

    public static boolean finite(Matrix4f matrix) {
        for (float value : matrix.get(new float[16])) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
