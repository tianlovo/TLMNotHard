package com.tianluoqaq.tlmnh.common.life.spi;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;

/** 食物饥饿值、饱和度与扩展元物品饮食效果的窄适配契约；默认退回原版 ItemFood 数值 */
public interface NutritionCompatProvider {

    NutritionCompatProvider NOOP = new NutritionCompatProvider() {};

    final class PotionEntry {

        public final int potionId;
        public final int durationTicks;
        public final int amplifier;
        public final float probability;
        public final boolean ambient;

        public PotionEntry(int potionId, int durationTicks, int amplifier, float probability, boolean ambient) {
            this.potionId = potionId;
            this.durationTicks = durationTicks;
            this.amplifier = amplifier;
            this.probability = probability;
            this.ambient = ambient;
        }
    }

    final class CustomFoodData {

        private final ItemStack emptyContainer;
        public final boolean curesMilk;
        public final List<PotionEntry> effects;

        public CustomFoodData(ItemStack emptyContainer, boolean curesMilk, List<PotionEntry> effects) {
            this.emptyContainer = emptyContainer == null ? null : emptyContainer.copy();
            this.curesMilk = curesMilk;
            this.effects = effects == null ? Collections.emptyList()
                : Collections.unmodifiableList(Objects.requireNonNull(effects));
        }

        public ItemStack emptyContainerCopy() {
            return emptyContainer == null ? null : emptyContainer.copy();
        }
    }

    default float[] foodValues(ItemStack stack) {
        if (stack != null && stack.getItem() instanceof ItemFood food) {
            return new float[] { food.func_150905_g(stack), food.func_150906_h(stack) };
        }
        return null;
    }

    default CustomFoodData inspectCustomFood(ItemStack stack) throws ReflectiveOperationException {
        return null;
    }
}
