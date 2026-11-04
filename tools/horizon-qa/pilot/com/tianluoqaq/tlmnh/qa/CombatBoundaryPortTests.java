package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;

/** Three bounded natural-root inputs; constructor-time profiles reuse the native arrow observer. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class CombatBoundaryPortTests {

    private CombatBoundaryPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "boundary_stationary_f")
    public static void faithfulSourceStationary(GameTestHelper h) {
        run(h, "stationary", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "boundary_stationary_f_r")
    public static void faithfulNativeStationary(GameTestHelper h) {
        run(h, "stationary", "F-R");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "boundary_stationary_n_static")
    public static void enhancedStaticStationary(GameTestHelper h) {
        run(h, "stationary", "N-static");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "boundary_stationary_n_last")
    public static void enhancedLastSampleStationary(GameTestHelper h) {
        run(h, "stationary", "N-last");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "boundary_stationary_n")
    public static void enhancedMotionStationary(GameTestHelper h) {
        run(h, "stationary", "N");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "boundary_peek_f")
    public static void faithfulSourcePeek(GameTestHelper h) {
        run(h, "peek", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "boundary_peek_f_r")
    public static void faithfulNativePeek(GameTestHelper h) {
        run(h, "peek", "F-R");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "boundary_peek_n_static")
    public static void enhancedStaticPeek(GameTestHelper h) {
        run(h, "peek", "N-static");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "boundary_peek_n_last")
    public static void enhancedLastSamplePeek(GameTestHelper h) {
        run(h, "peek", "N-last");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "boundary_peek_n")
    public static void enhancedMotionPeek(GameTestHelper h) {
        run(h, "peek", "N");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "boundary_finite_f")
    public static void faithfulSourceFinite(GameTestHelper h) {
        run(h, "finite", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "boundary_finite_n")
    public static void enhancedMotionFinite(GameTestHelper h) {
        run(h, "finite", "N");
    }

    private static void run(GameTestHelper h, String scene, String arm) {
        var c = new Collector(h, scene, arm);
        h.afterTest(c::close);
        try {
            c.setup();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare complete combat boundary", error);
        }
        h.onEachTick("complete combat boundary", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
    }

    public static final class Collector extends CombatMechanismPortTests.Collector {

        final JsonArray boundaryEvents = new JsonArray();
        ItemStack reserve, reserveSnapshot;
        boolean restockPending;
        JsonArray targetPlan;
        String targetInputPhase = "registered_spawn_before_first_update";
        int firstZero = -1, secondZero = -1, restockAt = -1;
        int firstDryFrames, secondDryFrames;

        Collector(GameTestHelper h, String scene, String arm) {
            super(h, scene, arm);
            targetPlan = NaturalRangedPairTests.vector(17.5, 1, 4.5);
            ammunition = new ItemStack(Items.arrow, scene.equals("finite") ? 3 : 24);
            reserve = scene.equals("finite") ? new ItemStack(Items.arrow) : null;
            reserveSnapshot = reserve == null ? null : reserve.copy();
            report.add("boundaryEvents", boundaryEvents);
            report.addProperty("endObserverScope", "Read native END facts and queue next START inputs only");
            report.addProperty("oldCombatSamplesReplaced", false);
        }

        @Override
        String contractResource() {
            return "/tlmnh-combat-boundaries/contract.json";
        }

        @Override
        String expectedContract() {
            return "COMBAT_BOUNDARIES_V1";
        }

        @Override
        String captureSchema() {
            return "combat-boundary-port-v1";
        }

        @Override
        int frameLimit() {
            return scene.equals("finite") ? 360 : scene.equals("peek") ? 260 : 220;
        }

        int reserveCount() {
            return reserve == null ? 0 : reserve.stackSize;
        }

        int ammoBudget() {
            return scene.equals("finite") ? 4 : 24;
        }

        @Override
        void setup() throws Exception {
            super.setup();
            h.assertTrue(arrowCount() == (scene.equals("finite") ? 3 : 24), "Initial finite arrow input differs");
            report.add("ammunitionInitial", item(ammunition));
            report.add("externalReserveInitial", item(reserve));
            report.addProperty("initialInventoryArrows", arrowCount());
            report.addProperty("initialExternalReserveArrows", reserveCount());
            report.addProperty("ammoBudget", ammoBudget());
            report.add(
                "boundaryObserverArtifact",
                DevelopmentArtifactIdentity.capture(CombatBoundaryPortTests.class, Collector.class));
        }

        @Override
        void applyInputs() {
            super.applyInputs();
            targetInputPhase = "START_before_world_update";
            targetPlan = NaturalRangedPairTests.vector(17.5, 1, 4.5);
            if (scene.equals("peek") && target.isEntityAlive()) {
                double z = 4.5 + (frame < 40 ? 0 : 1.7 * Math.sin((frame - 40) * .12));
                targetPlan = NaturalRangedPairTests.vector(17.5, 1, z);
                target.setPosition(f.x + 17.5, f.y + 1, f.z + z);
                target.motionX = target.motionY = target.motionZ = 0;
                if (frame == 40 || frame == 140) {
                    for (int y = 1; y < 10; y++) for (int zCell = 1; zCell < 8; zCell++) {
                        boolean solid = frame == 40 ? zCell == 4 : zCell != 4 || y > 3;
                        h.setBlock(13, y, zCell, solid ? Blocks.stone : Blocks.air);
                    }
                    var event = boundaryEvent(frame == 40 ? "pillar" : "doorway");
                    event.add("layoutAfter", layout());
                }
            }
            if (restockPending) {
                h.assertTrue(
                    scene.equals("finite") && phase == 1
                        && arrowCount() == 0
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0) == null
                        && reserveCount() == 1
                        && ItemStack.areItemStacksEqual(reserve, reserveSnapshot),
                    "Owned one-arrow restock input changed");
                var event = boundaryEvent("insert_owned_one_arrow");
                event.add("reserveBefore", item(reserve));
                f.maid.getMaidInventory()
                    .setInventorySlotContents(0, reserve);
                reserve = null;
                restockPending = false;
                restockAt = frame;
                phase = 2;
                event.add(
                    "slotAfter",
                    item(
                        f.maid.getMaidInventory()
                            .getStackInSlot(0)));
                event.addProperty("inventoryArrowsAfter", arrowCount());
                event.addProperty("externalReserveArrowsAfter", reserveCount());
            }
            targetInput = entity(target, false);
        }

        JsonObject boundaryEvent(String action) {
            var event = new JsonObject();
            event.addProperty("frame", frame);
            event.addProperty("action", action);
            event.addProperty("phase", "START_before_world_update");
            event.addProperty("actualArrowJoinsBefore", shots.size());
            event.add("target", entity(target, false));
            boundaryEvents.add(event);
            return event;
        }

        @Override
        void afterObservedFrame(JsonObject row) {
            if (scene.equals("finite")) {
                if (phase == 0 && arrowCount() == 0 && shots.size() == 3) {
                    firstZero = frame;
                    phase = 1;
                } else if (phase == 1) {
                    h.assertTrue(
                        arrowCount() == 0 && shots.size() == 3
                            && weapon.getItemDamage() == 3
                            && ItemStack.areItemStacksEqual(reserve, reserveSnapshot),
                        "Native dry-ammo window changed resources");
                    firstDryFrames = frame - firstZero;
                    if (firstDryFrames >= 40) restockPending = true;
                } else if (phase == 2 && arrowCount() == 0 && shots.size() == 4) {
                    secondZero = frame;
                    phase = 3;
                } else if (phase == 3) {
                    h.assertTrue(
                        arrowCount() == 0 && shots.size() == 4 && weapon.getItemDamage() == 4 && reserve == null,
                        "Native final dry-ammo window changed resources");
                    secondDryFrames = frame - secondZero;
                }
            }
            row.add("boundaryTargetInputPlan", targetPlan);
            row.addProperty("boundaryTargetInputPhase", targetInputPhase);
            row.addProperty("phase", phase);
            row.addProperty("ammoBudget", ammoBudget());
            row.addProperty("reserveArrows", reserveCount());
            row.addProperty("externallyHeldArrows", reserveCount());
            row.addProperty("firstDryCompletedFrames", firstDryFrames);
            row.addProperty("secondDryCompletedFrames", secondDryFrames);
            row.addProperty(
                "boundaryGeometry",
                scene.equals("peek") ? frame < 40 ? "clear" : frame < 140 ? "pillar" : "doorway" : "clear");
        }

        @Override
        void validateResources() {
            h.assertTrue(
                heldAmmo == null && heldBow == null
                    && arrowCount() + reserveCount() + bow.getShots() == ammoBudget()
                    && shots.size() == bow.getShots()
                    && weapon.getItemDamage() == shots.size(),
                "Finite inventory/reserve/actual arrow joins or exactly-once bow wear lost conservation");
        }

        @Override
        void finish() {
            report.addProperty("firstNaturalZeroFrame", firstZero);
            report.addProperty("restockStartFrame", restockAt);
            report.addProperty("secondNaturalZeroFrame", secondZero);
            report.addProperty("firstDryCompletedFrames", firstDryFrames);
            report.addProperty("secondDryCompletedFrames", secondDryFrames);
            report.add("externalReserveFinal", item(reserve));
            report.add(
                "ammunitionFinal",
                item(
                    f.maid.getMaidInventory()
                        .getStackInSlot(0)));
            report.addProperty("finiteSequenceComplete", phase == 3 && firstDryFrames >= 40 && secondDryFrames >= 40);
            report.addProperty("resourceAssertionsPassed", true);
            super.finish();
        }

        static JsonObject item(ItemStack stack) {
            var tag = new NBTTagCompound();
            if (stack != null) stack.writeToNBT(tag);
            return SleepAttributePortSnapshot.binaryNbt(tag);
        }

        @Override
        String outputName() {
            return "combat-boundary-" + scene + "-" + arm + ".json";
        }
    }
}
