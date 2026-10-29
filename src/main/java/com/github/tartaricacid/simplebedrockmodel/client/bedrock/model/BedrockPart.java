/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/model/BedrockPart.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class BedrockPart {

    private static final Vector3f[] NORMALS = new Vector3f[6];
    private static final int MAX_LIGHT_TEXTURE = 0x00F000F0;

    public final List<BedrockCube> cubes = new ArrayList<>();
    public final List<BedrockPart> children = new ArrayList<>();

    public float x = 0, y = 0, z = 0;
    /**
     * 用来记录 BedrockPart 初始旋转角度，用于动画状态重置
     */
    public float initRotX = 0, initRotY = 0, initRotZ = 0;
    public float xRot = 0, yRot = 0, zRot = 0;
    public float offsetX = 0, offsetY = 0, offsetZ = 0;
    public float xScale = 1, yScale = 1, zScale = 1;
    /**
     * 可能用于动画旋转的四元数
     * <p>
     * BedrockPart 支持两套旋转方式：一种是欧拉角，一种是四元数
     */
    public Quaternionf additionalQuaternion = new Quaternionf(0, 0, 0, 1);

    public @Nullable BedrockPart parent = null;
    public boolean visible = true;
    public boolean illuminated = false;
    public boolean mirror = false;

    static {
        for (int i = 0; i < NORMALS.length; i++) {
            NORMALS[i] = new Vector3f();
        }
    }

    public void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void render(LegacyPoseStack poseStack, LegacyVertexConsumer consumer, int lightmap, int overlay) {
        this.render(poseStack, consumer, lightmap, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    public void render(LegacyPoseStack poseStack, LegacyVertexConsumer consumer, int lightmap, int overlay, float red,
        float green, float blue, float alpha) {
        int cubePackedLight = illuminated ? MAX_LIGHT_TEXTURE : lightmap;
        if (this.visible) {
            // 缩放过小时，直接退出渲染
            boolean xNearZero = -1E-5F < xScale && xScale < 1E-5F;
            boolean yNearZero = -1E-5F < yScale && yScale < 1E-5F;
            boolean zNearZero = -1E-5F < zScale && zScale < 1E-5F;
            if ((xNearZero && yNearZero) || (xNearZero && zNearZero) || (yNearZero && zNearZero)) {
                return;
            }

            if (!this.cubes.isEmpty() || !this.children.isEmpty()) {
                poseStack.pushPose();
                this.translateAndRotateAndScale(poseStack);
                this.compile(poseStack.last(), consumer, cubePackedLight, overlay, red, green, blue, alpha);

                for (BedrockPart part : this.children) {
                    part.render(poseStack, consumer, cubePackedLight, overlay, red, green, blue, alpha);
                }

                poseStack.popPose();
            }
        }
    }

    /**
     * 不带缩放的平移和旋转
     */
    @Deprecated
    @SuppressWarnings("all")
    public void translateAndRotate(LegacyPoseStack poseStack) {
        poseStack.translate(
            (this.x / 16.0F) + this.offsetX,
            (this.y / 16.0F) + this.offsetY,
            (this.z / 16.0F) + this.offsetZ);
        if (this.xRot != 0.0F || this.yRot != 0.0F || this.zRot != 0.0F) {
            poseStack.last()
                .pose()
                .rotateZYX(this.zRot, this.yRot, this.xRot);
            poseStack.last()
                .normal()
                .rotateZYX(this.zRot, this.yRot, this.xRot);
        }
    }

    public Vector3f getTranslateAndRotateVector3f() {
        Quaternionf quaternionf = new Quaternionf();
        if (this.xRot != 0.0F || this.yRot != 0.0F || this.zRot != 0.0F) {
            quaternionf.rotateZYX(this.zRot, this.yRot, this.xRot);
        }
        float finalX = (this.x / 16.0F) + this.offsetX;
        float finalY = (this.y / 16.0F) + this.offsetY;
        float finalZ = (this.z / 16.0F) + this.offsetZ;
        Vector3f translation = new Vector3f(finalX, finalY, finalZ);
        return quaternionf.transform(translation);
    }

    public void translateAndRotateAndScale(LegacyPoseStack poseStack) {
        translateAndRotate(poseStack);
        poseStack.mulPose(additionalQuaternion);
        poseStack.scale(xScale, yScale, zScale);
    }

    private void compile(LegacyPoseStack.Pose pose, LegacyVertexConsumer consumer, int lightmap, int overlay, float red,
        float green, float blue, float alpha) {
        Matrix3f normal = pose.normal();
        NORMALS[0].set(-normal.m10(), -normal.m11(), -normal.m12());
        NORMALS[1].set(normal.m10(), normal.m11(), normal.m12());
        NORMALS[2].set(-normal.m20(), -normal.m21(), -normal.m22());
        NORMALS[3].set(normal.m20(), normal.m21(), normal.m22());
        NORMALS[4].set(-normal.m00(), -normal.m01(), -normal.m02());
        NORMALS[5].set(normal.m00(), normal.m01(), normal.m02());
        for (BedrockCube bedrockCube : this.cubes) {
            bedrockCube.compile(pose, NORMALS, consumer, lightmap, overlay, red, green, blue, alpha);
        }
    }

    public BedrockCube getRandomCube(Random random) {
        return this.cubes.get(random.nextInt(this.cubes.size()));
    }

    public boolean isEmpty() {
        return this.cubes.isEmpty();
    }

    public void setInitRotationAngle(float x, float y, float z) {
        this.initRotX = x;
        this.initRotY = y;
        this.initRotZ = z;
    }

    public float getInitRotX() {
        return initRotX;
    }

    public float getInitRotY() {
        return initRotY;
    }

    public float getInitRotZ() {
        return initRotZ;
    }

    public void addChild(BedrockPart model) {
        this.children.add(model);
        model.parent = this;
    }

    @Nullable
    public BedrockPart getParent() {
        return parent;
    }
}
