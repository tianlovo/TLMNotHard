package com.tianluoqaq.tlmnh.compat.angelica.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.compat.spi.RenderPlatformCompat;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.angelica.AngelicaCompat;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;

/** 目标渲染平台的窄状态适配，缓存成员查找，不引入Angelica打包依赖 */
public final class MaidRenderPlatformState {

    private static Bindings bindings;
    private static ClassLoader providerLoader = MaidRenderPlatformState.class.getClassLoader();

    private MaidRenderPlatformState() {}

    private static Bindings bindings() {
        if (bindings == null) bindings = new Bindings();
        return bindings;
    }

    public static void preflight() {
        var state = bindings();
        matrixDepth(GL11.GL_MODELVIEW_STACK_DEPTH);
        new FilterWindow(state);
    }

    public static int matrixDepth(int compatibilityName) {
        try {
            var angelica = bindings().angelica;
            int depth;
            if (angelica == null) depth = GL11.glGetInteger(compatibilityName);
            else {
                Object stack = invoke(angelica.matrixStack, null);
                depth = (Integer) invoke(angelica.matrixDepth, null, stack);
            }
            if (depth < AngelicaClientRenderLayout.MIN_DEPTH_THRESHOLD || depth > AngelicaClientRenderLayout.MAX_DEPTH_THRESHOLD)
                throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_INVALID_NATIVE_MATRIX_STACK_DEPTH + depth);
            return depth;
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            IntegrationGuard.trip(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, failure);
            throw failure;
        }
    }

    public static FilterWindow captureFilterWindow() {
        try {
            return new FilterWindow(bindings());
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            IntegrationGuard.trip(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, failure);
            throw failure;
        }
    }

    private static final class Bindings {

        private final Field vanillaMin = vanillaField(AngelicaClientRenderReflectionSpec.SRG_MEMBER_FIELD_147958_E);
        private final Field vanillaMag = vanillaField(AngelicaClientRenderReflectionSpec.SRG_MEMBER_FIELD_147956_F);
        private final Field vanillaAniso = vanillaField(AngelicaClientRenderReflectionSpec.SRG_MEMBER_FIELD_152779_G);
        private final Angelica angelica = Angelica.find();
    }

    private static Field vanillaField(String name) {
        var field = ReflectionHelper.findField(
            TextureUtil.class,
            ObfuscationReflectionHelper.remapFieldNames(TextureUtil.class.getName(), name));
        if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
            || field.getType() != (name.equals(AngelicaClientRenderReflectionSpec.SRG_MEMBER_FIELD_152779_G) ? float.class : int.class))
            throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_UNSUPPORTED_VANILLA_FILTER_FIELD + name);
        return field;
    }

    public static final class FilterWindow implements RenderPlatformCompat.FilterWindowHandle {

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
            if (state.angelica != null && context == null) throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_MISSING_ANGELICA_CONTEXT);
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

        @Override
        public void restore() {
            Throwable failure = null;
            failure = restoreField(state.vanillaMin, null, vanillaMin, failure);
            failure = restoreField(state.vanillaMag, null, vanillaMag, failure);
            failure = restoreField(state.vanillaAniso, null, vanillaAniso, failure);
            for (int i = 0; i < saved.length; i++)
                failure = restoreField(state.angelica.filterFields[i], context, saved[i], failure);
            if (failure != null) IntegrationGuard.trip(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, failure);
            rethrow(failure);
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_CANNOT_READ_NATIVE_RENDER_STATE, failure);
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
            throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_NATIVE_RENDER_STATE_CALLBACK_FAILED, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_CANNOT_ACCESS_NATIVE_RENDER_STATE, failure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_NATIVE_RENDER_STATE_RESTORE_FAILED, failure);
    }

    private static final class Angelica {

        private final Method matrixStack, matrixDepth, context;
        private final Field[] filterFields;

        private Angelica(Class<?> manager) throws ReflectiveOperationException {
            Class<?> matrices;
            try {
                matrices = Class.forName(AngelicaClientRenderReflectionSpec.TARGET_CLASS_GLSM_MATRICES, true, providerLoader);
            } catch (ClassNotFoundException legacy) {
                matrices = manager;
            }
            matrixStack = matrices.getMethod(AngelicaClientRenderIds.ID_GETMATRIXSTACK);
            matrixDepth = matrices.getMethod(AngelicaClientRenderIds.ID_GETMATRIXSTACKDEPTH, matrixStack.getReturnType());
            context = manager.getMethod(AngelicaClientRenderIds.ID_CTX);
            if (matrixDepth.getReturnType() != int.class || !Modifier.isStatic(matrixStack.getModifiers())
                || !Modifier.isStatic(matrixDepth.getModifiers())
                || !Modifier.isStatic(context.getModifiers()))
                throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_UNSUPPORTED_ANGELICA_RENDER_METHOD_CONTRACT);
            Class<?> contextType = context.getReturnType();
            filterFields = new Field[] { contextType.getField(AngelicaClientRenderIds.ID_SAVEDFILTERTEXTUREID),
                contextType.getField(AngelicaClientRenderIds.ID_SAVEDFILTERMIN), contextType.getField(AngelicaClientRenderIds.ID_SAVEDFILTERMAG),
                contextType.getField(AngelicaClientRenderIds.ID_SAVEDFILTERANISO) };
            for (int i = 0; i < filterFields.length; i++) {
                var field = filterFields[i];
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                    || field.getType() != (i == AngelicaClientRenderLayout.TARGET_I_THRESHOLD ? float.class : int.class))
                    throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_UNSUPPORTED_ANGELICA_FILTER_FIELD_CONTRACT);
            }
        }

        private static Angelica find() {
            Class<?> manager;
            try {
                manager = Class.forName(AngelicaClientRenderReflectionSpec.TARGET_CLASS_GL_STATE_MANAGER, true, providerLoader);
            } catch (ClassNotFoundException absent) {
                if (Loader.isModLoaded(AngelicaCompat.MOD_ID))
                    throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_ANGELICA_RENDER_API_MISSING, absent);
                return null;
            }
            try {
                return new Angelica(manager);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(AngelicaClientRenderDiagnostics.MSG_UNSUPPORTED_ANGELICA_NATIVE_RENDER_STATE, failure);
            }
        }
    }
}
