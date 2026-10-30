/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/builtin/math/DieRoll.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math;

import java.util.Random;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.function.ContextFunction;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangRandom;

public class DieRoll extends ContextFunction<Object> {

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 3;
    }

    @Override
    protected Object eval(ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        int i = MolangRandom.rollCount(arguments.getAsInt(context, 0));
        double min = arguments.getAsDouble(context, 1);
        double range = arguments.getAsDouble(context, 2);
        if (min > range) {
            double temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        double total = 0;
        Random rnd = context.entity()
            .random();
        while (i-- > 0) {
            total += min + rnd.nextDouble() * range;
        }
        return total;
    }
}
