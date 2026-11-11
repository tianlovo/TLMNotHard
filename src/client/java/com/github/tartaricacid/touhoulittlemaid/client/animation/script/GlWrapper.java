/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/script/GlWrapper.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: CPU pose stack and nested bindings; no GL or script-engine dependency
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;

public class GlWrapper {

    private static final ThreadLocal<Deque<LegacyPoseStack>> POSES = ThreadLocal.withInitial(ArrayDeque::new);

    public static void setPoseStack(LegacyPoseStack poseStack) {
        POSES.get()
            .push(Objects.requireNonNull(poseStack));
    }

    public static void clearPoseStack() {
        var poses = POSES.get();
        if (poses.isEmpty()) throw new IllegalStateException("No active maid animation pose");
        poses.pop();
        if (poses.isEmpty()) POSES.remove();
    }

    private static LegacyPoseStack pose() {
        var poses = POSES.get();
        if (poses.isEmpty()) {
            POSES.remove();
            throw new IllegalStateException("Maid animation transform outside a frame");
        }
        return poses.peek();
    }

    public static void translate(double x, double y, double z) {
        pose().translate(x, y, z);
    }

    public static void rotate(double angle, double x, double y, double z) {
        Vector3f axis = new Vector3f(normalize(x), normalize(y), normalize(z));
        pose().mulPose(new Quaternionf().rotateAxis((float) angle * ((float) Math.PI / 180), axis));
    }

    public static void scale(double x, double y, double z) {
        pose().scale((float) x, (float) y, (float) z);
    }

    public static void pushMatrix() {
        pose().pushPose();
    }

    public static void popMatrix() {
        pose().popPose();
    }

    private static float normalize(double value) {
        if (value >= 1.0E-5) return 1;
        if (value <= -1.0E-5) return -1;
        return 0;
    }
}
