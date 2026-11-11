package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.LinkedHashMap;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.TLMBinding;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.YSMBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.MolangParser;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;

/** 注册内置资源实际使用的 TLM 引擎命名空间，不依赖外部模型模组 */
public final class MaidMolangBindings {

    private MaidMolangBindings() {}

    public static MolangParser parser() {
        var bindings = new LinkedHashMap<String, ObjectBinding>();
        bindings.put(MolangRenderIds.ID_TLM, TLMBinding.INSTANCE);
        bindings.put(MolangRenderIds.ID_YSM, YSMBinding.INSTANCE);
        return new MolangParser(bindings);
    }
}
