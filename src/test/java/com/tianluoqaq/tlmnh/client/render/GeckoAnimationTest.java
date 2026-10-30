package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import net.minecraft.util.ResourceLocation;

import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.TLMBinding;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.YSMBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.InstructionKeyFrameExecutor;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.MolangParser;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.binding.ObjectBinding;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangBindings;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 检查原资源解析和数学采样，控制器策略、动画画面及声音另行验收 */
public class GeckoAnimationTest {

    private static final float EPSILON = 1e-5F;

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void everyReferencedFileParsesWithoutSilencingExpressionErrors() throws Exception {
        var bindings = new HashMap<String, ObjectBinding>();
        bindings.put("tlm", TLMBinding.INSTANCE);
        bindings.put("ysm", YSMBinding.INSTANCE);
        var expressions = new ArrayList<IValue>();
        var parser = new MolangParser(bindings) {

            @Override
            public IValue parseExpressionRequired(String source) {
                var result = super.parseExpressionRequired(source);
                expressions.add(result);
                return result;
            }
        };
        var locations = new HashSet<ResourceLocation>();
        for (var choice : BuiltinModelCatalog.choices()) if (choice.isGecko()) locations.addAll(choice.getAnimations());
        int clips = 0;
        for (var location : locations) {
            try (var stream = resource(location)) {
                clips += BuiltinGeckoAnimations.read(stream, parser)
                    .getAllAnimations()
                    .size();
            }
        }
        assertEquals(55, locations.size());
        assertEquals(1548, clips);
        var context = context();
        var frame = context.entity();
        frame.health = 13;
        frame.maxHealth = 20;
        frame.foodLevel = 17;
        frame.partialTick = 0.5;
        frame.setEquipment(Slot.MAINHAND, "minecraft:bow", 384, 0);
        frame.netHeadYaw = 13;
        frame.headPitch = -7;
        frame.lifetime = 2;
        context.animationControllerContext()
            .setAnimTime(0.5);
        for (var expression : expressions) expression.evalUnsafe(ExpressionEvaluator.evaluator(context));
        assertTrue(expressions.size() > 1000);
        System.out.println(
            "TLMNH_GECKO_PARSE_PASS files=" + locations.size()
                + " clips="
                + clips
                + " expressions="
                + expressions.size()
                + " scope=cpu-parse-and-evaluate-only");
    }

    @Test
    public void everyGeckoChoiceSamplesAgainstItsOwnBonesAndSharesOnlyDefinitions() throws Exception {
        var reads = new HashMap<ResourceLocation, Integer>();
        var animations = new BuiltinGeckoAnimations(location -> {
            reads.merge(location, 1, Integer::sum);
            return resource(location);
        });
        var models = new BuiltinMaidModels(GeckoAnimationTest::resource);
        int choices = 0, winefox = 0, sampled = 0;
        for (var choice : BuiltinModelCatalog.choices()) {
            if (!choice.isGecko()) continue;
            var model = models.create(choice)
                .getGecko();
            var context = context();
            context.modelResources()
                .bindBones(model.bones());
            context.entity().health = context.entity().maxHealth = 20;
            var evaluator = ExpressionEvaluator.evaluator(context);
            var file = animations.load(choice);
            assertSame(file, animations.load(choice));
            for (var animation : file.getAllAnimations()) {
                for (double tick : new double[] { 0, Math.min(7, animation.animationLength / 2),
                    Math.min(25, animation.animationLength) }) {
                    context.animationControllerContext()
                        .setAnimTime(tick / 20);
                    context.modelResources()
                        .physics()
                        .update((float) tick);
                    for (var bone : animation.boneAnimations) {
                        if (model.bones()
                            .containsKey(bone.boneName)) {
                            sampled += sample(bone.rotationKeyFrames, tick, evaluator);
                            sampled += sample(bone.positionKeyFrames, tick, evaluator);
                            sampled += sample(bone.scaleKeyFrames, tick, evaluator);
                        }
                    }
                }
            }
            choices++;
            if (choice.isWinefox()) winefox++;
        }
        assertEquals(41, choices);
        assertEquals(33, winefox);
        assertEquals(55, reads.size());
        for (int count : reads.values()) assertEquals(1, count);
        assertTrue(sampled > 10000);
        System.out.println(
            "TLMNH_GECKO_SAMPLE_PASS choices=" + choices
                + " winefox="
                + winefox
                + " vectors="
                + sampled
                + " scope=cpu-keyframes-only");
    }

