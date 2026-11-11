package com.tianluoqaq.tlmnh.common.progression;

import gregtech.api.util.GTModHandler;

/** GregTech 配方注册标志位定义 */
public final class GregTechProgressionTuning {

    public static final long BITS = GTModHandler.RecipeBits.NOT_REMOVABLE
        | GTModHandler.RecipeBits.DO_NOT_CHECK_FOR_COLLISIONS;

    private GregTechProgressionTuning() {}
}
