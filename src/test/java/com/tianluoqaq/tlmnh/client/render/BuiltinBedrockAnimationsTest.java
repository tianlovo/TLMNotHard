package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.EntityBaseAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.IAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.MaidArmorAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.MaidBaseAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.MaidExtraAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.GlWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 覆盖原组合与资源中的真实骨骼；无 GL，不代替两端画面和生活行为验收 */
public class BuiltinBedrockAnimationsTest {

    private static final float EPSILON = 1E-5F;

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void everyBuiltinBedrockChoiceAnimatesThroughStateChanges() throws Exception {
        var models = new BuiltinMaidModels(
            location -> getClass()
                .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath()));
        int choices = 0, winefox = 0;
        long[] vertices = { 0 };
        for (var choice : BuiltinModelCatalog.choices()) {
            if (choice.isGecko()) continue;
            var model = models.create(choice);
            var state = state();
            for (int frame = 0; frame < 10; frame++) {
                state.sitting = frame == 2;
                state.passenger = frame == 3;
                state.sleeping = frame == 4;
                state.begging = frame == 5;
                state.attackAnim = frame == 6 ? 0.5F : 0;
                state.swingLeft = frame % 2 == 0;
                state.usingItem = frame == 7 || frame == 8;
                state.usingOffhand = frame == 7;
                state.aimingBow = frame == 9;
                state.mainHandPresent = frame == 9;
                state.helmet = state.chestPlate = state.leggings = state.boots = frame >= 5;
                state.health = frame >= 5 ? 4 : 20;
                state.hurt = frame == 5;
                state.dimension = frame % 3 - 1;
                model.setupPose(state, frame * 0.8F, frame == 1 ? 0.7F : 0, frame * 11.3F, 35, -15);
                for (var wrapper : model.getBedrockPose()
                    .getModels()
                    .values()) {
                    assertTrue(choice.getId(), Float.isFinite(wrapper.getRotateAngleX()));
                    assertTrue(choice.getId(), Float.isFinite(wrapper.getRotateAngleY()));
                    assertTrue(choice.getId(), Float.isFinite(wrapper.getRotateAngleZ()));
                }
                model.renderGeometry(new LegacyPoseStack(), (x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz) -> {
                    assertTrue(choice.getId(), Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z));
                    assertTrue(choice.getId(), Float.isFinite(u) && Float.isFinite(v));
                    assertTrue(choice.getId(), Float.isFinite(nx) && Float.isFinite(ny) && Float.isFinite(nz));
                    vertices[0]++;
                }, 0);
            }
            choices++;
            if (choice.isWinefox()) winefox++;
        }
        assertEquals(2, winefox);
        assertTrue(choices > 200 && vertices[0] > 1000000);
        System.out.println(
            "TLMNH_BEDROCK_ANIMATION_PASS choices=" + choices
                + " winefox="
                + winefox
                + " framesPerChoice=10 vertices="
                + vertices[0]
                + " java="
                + System.getProperty("java.version"));
    }

    @Test
    public void headAndSleepKeepOriginalAnglesAndHatVisibility() {
        var parts = parts("head", "hat");
        var state = state();
        run(MaidBaseAnimation.getHeadDefault(), state, parts, 0, 90, 30);
        assertEquals(
            (float) Math.PI / 6,
            parts.get("head")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            (float) Math.PI / 2,
            parts.get("head")
                .getRotateAngleY(),
            EPSILON);
        assertTrue(
            parts.get("hat")
                .getModelRenderer().visible);
        state.sleeping = true;
        run(MaidBaseAnimation.getHeadDefault(), state, parts, 0, 90, 30);
        assertEquals(
            (float) Math.PI / 12,
            parts.get("head")
                .getRotateAngleX(),
            EPSILON);
        assertFalse(
            parts.get("hat")
                .getModelRenderer().visible);
    }

    @Test
    public void attackAndUseRespectTheSelectedHand() {
        var parts = parts("armLeft", "armRight");
        var state = state();
        state.attackAnim = 0.5F;
        state.swingLeft = true;
        run(MaidBaseAnimation.getArmDefault(), state, parts, 0, 0, 0);
        assertEquals(
            0.2908916F,
            parts.get("armLeft")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            0,
            parts.get("armRight")
                .getRotateAngleX(),
            EPSILON);
        state.attackAnim = 0;
        state.usingItem = true;
        state.usingOffhand = true;
        run(MaidBaseAnimation.getArmDefault(), state, parts, 0, 0, 0);
        assertEquals(
            -1.3962634F,
            parts.get("armLeft")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            0.4363323F,
            parts.get("armLeft")
                .getRotateAngleY(),
            EPSILON);
        assertEquals(
            0,
            parts.get("armRight")
                .getRotateAngleX(),
            EPSILON);
        state.usingOffhand = false;
        run(MaidBaseAnimation.getArmDefault(), state, parts, 0, 0, 0);
        assertEquals(
            -1.3962634F,
            parts.get("armRight")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            -0.3490658F,
            parts.get("armRight")
                .getRotateAngleY(),
            EPSILON);
    }

    @Test
    public void sittingMovesBodyAndRestoresStandingOnTheNextFrame() throws Exception {
        var models = new BuiltinMaidModels(
            location -> getClass()
                .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath()));
        var model = models.create(BuiltinModelCatalog.get("touhou_little_maid:hakurei_reimu"));
        var state = state();
        state.sitting = true;
        model.setupPose(state, 0, 0, 0, 0, 0);
        var parts = model.getBedrockPose()
            .getModels();
        assertEquals(
            -1.134F,
            parts.get("legLeft")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            -0.798F,
            parts.get("armLeft")
                .getRotateAngleX(),
            EPSILON);
        var transform = new LegacyPoseStack();
        model.getBedrockPose()
            .applyTransform(transform);
        assertEquals(
            0.3F,
            transform.last()
                .pose()
                .m31(),
            EPSILON);
        state.sitting = false;
        model.setupPose(state, 0, 0, 0, 0, 0);
        assertEquals(
            0,
            parts.get("legLeft")
                .getRotateAngleX(),
            EPSILON);
        transform = new LegacyPoseStack();
        model.getBedrockPose()
            .applyTransform(transform);
        assertEquals(
            0,
            transform.last()
                .pose()
                .m31(),
            EPSILON);
    }

    @Test
    public void armorAndHealthConditionsRemainIndependent() {
        var parts = parts(
            "helmet",
            "_helmet",
            "bootsLeft",
            "_bootsLeft",
            "healthLessQuarterShow",
            "healthLessHalfShow");
        var state = state();
        state.helmet = true;
        run(MaidArmorAnimation.getArmorDefault(), state, parts, 0, 0, 0);
        run(MaidArmorAnimation.getArmorReverse(), state, parts, 0, 0, 0);
        assertTrue(
            parts.get("helmet")
                .getModelRenderer().visible);
        assertFalse(
            parts.get("_helmet")
                .getModelRenderer().visible);
        assertFalse(
            parts.get("bootsLeft")
                .getModelRenderer().visible);
        assertTrue(
            parts.get("_bootsLeft")
                .getModelRenderer().visible);
        state.health = 5;
        run(MaidExtraAnimation.getHealthLessShow(), state, parts, 0, 0, 0);
        assertTrue(
            parts.get("healthLessQuarterShow")
                .getModelRenderer().visible);
        state.health = 6;
        run(MaidExtraAnimation.getHealthLessShow(), state, parts, 0, 0, 0);
        assertFalse(
            parts.get("healthLessQuarterShow")
                .getModelRenderer().visible);
        assertTrue(
            parts.get("healthLessHalfShow")
                .getModelRenderer().visible);
    }

    @Test
    public void reverseBlinkSupportsEitherSpellingWithoutTheOtherBone() {
        for (var name : new String[] { "_bink", "_blink" }) {
            var parts = parts(name);
            run(MaidExtraAnimation.getHeadReverseBlink(), state(), parts, 57, 0, 0);
            assertFalse(
                parts.get(name)
                    .getModelRenderer().visible);
            run(MaidExtraAnimation.getHeadReverseBlink(), state(), parts, 10, 0, 0);
            assertTrue(
                parts.get(name)
                    .getModelRenderer().visible);
        }
    }

    @Test
    public void dimensionVisibilityAndEmptyRandomSelectionAreSafe() {
        var state = state();
        var parts = parts("netherWorldShow", "overWorldShow", "endWorldShow");
        state.dimension = -1;
        run(EntityBaseAnimation.getBaseDimDefault(), state, parts, 0, 0, 0);
        assertTrue(
            parts.get("netherWorldShow")
                .getModelRenderer().visible);
        assertFalse(
            parts.get("overWorldShow")
                .getModelRenderer().visible);
        assertFalse(
            parts.get("endWorldShow")
                .getModelRenderer().visible);
        run(EntityBaseAnimation.getRandomSelect(), state, parts(), 0, 0, 0);
        parts = parts("randomSelect1", "randomSelect2", "randomSelect3");
        state = new MaidAnimationState(new UUID(0, Long.MIN_VALUE));
        run(EntityBaseAnimation.getRandomSelect(), state, parts, 0, 0, 0);
        assertEquals(
            1,
            parts.values()
                .stream()
                .filter(part -> part.getModelRenderer().visible)
                .count());
    }

    @Test
    public void fixedArmScriptKeepsInitialPitchAndAttackSide() {
        var parts = parts("armLeft", "armRight");
        parts.get("armLeft")
            .getModelRenderer().initRotX = 0.2F;
        parts.get("armRight")
            .getModelRenderer().initRotX = -0.1F;
        var state = state();
        state.attackAnim = 0.5F;
        state.swingLeft = true;
        run(
            BuiltinBedrockAnimations.resolve(new ResourceLocation("authors_and_credits:animation/arm.js")),
            state,
            parts,
            0,
            0,
            0);
        assertEquals(
            0.4908916F,
            parts.get("armLeft")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            -0.75F,
            parts.get("armLeft")
                .getRotateAngleZ(),
            EPSILON);
        assertEquals(
            -0.1F,
            parts.get("armRight")
                .getRotateAngleX(),
            EPSILON);
        assertEquals(
            0.35F,
            parts.get("armRight")
                .getRotateAngleZ(),
            EPSILON);
    }

    @Test
    public void fixedFloatingScriptKeepsSittingAndSleepingTransforms() {
        var parts = parts("floatLeg", "left", "right", "armLeft", "armRight");
        var state = state();
        var animation = BuiltinBedrockAnimations
            .resolve(new ResourceLocation("authors_and_credits:animation/float.js"));
        var poses = new LegacyPoseStack();
        GlWrapper.setPoseStack(poses);
        try {
            animation.setRotationAngles(0, 0.5F, 0, 0, 0, 0.0625F, state, parts);
            assertEquals(
                0.5795595F,
                parts.get("floatLeg")
                    .getRotateAngleX(),
                EPSILON);
            assertEquals(
                -0.1F,
                poses.last()
                    .pose()
                    .m31(),
                EPSILON);
        } finally {
            GlWrapper.clearPoseStack();
        }
        state.sitting = state.sleeping = true;
        poses = new LegacyPoseStack();
        GlWrapper.setPoseStack(poses);
        try {
            animation.setRotationAngles(0, 0, 0, 0, 0, 0.0625F, state, parts);
            assertEquals(
                -1,
                parts.get("armLeft")
                    .getRotateAngleX(),
                EPSILON);
            assertEquals(
                0.75F,
                parts.get("armLeft")
                    .getRotateAngleY(),
                EPSILON);
            assertEquals(
                0.5F,
                poses.last()
                    .pose()
                    .m31(),
                EPSILON);
        } finally {
            GlWrapper.clearPoseStack();
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> BuiltinBedrockAnimations.resolve(new ResourceLocation("tlmnh:missing.js")));
    }

    private static MaidAnimationState state() {
        return new MaidAnimationState(new UUID(0, 0));
    }

    private static Map<String, ModelRendererWrapper> parts(String... names) {
        var parts = new HashMap<String, ModelRendererWrapper>();
        for (var name : names) parts.put(name, new ModelRendererWrapper(new BedrockPart()));
        return parts;
    }

    private static void run(IAnimation<MaidAnimationState> animation, MaidAnimationState state,
        Map<String, ModelRendererWrapper> parts, float age, float yaw, float pitch) {
        animation.setRotationAngles(0, 0, age, yaw, pitch, 0.0625F, state, parts);
    }
}