    @Test
    public void linearTransformsUseRadiansSignsAndDerivedEndTime() {
        var animation = animation(
            "\"rotation\":{\"0\":[0,0,0],\"1\":[90,180,270]},"
                + "\"position\":{\"0\":[1,2,3],\"1\":[5,6,7]},\"scale\":[2]");
        assertEquals(20, animation.animationLength, 0);
        var evaluator = ExpressionEvaluator.evaluator(context());
        var bone = animation.boneAnimations.get(0);
        vector(
            -Math.PI / 4,
            -Math.PI / 2,
            Math.PI * 0.75,
            GeckoKeyframes.sample(bone.rotationKeyFrames, 10, evaluator));
        vector(3, 4, 5, GeckoKeyframes.sample(bone.positionKeyFrames, 10, evaluator));
        vector(2, 2, 2, GeckoKeyframes.sample(bone.scaleKeyFrames, 0, evaluator));
        vector(5, 6, 7, GeckoKeyframes.sample(bone.positionKeyFrames, 99, evaluator));
    }

    @Test
    public void catmullRomAndPrePostDiscontinuitiesKeepTheirBoundaryValues() {
        var evaluator = ExpressionEvaluator.evaluator(context());
        var catmull = animation(
            "\"position\":{\"0\":[0,0,0]," + "\"1\":{\"post\":[10,0,0],\"lerp_mode\":\"catmullrom\"},"
                + "\"2\":[30,0,0],\"3\":[40,0,0]}").boneAnimations.get(0).positionKeyFrames;
        vector(3.75, 0, 0, GeckoKeyframes.sample(catmull, 10, evaluator));
        vector(20, 0, 0, GeckoKeyframes.sample(catmull, 30, evaluator));
        var discontinuous = animation(
            "\"position\":{\"0\":{\"pre\":[1],\"post\":[5]}," + "\"1\":{\"pre\":[9],\"post\":[20]}}").boneAnimations
                .get(0).positionKeyFrames;
        vector(5, 5, 5, GeckoKeyframes.sample(discontinuous, 0, evaluator));
        vector(7, 7, 7, GeckoKeyframes.sample(discontinuous, 10, evaluator));
        vector(20, 20, 20, GeckoKeyframes.sample(discontinuous, 20, evaluator));
        var delayed = animation("\"position\":{\"1\":{\"pre\":[1],\"post\":[4]}}").boneAnimations
            .get(0).positionKeyFrames;
        vector(1, 1, 1, GeckoKeyframes.sample(delayed, 0, evaluator));
        vector(4, 4, 4, GeckoKeyframes.sample(delayed, 20, evaluator));
    }

    @Test
    public void eachVectorExpressionGetsItsOwnTemporaryFrame() {
        var animation = animation("\"position\":[\"t.x=17;return t.x\",\"t.x ?? 5\",\"q.health\"]");
        var context = context();
        context.entity().health = 13;
        vector(
            17,
            5,
            13,
            GeckoKeyframes
                .sample(animation.boneAnimations.get(0).positionKeyFrames, 0, ExpressionEvaluator.evaluator(context)));
    }

