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
        toolType = type(GregTechCombatReflectionSpec.TARGET_CLASS_META_GENERATED_TOOL01);
        var generated = type(GregTechCombatReflectionSpec.TARGET_CLASS_META_GENERATED_TOOL);
        var base = type(GregTechCombatReflectionSpec.TARGET_CLASS_META_BASE_ITEM);
        statsType = type(GregTechCombatReflectionSpec.TARGET_CLASS_I_TOOL_STATS);
        knifeStatsType = type(GregTechCombatReflectionSpec.TARGET_CLASS_TOOL_KNIFE);
        butcheryStatsType = type(GregTechCombatReflectionSpec.TARGET_CLASS_TOOL_BUTCHERY_KNIFE);
        var materials = type(GregTechCombatReflectionSpec.TARGET_CLASS_MATERIALS);
        var metadata = type(GregTechCombatReflectionSpec.TARGET_CLASS_ID_META_TOOL01);
        instance = publicField(toolType, GregTechCombatIds.KEY_INSTANCE, toolType, true);
        registeredStats = generated.getField(GregTechCombatIds.ID_MTOOLSTATS);
        if (!Modifier.isPublic(registeredStats.getModifiers()) || Modifier.isStatic(registeredStats.getModifiers())
            || !Map.class.isAssignableFrom(registeredStats.getType()))
            throw new NoSuchFieldException(GregTechCombatDiagnostics.MSG_UNEXPECTED_PUBLIC_GREGTECH_TOOL_REGISTRATION);
        nullMaterial = publicField(materials, GregTechCombatIds.ID_NULL, materials, true);
        var id = publicField(metadata, GregTechCombatIds.KEY_ID, int.class, false);
        knifeMetadata = id.getInt(publicField(metadata, GregTechCombatIds.KEY_KNIFE, metadata, true).get(null));
        butcheryMetadata = id.getInt(publicField(metadata, GregTechCombatIds.KEY_BUTCHERYKNIFE, metadata, true).get(null));
        toolStats = publicMethod(generated, GregTechCombatIds.ID_GETTOOLSTATS, statsType, false, ItemStack.class);
        electricStats = publicMethod(generated, GregTechCombatIds.ID_GETELECTRICSTATS, Long[].class, false, ItemStack.class);
        combatDamage = publicMethod(generated, GregTechCombatIds.ID_GETTOOLCOMBATDAMAGE, float.class, false, ItemStack.class);
        maximumDamage = publicMethod(generated, GregTechCombatIds.ID_GETTOOLMAXDAMAGE, long.class, true, ItemStack.class);
        currentDamage = publicMethod(generated, GregTechCombatIds.ID_GETTOOLDAMAGE, long.class, true, ItemStack.class);
        primaryMaterial = publicMethod(generated, GregTechCombatIds.ID_GETPRIMARYMATERIAL, materials, true, ItemStack.class);
        damageTool = publicMethod(generated, GregTechCombatIds.ID_DODAMAGE, boolean.class, false, ItemStack.class, long.class);
        forEachBehavior = publicMethod(base, GregTechCombatIds.ID_FOREACHBEHAVIOR, boolean.class, false, ItemStack.class, Predicate.class);
        entityAttackCost = publicMethod(statsType, GregTechCombatIds.ID_GETTOOLDAMAGEPERENTITYATTACK, int.class, false);
        hurtResistance = publicMethod(statsType, GregTechCombatIds.ID_GETHURTRESISTANCETIME, int.class, false, int.class, Entity.class);
        damageSource = publicMethod(
            statsType,
            GregTechCombatIds.ID_GETDAMAGESOURCE,
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
            throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_TOOL_REGISTRATION_IS_NO);
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
            throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_PUBLIC_TOOL_REGISTRATION_CONTAINS);
        return stats;
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, GregTechWeaponApi.class.getClassLoader());
    }

    private static Field publicField(Class<?> owner, String name, Class<?> expected, boolean isStatic)
        throws NoSuchFieldException {
        var field = owner.getField(name);
        if (!Modifier.isPublic(field.getModifiers()) || Modifier.isStatic(field.getModifiers()) != isStatic
            || field.getType() != expected) throw new NoSuchFieldException(GregTechCombatDiagnostics.MSG_UNEXPECTED_GREGTECH_PUBLIC_FIELD + name);
        return field;
    }

    private static Method publicMethod(Class<?> owner, String name, Class<?> result, boolean isStatic,
        Class<?>... parameters) throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()) != isStatic
            || method.getReturnType() != result)
            throw new NoSuchMethodException(GregTechCombatDiagnostics.MSG_UNEXPECTED_GREGTECH_PUBLIC_METHOD + name);
        return method;
    }

    private static Object read(Field field, Object receiver) {
        try {
            return field.get(receiver);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_PUBLIC_FIELD_BECAME_INACCESSIBLE, failure);
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
            throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_PUBLIC_API_INVOCATION_FAILED, cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(GregTechCombatDiagnostics.MSG_GREGTECH_PUBLIC_METHOD_BECAME_INACCESSIBLE, failure);
        }
    }

    private static final class Holder {

        static final GregTechWeaponApi API = load();

        private static GregTechWeaponApi load() {
            try {
                return new GregTechWeaponApi();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(GregTechCombatDiagnostics.MSG_SUPPORTED_GREGTECH_PUBLIC_WEAPON_API, failure);
            }
        }
    }
}
