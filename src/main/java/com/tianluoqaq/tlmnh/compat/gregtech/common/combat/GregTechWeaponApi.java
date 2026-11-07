package com.tianluoqaq.tlmnh.compat.gregtech.common.combat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 固定GT版本的公开API薄层，避免编译器解析工具类的可选模组父接口 */
final class GregTechWeaponApi {

    private static final Predicate<Object> ANY_BEHAVIOR = ignored -> true;
    private final Class<?> toolType, statsType, knifeStatsType, butcheryStatsType;
    private final Field instance, registeredStats, nullMaterial;
    private final Method toolStats, electricStats, combatDamage, maximumDamage, currentDamage, primaryMaterial,
        damageTool, forEachBehavior, entityAttackCost, hurtResistance, damageSource;
    final int knifeMetadata, butcheryMetadata;

    private GregTechWeaponApi() throws ReflectiveOperationException {
        toolType = type("gregtech.common.items.MetaGeneratedTool01");
        var generated = type("gregtech.api.items.MetaGeneratedTool");
        var base = type("gregtech.api.items.MetaBaseItem");
        statsType = type("gregtech.api.interfaces.IToolStats");
        knifeStatsType = type("gregtech.common.tools.ToolKnife");
        butcheryStatsType = type("gregtech.common.tools.ToolButcheryKnife");
        var materials = type("gregtech.api.enums.Materials");
        var metadata = type("gregtech.common.items.IDMetaTool01");
        instance = publicField(toolType, "INSTANCE", toolType, true);
        registeredStats = generated.getField("mToolStats");
        if (!Modifier.isPublic(registeredStats.getModifiers()) || Modifier.isStatic(registeredStats.getModifiers())
            || !Map.class.isAssignableFrom(registeredStats.getType()))
            throw new NoSuchFieldException("Unexpected public GregTech tool registration field");
        nullMaterial = publicField(materials, "_NULL", materials, true);
        var id = publicField(metadata, "ID", int.class, false);
        knifeMetadata = id.getInt(publicField(metadata, "KNIFE", metadata, true).get(null));
        butcheryMetadata = id.getInt(publicField(metadata, "BUTCHERYKNIFE", metadata, true).get(null));
        toolStats = publicMethod(generated, "getToolStats", statsType, false, ItemStack.class);
        electricStats = publicMethod(generated, "getElectricStats", Long[].class, false, ItemStack.class);
        combatDamage = publicMethod(generated, "getToolCombatDamage", float.class, false, ItemStack.class);
        maximumDamage = publicMethod(generated, "getToolMaxDamage", long.class, true, ItemStack.class);
        currentDamage = publicMethod(generated, "getToolDamage", long.class, true, ItemStack.class);
        primaryMaterial = publicMethod(generated, "getPrimaryMaterial", materials, true, ItemStack.class);
        damageTool = publicMethod(generated, "doDamage", boolean.class, false, ItemStack.class, long.class);
        forEachBehavior = publicMethod(base, "forEachBehavior", boolean.class, false, ItemStack.class, Predicate.class);
        entityAttackCost = publicMethod(statsType, "getToolDamagePerEntityAttack", int.class, false);
        hurtResistance = publicMethod(statsType, "getHurtResistanceTime", int.class, false, int.class, Entity.class);
        damageSource = publicMethod(
            statsType,
            "getDamageSource",
            DamageSource.class,
            false,
            EntityLivingBase.class,
            Entity.class);
    }

    static GregTechWeaponApi get() {
        return Holder.API;
    }

    boolean exactTool(Item item) {
        return item != null && item.getClass() == toolType && item == read(instance, null);
    }

    boolean exactStats(Object stats, boolean butchery) {
        return stats != null && stats.getClass() == (butchery ? butcheryStatsType : knifeStatsType);
    }

    Object registeredStats(Item tool, int metadata) {
        Object map = read(registeredStats, tool);
        if (!(map instanceof Map<?, ?>entries))
            throw new IllegalStateException("GregTech tool registration is no longer a public map");
        return checkedStats(entries.get((short) metadata));
    }

    Object toolStats(Item tool, ItemStack stack) {
        return checkedStats(invoke(toolStats, tool, stack));
    }

    boolean hasBehavior(Item tool, ItemStack stack) {
        return (Boolean) invoke(forEachBehavior, tool, stack, ANY_BEHAVIOR);
    }

    boolean electric(Item tool, ItemStack stack) {
        return invoke(electricStats, tool, stack) != null;
    }

    boolean missingPrimaryMaterial(ItemStack stack) {
        return invoke(primaryMaterial, null, stack) == read(nullMaterial, null);
    }

    long maximumDamage(ItemStack stack) {
        return (Long) invoke(maximumDamage, null, stack);
    }

    long currentDamage(ItemStack stack) {
        return (Long) invoke(currentDamage, null, stack);
    }

    float combatDamage(Item tool, ItemStack stack) {
        return (Float) invoke(combatDamage, tool, stack);
    }

    int entityAttackCost(Object stats) {
        return (Integer) invoke(entityAttackCost, stats);
    }

    int hurtResistance(Object stats, int original, Entity target) {
        return (Integer) invoke(hurtResistance, stats, original, target);
    }

    DamageSource damageSource(Object stats, EntityLivingBase attacker, Entity target) {
        return (DamageSource) invoke(damageSource, stats, attacker, target);
    }

    boolean damageTool(Item tool, ItemStack stack, long amount) {
        return (Boolean) invoke(damageTool, tool, stack, amount);
    }

    private Object checkedStats(Object stats) {
        if (stats != null && !statsType.isInstance(stats))
            throw new IllegalStateException("GregTech public tool registration contains an unexpected stats type");
        return stats;
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, GregTechWeaponApi.class.getClassLoader());
    }

    private static Field publicField(Class<?> owner, String name, Class<?> expected, boolean isStatic)
        throws NoSuchFieldException {
        var field = owner.getField(name);
        if (!Modifier.isPublic(field.getModifiers()) || Modifier.isStatic(field.getModifiers()) != isStatic
            || field.getType() != expected) throw new NoSuchFieldException("Unexpected GregTech public field: " + name);
        return field;
    }

    private static Method publicMethod(Class<?> owner, String name, Class<?> result, boolean isStatic,
        Class<?>... parameters) throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()) != isStatic
            || method.getReturnType() != result)
            throw new NoSuchMethodException("Unexpected GregTech public method: " + name);
        return method;
    }

    private static Object read(Field field, Object receiver) {
        try {
            return field.get(receiver);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("GregTech public field became inaccessible", failure);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause();
            IntegrationGuard.throwIfFatal(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("GregTech public API invocation failed", cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("GregTech public method became inaccessible", failure);
        }
    }

    private static final class Holder {

        static final GregTechWeaponApi API = load();

        private static GregTechWeaponApi load() {
            try {
                return new GregTechWeaponApi();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Supported GregTech public weapon API is unavailable", failure);
            }
        }
    }
}
