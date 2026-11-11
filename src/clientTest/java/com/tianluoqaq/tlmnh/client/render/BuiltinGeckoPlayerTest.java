package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangBindings;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** CPU 实体快照与实际内置资源的播放检查，不创建游戏窗口或代替画面验收 */
public class BuiltinGeckoPlayerTest {

    private static final double EPSILON = 1e-5;
    private static final String[] MAIN = { "idle", "walk", "run", "jump", "attacked", "swim_stand", "sit", "chair",
        "boat", "ladder_up", "ladder_stillness", "ladder_down", "swim", "sleep", "death", "vehicle$minecraft:player" };

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void mainPriorityPreservesLifeMovementAndCarryingStates() {
        var fixture = fixture(MAIN);
        var state = fixture.state;
        fixture.tick(0);
        assertEquals("idle", fixture.clip("main"));
        fixture.amount = 0.2F;
        fixture.tick(5);
        assertEquals("walk", fixture.clip("main"));
        state.sprinting = true;
        fixture.tick(10);
        assertEquals("run", fixture.clip("main"));
        state.onGround = false;
        fixture.tick(15);
        assertEquals("jump", fixture.clip("main"));
        state.hurt = true;
        fixture.tick(20);
        assertEquals("attacked", fixture.clip("main"));
        fixture.frame.inWater = true;
        fixture.tick(25);
        assertEquals("swim_stand", fixture.clip("main"));
        state.sitting = true;
        fixture.tick(30);
        assertEquals("sit", fixture.clip("main"));
        state.passenger = true;
        fixture.tick(35);
        assertEquals("chair", fixture.clip("main"));
        state.vehicleId = "minecraft:boat";
        fixture.tick(40);
        assertEquals("boat", fixture.clip("main"));
        state.vehicleId = "minecraft:player";
        fixture.tick(45);
        assertEquals("vehicle$minecraft:player", fixture.clip("main"));
        state.climbing = true;
        state.verticalSpeed = 1;
        fixture.tick(50);
        assertEquals("ladder_up", fixture.clip("main"));
        state.verticalSpeed = 0;
        fixture.tick(55);
        assertEquals("ladder_stillness", fixture.clip("main"));
        state.verticalSpeed = -1;
        fixture.tick(60);
        assertEquals("ladder_down", fixture.clip("main"));
        state.swimming = true;
        fixture.tick(65);
        assertEquals("swim", fixture.clip("main"));
        state.sleeping = true;
        fixture.tick(70);
        assertEquals("sleep", fixture.clip("main"));
        state.dead = true;
        fixture.tick(75);
        assertEquals("death", fixture.clip("main"));
    }

    @Test
    public void itemConditionsPreferExactIdThenClassThenUseAction() {
        var fixture = fixture(
            "idle",
            "hold_mainhand:empty",
            "hold_mainhand$sandbox:tool",
            "hold_mainhand:sword",
            "hold_mainhand:bow",
            "hold_offhand:sword",
            "use_mainhand:bow",
            "use_offhand:drink",
            "head:default");
        fixture.tick(0);
        assertEquals("hold_mainhand:empty", fixture.clip("hold_mainhand"));
        fixture.frame.setEquipment(Slot.MAINHAND, "sandbox:tool", 30, 0, "sword", "bow");
        fixture.frame.setEquipment(Slot.OFFHAND, "minecraft:iron_sword", 30, 0, "sword", "block");
        fixture.tick(3);
        assertEquals("hold_mainhand$sandbox:tool", fixture.clip("hold_mainhand"));
        assertEquals("hold_offhand:sword", fixture.clip("hold_offhand"));
        fixture.frame.setEquipment(Slot.MAINHAND, "minecraft:iron_sword", 30, 0, "sword", "bow");
        fixture.tick(6);
        assertEquals("hold_mainhand:sword", fixture.clip("hold_mainhand"));
        fixture.frame.setEquipment(Slot.MAINHAND, "minecraft:bow", 384, 0, "", "bow");
        fixture.tick(9);
        assertEquals("hold_mainhand:bow", fixture.clip("hold_mainhand"));
        fixture.state.usingItem = true;
        fixture.tick(12);
        assertEquals("use_mainhand:bow", fixture.clip("use"));
        fixture.state.usingOffhand = true;
        fixture.frame.setEquipment(Slot.OFFHAND, "minecraft:potion", 0, 0, "", "drink");
        fixture.tick(15);
        assertEquals("use_offhand:drink", fixture.clip("use"));
        fixture.frame.setEquipment(Slot.HEAD, "IC2:itemArmorQuantumHelmet", 100, 4);
        fixture.tick(18);
        assertEquals("head:default", fixture.clip("head_controller"));
    }

