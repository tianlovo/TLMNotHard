package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Random;

import org.joml.Vector3f;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.AnimationState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.PlayState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.AnimationBuilder;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate.AnimationEvent;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.manager.AnimationData;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.AnimationProcessor;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util.RateLimiter;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoBone;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangBindings;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;

/** 控制器在纯数据骨骼上的语义检查，游戏画面仍须单独验收 */
public class GeckoControllerTest {

    private static final double EPSILON = 1e-5;

    @Test
    public void repeatedPredicatesKeepClipTimeAndDynamicExpressions() {
        var fixture = fixture("\"loop\":true," + bone("\"position\":[\"q.anim_time*20\",0,0]"));
        var layer = fixture.layer("main", 0, new AnimationBuilder().loop("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(5);
        assertEquals(5, fixture.bone.getPositionX(), EPSILON);
        fixture.tick(10);
        assertEquals(10, fixture.bone.getPositionX(), EPSILON);
        assertEquals(AnimationState.RUNNING, layer.controller.getAnimationState());
    }

    @Test
    public void transitionStartsAtCurrentPoseAndReachesNewPose() {
        var fixture = fixture("\"loop\":true," + bone("\"position\":[2,0,0]"));
        fixture.animations.putAnimation("next", clip("next", "\"loop\":true," + bone("\"position\":[10,0,0]")));
        var layer = fixture.layer("main", 4, new AnimationBuilder().loop("test"));
        fixture.tick(0);
        fixture.tick(4);
        assertEquals(2, fixture.bone.getPositionX(), EPSILON);
        layer.builder = new AnimationBuilder().loop("next");
        fixture.tick(6);
        assertEquals(2, fixture.bone.getPositionX(), EPSILON);
        fixture.tick(8);
        assertEquals(6, fixture.bone.getPositionX(), EPSILON);
        fixture.tick(10);
        assertEquals(10, fixture.bone.getPositionX(), EPSILON);
        fixture.tick(11);
        assertEquals(10, fixture.bone.getPositionX(), EPSILON);
    }

    @Test
    public void oneShotSamplesEndpointAndExecutesLastInstructionExactlyOnce() {
        var fixture = fixture(timedPosition());
        var layer = fixture.layer("main", 0, new AnimationBuilder().playOnce("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(21);
        assertEquals(20, fixture.bone.getPositionX(), EPSILON);
        assertEquals(2, fixture.value("v.events"), EPSILON);
        assertEquals(AnimationState.STOPPED, layer.controller.getAnimationState());
        fixture.tick(22);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
        assertEquals(2, fixture.value("v.events"), EPSILON);
    }

    @Test
    public void holdRetainsTheFinalPoseUntilPredicateStops() {
        var fixture = fixture(timedPosition());
        var layer = fixture.layer("main", 0, new AnimationBuilder().playAndHold("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(20);
        fixture.tick(30);
        assertEquals(20, fixture.bone.getPositionX(), EPSILON);
        assertEquals(2, fixture.value("v.events"), EPSILON);
        layer.playing = false;
        fixture.tick(32);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
    }

    @Test
    public void loopRestartsAtObservedBoundaryLikeOriginalAndCompletesPreviousTimeline() {
        var fixture = fixture(timedPosition());
        fixture.layer("main", 0, new AnimationBuilder().loop("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(25);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
        assertEquals(3, fixture.value("v.events"), EPSILON);
        fixture.tick(30);
        assertEquals(5, fixture.bone.getPositionX(), EPSILON);
        assertEquals(3, fixture.value("v.events"), EPSILON);
    }

    @Test
    public void parallelRotationsKeepUpstreamOrderedAccumulation() {
        var fixture = fixture("\"loop\":true," + bone("\"rotation\":[0,0,10]"));
        fixture.animations.putAnimation("swing", clip("swing", "\"loop\":true," + bone("\"rotation\":[0,0,20]")));
        fixture.animations.putAnimation("extra", clip("extra", "\"loop\":true," + bone("\"rotation\":[0,0,5]")));
        fixture.layer("main", 0, new AnimationBuilder().loop("test"));
        fixture.layer("swing", 0, new AnimationBuilder().loop("swing"));
        fixture.layer("parallel_0", 0, new AnimationBuilder().loop("extra"));
        fixture.tick(0);
        fixture.tick(0);
        assertEquals(Math.toRadians(35), fixture.bone.getRotationZ(), EPSILON);
        fixture.tick(1);
        assertEquals(Math.toRadians(35), fixture.bone.getRotationZ(), EPSILON);
    }

    @Test
    public void stoppedChannelsFadeFromLastPoseWithoutFrameRateDependence() {
        var fixture = fixture("\"loop\":true," + bone("\"position\":[10,0,0],\"rotation\":[0,0,40],\"scale\":[2]"));
        fixture.data.setResetSpeedInTicks(4);
        var layer = fixture.layer("main", 0, new AnimationBuilder().loop("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(5);
        layer.playing = false;
        fixture.tick(7);
        assertEquals(5, fixture.bone.getPositionX(), EPSILON);
        assertEquals(Math.toRadians(20), fixture.bone.getRotationZ(), EPSILON);
        assertEquals(1.5, fixture.bone.getScaleX(), EPSILON);
        fixture.tick(7);
        assertEquals(5, fixture.bone.getPositionX(), EPSILON);
        fixture.tick(9);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
        assertEquals(0, fixture.bone.getRotationZ(), EPSILON);
        assertEquals(1, fixture.bone.getScaleX(), EPSILON);
    }

    @Test
    public void sharedDefinitionsDoNotShareControllerTimeOrVariables() {
        var first = fixture("\"loop\":true," + bone("\"position\":[\"q.anim_time*20+v.offset\",0,0]"));
        var second = new Fixture(first.animations);
        first.layer("main", 0, new AnimationBuilder().loop("test"));
        second.layer("main", 0, new AnimationBuilder().loop("test"));
        first.value("v.offset=2;");
        second.value("v.offset=7;");
        first.tick(0);
        first.tick(0);
        second.tick(0);
        second.tick(0);
        first.tick(5);
        second.tick(10);
        assertEquals(7, first.bone.getPositionX(), EPSILON);
        assertEquals(17, second.bone.getPositionX(), EPSILON);
        assertNotSame(first.context.animationControllerContext(), second.context.animationControllerContext());
    }

    @Test
    public void zeroResetDurationProducesFiniteImmediateRestPose() {
        var fixture = fixture("\"loop\":true," + bone("\"position\":[10,0,0]"));
        fixture.data.setResetSpeedInTicks(0);
        var layer = fixture.layer("main", 0, new AnimationBuilder().loop("test"));
        fixture.tick(0);
        fixture.tick(0);
        layer.playing = false;
        fixture.tick(0);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
        assertTrue(Float.isFinite(fixture.bone.getScaleX()));
    }

    @Test
    public void zeroLengthTransitionKeepsOriginalStartupFrameBeforeSchedulingInstructions() {
        var fixture = fixture(timedPosition());
        var layer = fixture.layer("main", 0, new AnimationBuilder().playOnce("test"));
        fixture.tick(0);
        assertEquals(AnimationState.TRANSITIONING, layer.controller.getAnimationState());
        assertEquals(0, fixture.value("v.events"), EPSILON);
        fixture.tick(1);
        assertEquals(AnimationState.RUNNING, layer.controller.getAnimationState());
        assertEquals(1, layer.controller.tickOffset, EPSILON);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
        assertEquals(1, fixture.value("v.events"), EPSILON);
        fixture.tick(1);
        assertEquals(1, fixture.value("v.events"), EPSILON);
        fixture.tick(2);
        assertEquals(1, fixture.bone.getPositionX(), EPSILON);
    }

    @Test
    public void queuedClipsFinishThePreviousTimelineBeforeSwitchingBones() {
        var fixture = fixture(timedPosition());
        fixture.animations.putAnimation("next", clip("next", "\"loop\":true," + bone("\"position\":[33,0,0]")));
        fixture.layer(
            "main",
            0,
            new AnimationBuilder().playOnce("test")
                .loop("next"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(21);
        assertEquals(20, fixture.bone.getPositionX(), EPSILON);
        assertEquals(2, fixture.value("v.events"), EPSILON);
        fixture.tick(22);
        assertEquals(33, fixture.bone.getPositionX(), EPSILON);
        fixture.tick(23);
        assertEquals(33, fixture.bone.getPositionX(), EPSILON);
        assertEquals(2, fixture.value("v.events"), EPSILON);
    }

    @Test
    public void predicateResumeRestartsFinishedClipAndItsTimeline() {
        var fixture = fixture(timedPosition());
        var layer = fixture.layer("main", 0, new AnimationBuilder().playOnce("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(21);
        layer.playing = false;
        fixture.tick(22);
        layer.playing = true;
        fixture.tick(23);
        assertEquals(0, fixture.bone.getPositionX(), EPSILON);
        assertEquals(2, fixture.value("v.events"), EPSILON);
        fixture.tick(24);
        assertEquals(3, fixture.value("v.events"), EPSILON);
        fixture.tick(29);
        assertEquals(5, fixture.bone.getPositionX(), EPSILON);
    }

    @Test
    public void geometryReloadClearsVariablesAndRebindsControllerQueues() {
        var fixture = fixture(timedPosition());
        fixture.layer("main", 0, new AnimationBuilder().playAndHold("test"));
        fixture.tick(0);
        fixture.tick(0);
        fixture.tick(10);
        assertEquals(10, fixture.bone.getPositionX(), EPSILON);
        var replacement = newBone();
        fixture.processor.registerModelRenderer(Collections.singletonMap("root", replacement));
        fixture.tick(50);
        assertEquals(0, replacement.getPositionX(), EPSILON);
        assertEquals(0, fixture.value("v.events"), EPSILON);
        fixture.tick(51);
        assertEquals(1, fixture.value("v.events"), EPSILON);
        fixture.tick(56);
        assertEquals(5, replacement.getPositionX(), EPSILON);
        assertEquals(10, fixture.bone.getPositionX(), EPSILON);
    }

    @Test
    public void controllerTimesRemainIndependentWithinOneAnimatedModel() {
        var fixture = fixture("\"loop\":true," + bone("\"position\":[\"q.anim_time*20\",0,0]"));
        fixture.animations
            .putAnimation("second", clip("second", "\"loop\":true," + bone("\"rotation\":[0,0,\"q.anim_time*20\"]")));
        fixture.layer("main", 0, new AnimationBuilder().loop("test"));
        var secondary = fixture.layer("parallel_0", 0, new AnimationBuilder().loop("second"));
        secondary.playing = false;
        fixture.tick(0);
        fixture.tick(1);
        fixture.tick(5);
        secondary.playing = true;
        fixture.tick(10);
        fixture.tick(11);
        fixture.tick(16);
        assertEquals(15, fixture.bone.getPositionX(), EPSILON);
        assertEquals(Math.toRadians(5), fixture.bone.getRotationZ(), EPSILON);
    }

    @Test
    public void rateLimiterHandlesDuplicateFramesAndInvalidTimeWithoutPoisoningState() {
        var limiter = new RateLimiter(60);
        assertTrue(limiter.request(0));
        assertFalse(limiter.request(0));
        assertFalse(limiter.request(Float.NaN));
        assertTrue(limiter.request(1));
        assertFalse(limiter.request(Float.POSITIVE_INFINITY));
        assertFalse(limiter.request(0.5F));
        assertTrue(limiter.request(1.1F));
    }

    private static String timedPosition() {
        return "\"animation_length\":1,\"timeline\":{\"0\":\"v.events=v.events+1;\",\"1\":\"v.events=v.events+1;\"},"
            + bone("\"position\":{\"0\":[0,0,0],\"1\":[20,0,0]}");
    }

    private static String bone(String channels) {
        return "\"bones\":{\"root\":{" + channels + "}}";
    }

    private static Fixture fixture(String animation) {
        var file = new AnimationFile();
        file.putAnimation("test", clip(animation));
        return new Fixture(file);
    }

    private static Animation clip(String animation) {
        return clip("test", animation);
    }

    private static Animation clip(String name, String animation) {
        var json = "{\"animations\":{\"" + name + "\":{" + animation + "}}}";
        return BuiltinGeckoAnimations
            .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), MaidMolangBindings.parser())
            .getAnimation(name);
    }

    private static AnimatedGeoBone newBone() {
        return new AnimatedGeoBone(
            new GeoBone(
                Collections.emptyList(),
                "root",
                new Vector3f(),
                new Vector3f(),
                null,
                false,
                null,
                false,
                false),
            null);
    }

    private static final class Layer {

        private AnimationBuilder builder;
        private boolean playing = true;
        private final AnimationController<Fixture> controller;

        private Layer(Fixture fixture, String name, float transition, AnimationBuilder builder) {
            this.builder = builder;
            controller = new AnimationController<>(fixture, name, transition, event -> {
                if (!playing) return PlayState.STOP;
                event.getController()
                    .setAnimation(this.builder);
                return PlayState.CONTINUE;
            });
        }
    }

    private static final class Fixture implements GeckoAnimationTarget {

        private final AnimationFile animations;
        private final AnimationData data = new AnimationData();
        private final VariableStorage storage = new VariableStorage();
        private final MolangAnimationContext<MolangQueryFrame> context = new MolangAnimationContext<>(
            new MolangQueryFrame(),
            storage,
            new Random(17));
        private final AnimatedGeoBone bone = newBone();
        private final AnimationProcessor<Fixture> processor = new AnimationProcessor<>(this, storage);

        private Fixture(AnimationFile animations) {
            this.animations = animations;
            processor.registerModelRenderer(Collections.singletonMap("root", bone));
        }

        private Layer layer(String name, float transition, AnimationBuilder builder) {
            var result = new Layer(this, name, transition, builder);
            data.addAnimationController(result.controller);
            return result;
        }

        private void tick(double tick) {
            var event = new AnimationEvent<>(this, 0, 0, 0, false, Collections.emptyList());
            event.animationTick = tick;
            processor.tickAnimation(tick, event, context);
        }

        private double value(String source) {
            return context.evaluate(
                MaidMolangBindings.parser()
                    .parseExpressionRequired(source));
        }

        @Override
        public Animation getAnimation(String name) {
            return animations.getAnimation(name);
        }

        @Override
        public AnimationData getAnimationData() {
            return data;
        }
    }
}
