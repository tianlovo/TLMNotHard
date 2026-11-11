/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/variable/MoveInputVariable.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.variable;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util.MathUtil;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.MathHelper;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;

public class MoveInputVariable {

    public static double getVertical(MolangQueryFrame entity) {

        // 求出当前移动的水平分量
        double x = entity.positionDelta(0);
        double z = entity.positionDelta(2);

        // 如果移动数值过小，那么认为没有一点，返回 0
        if (Math.sqrt(x * x + z * z) < 1.0E-4) {
            return 0;
        }

        // 计算移动角度和实体偏航角度，并作差计算出相对角度
        float moveAngleDeg = MathUtil.radiansToDegrees((float) Math.atan2(z, x));
        float entityYawDeg = 90 - MathHelper.wrapDegrees(-entity.viewYaw);
        float relativeAnglesDeg = MathHelper.wrapDegrees(moveAngleDeg - entityYawDeg);

        return Math.cos(MathUtil.degreesToRadians(relativeAnglesDeg));
    }

    public static double getHorizontal(MolangQueryFrame entity) {

        // 求出当前移动的水平分量
        double x = entity.positionDelta(0);
        double z = entity.positionDelta(2);

        // 如果移动数值过小，那么认为没有一点，返回 0
        if (Math.sqrt(x * x + z * z) < 1.0E-4) {
            return 0;
        }

        // 计算移动角度和实体偏航角度，并作差计算出相对角度
        float moveAngleDeg = MathUtil.radiansToDegrees((float) Math.atan2(z, x));
        float entityYawDeg = 90 - MathHelper.wrapDegrees(-entity.viewYaw);
        float relativeAnglesDeg = MathHelper.wrapDegrees(moveAngleDeg - entityYawDeg);

        return Math.sin(MathUtil.degreesToRadians(relativeAnglesDeg));
    }
}
