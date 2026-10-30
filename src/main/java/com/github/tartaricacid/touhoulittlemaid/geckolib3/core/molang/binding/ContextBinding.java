/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/binding/ContextBinding.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding;

import java.util.HashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.variable.IValueEvaluator;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.variable.LambdaVariable;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.Function;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;

public class ContextBinding implements ObjectBinding {

    private final Map<String, Object> bindings = new HashMap<>();

    @Override
    public Object getProperty(String name) {
        return bindings.get(name);
    }

    public void function(String name, Function function) {
        bindings.put(name, function);
    }

    public void constValue(String name, Object value) {
        bindings.put(name, value);
    }

    public void var(String name, IValueEvaluator<?, IContext<Object>> evaluator) {
        bindings.put(name, new LambdaVariable<>(evaluator));
    }

}