    @Test
    public void controllersKeepTheUpstreamOrderAndDoNotLoadMissingLayers() {
        var fixture = fixture("idle", "pre_parallel0", "parallel0");
        fixture.tick(0);
        var names = new ArrayList<String>();
        for (var controller : fixture.player.getAnimationData()
            .getAnimationControllers()) names.add(controller.getName());
        assertTrue(names.indexOf("pre_parallel_0_controller") < names.indexOf("main"));
        assertTrue(names.indexOf("hold_offhand") < names.indexOf("hold_mainhand"));
        assertTrue(names.indexOf("swing") < names.indexOf("use"));
        assertTrue(names.indexOf("passenger") < names.indexOf("parallel_0_controller"));
        assertTrue(names.indexOf("parallel_7_controller") < names.indexOf("head_controller"));
        assertEquals("pre_parallel0", fixture.clip("pre_parallel_0_controller"));
        assertEquals("parallel0", fixture.clip("parallel_0_controller"));
        assertEquals("", fixture.clip("parallel_1_controller"));
    }

    @Test
    public void headLookDoesNotAccumulateAcrossDuplicateFramesAndPauseFreezesPose() {
        var fixture = fixture("idle");
        fixture.frame.headPitch = 15;
        fixture.frame.netHeadYaw = 30;
        fixture.tick(0);
        fixture.tick(3);
        fixture.tick(3);
        var head = fixture.model.head();
        assertEquals(Math.toRadians(15), head.getRotationX(), EPSILON);
        assertEquals(Math.toRadians(30), head.getRotationY(), EPSILON);
        fixture.player.advanceClock(100, true);
        fixture.frame.headPitch = 80;
        assertFalse(fixture.player.apply(fixture.state, fixture.frame, 0, 0, 0, true));
        assertEquals(Math.toRadians(15), head.getRotationX(), EPSILON);
        fixture.player.advanceClock(200, false);
        assertEquals(3, fixture.player.getSeekTicks(), 0);
        fixture.player.apply(fixture.state, fixture.frame, 0, 0, 0, false);
        assertEquals(Math.toRadians(80), head.getRotationX(), EPSILON);
    }

    @Test
    public void repeatedSwingRestartsOnceWithoutRestartingEveryRender() {
        var fixture = fixture("idle", "swing_hand");
        fixture.state.swinging = true;
        fixture.state.swingTick = 0;
        fixture.tick(0);
        fixture.state.swingTick = 3;
        fixture.tick(3);
        var controller = fixture.controller("swing");
        double offset = controller.tickOffset;
        fixture.tick(4);
        assertEquals(offset, controller.tickOffset, 0);
        fixture.state.swingTick = 0;
        fixture.tick(5);
        assertEquals(5, controller.tickOffset, 0);
    }

    @Test
    public void snowballPickupKeepsPlayingUntilEmptyResetAndBeggingKeepsPriority() {
        var fixture = fixture("idle", "beg", "pick_up_snowball", "empty");
        fixture.state.pickingUpSnowball = true;
        fixture.tick(0);
        assertEquals("pick_up_snowball", fixture.clip("misc"));
        fixture.tick(6);
        double firstOffset = fixture.controller("misc").tickOffset;
        fixture.tick(7);
        assertEquals(firstOffset, fixture.controller("misc").tickOffset, 0);
        fixture.tick(8);
        assertEquals(firstOffset, fixture.controller("misc").tickOffset, 0);
        fixture.state.begging = true;
        fixture.tick(11);
        assertEquals("beg", fixture.clip("misc"));
        fixture.state.begging = false;
        fixture.state.pickingUpSnowball = false;
        fixture.state.snowballAnimationExpired = true;
        fixture.tick(14);
        assertEquals("empty", fixture.clip("misc"));
        fixture.state.snowballAnimationExpired = false;
        fixture.state.pickingUpSnowball = true;
        fixture.tick(18);
        assertEquals("pick_up_snowball", fixture.clip("misc"));
        assertEquals(18, fixture.controller("misc").tickOffset, 0);
    }

