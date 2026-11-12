package com.tianluoqaq.tlmnh.common.life.meal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.ObfuscationReflectionHelper;

/** GTNH食物数据适配，不把女仆伪装成玩家执行onEaten */
public final class MaidFoodProfile {

    public final float total;
    public final int hunger;
    public final float saturationModifier;
    public final int duration;
    private ItemStack container;
    private boolean milk;
    private boolean requiresNutrition = true, requiresGregtech;
    private final List<Effect> effects = new ArrayList<>();

    private MaidFoodProfile(ItemStack stack, int hunger, float saturation) {
        this.hunger = hunger;
        this.saturationModifier = saturation;
        total = hunger + hunger * saturation * MaidMealTuning.FOOD_PROFILE_TOTAL;
        duration = stack.getMaxItemUseDuration();
    }

    public static MaidFoodProfile inspect(ItemStack stack) {
        return IntegrationGuard
            .call(ExternalFeature.NUTRITION, () -> inspectSafe(stack == null ? null : stack.copy()), () -> null);
    }

    private static MaidFoodProfile inspectSafe(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return null;
        EnumAction action = stack.getItemUseAction();
        if (action != EnumAction.eat && action != EnumAction.drink) return null;
        var values = CompatServices.nutrition()
            .foodValues(stack);
        if (values == null || values[MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE] < 0
            || !Float.isFinite(values[MaidMealTuning.IS_FINITE_SINGLE_UNIT])) return null;
        var profile = new MaidFoodProfile(stack, (int) values[0], values[MaidMealTuning.IS_FINITE_SINGLE_UNIT]);
        if (!Float.isFinite(profile.total) || profile.total < 0
            || profile.duration <= 0
            || profile.duration > MaidMealTuning.MAX_DURATION_THRESHOLD) return null;
        {
            if (stack.getItem() instanceof ItemFood food) {
                String id = Item.itemRegistry.getNameForObject(food);
                boolean vanilla = id != null && id.startsWith(MaidMealIds.ID_MINECRAFT);
                if (!vanilla && (!inherited(
                    food.getClass(),
                    MaidMealIds.ID_ONEATEN,
                    MaidMealReflectionSpec.SRG_MEMBER_FUNC_77654_B)
                    || !inherited(
                        food.getClass(),
                        MaidMealIds.ID_ONFOODEATEN,
                        MaidMealReflectionSpec.SRG_MEMBER_FUNC_77849_C)))
                    return null;
                if (stack.getItem()
                    .hasContainerItem(stack))
                    profile.container = stack.getItem()
                        .getContainerItem(stack.copy());
                if (stack.getItem() == Items.mushroom_stew) profile.container = new ItemStack(Items.bowl);
                int idEffect = ObfuscationReflectionHelper.getPrivateValue(
                    ItemFood.class,
                    food,
                    MaidMealIds.ID_POTIONID,
                    MaidMealReflectionSpec.SRG_MEMBER_FIELD_77851_CA);
                int seconds = ObfuscationReflectionHelper.getPrivateValue(
                    ItemFood.class,
                    food,
                    MaidMealIds.ID_POTIONDURATION,
                    MaidMealReflectionSpec.SRG_MEMBER_FIELD_77850_CB);
                int amplifier = ObfuscationReflectionHelper.getPrivateValue(
                    ItemFood.class,
                    food,
                    MaidMealIds.ID_POTIONAMPLIFIER,
                    MaidMealReflectionSpec.SRG_MEMBER_FIELD_77857_CC);
                float chance = ObfuscationReflectionHelper.getPrivateValue(
                    ItemFood.class,
                    food,
                    MaidMealIds.ID_POTIONEFFECTPROBABILITY,
                    MaidMealReflectionSpec.SRG_MEMBER_FIELD_77858_CD);
                profile.effect(
                    idEffect,
                    seconds * MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM,
                    amplifier,
                    chance,
                    false);
                if (food == Items.golden_apple) {
                    profile.effect(
                        Potion.field_76444_x.id,
                        MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_2,
                        MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE,
                        MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                        false);
                    if (stack.getItemDamage() > 0) {
                        profile.effects.clear();
                        profile.effect(
                            Potion.field_76444_x.id,
                            MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_2,
                            MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE,
                            MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                            false);
                        profile.effect(
                            Potion.regeneration.id,
                            MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_3,
                            MaidMealTuning.FOOD_PROFILE_EFFECT_THIRD_PARAM,
                            MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                            false);
                        profile.effect(
                            Potion.resistance.id,
                            MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_4,
                            MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE,
                            MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                            false);
                        profile.effect(
                            Potion.fireResistance.id,
                            MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_4,
                            MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE,
                            MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                            false);
                    }
                }
                if (food == Items.fish && stack.getItemDamage() == MaidMealTuning.TARGET_GET_ITEM_DAMAGE_THRESHOLD) {
                    profile.effect(
                        Potion.poison.id,
                        MaidMealTuning.MAX_DURATION_THRESHOLD,
                        MaidMealTuning.TARGET_GET_ITEM_DAMAGE_THRESHOLD,
                        MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                        false);
                    profile.effect(
                        Potion.hunger.id,
                        MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_5,
                        MaidMealTuning.FOOD_PROFILE_TOTAL,
                        MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                        false);
                    profile.effect(
                        Potion.confusion.id,
                        MaidMealTuning.FOOD_PROFILE_EFFECT_SECOND_PARAM_5,
                        MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                        MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                        false);
                }
                return profile;
            }
            return IntegrationGuard.call(ExternalFeature.GREGTECH_FOOD, () -> gregtech(stack, profile), () -> null);
        }
    }

