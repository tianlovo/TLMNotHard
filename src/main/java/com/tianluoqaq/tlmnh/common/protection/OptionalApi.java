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
        Objects.requireNonNull(modLoaded, ProtectionIds.ID_MODLOADED);
        Objects.requireNonNull(modId, ProtectionIds.ID_MODID);
        Objects.requireNonNull(className, ProtectionIds.ID_CLASSNAME);
        Objects.requireNonNull(loader, ProtectionIds.ID_LOADER);
        if (!modLoaded.test(modId)) return null;
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(
                ProtectionDiagnostics.MSG_OPTIONAL_API_CLASS_MISSING_WHILE + modId
                    + ProtectionDiagnostics.MSG_IS_LOADED
                    + className,
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
        Objects.requireNonNull(fieldName, ProtectionIds.ID_FIELDNAME);
        Objects.requireNonNull(expectedType, ProtectionIds.ID_EXPECTEDTYPE);
        var owner = findClass(modLoaded, modId, className, loader);
        if (owner == null) return null;
        try {
            var field = owner.getField(fieldName);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != expectedType) {
                throw new IllegalStateException(
                    ProtectionDiagnostics.MSG_OPTIONAL_API_FIELD_SIGNATURE_MISMATCH + className
                        + ProtectionIds.FRAGMENT_PREFIX
                        + fieldName
                        + ProtectionDiagnostics.MSG_EXPECTED_STATIC
                        + expectedType.getName()
                        + ProtectionDiagnostics.MSG_FOUND
                        + Modifier.toString(field.getModifiers())
                        + ProtectionDiagnostics.SINGLE_SPACE
                        + field.getType()
                            .getName());
            }
            return MethodHandles.publicLookup()
                .unreflectGetter(field);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException(
                ProtectionDiagnostics.MSG_OPTIONAL_API_FIELD_MISSING_OR + className
                    + ProtectionIds.FRAGMENT_PREFIX
                    + fieldName,
                failure);
        }
    }

    private static MethodHandle bindMethod(Predicate<String> modLoaded, ClassLoader loader, String modId,
        String className, String methodName, MethodType expectedType, boolean expectStatic) {
        Objects.requireNonNull(methodName, ProtectionIds.ID_METHODNAME);
        Objects.requireNonNull(expectedType, ProtectionIds.ID_EXPECTEDTYPE);
        var owner = findClass(modLoaded, modId, className, loader);
        if (owner == null) return null;
        try {
            var method = owner.getMethod(methodName, expectedType.parameterArray());
            if (Modifier.isStatic(method.getModifiers()) != expectStatic
                || method.getReturnType() != expectedType.returnType()) {
                throw new IllegalStateException(
                    ProtectionDiagnostics.MSG_OPTIONAL_API_METHOD_SIGNATURE_MISMATCH + className
                        + ProtectionIds.FRAGMENT_PREFIX
                        + methodName
                        + ProtectionDiagnostics.MSG_EXPECTED
                        + (expectStatic ? ProtectionDiagnostics.MSG_STATIC : ProtectionDiagnostics.MSG_INSTANCE)
                        + expectedType
                        + ProtectionDiagnostics.MSG_FOUND
                        + Modifier.toString(method.getModifiers())
                        + ProtectionDiagnostics.SINGLE_SPACE
                        + method.getReturnType()
                            .getName());
            }
            return MethodHandles.publicLookup()
                .unreflect(method);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException(
                ProtectionDiagnostics.MSG_OPTIONAL_API_METHOD_MISSING_OR + className
                    + ProtectionIds.FRAGMENT_PREFIX
                    + methodName
                    + expectedType,
                failure);
        }
    }

    public static boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }
}
