package com.tianluoqaq.tlmnh.client.render;

/** 模型顶点接口与 OpenGL 分开，几何、UV 和矩阵可以在无窗口环境验证 */
@FunctionalInterface
public interface LegacyVertexConsumer {

    void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
        int overlay, int lightmap, float normalX, float normalY, float normalZ);
}
