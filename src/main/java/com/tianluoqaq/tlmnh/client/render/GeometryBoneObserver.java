package com.tianluoqaq.tlmnh.client.render;

/** 可选的只读几何观察边界；faceMasks 为实际 mesh 的独立副本，观察器不得修改模型 */
public interface GeometryBoneObserver {

    void beginBone(String boneName, int[] faceMasks);

    void endBone();
}
