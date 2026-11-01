package com.tianluoqaq.tlmnh.common.life.meal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import squeek.applecore.api.AppleCoreAPI;

/** GTNH食物数据适配，不把女仆伪装成玩家执行onEaten */
public final class MaidFoodProfile {

    public final float total;
    public final int duration;
    private ItemStack container;
    private boolean milk;
    private final List<Effect> effects = new ArrayList<>();
    private static boolean warned;

    private MaidFoodProfile(ItemStack stack, int hunger, float saturation) {
        total = hunger + hunger * saturation * 2;
        duration = stack.getMaxItemUseDuration();
    }

    public static MaidFoodProfile inspect(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return null;
        EnumAction action = stack.getItemUseAction();
        if (action != EnumAction.eat && action != EnumAction.drink) return null;
        var values = AppleCoreAPI.accessor.getFoodValues(stack);
        if (values == null || values.hunger < 0 || !Float.isFinite(values.saturationModifier)) return null;
        var profile = new MaidFoodProfile(stack, values.hunger, values.saturationModifier);
        if (!Float.isFinite(profile.total) || profile.total < 0 || profile.duration <= 0 || profile.duration > 1200)
            return null;
        try {
            if (stack.getItem() instanceof ItemFood food) {
                String id = net.minecraft.item.Item.itemRegistry.getNameForObject(food);
                boolean vanilla = id != null && id.startsWith("minecraft:");
                if (!vanilla && (!inherited(food.getClass(), "onEaten", "func_77654_b")
                    || !inherited(food.getClass(), "onFoodEaten", "func_77849_c"))) return null;
                if (stack.getItem()
                    .hasContainerItem(stack))
                    profile.container = stack.getItem()
                        .getContainerItem(stack.copy());
                if (stack.getItem() == Items.mushroom_stew) profile.container = new ItemStack(Items.bowl);
                int idEffect = ObfuscationReflectionHelper
                    .getPrivateValue(ItemFood.class, food, "potionId", "field_77851_ca");
                int seconds = ObfuscationReflectionHelper
                    .getPrivateValue(ItemFood.class, food, "potionDuration", "field_77850_cb");
                int amplifier = ObfuscationReflectionHelper
                    .getPrivateValue(ItemFood.class, food, "potionAmplifier", "field_77857_cc");
                float chance = ObfuscationReflectionHelper
                    .getPrivateValue(ItemFood.class, food, "potionEffectProbability", "field_77858_cd");
                profile.effect(idEffect, seconds * 20, amplifier, chance, false);
                if (food == Items.golden_apple) {
                    profile.effect(Potion.field_76444_x.id, 2400, 0, 1, false);
                    if (stack.getItemDamage() > 0) {
                        profile.effects.clear();
                        profile.effect(Potion.field_76444_x.id, 2400, 0, 1, false);
                        profile.effect(Potion.regeneration.id, 600, 4, 1, false);
                        profile.effect(Potion.resistance.id, 6000, 0, 1, false);
                        profile.effect(Potion.fireResistance.id, 6000, 0, 1, false);
                    }
                }
                if (food == Items.fish && stack.getItemDamage() == 3) {
                    profile.effect(Potion.poison.id, 1200, 3, 1, false);
                    profile.effect(Potion.hunger.id, 300, 2, 1, false);
                    profile.effect(Potion.confusion.id, 300, 1, 1, false);
                }
                return profile;
            }
            // 已核 GT5 5.09.54.265：食物表公开，效果与容器沿用固定字段，没有 NPC 接口
            Class<?> meta = Class.forName("gregtech.api.items.MetaGeneratedItem");
            if (!meta.isInstance(stack.getItem())) return null;
            var stats = (Map<?, ?>) meta.getField("mFoodStats")
                .get(stack.getItem());
            Object stat = stats.get((short) stack.getItemDamage());
            if (stat == null || !stat.getClass()
                .getName()
                .equals("gregtech.api.util.GTFoodStat")) return null;
            if ((boolean) field(stat, "mExplosive")) return null;
            var empty = (ItemStack) field(stat, "mEmptyContainer");
            profile.container = empty == null ? null : empty.copy();
            profile.milk = (boolean) field(stat, "mMilk");
            int[] potions = (int[]) field(stat, "mPotionEffects");
            boolean ambient = (boolean) field(stat, "mInvisibleParticles");
            for (int i = 3; i < potions.length; i += 4)
                profile.effect(potions[i - 3], potions[i - 2], potions[i - 1], potions[i] / 100F, ambient);
            return profile;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!warned) {
                warned = true;
                TLMNotHard.LOG.warn("Food data unavailable; affected food is not consumed", failure);
            }
            return null;
        }
    }

    /** 上游呼吸任务先选双手，再查背包；普通药水不参与日常进食 */
    public static MaidFoodProfile inspectBreathing(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return null;
        if (stack.getItem() == Items.potionitem && !ItemPotion.isSplash(stack.getItemDamage())) {
            var effects = Items.potionitem.getEffects(stack);
            if (effects == null) return null;
            var profile = new MaidFoodProfile(stack, 0, 0);
            profile.container = new ItemStack(Items.glass_bottle);
            for (PotionEffect effect : effects) profile
                .effect(effect.getPotionID(), effect.getDuration(), effect.getAmplifier(), 1, effect.getIsAmbient());
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
                && method.getParameterTypes().length == 3
                && method.getParameterTypes()[0] == ItemStack.class
                && method.getParameterTypes()[1] == World.class
                && method.getParameterTypes()[2] == EntityPlayer.class) return false;
            type = type.getSuperclass();
        }
        return type == ItemFood.class;
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private void effect(int id, int ticks, int amplifier, float chance, boolean ambient) {
        if (id > 0 && id < Potion.potionTypes.length && Potion.potionTypes[id] != null && ticks > 0 && chance > 0)
            effects.add(new Effect(id, ticks, amplifier, chance, ambient));
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
