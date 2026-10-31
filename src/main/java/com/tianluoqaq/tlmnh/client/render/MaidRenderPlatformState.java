package com.tianluoqaq.tlmnh.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** 目标渲染平台的窄状态适配，缓存成员查找，不引入Angelica打包依赖 */
final class MaidRenderPlatformState {

    private static final Field VANILLA_MIN = vanillaField("field_147958_e");
    private static final Field VANILLA_MAG = vanillaField("field_147956_f");
    private static final Field VANILLA_ANISO = vanillaField("field_152779_g");
    private static final Angelica ANGELICA = Angelica.find();

    private MaidRenderPlatformState() {}

    static int matrixDepth(int compatibilityName) {
        if (ANGELICA == null) return GL11.glGetInteger(compatibilityName);
        Object stack = invoke(ANGELICA.matrixStack, null);
        return (Integer) invoke(ANGELICA.matrixDepth, null, stack);
    }

    static FilterWindow captureFilterWindow() {
        return new FilterWindow();
    }

    private static Field vanillaField(String name) {
        return ReflectionHelper.findField(
            TextureUtil.class,
            ObfuscationReflectionHelper.remapFieldNames(TextureUtil.class.getName(), name));
    }

    static final class FilterWindow {

        private final Object vanillaMin = read(VANILLA_MIN, null);
        private final Object vanillaMag = read(VANILLA_MAG, null);
        private final Object vanillaAniso = read(VANILLA_ANISO, null);
        private final Object context;
        private final Object[] saved;

        private FilterWindow() {
            context = ANGELICA == null ? null : invoke(ANGELICA.context, null);
            if (context == null) saved = new Object[0];
            else {
                saved = new Object[ANGELICA.filterFields.length];
                for (int i = 0; i < saved.length; i++) saved[i] = read(ANGELICA.filterFields[i], context);
            }
        }

        void restore() {
            Throwable failure = null;
            failure = restoreField(VANILLA_MIN, null, vanillaMin, failure);
            failure = restoreField(VANILLA_MAG, null, vanillaMag, failure);
            failure = restoreField(VANILLA_ANISO, null, vanillaAniso, failure);
            for (int i = 0; i < saved.length; i++)
                failure = restoreField(ANGELICA.filterFields[i], context, saved[i], failure);
            rethrow(failure);
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read native render state", failure);
        }
    }

    private static Throwable restoreField(Field field, Object owner, Object value, Throwable previous) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException | RuntimeException | Error failure) {
            if (previous == null) return failure;
            if (failure != previous) previous.addSuppressed(failure);
        }
        return previous;
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause();
            rethrow(cause);
            throw new IllegalStateException("Native render state callback failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access native render state", failure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Native render state restore failed", failure);
    }

    private static final class Angelica {

        private final Method matrixStack, matrixDepth, context;
        private final Field[] filterFields;

        private Angelica(Class<?> manager) throws ReflectiveOperationException {
            matrixStack = manager.getMethod("getMatrixStack");
            matrixDepth = manager.getMethod("getMatrixStackDepth", matrixStack.getReturnType());
            context = manager.getMethod("ctx");
            Class<?> contextType = context.getReturnType();
            filterFields = new Field[] { contextType.getField("savedFilterTextureId"),
                contextType.getField("savedFilterMin"), contextType.getField("savedFilterMag"),
                contextType.getField("savedFilterAniso") };
        }

        private static Angelica find() {
            Class<?> manager;
            try {
                manager = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
            } catch (ClassNotFoundException absent) {
                return null;
            }
            try {
                return new Angelica(manager);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Angelica native render state API", failure);
            }
        }
    }
}