    private static MaidFoodProfile gregtech(ItemStack stack, MaidFoodProfile profile)
        throws ReflectiveOperationException {
        var data = CompatServices.nutrition()
            .inspectCustomFood(stack);
        if (data == null) return null;
        profile.container = data.emptyContainerCopy();
        profile.milk = data.curesMilk;
        profile.requiresGregtech = true;
        for (var effect : data.effects)
            profile.effect(effect.potionId, effect.durationTicks, effect.amplifier, effect.probability, effect.ambient);
        return profile;
    }

    /** 上游呼吸任务先选双手，再查背包；普通药水不参与日常进食 */
    public static MaidFoodProfile inspectBreathing(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return null;
        if (stack.getItem() == Items.potionitem && !ItemPotion.isSplash(stack.getItemDamage())) {
            var effects = Items.potionitem.getEffects(stack);
            if (effects == null) return null;
            var profile = new MaidFoodProfile(
                stack,
                MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE,
                MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE);
            profile.requiresNutrition = false;
            profile.container = new ItemStack(Items.glass_bottle);
            for (PotionEffect effect : effects) profile.effect(
                effect.getPotionID(),
                effect.getDuration(),
                effect.getAmplifier(),
                MaidMealTuning.IS_FINITE_SINGLE_UNIT,
                effect.getIsAmbient());
            return profile.providesAir() ? profile : null;
        }
        var profile = inspect(stack);
        return profile != null && profile.providesAir() ? profile : null;
    }

    private boolean providesAir() {
        for (Effect effect : effects) if (effect.id == Potion.waterBreathing.id) return true;
        return false;
    }

    private static boolean inherited(Class<?> type, String mcp, String srg) {
        while (type != ItemFood.class && type != null) {
            for (Method method : type.getDeclaredMethods()) if ((method.getName()
                .equals(mcp)
                || method.getName()
                    .equals(srg))
                && method.getParameterTypes().length == MaidMealTuning.TARGET_GET_ITEM_DAMAGE_THRESHOLD
                && method.getParameterTypes()[MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE] == ItemStack.class
                && method.getParameterTypes()[MaidMealTuning.IS_FINITE_SINGLE_UNIT] == World.class
                && method.getParameterTypes()[MaidMealTuning.FOOD_PROFILE_TOTAL] == EntityPlayer.class) return false;
            type = type.getSuperclass();
        }
        return type == ItemFood.class;
    }

    private void effect(int id, int ticks, int amplifier, float chance, boolean ambient) {
        if (id > 0 && id < Potion.potionTypes.length && Potion.potionTypes[id] != null && ticks > 0 && chance > 0)
            effects.add(new Effect(id, ticks, amplifier, chance, ambient));
    }

    public boolean available() {
        return (!requiresNutrition || IntegrationGuard.available(ExternalFeature.NUTRITION))
            && (!requiresGregtech || IntegrationGuard.available(ExternalFeature.GREGTECH_FOOD));
    }

    public ItemStack container() {
        return container == null ? null : container.copy();
    }

    public void applyEffects(EntityMaid maid) {
        if (milk) maid.curePotionEffects(new ItemStack(Items.milk_bucket));
        for (Effect effect : effects) if (maid.getRNG()
            .nextFloat() < effect.chance)
            maid.addPotionEffect(new PotionEffect(effect.id, effect.ticks, effect.amplifier, effect.ambient));
    }

    private static final class Effect {

        final int id, ticks, amplifier;
        final float chance;
        final boolean ambient;

        Effect(int id, int ticks, int amplifier, float chance, boolean ambient) {
            this.id = id;
            this.ticks = ticks;
            this.amplifier = amplifier;
            this.chance = chance;
            this.ambient = ambient;
        }
    }
}
