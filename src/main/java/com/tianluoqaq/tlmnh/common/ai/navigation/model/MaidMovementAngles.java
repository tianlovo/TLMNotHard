package com.tianluoqaq.tlmnh.common.ai.navigation.model;

/** 现代移动控制所用查表角度，保留Mth.atan2的有限精度规则 */
public final class MaidMovementAngles {

    private static final double[] ASIN = new double[NavigationModelTuning.MOVEMENT_ANGLES_DOMAIN_PARAM],
        COS = new double[NavigationModelTuning.MOVEMENT_ANGLES_DOMAIN_PARAM];
    static {
        for (int i = 0; i < NavigationModelTuning.MOVEMENT_ANGLES_DOMAIN_PARAM; i++) {
            ASIN[i] = Math.asin(i / NavigationModelTuning.MOVEMENT_ANGLES_ASIN_FIRST_FACTOR);
            COS[i] = Math.cos(ASIN[i]);
        }
    }

    private MaidMovementAngles() {}

    public static double atan2(double y, double x) {
        double squared = x * x + y * y;
        if (Double.isNaN(squared)) return Double.NaN;
        boolean negativeY = y < 0, negativeX = x < 0;
        if (negativeY) y = -y;
        if (negativeX) x = -x;
        boolean swap = y > x;
        if (swap) {
            double old = x;
            x = y;
            y = old;
        }
        double half = NavigationModelTuning.MOVEMENT_ANGLES_HALF * squared;
        double inverse = Double.longBitsToDouble(
            NavigationModelTuning.MOVEMENT_ANGLES_LONG_BITS_TO_DOUBLE_FIRST_LIMIT
                - (Double.doubleToRawLongBits(squared) >> NavigationModelTuning.MOVEMENT_ANGLES_BIT_SHIFT_1));
        inverse *= NavigationModelTuning.MOVEMENT_ANGLES_ATAN2_FACTOR - half * inverse * inverse;
        x *= inverse;
        y *= inverse;
        double biased = NavigationModelDerivedTuning.BIAS + y;
        int index = (int) Double.doubleToRawLongBits(biased);
        double error = y * COS[index] - x * (biased - NavigationModelDerivedTuning.BIAS);
        double angle = ASIN[index] + (NavigationModelTuning.MOVEMENT_ANGLES_ATAN2_PARAM + error * error) * error
            * NavigationModelTuning.MOVEMENT_ANGLES_ANGLE;
        if (swap) angle = Math.PI / NavigationModelTuning.MIDPOINT_DIVISOR - angle;
        if (negativeX) angle = Math.PI - angle;
        if (negativeY) angle = -angle;
        return angle;
    }
}