    @Test
    public void everyGeckoChoicePlaysCoreStatesWithItsOwnControllerAndPhysics() throws Exception {
        var models = new BuiltinMaidModels(BuiltinGeckoPlayerTest::resource);
        int choices = 0, winefox = 0, frames = 0;
        for (var choice : BuiltinModelCatalog.choices()) {
            if (!choice.isGecko()) continue;
            var model = models.create(choice);
            var player = model.getGeckoPlayer();
            assertNotSame(
                player,
                models.create(choice)
                    .getGeckoPlayer());
            for (int scenario = 0; scenario < 10; scenario++) {
                var state = new MaidAnimationState(new UUID(0, 17));
                var query = new MolangQueryFrame();
                query.health = query.maxHealth = 20;
                query.headPitch = 7;
                query.netHeadYaw = 13;
                float amount = 0;
                if (scenario == 1) amount = 0.5F;
                if (scenario == 2) state.sitting = true;
                if (scenario == 3) state.sleeping = true;
                if (scenario == 4) state.hurt = true;
                if (scenario == 5) {
                    state.swinging = true;
                    state.swingTick = 1;
                }
                if (scenario == 6 || scenario == 7) {
                    state.usingItem = true;
                    state.usingOffhand = scenario == 7;
                    query.setEquipment(
                        state.usingOffhand ? Slot.OFFHAND : Slot.MAINHAND,
                        "minecraft:bow",
                        384,
                        0,
                        "",
                        "bow");
                }
                if (scenario == 8) state.begging = true;
                if (scenario == 9) {
                    state.passenger = true;
                    state.vehicleId = "minecraft:player";
                }
                for (int step : new int[] { 0, 3, 7 }) {
                    player.advanceClock(scenario * 12 + step, false);
                    player.apply(state, query, step, amount, 0, false);
                    String expectedMain = scenario == 1 ? "walk"
                        : scenario == 2 ? "sit"
                            : scenario == 3 ? "sleep"
                                : scenario == 4 ? "attacked"
                                    : scenario == 9 ? player.getAnimation("vehicle$minecraft:player") == null ? "chair"
                                        : "vehicle$minecraft:player" : "idle";
                    assertEquals(choice.getId() + " scenario=" + scenario, expectedMain, selected(player, "main"));
                    for (var bone : model.getGecko()
                        .bones()
                        .values()) {
                        for (float value : new float[] { bone.getRotationX(), bone.getRotationY(), bone.getRotationZ(),
                            bone.getPositionX(), bone.getPositionY(), bone.getPositionZ(), bone.getScaleX(),
                            bone.getScaleY(), bone.getScaleZ() })
                            assertTrue(
                                choice.getId() + " scenario=" + scenario + " bone=" + bone.getName(),
                                Float.isFinite(value));
                    }
                    frames++;
                }
            }
            choices++;
            if (choice.isWinefox()) winefox++;
        }
        assertEquals(41, choices);
        assertEquals(33, winefox);
        assertEquals(1230, frames);
        System.out.println(
            "TLMNH_GECKO_PLAYER_PASS choices=" + choices
                + " winefox="
                + winefox
                + " frames="
                + frames
                + " scope=cpu-controller-and-pose-only");
    }

    private static Fixture fixture(String... clips) {
        var definitions = new LinkedHashMap<String, Animation>();
        for (String name : clips) {
            String json = "{\"animations\":{\"" + name
                + "\":{\"loop\":true,\"animation_length\":1,\"bones\":{\"Root\":{\"position\":[0,0,0]}}}}}";
            var file = BuiltinGeckoAnimations
                .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), MaidMolangBindings.parser());
            definitions.put(name, file.getAnimation(name));
        }
        String geometry = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
            + "\"bones\":[{\"name\":\"Root\",\"pivot\":[0,0,0]},{\"name\":\"Head\",\"parent\":\"Root\",\"pivot\":[0,24,0]}]}]}";
        var model = new AnimatedGeoModel(
            GeoBuilder.getGeoBuilder()
                .constructGeoModel(RawGeometryTree.parseHierarchy(Converter.fromJsonString(geometry))));
        return new Fixture(model, new BuiltinGeckoPlayer(model, new AnimationFile(definitions), 17));
    }

    private static InputStream resource(ResourceLocation location) throws IOException {
        var result = BuiltinGeckoPlayerTest.class
            .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
        if (result == null) throw new IOException("Missing resource " + location);
        return result;
    }

    private static String selected(BuiltinGeckoPlayer player, String name) {
        for (var controller : player.getAnimationData()
            .getAnimationControllers()) {
            if (name.equals(controller.getName())) {
                var animation = controller.getCurrentAnimation();
                return animation == null ? "" : animation.animationName;
            }
        }
        throw new AssertionError("Missing controller " + name);
    }

    private static final class Fixture {

        private final AnimatedGeoModel model;
        private final BuiltinGeckoPlayer player;
        private final MaidAnimationState state = new MaidAnimationState(new UUID(0, 17));
        private final MolangQueryFrame frame = new MolangQueryFrame();
        private float amount;

        private Fixture(AnimatedGeoModel model, BuiltinGeckoPlayer player) {
            this.model = model;
            this.player = player;
        }

        private void tick(double tick) {
            player.advanceClock(tick, false);
            player.apply(state, frame, 0, amount, 0, false);
        }

        private AnimationController<?> controller(String name) {
            for (var controller : player.getAnimationData()
                .getAnimationControllers()) if (name.equals(controller.getName())) return controller;
            throw new AssertionError("Missing controller " + name);
        }

        private String clip(String controller) {
            var animation = controller(controller).getCurrentAnimation();
            return animation == null ? "" : animation.animationName;
        }
    }
}
