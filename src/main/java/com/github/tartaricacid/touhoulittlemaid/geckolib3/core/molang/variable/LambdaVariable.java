/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/variable/LambdaVariable.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.variable;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;

public class LambdaVariable<TTarget> extends ContextVariable<TTarget> {

    private final IValueEvaluator<?, IContext<TTarget>> evaluator;

    public LambdaVariable(IValueEvaluator<?, IContext<TTarget>> evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public final Object evaluate(IContext<TTarget> entityContext) {
        return evaluator.eval(entityContext);
    }
}
