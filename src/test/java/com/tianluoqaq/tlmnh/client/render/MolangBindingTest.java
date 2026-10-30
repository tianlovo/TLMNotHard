package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.variable.ForeignVariableBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.variable.ScopedVariableBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.variable.TempVariableBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.MathBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.PooledStringHashSet;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.StringPool;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.DoubleValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.MolangValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.RotationValue;
import com.github.tartaricacid.touhoulittlemaid.molang.MolangEngine;
import com.github.tartaricacid.touhoulittlemaid.molang.parser.ParseException;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ValueConversions;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangRandom;

/** 覆盖真实数学绑定、变量生命周期和跨实例执行，不把表达式通过当作实际画面验收 */
public class MolangBindingTest {

    @Test
    public void allMathFunctionsKeepThePinnedUpstreamSemantics() throws Exception {
        var h = new Harness();
        Object[][] cases = { { "math.pi", Math.PI }, { "math.e", Math.E }, { "math.abs(-2)", 2D },
            { "math.floor(-1.2)", -2D }, { "math.ceil(-1.2)", -1D }, { "math.trunc(-1.8)", -1D },
            { "math.round(-1.5)", -1D }, { "math.clamp(9, 2, 4)", 4D }, { "math.clamp(-9, 2, 4)", 2D },
            { "math.min(2, -4)", -4D }, { "math.max(2, -4)", 2D }, { "math.pow(2, 3)", 8D }, { "math.exp(1)", Math.E },
            { "math.ln(math.e)", 1D }, { "math.sqrt(9)", 3D }, { "math.mod(-5, 3)", -2D }, { "math.sin(90)", 1D },
            { "math.cos(180)", -1D }, { "math.asin(1)", Math.PI / 2 }, { "math.acos(-1)", Math.PI },
            { "math.atan(1)", Math.PI / 4 }, { "math.atan2(1, 0)", Math.PI / 2 }, { "math.lerp(2, 8, 0.25)", 3.5D },
            { "math.lerprotate(170, -170, 0.5)", 180D }, { "math.min_angle(540)", -180D },
            { "math.min_angle(-190)", 170D },
            // 固定上游先 ceil 再应用三次式；不是连续的标准 Hermite 平滑公式
            { "math.hermite_blend(0.25)", 1D }, { "math.hermite(0)", 0D }, { "math.random(4, 4)", 4D },
            { "math.random(4, 4, 99)", 4D }, { "math.random_integer(4, 4)", 4D }, { "math.randomi(4, 4)", 4D },
            { "math.die_roll(3, 2, 2)", 6D }, { "math.roll(3, 2, 2)", 6D }, { "math.die_roll_integer(3, 2, 2)", 6D },
            { "math.rolli(3, 2, 2)", 6D } };
        for (var pair : cases) {
            assertEquals((String) pair[0], (Double) pair[1], h.number((String) pair[0]), 1e-7);
        }
        assertEquals(0, h.number("math.sqrt(-1)"), 0);
    }

