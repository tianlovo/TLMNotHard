package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Random;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.PhysicsManager;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.physics.IPhysics;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.physics.SecondOrder;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangBindings;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** TLM 内嵌的 ysm 查询和模型物理测试，不加载外部 YSM 模组 */
public class MolangModelQueryTest {

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void builtinNamespacesReadCurrentMaidAndEquipmentState() throws Exception {
        var context = context();
        var parser = MaidMolangBindings.parser();
        var frame = context.entity();
        frame.begging = true;
        frame.sitting = true;
        frame.closeEyes = true;
        frame.netHeadYaw = 13;
        frame.headPitch = -8;
        frame.foodLevel = 9;
        frame.setEquipment(Slot.MAINHAND, "minecraft:bow", 384, 0);
        frame.setEquipment(Slot.HEAD, "IC2:itemArmorQuantumHelmet", 100, 0);
        assertEquals(9, context.evaluate(parser.parseExpressionRequired("ysm.food_level")), 0);
        assertEquals(13, context.evaluate(parser.parseExpressionRequired("ysm.head_yaw")), 0);
        assertEquals(-8, context.evaluate(parser.parseExpressionRequired("ysm.head_pitch")), 0);
        assertEquals(
            1,
            context.evaluate(
                parser.parseExpressionRequired(
                    "tlm.is_begging && tlm.is_sitting && ysm.is_close_eyes && ysm.has_mainhand && ysm.has_helmet")),
            0);
        assertEquals(0, context.evaluate(parser.parseExpressionRequired("tlm.has_backpack || ysm.has_offhand")), 0);
        var equipment = parser.parseExpressionRequired("ysm.has_mainhand");
        frame.setEquipment(Slot.MAINHAND, null, 0, 0);
        assertEquals(0, context.evaluate(equipment), 0);
        var unrelated = new MolangAnimationContext<>(new Object(), new VariableStorage(), new Random(1));
        assertNull(unrelated.evaluateUnsafe(parser.parseExpressionRequired("ysm.input_vertical")));
    }

    @Test
    public void movementInputsFollowViewYawAndPartialTickDisplacement() {
        var context = context();
        var parser = MaidMolangBindings.parser();
        var vertical = parser.parseExpressionRequired("ysm.input_vertical");
        var horizontal = parser.parseExpressionRequired("ysm.input_horizontal");
        var frame = context.entity();
        frame.partialTick = 0.5;
        frame.z = 1;
        assertEquals(1, context.evaluate(vertical), 1e-6);
        assertEquals(0, context.evaluate(horizontal), 1e-6);
        frame.z = 0;
        frame.x = 1;
        assertEquals(0, context.evaluate(vertical), 1e-6);
        assertEquals(-1, context.evaluate(horizontal), 1e-6);
        frame.x = -1;
        frame.viewYaw = 90;
        assertEquals(1, context.evaluate(vertical), 1e-6);
        assertEquals(0, context.evaluate(horizontal), 1e-6);
        frame.partialTick = 0;
        assertEquals(0, context.evaluate(vertical), 0);
        assertEquals(0, context.evaluate(horizontal), 0);
    }

