package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.function.Function;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.ContextBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.variable.LambdaVariable;

/** 内置动画各命名空间共用的类型边界，避免把游戏实体带入求值器 */
public abstract class MolangFrameBinding extends ContextBinding {

    protected final void frameVar(String name, Function<MolangQueryFrame, Object> evaluator) {
        constValue(name, new LambdaVariable<MolangQueryFrame>(ctx -> evaluator.apply(ctx.entity())) {

            @Override
            protected boolean validateContext(IContext<?> context) {
                return context.entity() instanceof MolangQueryFrame;
            }
        });
    }
}
