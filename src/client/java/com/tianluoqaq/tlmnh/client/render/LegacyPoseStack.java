package com.tianluoqaq.tlmnh.client.render;

import java.util.ArrayDeque;
import java.util.Deque;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** 在 CPU 侧保存骨骼变换，顶点提交后再由 1.7.10 的实体矩阵转换到世界 */
public final class LegacyPoseStack {

    private final Deque<Pose> stack = new ArrayDeque<>();

    public LegacyPoseStack() {
        stack.push(new Pose(new Matrix4f(), new Matrix3f()));
    }

    public void pushPose() {
        var current = last();
        stack.push(new Pose(new Matrix4f(current.pose), new Matrix3f(current.normal)));
    }

    public void popPose() {
        if (stack.size() == 1) throw new IllegalStateException("Cannot pop the root model pose");
        stack.pop();
    }

    public Pose last() {
        return stack.peek();
    }

    public void translate(double x, double y, double z) {
        last().pose.translate((float) x, (float) y, (float) z);
    }

    public void mulPose(Quaternionf rotation) {
        last().pose.rotate(rotation);
        last().normal.rotate(rotation);
    }

    public void scale(float x, float y, float z) {
        last().pose.scale(x, y, z);
        // 余子式与逆转置只差正系数，提交时归一化；单轴归零仍保留平面法线
        float orientation = (x < 0.0F ^ y < 0.0F ^ z < 0.0F) ? -1.0F : 1.0F;
        double nx = orientation * (double) y * z;
        double ny = orientation * (double) x * z;
        double nz = orientation * (double) x * y;
        double largest = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
        // 去掉公因子，避免连续均匀缩放让法线下溢
        if (largest > 0.0) {
            last().normal.scale((float) (nx / largest), (float) (ny / largest), (float) (nz / largest));
        } else {
            last().normal.zero();
        }
    }

    public static final class Pose {

        private final Matrix4f pose;
        private final Matrix3f normal;

        private Pose(Matrix4f pose, Matrix3f normal) {
            this.pose = pose;
            this.normal = normal;
        }

        public Matrix4f pose() {
            return pose;
        }

        public Matrix3f normal() {
            return normal;
        }
    }
}
