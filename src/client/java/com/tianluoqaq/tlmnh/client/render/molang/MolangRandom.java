package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.Random;

/** 保留上游整数随机的上界排除语义，处理等端点、范围溢出和过大的投掷次数 */
public final class MolangRandom {

    private static final int MAX_ROLLS = 1024;

    private MolangRandom() {}

    public static int rollCount(int requested) {
        return Math.max(0, Math.min(requested, MAX_ROLLS));
    }

    public static int nextInteger(Random random, int first, int second) {
        int min = Math.min(first, second);
        long width = (long) Math.max(first, second) - min;
        if (width == 0) return min;
        if (width <= Integer.MAX_VALUE) return min + random.nextInt((int) width);
        long bits;
        long offset;
        do {
            bits = random.nextLong() >>> 1;
            offset = bits % width;
        } while (bits - offset + width - 1 < 0);
        return (int) (min + offset);
    }
}