    @Test
    public void boneRotationReadsLiveModelRadiansAsReadonlyDegreeComponents() throws Exception {
        var models = new BuiltinMaidModels(
            location -> MolangModelQueryTest.class
                .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath()));
        var choice = BuiltinModelCatalog.choices()
            .stream()
            .filter(c -> c.isGecko())
            .findFirst()
            .get();
        var bone = models.create(choice)
            .getGecko()
            .bones()
            .values()
            .iterator()
            .next();
        var context = context();
        context.modelResources()
            .bindBones(Collections.singletonMap("sample", bone));
        var parser = MaidMolangBindings.parser();
        var x = parser.parseExpressionRequired("ysm.bone_rot('sample').x");
        bone.setRotationX((float) (Math.PI / 2));
        bone.setRotationY((float) (-Math.PI / 4));
        bone.setRotationZ((float) Math.PI);
        assertEquals(-90, context.evaluate(x), 1e-4);
        assertEquals(45, context.evaluate(parser.parseExpressionRequired("ysm.bone_rot('sample').y")), 1e-4);
        assertEquals(180, context.evaluate(parser.parseExpressionRequired("ysm.bone_rot('sample').z")), 1e-4);
        assertEquals(
            -90,
            context
                .evaluate(parser.parseExpressionRequired("v.read=ysm.bone_rot('sample');v.read.x=3;return v.read.x")),
            1e-4);
        bone.setRotationX(0);
        assertEquals(0, context.evaluate(x), 0);
        assertEquals(7, context.evaluate(parser.parseExpressionRequired("ysm.bone_rot('missing').x ?? 7")), 0);
    }

    @Test
    public void secondOrderStateIsIsolatedByMaidAndRebuiltOnModelChange() {
        var first = context();
        var second = context();
        var value = MaidMolangBindings.parser()
            .parseExpressionRequired("ysm.second_order('ear', q.health, 2, 0.7, 1)");
        first.entity().health = 10;
        second.entity().health = -4;
        assertEquals(10, first.evaluate(value), 0);
        assertEquals(-4, second.evaluate(value), 0);
        var one = first.modelResources()
            .physics();
        var two = second.modelResources()
            .physics();
        assertNotSame(one.get("ear"), two.get("ear"));
        one.update(0);
        two.update(0);
        for (int i = 1; i <= 300; i++) {
            one.update(i / 3F);
            two.update(i / 3F);
            first.evaluate(value);
            second.evaluate(value);
        }
        assertEquals(10, first.evaluate(value), 0.01);
        assertEquals(-4, second.evaluate(value), 0.01);
        assertSame(
            first.modelResources(),
            first.createChild(new Object())
                .modelResources());
        first.modelResources()
            .bindBones(Collections.emptyMap());
        assertNull(one.get("ear"));
        assertTrue(two.get("ear") != null);
    }

    @Test
    public void repeatedOrRewoundRenderTicksDoNotAdvancePhysics() {
        var manager = new PhysicsManager();
        float[] elapsed = { 0 };
        manager.put("clock", new IPhysics() {

            @Override
            public void update(float timeStep) {
                elapsed[0] += timeStep;
            }

            @Override
            public void setArgs(float a, float b, float c, float d) {}

            @Override
            public float getValue() {
                return elapsed[0];
            }
        });
        manager.update(0);
        manager.update(1);
        manager.update(1);
        manager.update(0);
        manager.update(Float.NaN);
        assertEquals(0.05, elapsed[0], 1e-7);
        manager.update(2);
        assertEquals(0.1, elapsed[0], 1e-7);
    }

    @Test(timeout = 1000)
    public void zeroFrequencyInvalidInputsAndLongGapsKeepFiniteBoundedWork() {
        var physics = new SecondOrder(7, 0, 1, 1);
        physics.update(0.05F);
        assertEquals(7, physics.getValue(), 0);
        physics.setArgs(11, 5, 1, 1);
        physics.update(Float.MAX_VALUE);
        assertTrue(Float.isFinite(physics.getValue()));
        float saved = physics.getValue();
        physics.update(0);
        physics.update(Float.NaN);
        physics.setArgs(Float.NaN, 2, 1, 1);
        physics.update(0.1F);
        assertEquals(saved, physics.getValue(), 0);
        physics.setArgs(Float.MAX_VALUE, 2, 1, Float.MAX_VALUE);
        physics.update(0.05F);
        assertTrue(Float.isFinite(physics.getValue()));
    }

    private static MolangAnimationContext<MolangQueryFrame> context() {
        return new MolangAnimationContext<>(new MolangQueryFrame(), new VariableStorage(), new Random(4));
    }
}