    @Test
    public void integerRandomCoversNormalReversedAndOverflowingRanges() throws Exception {
        var h = new Harness();
        var normal = h.parse("math.random_integer(3, 8)");
        var reversed = h.parse("math.random_integer(8, 3)");
        var wide = h.parse("math.random_integer(-2147483648, 2147483647)");
        for (int i = 0; i < 100; i++) {
            double value = h.context.evaluate(normal);
            assertTrue(value >= 3 && value < 8);
            value = h.context.evaluate(reversed);
            assertTrue(value >= 3 && value < 8);
            value = h.context.evaluate(wide);
            assertTrue(value >= Integer.MIN_VALUE && value < Integer.MAX_VALUE);
        }
        assertEquals(Integer.MIN_VALUE, h.number("math.random_integer(-2147483648, -2147483648)"), 0);
        assertEquals(Integer.MAX_VALUE, MolangRandom.nextInteger(new Random(1), Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, h.number("math.die_roll_integer(-4, 1, 9)"), 0);
        assertEquals(5120, h.number("math.die_roll_integer(2147483647, 5, 5)"), 0);
        assertEquals(5120, h.number("math.die_roll(2147483647, 5, 5)"), 0);
    }

    @Test
    public void seededRandomStreamsRemainIndependentWhenMaidsInterleave() throws Exception {
        var h = new Harness();
        var value = h.parse("math.random(0, 1) + math.randomi(0, 100)");
        var first = context(42);
        var second = context(42);
        var reference = context(42);
        for (int i = 0; i < 40; i++) {
            double expected = reference.evaluate(value);
            assertEquals(expected, first.evaluate(value), 0);
            h.context.evaluate(value);
            assertEquals(expected, second.evaluate(value), 0);
        }
    }

    @Test
    public void persistentVariablesAndPublicAliasesBelongToOneMaid() throws Exception {
        var h = new Harness();
        var first = context(1);
        var second = context(2);
        var publicNames = new PooledStringHashSet();
        publicNames.add("counter");
        ((VariableStorage) first.scopedStorage()).initialize(publicNames);
        var increment = h.parse("v.counter = (v.counter ?? 0) + 1; return c.counter");
        assertEquals(1, first.evaluate(increment), 0);
        assertEquals(2, first.evaluate(increment), 0);
        assertEquals(0, second.evaluate(increment), 0);
        var privateValue = h.parse("v.secret = 7; return c.secret ?? 19");
        assertEquals(19, first.evaluate(privateValue), 0);
        assertEquals(3, first.evaluate(increment), 0);
        first.scopedStorage()
            .setScoped(StringPool.computeIfAbsent("counter"), 8D);
        assertEquals(
            8D,
            first.foreignStorage()
                .getPublic(StringPool.getName("counter")));
    }

    @Test
    public void temporaryVariablesResetBetweenProgramsButNotInsideOneProgram() throws Exception {
        var h = new Harness();
        var written = h.parse("t.x = 17; return t.x");
        var unwritten = h.parse("return t.x ?? 5");
        assertEquals(17, h.context.evaluate(written), 0);
        assertEquals(5, h.context.evaluate(unwritten), 0);
        assertEquals(17, h.context.evaluate(written), 0);
        h.context.scopedStorage()
            .setScoped(StringPool.computeIfAbsent("saved"), 99D);
        h.context.tempStorage()
            .setTemp(512, "large frame");
        assertEquals(
            "large frame",
            h.context.tempStorage()
                .getTemp(512));
        h.context.evaluate(written);
        assertNull(
            h.context.tempStorage()
                .getTemp(512));
        assertEquals(
            99D,
            h.context.scopedStorage()
                .getScoped(StringPool.getName("saved")));
    }

    @Test
    public void modelInitializationClearsOldValuesAndRebuildsPublicVisibility() throws Exception {
        var h = new Harness();
        var storage = (VariableStorage) h.context.scopedStorage();
        var names = new PooledStringHashSet();
        names.add("old");
        storage.initialize(names);
        h.number("v.old = 4; v.private = 9; t.x = 8");
        assertNotNull(storage.getPublic(StringPool.getName("old")));
        names.clear();
        names.add("new");
        storage.initialize(names);
        assertNull(storage.getPublic(StringPool.getName("old")));
        assertNull(storage.getScoped(StringPool.getName("private")));
        assertNull(storage.getTemp(0));
        h.number("v.new = 3");
        assertEquals(3D, storage.getPublic(StringPool.getName("new")));
    }

    @Test
    public void childContextsDoNotExposeTheParentsPublicVariablesAccidentally() throws Exception {
        var h = new Harness();
        var storage = new VariableStorage();
        var names = new PooledStringHashSet();
        names.add("visible");
        storage.initialize(names);
        storage.setScoped(StringPool.getName("visible"), 12D);
        var child = h.context.createChild(storage);
        assertEquals(
            12D,
            child.foreignStorage()
                .getPublic(StringPool.getName("visible")));
        assertNull(
            h.context.createChild(new Object())
                .foreignStorage());
        assertEquals(h.context.scopedStorage(), child.scopedStorage());
    }

    @Test
    public void valueWrappersHonorEarlyReturnAndRotationSigns() throws Exception {
        var h = new Harness();
        var value = h.parse("v.x = 1; return 90; v.x = 2");
        assertEquals(90, h.context.evaluate(value), 0);
        assertEquals(
            1D,
            h.context.scopedStorage()
                .getScoped(StringPool.getName("x")));
        assertEquals(Math.PI / 2, h.context.evaluate(new RotationValue(value, false)), 1e-6);
        assertEquals(-Math.PI / 2, h.context.evaluate(new RotationValue(value, true)), 1e-6);
        var evaluator = ExpressionEvaluator.evaluator(h.context);
        assertTrue(DoubleValue.ONE.evalAsBoolean(evaluator));
        assertFalse(DoubleValue.ZERO.evalAsBoolean(evaluator));
        assertTrue(ValueConversions.asBoolean(h.context.evaluateUnsafe(h.parse("return 'maid'"))));
    }

    @Test
    public void invalidFunctionAritiesFailAtCompileTime() {
        var h = new Harness();
        for (var invalid : new String[] { "math.sin()", "math.pow(1)", "math.random(1)", "math.random_integer(1, 2, 3)",
            "math.die_roll(1, 2)", "math.clamp(1, 2)" }) {
            assertThrows(invalid, ParseException.class, () -> h.parse(invalid));
        }
    }

    private static MolangAnimationContext<Object> context(long seed) {
        return new MolangAnimationContext<>(new Object(), new VariableStorage(), new Random(seed));
    }

    private static final class Harness {

        private final TempVariableBinding temporary = new TempVariableBinding();
        private final ScopedVariableBinding scoped = new ScopedVariableBinding();
        private final ForeignVariableBinding foreign = new ForeignVariableBinding();
        private final MolangAnimationContext<Object> context = context(4);
        private final MolangEngine engine = MolangEngine.fromCustomBinding(name -> switch (name) {
            case "math" -> MathBinding.INSTANCE;
            case "v", "variable" -> scoped;
            case "c", "context" -> foreign;
            case "t", "temp" -> temporary;
            default -> null;
        });

        private IValue parse(String code) throws ParseException {
            try {
                return new MolangValue(engine.parse(code));
            } finally {
                temporary.reset();
            }
        }

        private double number(String code) throws ParseException {
            return context.evaluate(parse(code));
        }
    }
}
