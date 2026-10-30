/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/functions/BoneParamFunction.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.struct.Vec3fStruct;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.IBone;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFunction;

public abstract class BoneParamFunction extends MolangQueryFunction {

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }

    @Override
    protected Object eval(ExecutionContext<IContext<MolangQueryFrame>> context, ArgumentCollection arguments) {
        var str = arguments.getAsString(context, 0);
        if (str == null || str.isEmpty()) {
            return null;
        }

        var bone = context.entity()
            .modelResources()
            .bone(str);
        if (bone == null) {
            return null;
        }

        return getParam(bone);
    }

    protected abstract Vec3fStruct getParam(IBone bone);
}
