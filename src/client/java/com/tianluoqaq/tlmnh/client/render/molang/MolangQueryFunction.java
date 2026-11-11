package com.tianluoqaq.tlmnh.client.render.molang;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.function.ContextFunction;

/** 与上游实体函数一样校验上下文，查询只访问已捕获的帧数据 */
public abstract class MolangQueryFunction extends ContextFunction<MolangQueryFrame> {

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof MolangQueryFrame;
    }
}
