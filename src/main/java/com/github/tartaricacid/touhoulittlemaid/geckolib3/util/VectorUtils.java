/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/util/VectorUtils.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.util;

import net.minecraft.util.Vec3;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.Validate;
import org.joml.Vector3f;

public class VectorUtils {

    public static Vec3 fromArray(double[] array) {
        Validate.validIndex(ArrayUtils.toObject(array), 2);
        return Vec3.createVectorHelper(array[0], array[1], array[2]);
    }

    public static Vector3f fromArray(float[] array) {
        Validate.validIndex(ArrayUtils.toObject(array), 2);
        return new Vector3f(array[0], array[1], array[2]);
    }

    public static Vector3f convertDoubleToFloat(Vec3 vector) {
        return new Vector3f((float) vector.xCoord, (float) vector.yCoord, (float) vector.zCoord);
    }

    public static Vec3 convertFloatToDouble(Vector3f vector) {
        return Vec3.createVectorHelper(vector.x(), vector.y(), vector.z());
    }
}
