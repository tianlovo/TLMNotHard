/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/binding/variable/TempVariableBinding.
 * java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.variable;

import java.util.HashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.AssignableVariable;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;

public class TempVariableBinding implements ObjectBinding {

    private final Map<String, TempVariable> variableMap = new HashMap<>();
    private int topPointer = 0;

    @Override
    public Object getProperty(String name) {
        return variableMap.computeIfAbsent(name, k -> new TempVariable(topPointer++));
    }

    public void reset() {
        variableMap.clear();
        topPointer = 0;
    }

    private static final class TempVariable implements AssignableVariable {

        private final int address;

        private TempVariable(int address) {
            this.address = address;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object evaluate(final ExecutionContext<?> context) {
            return ((IContext<Object>) context.entity()).tempStorage()
                .getTemp(address);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void assign(ExecutionContext<?> context, Object value) {
            ((IContext<Object>) context.entity()).tempStorage()
                .setTemp(address, value);
        }
    }
}
