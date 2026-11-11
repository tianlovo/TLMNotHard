package com.tianluoqaq.tlmnh.compat.angelica.client.query;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端只读查询，保持原生GL和Angelica版本分支 */
@SideOnly(Side.CLIENT)
public final class AngelicaRenderQueries {

    private AngelicaRenderQueries() {}

    public static Object currentShaderPack() throws ReflectiveOperationException {
        return Class.forName(AngelicaClientQueryIds.TARGET_CLASS_IRIS)
            .getMethod(AngelicaClientQueryIds.ID_GETCURRENTPACK)
            .invoke(null);
    }

    public static int clientActiveTexture() {
        try {
            var manager = Class.forName(AngelicaClientQueryReflectionSpec.TARGET_CLASS_GL_STATE_MANAGER);
            try {
                manager = Class.forName(AngelicaClientQueryReflectionSpec.TARGET_CLASS_GLSM_TEXTURES);
            } catch (ClassNotFoundException legacy) {
                // native 的查询仍在 GLStateManager；正式版拆到 GLSMTextures
            }
            return GL13.GL_TEXTURE0 + (Integer) manager.getMethod(AngelicaClientQueryIds.ID_GETCLIENTACTIVETEXTUREUNIT)
                .invoke(null);
        } catch (ClassNotFoundException absent) {
            return GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                AngelicaClientQueryDiagnostics.MSG_CANNOT_OBSERVE_ACTUAL_CLIENT_TEXTURE,
                failure);
        }
    }
}
