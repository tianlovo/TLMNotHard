package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.PhysicsManager;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.IBone;

/** 模型实例内的骨骼和物理状态，与该实例的变量和随机源同寿命 */
public final class MolangModelResources {

    private final PhysicsManager physics = new PhysicsManager();
    private Map<String, ? extends IBone> bones = Collections.emptyMap();

    public void bindBones(Map<String, ? extends IBone> bones) {
        this.bones = Objects.requireNonNull(bones, "bones");
        physics.reset();
    }

    public IBone bone(String name) {
        return bones.get(name);
    }

    public PhysicsManager physics() {
        return physics;
    }
}
