package com.tianluoqaq.tlmnh.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** 目标渲染平台的窄状态适配，缓存成员查找，不引入Angelica打包依赖 */
final class MaidRenderPlatformState {

    private static Bindings bindings;
    private static ClassLoader providerLoader = MaidRenderPlatformState.class.getClassLoader();

    private MaidRenderPlatformState() {}

    private static Bindings bindings() {
        if (bindings == null) bindings = new Bindings();
        return bindings;
    }

    static void preflight() {
        var state = bindings();
        matrixDepth(GL11.GL_MODELVIEW_STACK_DEPTH);
        new FilterWindow(state);
    }

    static int matrixDepth(int compatibilityName) {
        try {
            var angelica = bindings().angelica;
            int depth;
            if (angelica == null) depth = GL11.glGetInteger(compatibilityName);
            else {
                Object stack = invoke(angelica.matrixStack, null);
                depth = (Integer) invoke(angelica.matrixDepth, null, stack);
            }
            if (depth < 1 || depth > 129)
                throw new IllegalStateException("Invalid native matrix stack depth: " + depth);
            return depth;
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            MaidRenderProtection.failed(failure);
            throw failure;
        }
    }

    static FilterWindow captureFilterWindow() {
        try {
            return new FilterWindow(bindings());
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            MaidRenderProtection.failed(failure);
            throw failure;
        }
    }

    private static final class Bindings {

        private final Field vanillaMin = vanillaField("field_147958_e");
        private final Field vanillaMag = vanillaField("field_147956_f");
        private final Field vanillaAniso = vanillaField("field_152779_g");
        private final Angelica angelica = Angelica.find();
    }

    private static Field vanillaField(String name) {
        var field = ReflectionHelper.findField(
            TextureUtil.class,
            ObfuscationReflectionHelper.remapFieldNames(TextureUtil.class.getName(), name));
        if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
            || field.getType() != (name.equals("field_152779_g") ? float.class : int.class))
            throw new IllegalStateException("Unsupported vanilla filter field: " + name);
        return field;
    }

    static final class FilterWindow implements AutoCloseable {

        private final Bindings state;
        private final Object vanillaMin, vanillaMag, vanillaAniso;
        private final Object context;
        private final Object[] saved;

        private FilterWindow(Bindings state) {
            this.state = state;
            vanillaMin = read(state.vanillaMin, null);
            vanillaMag = read(state.vanillaMag, null);
            vanillaAniso = read(state.vanillaAniso, null);
            context = state.angelica == null ? null : invoke(state.angelica.context, null);
            if (state.angelica != null && context == null) throw new IllegalStateException("Missing Angelica context");
            if (context == null) saved = new Object[0];
            else {
                saved = new Object[state.angelica.filterFields.length];
                for (int i = 0; i < saved.length; i++) saved[i] = read(state.angelica.filterFields[i], context);
            }
        }

        @Override
        public void close() {
            restore();
        }

        void restore() {
            Throwable failure = null;
            failure = restoreField(state.vanillaMin, null, vanillaMin, failure);
            failure = restoreField(state.vanillaMag, null, vanillaMag, failure);
            failure = restoreField(state.vanillaAniso, null, vanillaAniso, failure);
            for (int i = 0; i < saved.length; i++)
                failure = restoreField(state.angelica.filterFields[i], context, saved[i], failure);
            if (failure != null) MaidRenderProtection.failed(failure);
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
            Class<?> matrices;
            try {
                matrices = Class.forName("com.gtnewhorizons.angelica.glsm.GLSMMatrices", true, providerLoader);
            } catch (ClassNotFoundException legacy) {
                matrices = manager;
            }
            matrixStack = matrices.getMethod("getMatrixStack");
            matrixDepth = matrices.getMethod("getMatrixStackDepth", matrixStack.getReturnType());
            context = manager.getMethod("ctx");
            if (matrixDepth.getReturnType() != int.class || !Modifier.isStatic(matrixStack.getModifiers())
                || !Modifier.isStatic(matrixDepth.getModifiers())
                || !Modifier.isStatic(context.getModifiers()))
                throw new IllegalStateException("Unsupported Angelica render method contract");
            Class<?> contextType = context.getReturnType();
            filterFields = new Field[] { contextType.getField("savedFilterTextureId"),
                contextType.getField("savedFilterMin"), contextType.getField("savedFilterMag"),
                contextType.getField("savedFilterAniso") };
            for (int i = 0; i < filterFields.length; i++) {
                var field = filterFields[i];
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                    || field.getType() != (i == 3 ? float.class : int.class))
                    throw new IllegalStateException("Unsupported Angelica filter field contract");
            }
        }

        private static Angelica find() {
            Class<?> manager;
            try {
                manager = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager", true, providerLoader);
            } catch (ClassNotFoundException absent) {
                if (Loader.isModLoaded("angelica"))
                    throw new IllegalStateException("Angelica render API missing", absent);
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
