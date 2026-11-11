package com.tianluoqaq.tlmnh.compat.gregtech.common.food;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider.CustomFoodData;
import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider.PotionEntry;

/** 读取格雷科技元生成食物的容器、解毒奶属性与药水数组；爆炸类条目直接拒绝 */
public final class GregTechFoodAccess {

    private static final String META_ITEM_CLASS = "gregtech.api.items.MetaGeneratedItem";
    private static final String FOOD_STAT_CLASS = "gregtech.api.util.GTFoodStat";

    private GregTechFoodAccess() {}

    public static CustomFoodData inspect(ItemStack stack) throws ReflectiveOperationException {
        if (stack == null || stack.getItem() == null) return null;
        Class<?> meta = Class.forName(META_ITEM_CLASS, false, GregTechFoodAccess.class.getClassLoader());
        if (!meta.isInstance(stack.getItem())) return null;
        var stats = (Map<?, ?>) meta.getField("mFoodStats")
            .get(stack.getItem());
        Object stat = stats.get((short) stack.getItemDamage());
        if (stat == null || !FOOD_STAT_CLASS.equals(
            stat.getClass()
                .getName()))
            return null;
        if ((boolean) field(stat, "mExplosive")) return null;
        var empty = (ItemStack) field(stat, "mEmptyContainer");
        boolean milk = (boolean) field(stat, "mMilk");
        int[] potions = (int[]) field(stat, "mPotionEffects");
        boolean ambient = (boolean) field(stat, "mInvisibleParticles");
        var effects = new ArrayList<PotionEntry>();
        if (potions != null) {
            for (int i = 3; i < potions.length; i += 4) {
                effects
                    .add(new PotionEntry(potions[i - 3], potions[i - 2], potions[i - 1], potions[i] / 100F, ambient));
            }
        }
        return new CustomFoodData(empty, milk, effects);
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
}
