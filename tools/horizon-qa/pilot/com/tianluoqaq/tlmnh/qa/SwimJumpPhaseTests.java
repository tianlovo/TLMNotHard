package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SwimJumpPhaseTests {

    private SwimJumpPhaseTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 10, batch = "swim_jump_phase")
    public static void sourceJumpStartsInsideBrainAndCancellationClearsItsRequest(GameTestHelper helper)
        throws Exception {
        var fixture = new ModernCoreTests.Fixture(helper, false);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 1, z, Blocks.water);
        fixture.maid.setPosition(fixture.x + 2.5, fixture.y + 1.2, fixture.z + 4.5);
        fixture.owner.setPosition(fixture.x + 3.5, fixture.y + 1.2, fixture.z + 4.5);
        var observer = new Observer(helper, fixture);
        fixture.maid.getBrainCoordinator()
            .setAfterNavigationActionsForDevelopment(observer);
        FMLCommonHandler.instance()
            .bus()
            .register(observer);
        helper.afterTest(
            () -> {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(observer);
            });
        helper.onEachTick(
            "actual pre-Brain and post-native jump phases",
            () -> { if (observer.failure != null) throw observer.failure; });
        helper.succeedWhen(() -> observer.complete);
    }

    public static final class Observer implements MaidPolicyRuntime.AfterNavigationActions {

        private final GameTestHelper helper;
        private final ModernCoreTests.Fixture fixture;
        private final MaidSwimJumpTask brainJump, prototypeJump;
        private final java.lang.reflect.Field pending;
        private boolean before, complete;
        private RuntimeException failure;

        Observer(GameTestHelper helper, ModernCoreTests.Fixture fixture) throws Exception {
            this.helper = helper;
            this.fixture = fixture;
            var field = fixture.core.getClass()
                .getDeclaredField("swimJump");
            field.setAccessible(true);
            brainJump = (MaidSwimJumpTask) field.get(fixture.core);
            var aquatic = fixture.maid.getAquaticController();
            field = aquatic.getClass()
                .getDeclaredField("jump");
            field.setAccessible(true);
            prototypeJump = (MaidSwimJumpTask) field.get(aquatic);
            pending = aquatic.getClass()
                .getDeclaredField("pendingJump");
            pending.setAccessible(true);
        }

        @Override
        public void tick(MaidPolicyContext.Token token) {
            if (complete || failure != null) return;
            try {
                if (!before) {
                    var aquatic = fixture.maid.getAquaticController();
                    helper.assertTrue(
                        aquatic.inWater() && aquatic.waterHeight() > aquatic.fluidJumpThreshold()
                            && !aquatic.underWater(),
                        "Partial-water fixture did not enable passive jumping");
                    helper.assertTrue(
                        brainJump.getEndTimestamp() == 0 && prototypeJump.getEndTimestamp() == 0,
                        "Swim jump ran before the actual Brain phase");
                    before = true;
                }
                fixture.core.tick(token);
                helper.assertTrue(
                    brainJump.getEndTimestamp() > 0 && prototypeJump.getEndTimestamp() == 0,
                    "Actual Brain did not exclusively start its own swim jump");
            } catch (RuntimeException | AssertionError | LinkageError error) {
                failure = new IllegalStateException("Actual Brain phase probe failed", error);
            }
        }

        @Override
        public void afterLivingUpdate(MaidPolicyContext.Token token) {
            if (failure == null) fixture.core.afterLivingUpdate(token);
        }

        @Override
        public void cancel() {
            fixture.core.cancel();
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !before || complete || failure != null) return;
            try {
                helper.assertTrue(
                    brainJump.getEndTimestamp() > 0 && prototypeJump.getEndTimestamp() == 0,
                    "Actual Brain did not exclusively start its own swim jump");
                fixture.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    !brainJump.isRunning() && !pending.getBoolean(fixture.maid.getAquaticController()),
                    "Cancelled Brain kept a jump request");
                System.out.println("TLMNH_SWIM_JUMP_PHASE_PASS pre=stopped post=brain_only cancel=clear");
                complete = true;
            } catch (IllegalAccessException error) {
                failure = new IllegalStateException("Cannot inspect owned jump request", error);
            } catch (RuntimeException | AssertionError | LinkageError error) {
                failure = new IllegalStateException("Actual Brain cancellation probe failed", error);
            }
        }
    }
}
