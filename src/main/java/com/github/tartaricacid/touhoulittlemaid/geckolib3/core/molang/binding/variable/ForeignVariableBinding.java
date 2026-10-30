/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/binding/variable/ForeignVariableBinding.
 * java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.variable;

import java.util.HashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.StringPool;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.Variable;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;

@SuppressWarnings("MapOrSetKeyShouldOverrideHashCodeEquals")
public class ForeignVariableBinding implements ObjectBinding {

    private final Map<Integer, ForeignVariable> variableMap = new HashMap<>();

    @Override
    public Object getProperty(String name) {
        return variableMap.computeIfAbsent(StringPool.computeIfAbsent(name), ForeignVariable::new);
    }

    public void reset() {
        variableMap.clear();
    }

    private static final class ForeignVariable implements Variable {

        private final int name;

        private ForeignVariable(int name) {
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object evaluate(final ExecutionContext<?> context) {
            IForeignVariableStorage storage = ((IContext<Object>) context.entity()).foreignStorage();
            if (storage != null) {
                return storage.getPublic(name);
            } else {
                return null;
            }
        }
    }
}
