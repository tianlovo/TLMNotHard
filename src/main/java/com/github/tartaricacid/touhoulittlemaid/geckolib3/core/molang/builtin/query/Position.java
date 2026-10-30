/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/builtin/query/Position.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: pure query snapshots and Java 8; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFunction;

public class Position extends MolangQueryFunction {

    @Override
    protected Object eval(ExecutionContext<IContext<MolangQueryFrame>> context, ArgumentCollection arguments) {
        int axis = arguments.getAsInt(context, 0);
        if (axis < 0 || axis > 2) return null;
        return context.entity()
            .entity()
            .position(axis);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
