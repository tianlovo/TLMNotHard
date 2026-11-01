package com.tianluoqaq.tlmnh.compat.applecore.common.food;

import net.minecraft.item.ItemStack;

/** 仅在调用方的营养保护闭包内解析AppleCore，避免缺少API时提前加载 */
public final class AppleCoreNutrition {

    private AppleCoreNutrition() {}

    public static float[] values(ItemStack stack) {
        var values = squeek.applecore.api.AppleCoreAPI.accessor.getFoodValues(stack);
        return values == null ? null : new float[] { values.hunger, values.saturationModifier };
    }
}
