package com.tianluoqaq.tlmnh.client.render.molang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import net.minecraft.entity.ai.attributes.ServersideAttributeMap;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;

/** 使用原生属性与原魔法酒狐条件，防止把默认0的MaidHunger字段再次错接为food_level */
public class MaidAnimationFoodLevelTest {

    @Test
    public void queryUsesWatchedNativeAttributeWithUpstreamDefaultAndFallback() {
        var attributes = new ServersideAttributeMap();
        assertEquals(20, MaidMolangFrames.foodLevel(attributes));
        MaidCombatAttributes.register(attributes);
        var food = attributes.getAttributeInstance(MaidCombatAttributes.MAID_HUNGER);
        assertEquals(20, food.getAttributeValue(), 0);
        assertTrue(MaidCombatAttributes.MAID_HUNGER.getShouldWatch());
        assertEquals(20, MaidMolangFrames.foodLevel(attributes));
        food.setBaseValue(6.75);
        assertEquals(6, MaidMolangFrames.foodLevel(attributes));
        food.setBaseValue(7.25);
        assertEquals(7, MaidMolangFrames.foodLevel(attributes));
        food.setBaseValue(100);
        assertEquals(100, MaidMolangFrames.foodLevel(attributes));
        var anotherMaid = new ServersideAttributeMap();
        MaidCombatAttributes.register(anotherMaid);
        assertEquals(20, MaidMolangFrames.foodLevel(anotherMaid));
    }

    @Test
    public void ordinaryNativeDefaultDoesNotTriggerOriginalMagicalHungerPose() throws Exception {
        JsonObject bones;
        try (var input = getClass()
            .getResourceAsStream("/assets/geckolib/animation/winefox_magical.main.animation.json")) {
            if (input == null) throw new IllegalStateException("Original magical winefox animation missing");
            bones = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("animations")
                .getAsJsonObject("idle")
                .getAsJsonObject("bones");
        }
        var parser = MaidMolangBindings.parser();
        var arm = parser.parseExpressionRequired(
            bones.getAsJsonObject("LeftArm")
                .getAsJsonArray("rotation")
                .get(0)
                .getAsString());
        var forearm = parser.parseExpressionRequired(
            bones.getAsJsonObject("LeftForeArm")
                .getAsJsonArray("rotation")
                .get(0)
                .getAsString());
        var head = parser.parseExpressionRequired(
            bones.getAsJsonObject("Head")
                .getAsJsonObject("rotation")
                .getAsJsonArray("0.0")
                .get(0)
                .getAsString());
        var attributes = new ServersideAttributeMap();
        MaidCombatAttributes.register(attributes);
        var frame = new MolangQueryFrame();
        var context = new MolangAnimationContext<>(frame, new VariableStorage(), new Random(1));
        frame.foodLevel = MaidMolangFrames.foodLevel(attributes);
        assertEquals(0, context.evaluate(arm), 0);
        assertEquals(0, context.evaluate(forearm), 0);
        assertEquals(0, context.evaluate(head), 0);
        attributes.getAttributeInstance(MaidCombatAttributes.MAID_HUNGER)
            .setBaseValue(0);
        frame.foodLevel = MaidMolangFrames.foodLevel(attributes);
        assertEquals(-25, context.evaluate(arm), 0);
        assertEquals(-27.5, context.evaluate(forearm), 0);
        assertEquals(25, context.evaluate(head), 0);
    }
}
