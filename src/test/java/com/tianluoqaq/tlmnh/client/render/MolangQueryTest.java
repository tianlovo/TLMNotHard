package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.Collections;
import java.util.Random;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.MolangParser;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.PooledStringHashSet;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.molang.parser.ParseException;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;

/** 查询使用实际 PrimaryBinding，纯数据测试不替代游戏实体取帧或画面验收 */
public class MolangQueryTest {

    @Test
    public void scalarQueriesKeepUpstreamUnitsAndHeadAxisNames() throws Exception {
        var h = new Harness();
        h.frame.lifetime = 7.25;
        h.frame.bodyYaw = -179;
        h.frame.netHeadYaw = 32;
        h.frame.headPitch = -17;
        h.frame.health = 13;
        h.frame.maxHealth = 20;
        h.frame.groundSpeed = 4.5;
        h.frame.verticalSpeed = -2.25;
        h.frame.yawSpeed = 720;
        h.frame.inWater = true;
        h.frame.inWaterOrRain = true;
        h.frame.jumping = false;
        h.frame.sneaking = true;
        Object[][] cases = { { "life_time", 7.25D }, { "head_x_rotation", 32D }, { "head_y_rotation", -17D },
            { "body_y_rotation", -179D }, { "health", 13D }, { "max_health", 20D }, { "ground_speed", 4.5D },
            { "vertical_speed", -2.25D }, { "yaw_speed", 720D }, { "is_in_water", 1D }, { "is_in_water_or_rain", 1D },
            { "is_jumping", 0D }, { "is_sneaking", 1D } };
        for (var pair : cases) {
            assertEquals((String) pair[0], (Double) pair[1], h.number("query." + pair[0]), 0);
            assertEquals((String) pair[0], (Double) pair[1], h.number("q." + pair[0]), 0);
        }
        h.frame.inWater = false;
        h.frame.jumping = true;
        assertEquals(1, h.number("!q.is_in_water && q.is_jumping"), 0);
    }

    @Test
    public void animationTimeBelongsToEachControllerContextAndNotTheCompiledExpression() throws Exception {
        var h = new Harness();
        var value = h.parse("q.anim_time * 10 + q.life_time");
        h.frame.lifetime = 8;
        var otherFrame = new MolangQueryFrame();
        otherFrame.lifetime = 21;
        var other = context(otherFrame);
        h.context.animationControllerContext()
            .setAnimTime(1.5);
        other.animationControllerContext()
            .setAnimTime(3);
        assertEquals(23, h.context.evaluate(value), 0);
        assertEquals(51, other.evaluate(value), 0);
        h.context.animationControllerContext()
            .setAnimTime(0);
        assertEquals(8, h.context.evaluate(value), 0);
        assertEquals(51, other.evaluate(value), 0);
        assertSame(
            h.context.animationControllerContext(),
            h.context.createChild(otherFrame)
                .animationControllerContext());
    }

    @Test
    public void compiledQueriesReadTheLatestFrameAcrossMultipleMaids() throws Exception {
        var h = new Harness();
        var value = h.parse("q.health + math.sin(q.head_x_rotation)");
        h.frame.health = 10;
        h.frame.netHeadYaw = 90;
        var otherFrame = new MolangQueryFrame();
        otherFrame.health = 3;
        var other = context(otherFrame);
        assertEquals(11, h.context.evaluate(value), 1e-7);
        assertEquals(3, other.evaluate(value), 0);
        otherFrame.health = 9;
        h.context.updateEntity(otherFrame);
        assertEquals(9, h.context.evaluate(value), 0);
        h.context.updateEntity(h.frame);
        assertEquals(11, h.context.evaluate(value), 1e-7);
    }

    @Test
    public void interpolatedPositionsKeepDoublePrecisionAtWorldEdges() throws Exception {
        var h = new Harness();
        h.frame.previousX = 29_999_999;
        h.frame.x = 29_999_999.125;
        h.frame.previousY = 64;
        h.frame.y = 65;
        h.frame.previousZ = -29_999_999;
        h.frame.z = -29_999_999.25;
        for (double partial : new double[] { 0, 0.5, 1 }) {
            h.frame.partialTick = partial;
            assertEquals(29_999_999 + 0.125 * partial, h.number("q.position(0)"), 0);
            assertEquals(64 + partial, h.number("q.position(1)"), 0);
            assertEquals(-29_999_999 - 0.25 * partial, h.number("q.position(2)"), 0);
            assertEquals(0.125 * partial, h.number("q.position_delta(0)"), 0);
            assertEquals(partial, h.number("q.position_delta(1)"), 0);
            assertEquals(-0.25 * partial, h.number("q.position_delta(2)"), 0);
        }
        assertEquals(91, h.number("q.position(3) ?? 91"), 0);
        assertEquals(92, h.number("q.position_delta(-1) ?? 92"), 0);
    }

