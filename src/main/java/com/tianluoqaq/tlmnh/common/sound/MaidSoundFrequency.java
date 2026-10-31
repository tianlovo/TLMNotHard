package com.tianluoqaq.tlmnh.common.sound;

import java.util.function.DoubleSupplier;

/** 保留上游客户端过滤顺序；满频率和试听不消耗随机数 */
public final class MaidSoundFrequency {

    private MaidSoundFrequency() {}

    public static boolean allow(float maidFrequency, int globalPercent, boolean testSound, DoubleSupplier random) {
        double effective = (double) maidFrequency * globalPercent / 100;
        return testSound || !(effective < 1) || !(random.getAsDouble() > effective);
    }
}
