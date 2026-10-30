/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/value/DoubleValue.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value;

import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

public class DoubleValue implements IValue {

    public static final DoubleValue ONE = new DoubleValue(1);
    public static final DoubleValue ZERO = new DoubleValue(0);

    private final double value;

    public DoubleValue(double value) {
        this.value = value;
    }

    @Override
    public double evalAsDouble(ExpressionEvaluator<?> evaluator) {
        return value;
    }

    @Override
    public boolean evalAsBoolean(ExpressionEvaluator<?> evaluator) {
        return value != 0;
    }

    @Override
    public Object evalUnsafe(ExpressionEvaluator<?> evaluator) {
        return value;
    }

    public double value() {
        return value;
    }
}
