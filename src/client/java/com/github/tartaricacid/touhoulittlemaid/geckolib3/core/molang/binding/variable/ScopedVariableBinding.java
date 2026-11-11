/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/binding/variable/ScopedVariableBinding.
 * java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.variable;

import java.util.HashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.StringPool;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.AssignableVariable;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;

@SuppressWarnings("MapOrSetKeyShouldOverrideHashCodeEquals")
public class ScopedVariableBinding implements ObjectBinding {

    private final Map<Integer, ScopedVariable> variableMap = new HashMap<>();

    @Override
    public Object getProperty(String name) {
        return variableMap.computeIfAbsent(StringPool.computeIfAbsent(name), ScopedVariable::new);
    }

    public void reset() {
        variableMap.clear();
    }

    private static final class ScopedVariable implements AssignableVariable {

        private final int name;

        private ScopedVariable(int name) {
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object evaluate(final ExecutionContext<?> context) {
            return ((IContext<Object>) context.entity()).scopedStorage()
                .getScoped(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void assign(ExecutionContext<?> context, Object value) {
            ((IContext<Object>) context.entity()).scopedStorage()
                .setScoped(name, value);
        }
    }
}
