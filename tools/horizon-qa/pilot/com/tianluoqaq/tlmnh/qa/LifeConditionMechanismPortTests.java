package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Two finite life episodes, using the existing complete-root native collection boundaries. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class LifeConditionMechanismPortTests {

    static final String RESOURCE = "/tlmnh-life-condition-mechanisms/contract.json";
    static final String CONTRACT = "LIFE_CONDITION_MECHANISMS_V1";

    private LifeConditionMechanismPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 3140, batch = "life_condition_pantry_f")
    public static void faithfulCabinetConditions(GameTestHelper h) {
        collect(h, false, "cabinet_conditions");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 3140, batch = "life_condition_pantry_n")
    public static void enhancedCabinetConditions(GameTestHelper h) {
        collect(h, true, "cabinet_conditions");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1340, batch = "life_condition_bed_f")
    public static void faithfulSharedBedHandoff(GameTestHelper h) {
        collect(h, false, "shared_bed_handoff");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1340, batch = "life_condition_bed_n")
    public static void enhancedSharedBedHandoff(GameTestHelper h) {
        collect(h, true, "shared_bed_handoff");
    }

    static void collect(GameTestHelper h, boolean enhanced, String mechanism) {
        var c = new Collector(h, enhanced ? MaidBrainProfile.ENHANCED : MaidBrainProfile.FAITHFUL_TLM, mechanism);
        h.afterTest(c::close);
        h.onEachTick("normal finite life conditions", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
        c.setup();
    }

    public static final class Collector extends TerrainLifeMechanismPortTests.PantryCollector {

        final JsonArray consumeEvents = new JsonArray();
        int primaryConsumes, secondaryConsumes, previousConsumes;
        int placementFrame = -1, firstSleep = -1, releaseFrame = -1, firstAwake = -1, successorSleep = -1;
        EntityMaid firstOccupant;
        ItemStack initialCake;
        boolean releasePending;

        Collector(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
            super(h, profile, mechanism, RESOURCE, CONTRACT, "life-condition-port-v1", "life-condition-");
            output.add("consumeEvents", consumeEvents);
        }

        @Override
        void setup() {
            super.setup();
            setPlannedOwner(scene.getAsJsonArray("ownerPosition"));
            try {
                output.add(
                    "lifeConditionObserverArtifact",
                    DevelopmentArtifactIdentity.capture(LifeConditionMechanismPortTests.class, Collector.class));
                output.add(
                    "lifeConditionProductionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        TileEntityMaidBed.class,
                        Class.forName("com.tianluoqaq.tlmnh.common.ai.faithful.MaidCakeAction"),
                        Class.forName("com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernLife")));
            } catch (Exception error) {
                throw new IllegalStateException("Life condition identities unavailable", error);
            }
            output.addProperty(
                "inputSchedule",
                "Only normal commands/world inputs at native START; native END observation is read-only");
            if (mechanism.equals("cabinet_conditions")) {
                // Both shells exist before actors join. No later shell placement can trap an actor inside it.
                shell(8, 16, 8, 16, 5);
                shell(10, 14, 10, 14, 4);
            }
        }

        void shell(int minX, int maxX, int minZ, int maxZ, int top) {
            for (int a = minX; a <= maxX; a++) for (int b = 1; b <= top; b++)
                for (int c = minZ; c <= maxZ; c++) if (a == minX || a == maxX || c == minZ || c == maxZ || b == top)
                    helper.setBlock(a, b, c, Blocks.glass);
        }

        @Override
        void beforeSpawn() {
            super.beforeSpawn();
            helper.setBlock(12, 2, 12, Blocks.air);
            helper.setBlock(12, 1, 12, Blocks.air);
            if (mechanism.equals("cabinet_conditions")) {
                var input = new ItemStack(Items.cake);
                var payload = new NBTTagCompound();
                payload.setInteger("LifeConditionInput", 201);
                payload.setIntArray("UnknownCakePayload", new int[] { 7, -17, 65537 });
                input.setTagCompound(payload);
                maid.getMaidInventory()
                    .setInventorySlotContents(0, input);
                initialCake = maid.getMaidInventory()
                    .getStackInSlot(0)
                    .copy();
                var fullItem = new NBTTagCompound();
                initialCake.writeToNBT(fullItem);
                output.add("initialCakeInput", SleepAttributePortSnapshot.binaryNbt(fullItem));
                output.addProperty(
                    "initialCakeItemRegistry",
                    net.minecraft.item.Item.itemRegistry.getNameForObject(initialCake.getItem()));
                output.addProperty("initialCakeCount", initialCake.stackSize);
                output.addProperty("initialCakeDamage", initialCake.getItemDamage());
            } else {
                helper.setBlock(12, 1, 13, MaidLifeContent.maidBed, 2);
                helper.setBlock(12, 1, 12, MaidLifeContent.maidBed, 10);
                maid.setSchedule(MaidSchedule.DAY);
                second.setSchedule(MaidSchedule.DAY);
                maid.setHunger(20);
                second.setHunger(20);
                maid.getHomeArea()
                    .restrictToCurrentSchedule();
                second.getHomeArea()
                    .restrictToCurrentSchedule();
            }
            output.add("secondInitial", TerrainLifeMechanismPortTests.raw(second, x, y, z));
            output.add("secondAttributeInputs", SleepAttributePortSnapshot.capture(second));
            output.addProperty("initialCakeUnits", mechanism.equals("cabinet_conditions") ? 1 : 0);
            output.add("initialLifeTileInput", tileInput());
        }

        JsonObject tileInput() {
            var row = new JsonObject();
            var tile = helper.getWorld()
                .getTileEntity(x + 12, y + 1, z + 12);
            row.addProperty("present", tile != null);
            if (tile != null) {
                row.addProperty(
                    "class",
                    tile.getClass()
                        .getName());
                var tag = new NBTTagCompound();
                tile.writeToNBT(tag);
                row.add("binaryNBT", SleepAttributePortSnapshot.binaryNbt(tag));
            }
            return row;
        }

        JsonObject startEvent(String kind) {
            var event = new JsonObject();
            event.addProperty("frame", frames.size());
            event.addProperty("kind", kind);
            event.addProperty("phase", "START_before_native_world_update");
            events.add(event);
            return event;
        }

        @Override
        void applyOwner() {
            super.applyOwner();
            if (maid == null) return;
            int index = frames.size();
            if (mechanism.equals("cabinet_conditions")) {
                if (index == 200) {
                    helper.setBlock(12, 1, 12, MaidLifeContent.snackCabinet);
                    var event = startEvent("place_native_empty_cabinet_inside_existing_closed_shell");
                    event.add("layoutAfter", layout());
                    event.add("tileAfter", tileInput());
                }
                if (index == 800) {
                    helper.setBlock(10, 1, 12, Blocks.air);
                    helper.setBlock(10, 2, 12, Blocks.air);
                    var event = startEvent("open_two_high_native_gate");
                    event.add("layoutAfter", layout());
                    event.add("tileAfter", tileInput());
                }
            } else if (releasePending && releaseFrame < 0) {
                releaseFrame = index;
                var event = startEvent("first_occupant_normal_all_and_wait");
                event.addProperty(
                    "occupantUuid",
                    firstOccupant.getUniqueID()
                        .toString());
                event.addProperty("triggerCompletedFrame", index - 1);
                firstOccupant.setSchedule(MaidSchedule.ALL);
                firstOccupant.setSitting(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void consumeSound(PlaySoundAtEntityEvent event) {
            if (closed || !mechanism.equals("cabinet_conditions")
                || !"random.eat".equals(event.name)
                || event.entity != maid && event.entity != second) return;
            if (event.entity == maid) primaryConsumes++;
            else secondaryConsumes++;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("actor", event.entity == maid ? "primary" : "secondary");
            row.addProperty("sound", event.name);
            row.addProperty("cancelled", event.isCanceled());
            row.addProperty("phase", "native_consume_sound_callback");
            consumeEvents.add(row);
        }

        @Override
        void cakeFrame(int index, JsonObject row) {
            if (mechanism.equals("cabinet_conditions")) observeCabinet(index, row);
            else observeBed(index, row);
        }

        void observeCabinet(int index, JsonObject row) {
            var world = helper.getWorld();
            boolean present = world.getBlock(x + 12, y + 2, z + 12) == Blocks.cake;
            int nativeBites = present ? world.getBlockMetadata(x + 12, y + 2, z + 12) : -1;
            int backpack = count(maid, Items.cake) + count(second, Items.cake);
            int used = primaryConsumes + secondaryConsumes;
            int remaining = present ? 7 - nativeBites : 0;
            int delta = used - previousConsumes;
            if (placementFrame < 0 && present) placementFrame = index;
            row.addProperty("phase", index < 200 ? "no_cabinet" : index < 800 ? "closed_cabinet" : "opened_cabinet");
            row.addProperty("worldCakePresent", present);
            row.addProperty("nativeCakeBites", nativeBites);
            row.addProperty("backpackCake", backpack);
            row.addProperty("primaryConsumes", primaryConsumes);
            row.addProperty("secondaryConsumes", secondaryConsumes);
            row.addProperty("nativeConsumeDelta", delta);
            row.addProperty("cakeBitesConsumed", used);
            row.addProperty("worldCakeRemainingMouths", remaining);
            row.addProperty("finiteMouthBalance", used + remaining + backpack * 7);
            row.addProperty("cabinetPresent", world.getBlock(x + 12, y + 1, z + 12) == MaidLifeContent.snackCabinet);
            row.addProperty(
                "gateClosed",
                world.getBlock(x + 10, y + 1, z + 12) == Blocks.glass
                    && world.getBlock(x + 10, y + 2, z + 12) == Blocks.glass);
            row.addProperty("naturalPlacementFrame", placementFrame);
            row.addProperty(
                "protectedCakeInputUnchanged",
                ItemStack.areItemStacksEqual(
                    initialCake,
                    maid.getMaidInventory()
                        .getStackInSlot(0)));
            helper.assertTrue(
                delta >= 0 && delta <= 2
                    && nativeBites >= -1
                    && nativeBites <= 6
                    && used + remaining + backpack * 7 == 7,
                "Finite cake balance differs from actual native consume callbacks");
            if (index < 800) helper.assertTrue(
                backpack == 1 && !present
                    && used == 0
                    && ItemStack.areItemStacksEqual(
                        initialCake,
                        maid.getMaidInventory()
                            .getStackInSlot(0)),
                "No/unreachable cabinet changed the finite protected cake input");
            if (placementFrame >= 0)
                helper.assertTrue(backpack == 0, "A native cake appeared before finite backpack payment");
            previousConsumes = used;
        }

        void observeBed(int index, JsonObject row) {
            var world = helper.getWorld();
            var tile = world.getTileEntity(x + 12, y + 1, z + 12);
            helper.assertTrue(tile instanceof TileEntityMaidBed, "Native shared bed head disappeared");
            var bed = (TileEntityMaidBed) tile;
            boolean a = maid.hasPose(MaidPose.SLEEPING), b = second.hasPose(MaidPose.SLEEPING);
            boolean ownedA = bed.isOwnedBy(maid), ownedB = bed.isOwnedBy(second);
            row.addProperty("primarySleeping", a);
            row.addProperty("secondarySleeping", b);
            row.addProperty("primaryBedOwned", ownedA);
            row.addProperty("secondaryBedOwned", ownedB);
            row.addProperty("bedOccupied", (world.getBlockMetadata(x + 12, y + 1, z + 12) & 4) != 0);
            row.addProperty("bedComplete", bed.complete());
            helper.assertTrue(
                !(a && b) && !(ownedA && ownedB) && (!a || ownedA) && (!b || ownedB) && bed.complete(),
                "Two natural maids shared a bed lease or lost their native bed");
            if (firstSleep < 0 && (a || b)) {
                firstSleep = index;
                firstOccupant = a ? maid : second;
            }
            if (firstOccupant != null && releaseFrame < 0) helper.assertTrue(
                firstOccupant == maid ? a : b,
                "First sleeper left before the scheduled normal release command");
            if (firstSleep >= 0 && releaseFrame < 0 && index >= firstSleep + 39) releasePending = true;
            if (releaseFrame >= 0) {
                boolean firstStillAsleep = firstOccupant == maid ? a : b;
                boolean successorAsleep = firstOccupant == maid ? b : a;
                if (!firstStillAsleep && firstAwake < 0) firstAwake = index;
                if (successorAsleep && successorSleep < 0) successorSleep = index;
            }
        }

        @Override
        void afterFrame(int index) {
            super.afterFrame(index);
            if (index + 1 != frameLimit()) return;
            boolean pantry = mechanism.equals("cabinet_conditions");
            output.addProperty("naturalPlacementFrame", placementFrame);
            output.addProperty("firstSleepFrame", firstSleep);
            output.addProperty("releaseCommandFrame", releaseFrame);
            output.addProperty("firstAwakeFrame", firstAwake);
            output.addProperty("successorSleepFrame", successorSleep);
            if (firstOccupant != null) output.addProperty(
                "firstOccupantUuid",
                firstOccupant.getUniqueID()
                    .toString());
            output.addProperty(
                "behaviorSuccess",
                pantry ? placementFrame >= 800 && primaryConsumes > 0 && secondaryConsumes > 0
                    : firstSleep >= 0 && firstAwake >= releaseFrame
                        && releaseFrame >= 0
                        && successorSleep >= firstAwake);
            output.addProperty("resourceAssertionsPassed", true);
            output.addProperty(
                "resourceAssertionScope",
                pantry
                    ? "One finite cake: actual consume callbacks+world mouths+backpack*7=7; no disappearance inferred as food"
                    : "One actual native bed; no duplicate sleepers/leases; normal first-occupant release and natural successor");
        }
    }
}
