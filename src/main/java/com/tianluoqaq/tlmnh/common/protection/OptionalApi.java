package com.tianluoqaq.tlmnh.common.protection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Predicate;

import cpw.mods.fml.common.Loader;

/**
 * 可选外部模组 API 的惰性句柄绑定器
 * 模组没装时直接返回 {@code null}；模组已装却找不到类、成员或签名对不上时，明确抛错交给 {@link IntegrationGuard} 熔断
 */
public final class OptionalApi {

    private OptionalApi() {}

    public static Class<?> findClass(String modId, String className, ClassLoader loader) {
        return findClass(OptionalApi::isModLoaded, modId, className, loader);
    }

    public static Class<?> findClass(Predicate<String> modLoaded, String modId, String className, ClassLoader loader) {
        Objects.requireNonNull(modLoaded, "modLoaded");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(loader, "loader");
        if (!modLoaded.test(modId)) return null;
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(
                "Optional API class missing while mod " + modId + " is loaded: " + className,
                missing);
        }
    }

    public static MethodHandle findStatic(String modId, String className, String methodName, MethodType expectedType) {
        return findStatic(
            OptionalApi::isModLoaded,
            OptionalApi.class.getClassLoader(),
            modId,
            className,
            methodName,
            expectedType);
    }

    public static MethodHandle findStatic(Predicate<String> modLoaded, ClassLoader loader, String modId,
        String className, String methodName, MethodType expectedType) {
        return bindMethod(modLoaded, loader, modId, className, methodName, expectedType, true);
    }

    public static MethodHandle findVirtual(String modId, String className, String methodName, MethodType expectedType) {
        return findVirtual(
            OptionalApi::isModLoaded,
            OptionalApi.class.getClassLoader(),
            modId,
            className,
            methodName,
            expectedType);
    }

    public static MethodHandle findVirtual(Predicate<String> modLoaded, ClassLoader loader, String modId,
        String className, String methodName, MethodType expectedType) {
        return bindMethod(modLoaded, loader, modId, className, methodName, expectedType, false);
    }

    public static MethodHandle findStaticGetter(String modId, String className, String fieldName,
        Class<?> expectedType) {
        return findStaticGetter(
            OptionalApi::isModLoaded,
            OptionalApi.class.getClassLoader(),
            modId,
            className,
            fieldName,
            expectedType);
    }

    public static MethodHandle findStaticGetter(Predicate<String> modLoaded, ClassLoader loader, String modId,
        String className, String fieldName, Class<?> expectedType) {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(expectedType, "expectedType");
        var owner = findClass(modLoaded, modId, className, loader);
        if (owner == null) return null;
        try {
            var field = owner.getField(fieldName);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != expectedType) {
                throw new IllegalStateException(
                    "Optional API field signature mismatch on " + className
                        + "#"
                        + fieldName
                        + ": expected static "
                        + expectedType.getName()
                        + ", found "
                        + Modifier.toString(field.getModifiers())
                        + " "
                        + field.getType()
                            .getName());
            }
            return MethodHandles.publicLookup()
                .unreflectGetter(field);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException(
                "Optional API field missing or inaccessible: " + className + "#" + fieldName,
                failure);
        }
    }

    private static MethodHandle bindMethod(Predicate<String> modLoaded, ClassLoader loader, String modId,
        String className, String methodName, MethodType expectedType, boolean expectStatic) {
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(expectedType, "expectedType");
        var owner = findClass(modLoaded, modId, className, loader);
        if (owner == null) return null;
        try {
            var method = owner.getMethod(methodName, expectedType.parameterArray());
            if (Modifier.isStatic(method.getModifiers()) != expectStatic
                || method.getReturnType() != expectedType.returnType()) {
                throw new IllegalStateException(
                    "Optional API method signature mismatch on " + className
                        + "#"
                        + methodName
                        + ": expected "
                        + (expectStatic ? "static " : "instance ")
                        + expectedType
                        + ", found "
                        + Modifier.toString(method.getModifiers())
                        + " "
                        + method.getReturnType()
                            .getName());
            }
            return MethodHandles.publicLookup()
                .unreflect(method);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException(
                "Optional API method missing or inaccessible: " + className + "#" + methodName + expectedType,
                failure);
        }
    }

    private static boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }
}