    @Test
    public void timelineInstructionsRunOncePerCycleAndPersistScopedVariables() throws Exception {
        var file = read(
            "{\"animations\":{\"test\":{\"loop\":true,\"animation_length\":1,"
                + "\"timeline\":{\"0\":\"v.counter=(v.counter ?? 0)+1;\","
                + "\"0.5\":[\"v.counter=v.counter+2;\",\"v.saved=q.health;\"]}}}}");
        var executor = new InstructionKeyFrameExecutor(file.getAnimation("test").customInstructionKeyframes);
        var context = context();
        context.entity().health = 7;
        var evaluator = ExpressionEvaluator.evaluator(context);
        var parser = MaidMolangBindings.parser();
        executor.executeTo(evaluator, 0);
        executor.executeTo(evaluator, 0);
        assertEquals(1, context.evaluate(parser.parseExpressionRequired("v.counter")), 0);
        executor.executeTo(evaluator, 9);
        assertEquals(1, context.evaluate(parser.parseExpressionRequired("v.counter")), 0);
        executor.executeRemaining(evaluator);
        assertTrue(executor.reachEnd());
        assertEquals(3, context.evaluate(parser.parseExpressionRequired("v.counter")), 0);
        assertEquals(7, context.evaluate(parser.parseExpressionRequired("v.saved")), 0);
        executor.reset();
        executor.executeTo(evaluator, 0);
        assertEquals(4, context.evaluate(parser.parseExpressionRequired("v.counter")), 0);
    }

    @Test
    public void reloadInvalidatesDefinitionsAndFailuresCloseTheirInputStreams() throws Exception {
        var animations = new BuiltinGeckoAnimations(GeckoAnimationTest::resource);
        var choice = BuiltinModelCatalog.get("geckolib:winefox_matured");
        if (choice == null) choice = BuiltinModelCatalog.choices()
            .stream()
            .filter(c -> c.isGecko())
            .findFirst()
            .get();
        var old = animations.load(choice);
        animations.reload(GeckoAnimationTest::resource);
        assertNotSame(old, animations.load(choice));
        int[] closed = { 0 };
        var broken = new BuiltinGeckoAnimations(location -> new FilterInputStream(bytes("{")) {

            @Override
            public void close() throws IOException {
                closed[0]++;
                super.close();
            }
        });
        var selected = choice;
        assertThrows(IOException.class, () -> broken.load(selected));
        assertEquals(1, closed[0]);
    }

    @Test
    public void malformedExpressionsAndTimesFailInsteadOfProducingQuietZeroes() {
        for (var source : new String[] { "{}", "{\"animations\":{\"test\":{\"animation_length\":-1}}}",
            "{\"animations\":{\"test\":{\"bones\":{\"root\":{\"position\":[\"q.missing_query\",0,0]}}}}}",
            "{\"animations\":{\"test\":{\"bones\":{\"root\":{\"position\":{\"-1\":[0,0,0]}}}}}}",
            "{\"animations\":{\"test\":{\"timeline\":{\"NaN\":\"v.x=1;\"}}}}" }) {
            assertThrows(source, IllegalArgumentException.class, () -> read(source));
        }
    }

    private static int sample(List<BoneKeyFrame> frames, double tick, ExpressionEvaluator<?> evaluator) {
        if (frames.isEmpty()) return 0;
        var value = GeckoKeyframes.sample(frames, tick, evaluator);
        assertTrue(Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z));
        return 1;
    }

    private static Animation animation(String bone) {
        return read("{\"animations\":{\"test\":{\"loop\":true,\"bones\":{\"root\":{" + bone + "}}}}}")
            .getAnimation("test");
    }

    private static com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile read(String json) {
        return BuiltinGeckoAnimations.read(bytes(json), MaidMolangBindings.parser());
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream resource(ResourceLocation location) throws IOException {
        var stream = GeckoAnimationTest.class
            .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
        if (stream == null) throw new IOException("Missing resource " + location);
        return stream;
    }

    private static MolangAnimationContext<MolangQueryFrame> context() {
        return new MolangAnimationContext<>(new MolangQueryFrame(), new VariableStorage(), new Random(17));
    }

    private static void vector(double x, double y, double z, Vector3f actual) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}
