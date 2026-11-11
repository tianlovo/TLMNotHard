package com.tianluoqaq.tlmnh.compat.gregtech.common.food;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider.CustomFoodData;
import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider.PotionEntry;

/** 读取格雷科技元生成食物的容器、解毒奶属性与药水数组；爆炸类条目直接拒绝 */
public final class GregTechFoodAccess {




    private GregTechFoodAccess() {}

    public static CustomFoodData inspect(ItemStack stack) throws ReflectiveOperationException {
        if (stack == null || stack.getItem() == null) return null;
        Class<?> meta = Class.forName(GregTechFoodReflectionSpec.META_ITEM_CLASS, false, GregTechFoodAccess.class.getClassLoader());
        if (!meta.isInstance(stack.getItem())) return null;
        var stats = (Map<?, ?>) meta.getField(GregTechFoodIds.ID_MFOODSTATS)
            .get(stack.getItem());
        Object stat = stats.get((short) stack.getItemDamage());
        if (stat == null || !GregTechFoodReflectionSpec.FOOD_STAT_CLASS.equals(
            stat.getClass()
                .getName()))
            return null;
        if ((boolean) field(stat, GregTechFoodIds.ID_MEXPLOSIVE)) return null;
        var empty = (ItemStack) field(stat, GregTechFoodIds.ID_MEMPTYCONTAINER);
        boolean milk = (boolean) field(stat, GregTechFoodIds.ID_MMILK);
        int[] potions = (int[]) field(stat, GregTechFoodIds.ID_MPOTIONEFFECTS);
        boolean ambient = (boolean) field(stat, GregTechFoodIds.ID_MINVISIBLEPARTICLES);
        var effects = new ArrayList<PotionEntry>();
        if (potions != null) {
            for (int i = GregTechFoodTuning.GREG_TECH_INSPECT_I_OFFSET; i < potions.length; i += GregTechFoodTuning.GREG_TECH_INSPECT_PARAM) {
                effects
                    .add(new PotionEntry(potions[i - GregTechFoodTuning.GREG_TECH_INSPECT_I_OFFSET], potions[i - GregTechFoodTuning.GREG_TECH_POTION_ENTRY_SECOND_PARAM], potions[i - 1], potions[i] / GregTechFoodTuning.GREG_TECH_POTION_ENTRY_FOURTH_FACTOR, ambient));
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
