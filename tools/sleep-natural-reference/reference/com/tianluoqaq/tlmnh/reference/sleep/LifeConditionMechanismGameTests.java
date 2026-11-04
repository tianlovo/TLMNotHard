package com.tianluoqaq.tlmnh.reference.sleep;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Complete original roots retain native search/schedule clocks and finite world resources. */
@GameTestHolder("tlmnh_life_condition_mechanisms")
@PrefixGameTestTemplate(false)
public final class LifeConditionMechanismGameTests {
    static final String RESOURCE = "/tlmnh-life-condition-mechanisms/contract.json";
    static final String CONTRACT = "LIFE_CONDITION_MECHANISMS_V3";

    private LifeConditionMechanismGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 500, batch = "life_air_open")
    public static void lowAirOpenSurface(GameTestHelper h) {
        FollowMechanismGameTests.collect(h, new AirSession(h, "low_air_open_surface"), "tlmnh.lifeConditionMechanismsProbe");
    }

    @GameTest(template = "cell", timeoutTicks = 500, batch = "life_air_roof")
    public static void lowAirRoofRelease(GameTestHelper h) {
        FollowMechanismGameTests.collect(h, new AirSession(h, "low_air_roof_release"), "tlmnh.lifeConditionMechanismsProbe");
    }


    public static void cabinetConditions(GameTestHelper h) { collect(h, "cabinet_conditions"); }

    public static void sharedBedHandoff(GameTestHelper h) { collect(h, "shared_bed_handoff"); }

    static void collect(GameTestHelper h, String mechanism) {
        FollowMechanismGameTests.collect(h, new Session(h, mechanism), "tlmnh.lifeConditionMechanismsProbe");
    }

    public static final class Session extends TerrainLifeMechanismGameTests.PantrySession {
        final JsonArray consumeEvents = new JsonArray();
        final JsonArray unmatchedConsumeEvents = new JsonArray();
        int primaryConsumes, secondaryConsumes, sharedAmbiguousConsumes, previousConsumes;
        int placementFrame = -1, firstSleep = -1, releaseFrame = -1, firstAwake = -1, successorSleep = -1;
        EntityMaid firstOccupant;
        final OpportunityReader opportunities = new OpportunityReader();
        JsonObject primaryOpportunityStart, secondaryOpportunityStart;
        int opportunityStartFrame = -1;
        ItemStack initialCake;
        boolean releasePending, observerRegistered;

        Session(GameTestHelper h, String mechanism) {
            super(h, mechanism, RESOURCE, CONTRACT, "life-condition-original-v2", "life-condition-original-");
            output.add("consumeEvents", consumeEvents);
            output.add("unmatchedConsumeEvents", unmatchedConsumeEvents);
        }

        @Override String identityProperty() { return "tlmnh.lifeConditionMechanisms.buildIdentity"; }

        @Override void setup() {
            super.setup();
            plannedOwner = absolute(scene.getAsJsonArray("ownerPosition"));
            output.addProperty("inputSchedule", "Only normal commands/world inputs at native START; END observation is read-only");
            MinecraftForge.EVENT_BUS.register(this);
            observerRegistered = true;
            if (mechanism.equals("cabinet_conditions")) {
                shell(8, 16, 8, 16, 5);
                shell(10, 14, 10, 14, 4);
            }
        }

        void shell(int minX, int maxX, int minZ, int maxZ, int top) {
            for (int x = minX; x <= maxX; x++) for (int y = 1; y <= top; y++)
                for (int z = minZ; z <= maxZ; z++)
                    if (x == minX || x == maxX || z == minZ || z == maxZ || y == top)
                        helper.setBlock(x, y, z, Blocks.GLASS);
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            helper.setBlock(12, 2, 12, Blocks.AIR);
            helper.setBlock(12, 1, 12, Blocks.AIR);
            if (mechanism.equals("cabinet_conditions")) {
                var input = new ItemStack(Items.CAKE);
                var payload = new CompoundTag();
                payload.putInt("LifeConditionInput", 201);
                payload.putIntArray("UnknownCakePayload", new int[] { 7, -17, 65537 });
                input.setTag(payload);
                maid.getMaidInv().setStackInSlot(0, input);
                initialCake = maid.getMaidInv().getStackInSlot(0).copy();
                output.add("initialCakeInput", SleepAttributeSnapshot.binaryNbt(initialCake.save(new CompoundTag())));
                output.addProperty("initialCakeItemRegistry", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(initialCake.getItem()).toString());
                output.addProperty("initialCakeCount", initialCake.getCount());
                output.addProperty("initialCakeDamage", initialCake.getDamageValue());
            } else {
                var bed = InitBlocks.MAID_BED.get().defaultBlockState().setValue(BlockMaidBed.FACING, Direction.NORTH);
                helper.setBlock(12, 1, 13, bed.setValue(BlockMaidBed.PART, BedPart.FOOT));
                helper.setBlock(12, 1, 12, bed.setValue(BlockMaidBed.PART, BedPart.HEAD));
                maid.setSchedule(MaidSchedule.DAY);
                second.setSchedule(MaidSchedule.DAY);
                maid.setHunger(20);
                second.setHunger(20);
                maid.restrictTo(maid.blockPosition(), 6);
                second.restrictTo(second.blockPosition(), 6);
            }
            output.add("secondInitial", raw(second));
            output.add("secondAttributeInputs", SleepAttributeSnapshot.capture(second));
            output.addProperty("initialCakeUnits", mechanism.equals("cabinet_conditions") ? 1 : 0);
            output.add("initialLifeTileInput", tileInput());
        }

        JsonObject tileInput() {
            var row = new JsonObject();
            var tile = level.getBlockEntity(origin.offset(12, 1, 12));
            row.addProperty("present", tile != null);
            if (tile != null) {
                row.addProperty("class", tile.getClass().getName());
                row.add("binaryNBT", SleepAttributeSnapshot.binaryNbt(tile.saveWithFullMetadata()));
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

        @Override void beforeWorld() {
            super.beforeWorld();
            if (maid == null) return;
            int index = frames.size();
            if (mechanism.equals("cabinet_conditions")) {
                if (index == 200) {
                    helper.setBlock(12, 1, 12, InitBlocks.SNACK_CABINET.get());
                    var event = startEvent("place_native_empty_cabinet_inside_existing_closed_shell");
                    event.add("layoutAfter", layout());
                    event.add("tileAfter", tileInput());
                }
                if (index == 800) {
                    helper.setBlock(10, 1, 12, Blocks.AIR);
                    helper.setBlock(10, 2, 12, Blocks.AIR);
                    var event = startEvent("open_two_high_native_gate");
                    event.add("layoutAfter", layout());
                    event.add("tileAfter", tileInput());
                }
            } else if (releasePending && releaseFrame < 0) {
                releaseFrame = index;
                var event = startEvent("first_occupant_normal_all_and_wait");
                event.addProperty("occupantUuid", firstOccupant.getUUID().toString());
                event.addProperty("triggerCompletedFrame", index - 1);
                firstOccupant.setSchedule(MaidSchedule.ALL);
                firstOccupant.setInSittingPose(true);
            }
            if (second != null) {
                opportunityStartFrame = index;
                primaryOpportunityStart = opportunities.sample(maid, "START_before_native_world_update");
                secondaryOpportunityStart = opportunities.sample(second, "START_before_native_world_update");
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void consumeSound(PlayLevelSoundEvent event) {
            if (closed || maid == null || !mechanism.equals("cabinet_conditions") || event.getLevel() != level
                || event.getSound() == null || event.getSound().value() != SoundEvents.GENERIC_EAT) return;
            boolean a = false, b = false;
            String binding;
            if (event instanceof PlayLevelSoundEvent.AtEntity entitySound) {
                a = entitySound.getEntity() == maid;
                b = entitySound.getEntity() == second;
                binding = "exact_native_entity";
            } else if (event instanceof PlayLevelSoundEvent.AtPosition positionSound) {
                a = maid.position().equals(positionSound.getPosition());
                b = second != null && second.position().equals(positionSound.getPosition());
                binding = "native_sound_position_equals_owned_actor_position";
                if (!a && !b && unmatchedConsumeEvents.size() < 16) {
                    var position = positionSound.getPosition();
                    if (position.x >= origin.getX() && position.x < origin.getX() + 25
                        && position.z >= origin.getZ() && position.z < origin.getZ() + 25) {
                        var diagnostic = new JsonObject();
                        diagnostic.addProperty("frame", frames.size());
                        diagnostic.add("soundPosition", relative(position));
                        diagnostic.add("primaryPosition", relative(maid.position()));
                        if (second != null) diagnostic.add("secondaryPosition", relative(second.position()));
                        diagnostic.addProperty("creditedAsConsumption", false);
                        unmatchedConsumeEvents.add(diagnostic);
                    }
                }
            } else return;
            if (!a && !b) return;
            if (a && b) sharedAmbiguousConsumes++;
            else if (a) primaryConsumes++;
            else secondaryConsumes++;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("actor", a && b ? "ambiguous_shared_position" : a ? "primary" : "secondary");
            row.addProperty("binding", binding);
            row.addProperty("sound", "minecraft:entity.generic.eat");
            row.addProperty("cancelled", event.isCanceled());
            row.addProperty("phase", "native_consume_sound_callback");
            consumeEvents.add(row);
        }

        @Override void cakeFrame(int index, JsonObject row) {
            if (mechanism.equals("cabinet_conditions")) observeCabinet(index, row);
            else observeBed(index, row);
        }

        void observeCabinet(int index, JsonObject row) {
            var state = level.getBlockState(origin.offset(12, 2, 12));
            boolean present = state.is(Blocks.CAKE);
            int nativeBites = present ? state.getValue(CakeBlock.BITES) : -1;
            int backpack = count(maid, Items.CAKE) + count(second, Items.CAKE);
            int used = primaryConsumes + secondaryConsumes + sharedAmbiguousConsumes;
            int remaining = present ? 7 - nativeBites : 0;
            int delta = used - previousConsumes;
            if (placementFrame < 0 && present) placementFrame = index;
            row.addProperty("phase", index < 200 ? "no_cabinet" : index < 800 ? "closed_cabinet" : "opened_cabinet");
            row.addProperty("worldCakePresent", present);
            row.addProperty("nativeCakeBites", nativeBites);
            row.addProperty("backpackCake", backpack);
            row.addProperty("primaryConsumes", primaryConsumes);
            row.addProperty("secondaryConsumes", secondaryConsumes);
            row.addProperty("ambiguousSharedConsumes", sharedAmbiguousConsumes);
            row.addProperty("nativeConsumeDelta", delta);
            row.addProperty("cakeBitesConsumed", used);
            row.addProperty("worldCakeRemainingMouths", remaining);
            row.addProperty("finiteMouthBalance", used + remaining + backpack * 7);
            row.addProperty("cabinetPresent", level.getBlockState(origin.offset(12, 1, 12)).is(InitBlocks.SNACK_CABINET.get()));
            row.addProperty("gateClosed", level.getBlockState(origin.offset(10, 1, 12)).is(Blocks.GLASS)
                && level.getBlockState(origin.offset(10, 2, 12)).is(Blocks.GLASS));
            row.addProperty("naturalPlacementFrame", placementFrame);
            row.addProperty("protectedCakeInputUnchanged", ItemStack.matches(initialCake, maid.getMaidInv().getStackInSlot(0)));
            helper.assertTrue(delta >= 0 && delta <= 2 && nativeBites >= -1 && nativeBites <= 6
                && used + remaining + backpack * 7 == 7, "Finite cake balance differs from actual native consume callbacks");
            if (index < 800) helper.assertTrue(backpack == 1 && !present && used == 0
                && ItemStack.matches(initialCake, maid.getMaidInv().getStackInSlot(0)),
                "No/unreachable cabinet changed the finite protected cake input");
            if (placementFrame >= 0) helper.assertTrue(backpack == 0, "A native cake appeared before finite backpack payment");
            previousConsumes = used;
        }

        void observeBed(int index, JsonObject row) {
            var head = origin.offset(12, 1, 12);
            var state = level.getBlockState(head);
            boolean a = maid.isSleeping(), b = second.isSleeping();
            boolean complete = state.is(InitBlocks.MAID_BED.get()) && state.getValue(BlockMaidBed.PART) == BedPart.HEAD
                && level.getBlockState(origin.offset(12, 1, 13)).is(InitBlocks.MAID_BED.get());
            row.addProperty("primarySleeping", a);
            row.addProperty("secondarySleeping", b);
            row.addProperty("bedOccupied", complete && state.getValue(BlockMaidBed.OCCUPIED));
            row.addProperty("bedComplete", complete);
            row.addProperty("nativePerActorBedLeaseExposed", false);
            maid.getSleepingPos().ifPresent(pos -> row.add("primarySleepingPosition", relative(net.minecraft.world.phys.Vec3.atLowerCornerOf(pos))));
            second.getSleepingPos().ifPresent(pos -> row.add("secondarySleepingPosition", relative(net.minecraft.world.phys.Vec3.atLowerCornerOf(pos))));
            helper.assertTrue(!(a && b) && complete && (!a || maid.getSleepingPos().orElseThrow().equals(head))
                && (!b || second.getSleepingPos().orElseThrow().equals(head)), "Two natural maids slept in one bed or lost its native anchor");
            if (firstSleep < 0 && (a || b)) {
                firstSleep = index;
                firstOccupant = a ? maid : second;
            }
            if (firstOccupant != null && releaseFrame < 0) helper.assertTrue(firstOccupant == maid ? a : b,
                "First sleeper left before the scheduled normal release command");
            if (firstSleep >= 0 && releaseFrame < 0 && index >= firstSleep + 39) releasePending = true;
            if (releaseFrame >= 0) {
                boolean firstStillAsleep = firstOccupant == maid ? a : b;
                boolean successorAsleep = firstOccupant == maid ? b : a;
                if (!firstStillAsleep && firstAwake < 0) firstAwake = index;
                if (successorAsleep && successorSleep < 0) successorSleep = index;
            }
        }

        @Override void afterFrame(int index) {
            super.afterFrame(index);
            var row = frames.get(index).getAsJsonObject();
            var observation = new JsonObject();
            observation.addProperty("schema", "life-search-opportunity-v1");
            observation.addProperty("startFrameMatches", opportunityStartFrame == index);
            if (opportunityStartFrame == index) {
                observation.add("primaryStart", primaryOpportunityStart);
                observation.add("secondaryStart", secondaryOpportunityStart);
            } else observation.addProperty("startAvailability", "UNAVAILABLE_START_PHASE");
            observation.add("primaryEnd", opportunities.sample(maid, "END_after_native_world_update"));
            observation.add("secondaryEnd", opportunities.sample(second, "END_after_native_world_update"));
            row.add("lifeSearchOpportunity", observation);
            if (index + 1 != frameLimit()) return;
            boolean pantry = mechanism.equals("cabinet_conditions");
            output.addProperty("naturalPlacementFrame", placementFrame);
            output.addProperty("firstSleepFrame", firstSleep);
            output.addProperty("releaseCommandFrame", releaseFrame);
            output.addProperty("firstAwakeFrame", firstAwake);
            output.addProperty("successorSleepFrame", successorSleep);
            if (firstOccupant != null) output.addProperty("firstOccupantUuid", firstOccupant.getUUID().toString());
            output.addProperty("behaviorSuccess", pantry ? placementFrame >= 800 && primaryConsumes > 0 && secondaryConsumes > 0
                : firstSleep >= 0 && firstAwake >= releaseFrame && releaseFrame >= 0 && successorSleep >= firstAwake);
            output.addProperty("resourceAssertionsPassed", true);
            output.addProperty("resourceAssertionScope", pantry ? "One finite cake: observed native consume callbacks+world mouths+backpack*7=7; ambiguous shared positions remain unassigned"
                : "One actual native bed; no simultaneous sleepers; normal first-occupant release and natural successor");
        }

        @Override void close() {
            if (observerRegistered) {
                observerRegistered = false;
                MinecraftForge.EVENT_BUS.unregister(this);
            }
            super.close();
        }
    }
    /** Reads task fields and raw memory storage; never invokes Behavior.tryStart or memory expiry accessors. */
    static final class OpportunityReader {
        final java.util.IdentityHashMap<Object, Integer> identities = new java.util.IdentityHashMap<>();
        final java.util.Map<String, java.lang.reflect.Field> fields = new java.util.HashMap<>();

        int identity(Object value) {
            Integer known = identities.get(value);
            if (known != null) return known;
            int next = identities.size() + 1;
            identities.put(value, next);
            return next;
        }

        Object read(Object value, Class<?> declaring, String name) {
            try {
                String key = declaring.getName() + "#" + name;
                var field = fields.get(key);
                if (field == null) {
                    field = declaring.getDeclaredField(name);
                    field.setAccessible(true);
                    fields.put(key, field);
                }
                return field.get(value);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Read-only life task field unavailable: " + declaring.getName() + "#" + name, failure);
            }
        }

        boolean stored(java.util.Map<?, ?> memories, Object key) {
            Object value = memories.get(key);
            return value instanceof java.util.Optional<?> optional && optional.isPresent();
        }

        JsonObject sample(EntityMaid actor, String phase) {
            var result = new JsonObject();
            result.addProperty("phase", phase);
            result.addProperty("worldTime", actor.level.getGameTime());
            result.addProperty("nativeAge", actor.tickCount);
            result.addProperty("availability", "AVAILABLE_RAW_VIEW");
            result.addProperty("checkRateApplicable", true);
            result.addProperty("bfsCallObservation", "NOT_INSTRUMENTED");
            result.addProperty("searchStartObservation", "NOT_DIRECTLY_OBSERVED");
            var brain = actor.getBrain();
            result.addProperty("rootIdentity", identity(brain));
            var memories = (java.util.Map<?, ?>) read(brain, net.minecraft.world.entity.ai.Brain.class, "memories");
            var walkType = net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET;
            var targetType = com.github.tartaricacid.touhoulittlemaid.init.InitEntities.TARGET_POS.get();
            boolean walk = stored(memories, walkType), target = stored(memories, targetType);
            result.addProperty("walkMemoryRegistered", memories.containsKey(walkType));
            result.addProperty("targetMemoryRegistered", memories.containsKey(targetType));
            result.addProperty("walkTargetRawStored", walk);
            result.addProperty("workTargetRawStored", target);
            boolean absent = memories.containsKey(walkType) && memories.containsKey(targetType) && !walk && !target;
            result.addProperty("requiredRawMemoryAbsent", absent);
            var tasks = new JsonArray();
            result.add("tasks", tasks);
            var table = (java.util.Map<?, ?>) read(brain, net.minecraft.world.entity.ai.Brain.class, "availableBehaviorsByPriority");
            for (var priority : table.entrySet()) {
                var activities = (java.util.Map<?, ?>) priority.getValue();
                for (var activity : activities.entrySet()) for (Object value : (java.util.Set<?>) activity.getValue()) {
                    if (!(value instanceof com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleMoveBlockTask task)) continue;
                    var row = new JsonObject();
                    row.addProperty("taskClass", task.getClass().getName());
                    row.addProperty("taskIdentity", identity(task));
                    row.addProperty("activity", activity.getKey().toString());
                    row.addProperty("activityActive", brain.getActiveActivities().contains(activity.getKey()));
                    row.addProperty("priority", (Number) priority.getKey());
                    row.addProperty("behaviorStatus", task.getStatus().name());
                    row.addProperty("requiredRawMemoryAbsent", absent);
                    var check = com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCheckRateTask.class;
                    row.addProperty("maxCheckRate", (Number) read(task, check, "maxCheckRate"));
                    row.addProperty("nextCheckTickCount", (Number) read(task, check, "nextCheckTickCount"));
                    tasks.add(row);
                }
            }
            return result;
        }
    }

    /** Two natural air-safety episodes on the existing native player/root/tick collection protocol. */
    public static final class AirSession extends FollowMechanismGameTests.Session {
        final String mechanism;
        final JsonArray damageCallbacks = new JsonArray();
        boolean outletOpened, registeredObserver, deathCallback;
        int firstHeadOutside = -1, firstAirRecovered = -1, firstBreathPlan = -1, breathPlanCleared = -1;
        int ownerArrivalAfterRecovery = -1, minimumAir = 60;
        float previousHealth = 20, netHealthLoss;

        AirSession(GameTestHelper helper, String mechanism) {
            super(helper, mechanism, RESOURCE, CONTRACT, "life-recovery-original-v1", "life-recovery-original-");
            this.mechanism = mechanism;
            output.add("damageCallbacks", damageCallbacks);
            output.addProperty("inputSchedule", "Fixed world inputs only at START; END observes actual native updates");
            output.addProperty("crossEngineFullNbtEqualityRequired", false);
        }

        @Override String identityProperty() { return "tlmnh.lifeConditionMechanisms.buildIdentity"; }
        @Override int frameLimit() { return scene.get("frames").getAsInt(); }

        @Override void setup() {
            super.setup();
            for (int x = 0; x < 25; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 25; z++)
                helper.setBlock(x, y, z, y == 0 ? Blocks.STONE : Blocks.AIR);
            TerrainLifeMechanismGameTests.enclose(helper, 25, 8);
            for (int x = 4; x <= 22; x++) for (int y = 1; y <= 3; y++) for (int z = 8; z <= 16; z++)
                helper.setBlock(x, y, z, Blocks.STONE);
            for (int x = 8; x <= 16; x++) for (int y = 1; y <= 3; y++) for (int z = 10; z <= 14; z++)
                helper.setBlock(x, y, z, Blocks.WATER);
            if (mechanism.equals("low_air_roof_release"))
                for (int x = 8; x <= 16; x++) for (int z = 10; z <= 14; z++) helper.setBlock(x, 4, z, Blocks.GLASS);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
            registeredObserver = true;
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            maid.setAirSupply(contract.get("initialAirSupply").getAsInt());
            output.addProperty("airInputAppliedBeforeJoin", maid.getAirSupply());
            output.addProperty("initialEyeFluidAvailability", "UNAVAILABLE_BEFORE_FIRST_NATIVE_FLUID_UPDATE");
        }

        @Override void spawn() {
            super.spawn();
            output.addProperty("actualInitialAirSupply", maid.getAirSupply());
            helper.assertTrue(maid.getAirSupply() == 60 && !maid.isInvulnerable(), "Native initial low-air/damage input differs");
        }

        @Override void beforeWorld() {
            super.beforeWorld();
            if (maid == null || outletOpened || !mechanism.equals("low_air_roof_release") || frames.size() != 40) return;
            var event = new JsonObject();
            event.addProperty("frame", frames.size());
            event.addProperty("kind", "open_fixed_native_air_outlet");
            event.addProperty("phase", "START_before_native_world_update");
            for (int x = 12; x <= 13; x++) for (int z = 12; z <= 13; z++) helper.setBlock(x, 4, z, Blocks.AIR);
            outletOpened = true;
            event.add("layoutAfter", layout());
            events.add(event);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void nativeHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
            if (closed || event.getEntity() != maid) return;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("nativeAge", maid.tickCount);
            row.addProperty("type", event.getSource().getMsgId());
            row.addProperty("amountAtObserver", event.getAmount());
            row.addProperty("healthBefore", maid.getHealth());
            row.addProperty("cancelledAtObserver", event.isCanceled());
            row.addProperty("scope", "Native LOWEST callback; later listeners/health commit are not guessed");
            damageCallbacks.add(row);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void nativeDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
            if (!closed && event.getEntity() == maid) {
                deathCallback = true;
                output.addProperty("nativeDeathCallbackObserved", true);
                output.addProperty("deathCancelledAtObserver", event.isCanceled());
            }
        }

        JsonObject airState() {
            var row = new JsonObject();
            row.addProperty("nativeAge", maid.tickCount);
            row.addProperty("worldTime", level.getGameTime());
            row.addProperty("health", maid.getHealth());
            row.addProperty("air", maid.getAirSupply());
            row.addProperty("headInWater", maid.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            row.addProperty("bodyInWater", maid.isInWater());
            row.addProperty("goingToBreath", maid.getSwimManager().isGoingToBreath());
            row.addProperty("alive", maid.isAlive());
            row.addProperty("registered", level.getEntity(maid.getUUID()) == maid);
            row.addProperty("ownerDistanceSquared", maid.distanceToSqr(owner));
            row.add("position", relative(maid.position()));
            row.add("eyePosition", relative(maid.getEyePosition()));
            row.addProperty("activeEffects", maid.getActiveEffects().toString());
            return row;
        }

        @Override void observe() throws Exception {
            if (maid != null && !complete && !closed && deathCallback && !maid.isAlive()) {
                netHealthLoss += Math.max(0, previousHealth - maid.getHealth());
                previousHealth = maid.getHealth();
                finishFacts("maid_death");
                finishObservation("maid_death");
                return;
            }
            super.observe();
        }

        @Override void afterFrame(int index) {
            var row = frames.get(index).getAsJsonObject();
            var state = airState();
            row.add("airState", state);
            minimumAir = Math.min(minimumAir, maid.getAirSupply());
            netHealthLoss += Math.max(0, previousHealth - maid.getHealth());
            previousHealth = maid.getHealth();
            if (!state.get("headInWater").getAsBoolean() && firstHeadOutside < 0) firstHeadOutside = index;
            if (maid.getAirSupply() >= 290 && firstAirRecovered < 0) firstAirRecovered = index;
            if (maid.getSwimManager().isGoingToBreath() && firstBreathPlan < 0) firstBreathPlan = index;
            if (firstBreathPlan >= 0 && !maid.getSwimManager().isGoingToBreath() && maid.getAirSupply() >= 290 && breathPlanCleared < 0)
                breathPlanCleared = index;
            if (firstAirRecovered >= 0 && !maid.getSwimManager().isGoingToBreath() && maid.distanceToSqr(owner) <= 4 && ownerArrivalAfterRecovery < 0)
                ownerArrivalAfterRecovery = index;
            if (index + 1 == frameLimit()) finishFacts("window_end");
        }

        void finishFacts(String reason) {
            minimumAir = Math.min(minimumAir, maid.getAirSupply());
            output.addProperty("terminalReason", reason);
            output.addProperty("firstHeadOutsideWaterFrame", firstHeadOutside);
            output.addProperty("firstAirAtLeast290Frame", firstAirRecovered);
            output.addProperty("firstBreathingPlanFrame", firstBreathPlan);
            output.addProperty("breathingPlanClearedFrame", breathPlanCleared);
            output.addProperty("ownerArrivalAfterRecoveryFrame", ownerArrivalAfterRecovery);
            output.addProperty("minimumAirSupply", minimumAir);
            output.addProperty("observedNetHealthLoss", netHealthLoss);
            output.addProperty("nativeDeathCallbackObserved", deathCallback);
            output.add("terminalActorState", airState());
            var tag = new CompoundTag();
            maid.saveWithoutId(tag);
            output.add("terminalActorBinaryNBT", SleepAttributeSnapshot.binaryNbt(tag));
            boolean safe = reason.equals("window_end") && maid.isAlive() && firstHeadOutside >= 0 && firstAirRecovered >= 0;
            output.addProperty("airSafetySucceeded", safe);
            output.addProperty("normalActivityResumeObserved", ownerArrivalAfterRecovery >= 0);
            output.addProperty("followTaskCauseDirectlyObserved", false);
            output.addProperty("behaviorSuccess", safe && ownerArrivalAfterRecovery >= 0);
            output.addProperty("resourceAssertionScope", "No material use input; complete native initial/terminal entity NBT retained");
            if (reason.equals("maid_death")) {
                output.addProperty("deathOutputsNotExplicitlyDeleted", true);
                output.addProperty("deathOutputOwnershipVerified", false);
            }
        }

        @Override JsonArray layout() {
            var rows = new JsonArray();
            for (int x = 0; x < 25; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 25; z++) {
                var state = level.getBlockState(origin.offset(x, y, z));
                if (state.isAir()) continue;
                var row = new JsonObject();
                row.addProperty("x", x); row.addProperty("y", y); row.addProperty("z", z);
                row.addProperty("registry", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                row.addProperty("rawBlockState", state.toString());
                row.addProperty("metadata", state.is(Blocks.WATER) ? state.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) : 0);
                rows.add(row);
            }
            return rows;
        }

        @Override void close() {
            if (closed) return;
            try {
                if (registeredObserver) {
                    registeredObserver = false;
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
                }
                for (int x = 0; x < 25; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 25; z++) helper.setBlock(x, y, z, Blocks.AIR);
                output.addProperty("extraCleanupComplete", true);
            } finally { super.close(); }
        }
    }

}