    @Test
    public void winefoxMainhandBowConditionsAndOffhandRemainIndependent() throws Exception {
        var h = new Harness();
        var winefox = h.parse("q.is_item_name_any('Mainhand', 'bow')");
        h.frame.setEquipment(Slot.MAINHAND, "minecraft:bow", 384, 34);
        h.frame.setEquipment(Slot.OFFHAND, "minecraft:shield", 336, 12);
        assertEquals(1, h.context.evaluate(winefox), 0);
        assertEquals(0, h.number("q.is_item_name_any('offhand', 'bow')"), 0);
        assertEquals(1, h.number("q.is_item_name_any('OFFHAND', 'stick', 'minecraft:shield')"), 0);
        assertEquals(384, h.number("q.max_durability('Mainhand')"), 0);
        assertEquals(350, h.number("q.remaining_durability('mainhand')"), 0);
        assertEquals(324, h.number("q.remaining_durability('offhand')"), 0);
        h.frame.setEquipment(Slot.MAINHAND, null, 0, 0);
        assertEquals(0, h.context.evaluate(winefox), 0);
        assertEquals(0, h.number("q.max_durability('mainhand')"), 0);
        assertEquals(0, h.number("q.remaining_durability('mainhand')"), 0);
    }

    @Test
    public void legacyRegistryCaseIsPreservedAndMalformedQueriesReturnNull() throws Exception {
        var h = new Harness();
        h.frame.setEquipment(Slot.HEAD, "IC2:itemArmorQuantumHelmet", 100, 4);
        assertEquals(1, h.number("q.is_item_name_any('head', 'IC2:itemArmorQuantumHelmet')"), 0);
        assertEquals(0, h.number("q.is_item_name_any('head', 'ic2:itemarmorquantumhelmet')"), 0);
        assertEquals(96, h.number("q.remaining_durability('head')"), 0);
        h.frame.setEquipment(Slot.MAINHAND, "minecraft:bow", 384, 0);
        assertNull(h.context.evaluateUnsafe(h.parse("q.is_item_name_any('mainhand', 'not an id')")));
        assertNull(h.context.evaluateUnsafe(h.parse("q.is_item_name_any('unknown', 'bow')")));
        assertNull(h.context.evaluateUnsafe(h.parse("q.max_durability('unknown')")));
        // 实际注册 ID 不应因高版本字符限制中断取帧
        h.frame.setEquipment(Slot.CHEST, "legacy:unusual$item", 50, 2);
        assertEquals(48, h.number("q.remaining_durability('chest')"), 0);
    }

    @Test
    public void primaryBindingCombinesQueriesAliasesVariablesAndLoops() throws Exception {
        var h = new Harness();
        var names = new PooledStringHashSet();
        names.add("counter");
        h.storage.initialize(names);
        h.frame.health = 6;
        var program = h.parse(
            "variable.counter = (v.counter ?? 0) + 1; t.sum = 0; "
                + "loop(3, {temp.sum = t.sum + q.health;}); return context.counter + t.sum");
        assertEquals(19, h.context.evaluate(program), 0);
        assertEquals(20, h.context.evaluate(program), 0);
        assertEquals(21, h.context.evaluate(program), 0);
        assertEquals(5, h.number("t.sum ?? 5"), 0);
    }

    @Test
    public void failedParsingDoesNotLeakTemporaryAddressesToTheNextProgram() throws Exception {
        var h = new Harness();
        assertThrows(ParseException.class, () -> h.parse("t.leaked = 1; return ("));
        assertEquals(7, h.number("t.first = 7; return t.first"), 0);
        assertEquals(7D, h.storage.getTemp(0));
        h.parser.reset();
        assertEquals(9, h.number("t.other = 9; return t.other"), 0);
        assertEquals(9D, h.storage.getTemp(0));
    }

    @Test
    public void queryFunctionsRejectInvalidArities() {
        var h = new Harness();
        for (var invalid : new String[] { "q.position()", "q.position(0, 1)", "q.position_delta()",
            "q.is_item_name_any('mainhand')", "q.max_durability()", "q.remaining_durability('mainhand', 2)" }) {
            assertThrows(invalid, ParseException.class, () -> h.parse(invalid));
        }
    }

    @Test
    public void queriesReturnNullWhenUsedWithAnUnrelatedDataContext() throws Exception {
        var h = new Harness();
        var unrelated = new MolangAnimationContext<>(new Object(), new VariableStorage(), new Random(1));
        assertNull(unrelated.evaluateUnsafe(h.parse("q.health")));
        assertNull(unrelated.evaluateUnsafe(h.parse("q.position(0)")));
        var child = unrelated.createChild(h.frame);
        h.frame.health = 15;
        assertEquals(
            15D,
            h.parse("q.health")
                .evalUnsafe(ExpressionEvaluator.evaluator(child)));
    }

    private static MolangAnimationContext<MolangQueryFrame> context(MolangQueryFrame frame) {
        return new MolangAnimationContext<>(frame, new VariableStorage(), new Random(1));
    }

    private static final class Harness {

        private final MolangParser parser = new MolangParser(Collections.emptyMap());
        private final MolangQueryFrame frame = new MolangQueryFrame();
        private final VariableStorage storage = new VariableStorage();
        private final MolangAnimationContext<MolangQueryFrame> context = new MolangAnimationContext<>(
            frame,
            storage,
            new Random(1));

        private IValue parse(String expression) throws ParseException {
            return parser.parseExpressionUnsafe(expression);
        }

        private double number(String expression) throws ParseException {
            return context.evaluate(parse(expression));
        }
    }
}
