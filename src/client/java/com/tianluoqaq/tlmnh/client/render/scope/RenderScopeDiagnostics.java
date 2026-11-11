package com.tianluoqaq.tlmnh.client.render.scope;

/** OpenGL 矩阵、纹理过滤与渲染状态守卫参数（Diagnostics） */
public final class RenderScopeDiagnostics {

    public static final String MSG_NATIVE_RENDER_STATE_UNAVAILABLE = "Native render state unavailable";
    public static final String MSG_UNKNOWN_ITEM_MATRIX_MODE = "Unknown item matrix mode";
    public static final String MSG_NATIVE_ITEM_RENDERER_REMOVED_CALLER = "Native item renderer removed caller matrix entries";
    public static final String MSG_UNBOUNDED_NATIVE_ITEM_MATRIX_LEAK = "Unbounded native item matrix leak";

    private RenderScopeDiagnostics() {}
}
