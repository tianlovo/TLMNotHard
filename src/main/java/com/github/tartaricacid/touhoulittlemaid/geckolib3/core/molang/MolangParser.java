/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/MolangParser.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: pure query snapshots and Java 8; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang;

import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.PrimaryBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.DoubleValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.MolangValue;
import com.github.tartaricacid.touhoulittlemaid.molang.MolangEngine;
import com.github.tartaricacid.touhoulittlemaid.molang.parser.ParseException;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;
import com.tianluoqaq.tlmnh.TLMNotHard;

public class MolangParser {

    private final MolangEngine engine;
    private final PrimaryBinding primaryBinding;

    public MolangParser(Map<String, ObjectBinding> extraBindings) {
        primaryBinding = new PrimaryBinding(extraBindings);
        engine = MolangEngine.fromCustomBinding(primaryBinding);
    }

    @SuppressWarnings("unused")
    public IValue parseExpression(String molangExpression) {
        try {
            return parseExpressionUnsafe(molangExpression);
        } catch (Exception e) {
            TLMNotHard.LOG.error("Failed to parse value \"{}\": {}", molangExpression, e.getMessage());
            return DoubleValue.ZERO;
        }
    }

    public IValue parseExpressionUnsafe(String molangExpression) throws ParseException {
        try {
            return new MolangValue(engine.parse(molangExpression));
        } finally {
            primaryBinding.popStackFrame();
        }
    }

    @SuppressWarnings("unused")
    public IValue getConstant(double value) {
        return new DoubleValue(value);
    }

    public void reset() {
        primaryBinding.reset();
    }
}
