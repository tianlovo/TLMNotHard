package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.StringPool;
import com.github.tartaricacid.touhoulittlemaid.molang.MolangEngine;
import com.github.tartaricacid.touhoulittlemaid.molang.lexer.MolangLexer;
import com.github.tartaricacid.touhoulittlemaid.molang.lexer.TokenKind;
import com.github.tartaricacid.touhoulittlemaid.molang.parser.ParseException;
import com.github.tartaricacid.touhoulittlemaid.molang.parser.ast.Expression;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.AssignableVariable;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.Function;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.HashMapStruct;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.Variable;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.StandardBindings;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ValueConversions;

/** 保留上游表达式语义；查询绑定、时间轴与实际模型驱动另行验证 */
public class MolangEngineTest {

    @Test
    public void arithmeticAndNestedConditionsRespectPrecedence() throws Exception {
        var harness = new Harness();
        assertEquals(7, harness.number("1 + 2 * 3"), 0);
        assertEquals(9, harness.number("(1 + 2) * 3"), 0);
        assertEquals(-5, harness.number("-2 * 3 + +1"), 0);
        assertEquals(4, harness.number("false ? 1 : true ? 4 : 8"), 0);
        assertEquals(6, harness.number("true ? false ? 2 : 6 : 8"), 0);
        assertEquals(0, harness.number("1 / 0"), 0);
        assertEquals(1, harness.number("2 <= 2 && 3 > 2 && 4 != 5"), 0);
    }

    @Test
    public void shortCircuitAndCoalescingSkipUnusedBranches() throws Exception {
        var harness = new Harness();
        assertFalse(ValueConversions.asBoolean(harness.evaluate("false && q.bump")));
        assertTrue(ValueConversions.asBoolean(harness.evaluate("true || q.bump")));
        assertEquals(0, harness.state.reads);
        assertEquals(11, harness.number("v.missing ?? 11"), 0);
        assertEquals(0, harness.number("0 ?? q.bump"), 0);
        assertEquals(4, harness.number("true ? 4 : q.bump"), 0);
        assertEquals(0, harness.state.reads);
        assertEquals(1, harness.number("true && q.bump"), 0);
        assertEquals(1, harness.state.reads);
    }

    @Test
    public void assignmentsAndReturnRetainFrameLocalValues() throws Exception {
        var harness = new Harness();
        assertEquals(9, harness.number("v.count = 3; v.count = v.count * 3; return v.count; v.count = 100"), 0);
        assertEquals(9, ValueConversions.asDouble(harness.state.values.get("count")), 0);
        assertEquals("maid", harness.evaluate("return 'maid'"));
        assertTrue(ValueConversions.asBoolean(harness.evaluate("'maid' == 'maid'")));
        assertFalse(ValueConversions.asBoolean(harness.evaluate("'maid' == 'fox'")));
    }

    @Test
    public void compiledExpressionsUseTheCurrentMaidContext() throws Exception {
        var harness = new Harness();
        var expressions = harness.engine.parse("return v.age * 2 + q.bump");
        var first = new State();
        var second = new State();
        first.values.put("age", 3D);
        second.values.put("age", 8D);
        assertEquals(7, ValueConversions.asDouble(evaluate(expressions, first)), 0);
        assertEquals(17, ValueConversions.asDouble(evaluate(expressions, second)), 0);
        assertEquals(8, ValueConversions.asDouble(evaluate(expressions, first)), 0);
        assertEquals(2, first.reads);
        assertEquals(1, second.reads);
    }

    @Test
    public void structAssignmentsCopyPropertiesBeforeLaterMutation() throws Exception {
        var harness = new Harness();
        assertEquals(3, harness.number("v.source.x = 3; v.copy = v.source; v.source.x = 9; return v.copy.x"), 0);
        assertEquals(9, harness.number("v.source.x"), 0);
        var original = new HashMapStruct();
        int x = StringPool.computeIfAbsent("x");
        original.putProperty(x, 2D);
        var copy = original.copy();
        original.putProperty(x, 4D);
        assertEquals(2, ValueConversions.asDouble(copy.getProperty(x)), 0);
        assertTrue(
            copy.toString()
                .contains("x=2.0"));
    }

