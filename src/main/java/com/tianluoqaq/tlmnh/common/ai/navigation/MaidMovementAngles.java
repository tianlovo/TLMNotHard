package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 现代移动控制所用查表角度，保留Mth.atan2的有限精度规则 */
public final class MaidMovementAngles {

    private static final double BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN = new double[257], COS = new double[257];
    static {
        for (int i = 0; i < 257; i++) {
            ASIN[i] = Math.asin(i / 256.0);
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
        double half = .5 * squared;
        double inverse = Double.longBitsToDouble(6910469410427058090L - (Double.doubleToRawLongBits(squared) >> 1));
        inverse *= 1.5 - half * inverse * inverse;
        x *= inverse;
        y *= inverse;
        double biased = BIAS + y;
        int index = (int) Double.doubleToRawLongBits(biased);
        double error = y * COS[index] - x * (biased - BIAS);
        double angle = ASIN[index] + (6 + error * error) * error * .16666666666666666;
        if (swap) angle = Math.PI / 2 - angle;
        if (negativeX) angle = Math.PI - angle;
        if (negativeY) angle = -angle;
        return angle;
    }
}
