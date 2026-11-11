package com.tianluoqaq.tlmnh.client.compat.spi;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 渲染平台矩阵栈与纹理采样窗口契约；默认直接查询原生 OpenGL 状态 */
@SideOnly(Side.CLIENT)
public interface RenderPlatformCompat {

    FilterWindowHandle NOOP_WINDOW = () -> {};

    RenderPlatformCompat NOOP = new RenderPlatformCompat() {};

    interface FilterWindowHandle extends AutoCloseable {

        void restore();

        @Override
        default void close() {
            restore();
        }
    }

    default void preflight() {}

    default int matrixDepth(int compatibilityName) {
        int depth = GL11.glGetInteger(compatibilityName);
        if (depth < ClientCompatSpiTuning.MIN_DEPTH_THRESHOLD || depth > ClientCompatSpiTuning.MAX_DEPTH_THRESHOLD)
            throw new IllegalStateException(ClientCompatSpiDiagnostics.MSG_INVALID_NATIVE_MATRIX_STACK_DEPTH + depth);
        return depth;
    }

    default FilterWindowHandle captureFilterWindow() {
        return NOOP_WINDOW;
    }
}