    @Test
    public void loopsKeepTheUpstreamBoundAndHonorBreakAndContinue() throws Exception {
        var harness = new Harness();
        assertEquals(1024, harness.number("v.n = 0; loop(1000000, {v.n = v.n + 1;}); return v.n"), 0);
        assertEquals(3, harness.number("v.n = 0; loop(10, {v.n = v.n + 1; v.n == 3 ? break;}); return v.n"), 0);
        assertEquals(0, harness.number("v.n = 0; loop(3, {continue; v.n = 100;}); return v.n"), 0);
        assertEquals(
            15,
            harness.number("v.n = 0; loop(10, {loop(10, {v.n = v.n + 1; v.n > 5 ? break;});}); return v.n"),
            0);
        harness.state.values.put("items", new Object[] { 2D, 3D, 4D });
        assertEquals(9, harness.number("v.n = 0; for_each(v.item, v.items, {v.n = v.n + v.item;}); return v.n"), 0);
        assertEquals(3, harness.number("v.n = 3; loop(2, {}); return v.n"), 0);
    }

    @Test
    public void functionArgumentsAreEvaluatedInTheCallingContext() throws Exception {
        var harness = new Harness();
        assertEquals(8, harness.number("sum(3, 2 + 3)"), 0);
        assertThrows(ParseException.class, () -> harness.engine.parse("sum(1)"));
        assertThrows(ParseException.class, () -> harness.engine.parse("sum(1, 2, 3)"));
    }

    @Test
    public void malformedInputReportsItsPosition() throws Exception {
        var harness = new Harness();
        var failure = assertThrows(ParseException.class, () -> harness.engine.parse("1 +\n )"));
        assertNotNull(failure.cursor());
        assertTrue(
            failure.getMessage()
                .contains("line 1"));
        assertThrows(ParseException.class, () -> harness.engine.parse("'unclosed"));
        assertThrows(ParseException.class, () -> harness.engine.parse("1 & 2"));
        var tokens = MolangLexer.tokenizeAll(".5 + 1.25 >= 1 && true");
        assertEquals(
            TokenKind.FLOAT,
            tokens.get(0)
                .kind());
        assertEquals(
            ".5",
            tokens.get(0)
                .value());
    }

    private static Object evaluate(List<Expression> expressions, State state) {
        var evaluator = ExpressionEvaluator.evaluator(state);
        Object last = 0D;
        for (var expression : expressions) {
            last = evaluator.eval(expression);
            var result = evaluator.popReturnValue();
            if (result != null) return result;
        }
        return last;
    }

    private static final class State {

        private final Map<String, Object> values = new HashMap<>();
        private int reads;
    }

    private static final class Harness {

        private final State state = new State();
        private final MolangEngine engine;

        private Harness() {
            ObjectBinding variables = name -> new AssignableVariable() {

                @Override
                public void assign(ExecutionContext<?> context, Object value) {
                    ((State) context.entity()).values.put(name, value);
                }

                @Override
                public Object evaluate(ExecutionContext<?> context) {
                    return ((State) context.entity()).values.get(name);
                }
            };
            ObjectBinding query = name -> "bump".equals(name) ? (Variable) context -> ++((State) context.entity()).reads
                : null;
            Function sum = new Function() {

                @Override
                public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
                    return arguments.getAsDouble(context, 0) + arguments.getAsDouble(context, 1);
                }

                @Override
                public boolean validateArgumentSize(int size) {
                    return size == 2;
                }
            };
            engine = MolangEngine.fromCustomBinding(name -> {
                switch (name) {
                    case "v":
                        return variables;
                    case "q":
                        return query;
                    case "loop":
                        return StandardBindings.LOOP_FUNC;
                    case "for_each":
                        return StandardBindings.FOR_EACH_FUNC;
                    case "sum":
                        return sum;
                    default:
                        return null;
                }
            });
        }

        private Object evaluate(String code) throws ParseException {
            return MolangEngineTest.evaluate(engine.parse(code), state);
        }

        private double number(String code) throws ParseException {
            return ValueConversions.asDouble(evaluate(code));
        }
    }
}
