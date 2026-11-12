package com.tianluoqaq.tlmnh.reference.sleep;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Narrow complete-root episodes supplementing the retained four-mechanism combat inputs. */
@GameTestHolder("tlmnh_combat_boundaries")
@PrefixGameTestTemplate(false)
public final class CombatBoundaryGameTests {
    private CombatBoundaryGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 280, batch = "boundary_stationary")
    public static void stationaryTarget(GameTestHelper h) { collect(h, "stationary"); }

    @GameTest(template = "cell", timeoutTicks = 320, batch = "boundary_peek")
    public static void pillarAndDoorway(GameTestHelper h) { collect(h, "peek"); }

    @GameTest(template = "cell", timeoutTicks = 420, batch = "boundary_finite")
    public static void naturalAmmoExhaustion(GameTestHelper h) { collect(h, "finite"); }

    static void collect(GameTestHelper h, String scene) {
        FollowMechanismGameTests.collect(h, new Session(h, scene), "tlmnh.combatBoundariesProbe");
    }

    public static final class Session extends CombatMechanismGameTests.Session {
        final JsonArray boundaryEvents = new JsonArray();
        ItemStack reserve, reserveSnapshot;
        boolean restockPending;
        JsonArray targetPlan;
        String targetInputPhase = "registered_spawn_before_first_update";
        int firstZero = -1, secondZero = -1, restockAt = -1;
        int firstDryFrames, secondDryFrames;

        Session(GameTestHelper h, String scene) {
            super(h, scene, "/tlmnh-combat-boundaries/contract.json", "COMBAT_BOUNDARIES_V2",
                "combat-boundary-original-v1", "combat-boundary-original-");
            targetPlan = vector(new Vec3(17.5, 1, 4.5));
            reserve = scene.equals("finite") ? new ItemStack(Items.ARROW) : ItemStack.EMPTY;
            reserveSnapshot = reserve.copy();
            output.add("boundaryEvents", boundaryEvents);
            output.addProperty("endObserverScope", "Read native END facts and queue next START inputs only");
            output.addProperty("sourceRefusals", "UNKNOWN; no inferred original release-gate telemetry");
            output.addProperty("oldCombatSamplesReplaced", false);
        }

        @Override String identityProperty() { return "tlmnh.combatBoundaries.buildIdentity"; }
        @Override int initialAmmoCount() { return mechanism.equals("finite") ? 3 : 24; }
        @Override int additionalExternalAmmo() { return reserve.getCount(); }
        @Override int ammoBudget() { return mechanism.equals("finite") ? 4 : 24; }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            helper.assertTrue(arrowCount() == initialAmmoCount(), "Initial actual arrow inventory differs");
            output.add("ammunitionInitial", item(maid.getMaidInv().getStackInSlot(0)));
            output.add("externalReserveInitial", item(reserve));
            output.addProperty("initialInventoryArrows", arrowCount());
            output.addProperty("initialExternalReserveArrows", reserve.getCount());
            output.addProperty("ammoBudget", ammoBudget());
        }

        @Override void beforeWorld() {
            super.beforeWorld();
            if (maid == null || target == null) return;
            int index = frames.size();
            targetInputPhase = "START_before_world_update";
            targetPlan = vector(new Vec3(17.5, 1, 4.5));
            if (mechanism.equals("peek") && target.isAlive()) {
                double z = 4.5 + (index < 40 ? 0 : 1.7 * Math.sin((index - 40) * .12));
                targetPlan = vector(new Vec3(17.5, 1, z));
                target.setPos(origin.getX() + 17.5, origin.getY() + 1, origin.getZ() + z);
                target.setDeltaMovement(Vec3.ZERO);
                if (index == 40 || index == 140) {
                    for (int y = 1; y < 10; y++) for (int zCell = 1; zCell < 8; zCell++) {
                        boolean solid = index == 40 ? zCell == 4 : zCell != 4 || y > 3;
                        helper.setBlock(13, y, zCell, solid ? Blocks.STONE : Blocks.AIR);
                    }
                    var event = boundaryEvent(index == 40 ? "pillar" : "doorway", index);
                    event.add("layoutAfter", layout());
                }
            }
            if (restockPending) {
                helper.assertTrue(mechanism.equals("finite") && phase == 1 && arrowCount() == 0
                    && maid.getMaidInv().getStackInSlot(0).isEmpty() && reserve.getCount() == 1
                    && ItemStack.matches(reserve, reserveSnapshot), "Owned one-arrow restock input changed");
                var event = boundaryEvent("insert_owned_one_arrow", index);
                event.add("reserveBefore", item(reserve));
                maid.getMaidInv().setStackInSlot(0, reserve);
                reserve = ItemStack.EMPTY;
                restockPending = false;
                restockAt = index;
                phase = 2;
                event.add("slotAfter", item(maid.getMaidInv().getStackInSlot(0)));
                event.addProperty("inventoryArrowsAfter", arrowCount());
                event.addProperty("externalReserveArrowsAfter", reserve.getCount());
            }
            targetCall = state(target);
        }

        JsonObject boundaryEvent(String action, int index) {
            var event = new JsonObject();
            event.addProperty("frame", index);
            event.addProperty("action", action);
            event.addProperty("phase", "START_before_world_update");
            event.addProperty("actualArrowJoinsBefore", shots.size());
            event.add("target", state(target));
            boundaryEvents.add(event);
            return event;
        }

        @Override void afterFrame(int index) {
            super.afterFrame(index);
            var row = frames.get(index).getAsJsonObject();
            if (mechanism.equals("finite")) {
                if (phase == 0 && arrowCount() == 0 && shots.size() == 3) {
                    firstZero = index;
                    phase = 1;
                } else if (phase == 1) {
                    helper.assertTrue(arrowCount() == 0 && shots.size() == 3 && weapon.getDamageValue() == 3
                        && ItemStack.matches(reserve, reserveSnapshot), "Original dry-ammo window changed resources");
                    firstDryFrames = index - firstZero;
                    if (firstDryFrames >= 40) restockPending = true;
                } else if (phase == 2 && arrowCount() == 0 && shots.size() == 4) {
                    secondZero = index;
                    phase = 3;
                } else if (phase == 3) {
                    helper.assertTrue(arrowCount() == 0 && shots.size() == 4 && weapon.getDamageValue() == 4
                        && reserve.isEmpty(), "Original final dry-ammo window changed resources");
                    secondDryFrames = index - secondZero;
                }
            }
            row.add("boundaryTargetInputPlan", targetPlan);
            row.addProperty("boundaryTargetInputPhase", targetInputPhase);
            row.addProperty("phase", phase);
            row.addProperty("ammoBudget", ammoBudget());
            row.addProperty("reserveArrows", reserve.getCount());
            row.addProperty("firstDryCompletedFrames", firstDryFrames);
            row.addProperty("secondDryCompletedFrames", secondDryFrames);
            row.addProperty("boundaryGeometry", mechanism.equals("peek")
                ? index < 40 ? "clear" : index < 140 ? "pillar" : "doorway" : "clear");
            if (index + 1 == frameLimit()) {
                output.addProperty("firstNaturalZeroFrame", firstZero);
                output.addProperty("restockStartFrame", restockAt);
                output.addProperty("secondNaturalZeroFrame", secondZero);
                output.addProperty("firstDryCompletedFrames", firstDryFrames);
                output.addProperty("secondDryCompletedFrames", secondDryFrames);
                output.add("externalReserveFinal", item(reserve));
                output.add("ammunitionFinal", item(maid.getMaidInv().getStackInSlot(0)));
                output.addProperty("finiteSequenceComplete", phase == 3 && firstDryFrames >= 40 && secondDryFrames >= 40);
                output.addProperty("resourceAssertionsPassed", true);
            }
        }
    }
}
