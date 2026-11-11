package com.tianluoqaq.tlmnh.qa;

import static com.tianluoqaq.tlmnh.qa.TerrainLifeMechanismPortTests.raw;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.compat.malisisdoors.common.block.NativeDoorState;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Reuses normal native-root collectors; post-exit frames observe the old world, not a fictitious live source tick. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class BehaviorConvergencePortTests {

    static final String RESOURCE = "/tlmnh-behavior-convergence/contract.json", CONTRACT = "BEHAVIOR_CONVERGENCE_V3";

    private BehaviorConvergencePortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 840, batch = "convergence_door_f")
    public static void faithfulRedstoneDoorRecovery(GameTestHelper h) {
        collect(h, false, "redstone_door_recovery");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 840, batch = "convergence_door_n")
    public static void enhancedRedstoneDoorRecovery(GameTestHelper h) {
        collect(h, true, "redstone_door_recovery");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 460, batch = "convergence_source_f")
    public static void faithfulSourceExitArrow(GameTestHelper h) {
        collect(h, false, "source_exit_arrow");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 460, batch = "convergence_source_n")
    public static void enhancedSourceExitArrow(GameTestHelper h) {
        collect(h, true, "source_exit_arrow");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2340, batch = "convergence_pantry_f")
    public static void faithfulPantrySleepCommand(GameTestHelper h) {
        collect(h, false, "pantry_sleep_command");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2340, batch = "convergence_pantry_n")
    public static void enhancedPantrySleepCommand(GameTestHelper h) {
        collect(h, true, "pantry_sleep_command");
    }

    static void collect(GameTestHelper h, boolean enhanced, String mechanism) {
        var profile = enhanced ? MaidBrainProfile.ENHANCED : MaidBrainProfile.FAITHFUL_TLM;
        FollowMechanismPortTests.Collector c = mechanism.equals("pantry_sleep_command")
            ? new PantryCollector(h, profile)
            : new Collector(h, profile, mechanism);
        h.afterTest(c::close);
        h.onEachTick("normal behavior convergence", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
        c.setup();
    }

    static void identity(JsonObject output) {
        try {
            output.add(
                "convergenceObserverArtifact",
                DevelopmentArtifactIdentity
                    .capture(BehaviorConvergencePortTests.class, Collector.class, PantryCollector.class));
            output.add(
                "convergenceProductionArtifact",
                DevelopmentArtifactIdentity.capture(
                    com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileSource.class,
                    MaidProjectileImpactProtection.class,
                    com.tianluoqaq.tlmnh.common.lifecycle.world.MaidDimensionTravel.class,
                    NativeDoorState.class,
                    com.github.tartaricacid.touhoulittlemaid.entity.favorability.FavorabilityManager.class,
                    com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type.class));
        } catch (Exception failure) {
            throw new IllegalStateException("Convergence identity unavailable", failure);
        }
    }

    public static final class Collector extends TerrainLifeMechanismPortTests.Collector {

        final List<EntityArrow> arrows = new ArrayList<>(), freshArrows = new ArrayList<>();
        final List<BlockSnapshot> endPlatform = new ArrayList<>();
        final JsonArray shots = new JsonArray(), freshShots = new JsonArray();
        EntityPig enemy, freshEnemy;
        EntityMaid candidate;
        MaidPolicyRuntime candidateRuntime;
        ItemStack weapon;
        WorldServer destination;
        int firstCross = -1, secondClose = -1, secondApproach = -1, secondOpen = -1, secondCross = -1;
        int firstBlockedSamples, secondBlockedSamples;
        int shiftedAt = -1, requestedAt = -1, boundaryShots;
        float boundaryTargetHealth;
        boolean reversePending, shiftPending, exitPending, sourceExited, secondOpportunityUnavailable;
        int destinationStarts, destinationEnds, candidateLivingUpdates;
        long lastDestinationStartTime = -1, lastDestinationEndTime = -1;
        final JsonArray destinationTickSamples = new JsonArray();

        Collector(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
            super(h, profile, mechanism, RESOURCE, CONTRACT, "behavior-convergence-port-v1", "behavior-convergence-");
            output.add("shots", shots);
            output.add("freshShots", freshShots);
            output.add("destinationTickSamples", destinationTickSamples);
        }

        @Override
        void onLiving(net.minecraft.entity.Entity entity) {
            super.onLiving(entity);
            if (candidate != null && entity == candidate) candidateLivingUpdates++;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void destinationTick(TickEvent.WorldTickEvent event) {
            if (closed || complete || failure != null || destination == null || event.world != destination) return;
            try {
                if (event.phase == TickEvent.Phase.START) {
                    destinationStarts++;
                    lastDestinationStartTime = destination.getTotalWorldTime();
                } else {
                    destinationEnds++;
                    lastDestinationEndTime = destination.getTotalWorldTime();
                }
                if (destinationTickSamples.size() < 8) {
                    var sample = destinationState();
                    sample.addProperty("eventPhase", event.phase.name());
                    destinationTickSamples.add(sample);
                }
            } catch (Exception | LinkageError | AssertionError error) {
                com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.throwIfFatal(error);
                failure = new IllegalStateException("Destination native tick observation failed", error);
                output.addProperty("error", error.toString());
            }
        }

        JsonObject destinationState() {
            var state = new JsonObject();
            state.addProperty("sourceObservationFrame", frames.size());
            state.addProperty("worldObjectIdentity", System.identityHashCode(destination));
            state.addProperty("dimension", destination.provider.dimensionId);
            state.addProperty("dimensionManagerObjectMatches", DimensionManager.getWorld(1) == destination);
            state.addProperty(
                "serverAllowNether",
                MinecraftServer.getServer()
                    .getAllowNether());
            state.addProperty("totalWorldTime", destination.getTotalWorldTime());
            state.addProperty("observedWorldStarts", destinationStarts);
            state.addProperty("observedWorldEnds", destinationEnds);
            state.addProperty("lastWorldStartTime", lastDestinationStartTime);
            state.addProperty("lastWorldEndTime", lastDestinationEndTime);
            state.addProperty("candidateLivingUpdateEvents", candidateLivingUpdates);
            state.addProperty("candidateAvailable", candidate != null);
            if (candidate == null) return state;
            int blockX = MathHelper.floor_double(candidate.posX), blockZ = MathHelper.floor_double(candidate.posZ);
            int chunkX = blockX >> 4, chunkZ = blockZ >> 4;
            boolean forced = destination.getPersistentChunks()
                .containsKey(new ChunkCoordIntPair(chunkX, chunkZ));
            int radius = forced ? 0 : 32;
            state.addProperty("candidateAge", candidate.ticksExisted);
            state.addProperty("candidateWorldObjectMatches", candidate.worldObj == destination);
            state.addProperty("candidateAddedToChunk", candidate.addedToChunk);
            state.addProperty("candidateInLoadedEntityList", destination.loadedEntityList.contains(candidate));
            state.addProperty(
                "candidateRegisteredById",
                destination.getEntityByID(candidate.getEntityId()) == candidate);
            state.addProperty("currentChunkX", chunkX);
            state.addProperty("currentChunkZ", chunkZ);
            state.addProperty("recordedChunkX", candidate.chunkCoordX);
            state.addProperty("recordedChunkY", candidate.chunkCoordY);
            state.addProperty("recordedChunkZ", candidate.chunkCoordZ);
            state.addProperty(
                "currentChunkExists",
                destination.getChunkProvider()
                    .chunkExists(chunkX, chunkZ));
            state.addProperty("currentChunkForced", forced);
            state.addProperty("nativeEligibilityRadius", radius);
            state.addProperty(
                "nativeEligibilityChunksExist",
                destination.checkChunksExist(blockX - radius, 0, blockZ - radius, blockX + radius, 0, blockZ + radius));
            state.addProperty("canUpdateEventOverrideObserved", false);
            state.addProperty(
                "scope",
                "Read-only native preconditions; CanUpdate event outcome is not inferred. No chunks loaded or entities ticked by this observation.");
            return state;
        }

        @Override
        void setup() {
            super.setup();
            identity(output);
            if (mechanism.equals("redstone_door_recovery")) {
                for (int a = 1; a < 32; a++) for (int b = 1; b <= 3; b++) {
                    helper.setBlock(a, b, 3, Blocks.stone);
                    helper.setBlock(a, b, 5, Blocks.stone);
                    if (b == 3) helper.setBlock(a, b, 4, Blocks.stone);
                }
                helper.setBlock(11, 1, 4, Blocks.iron_door, 0);
                helper.setBlock(11, 2, 4, Blocks.iron_door, 8);
            }
        }

        @Override
        void beforeSpawn() {
            super.beforeSpawn();
            if (!mechanism.equals("source_exit_arrow")) return;
            helper.assertTrue(
                maid.getHomeArea()
                    .setEnabled(true),
                "Native source home command rejected");
            maid.setTask(MaidTask.RANGED_ATTACK);
            weapon = new ItemStack(Items.bow);
            maid.setCurrentItemOrArmor(0, weapon);
            maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.arrow, 24));
            enemy = target(helper.getWorld(), x + 17.5, y + 1, z + 4.5);
            maid.getAttackListData()
                .attackGroups()
                .put(com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities.id(enemy), MonsterType.HOSTILE);
            output.add("enemyInitial", raw(enemy, x, y, z));
            output.addProperty("initialNonEmptyItemStackCount", 2);
        }

        EntityPig target(WorldServer world, double a, double b, double c) {
            var pig = new EntityPig(world);
            pig.func_110163_bv();
            pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(1000);
            pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            pig.setHealth(1000);
            pig.setPosition(a, b, c);
            helper.assertTrue(
                world.spawnEntityInWorld(pig) && world.getEntityByID(pig.getEntityId()) == pig,
                "Controlled native target rejected/replaced");
            return pig;
        }

        JsonObject event(String kind) {
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("kind", kind);
            row.addProperty("phase", "START_before_native_world_update");
            events.add(row);
            return row;
        }

        void endEvent(int index, String kind) {
            var row = event(kind);
            row.addProperty("frame", index);
            row.addProperty("phase", "END_after_recorded_frame");
        }

        boolean reverseOwner() {
            double relativeX = maid.posX - x - 8;
            double distance = maid.getDistanceSq(x + relativeX, y + 1, z + 4.5);
            boolean formed = relativeX >= 1 && relativeX <= 10.5 && distance >= 36 && distance < 100;
            var row = event(formed ? "bounded_reverse_owner_input" : "second_opportunity_not_geometrically_formed");
            row.add("actualMaid", vector(maid.posX - x, maid.posY - y, maid.posZ - z));
            row.add("plannedOwner", vector(relativeX, 1, 4.5));
            row.addProperty("distanceSquared", distance);
            row.addProperty("formed", formed);
            if (formed) setPlannedOwner(vector(relativeX, 1, 4.5));
            else secondOpportunityUnavailable = true;
            return formed;
        }

        @Override
        void applyOwner() {
            if (sourceExited) return;
            if (maid != null && mechanism.equals("redstone_door_recovery")) {
                int index = frames.size();
                if (reversePending && secondClose < 0) {
                    secondClose = index;
                    power(false);
                }
                if (secondClose >= 0 && secondApproach < 0
                    && !secondOpportunityUnavailable
                    && index >= secondClose + 20) {
                    var actual = doorState();
                    if (!actual.get("nativeOpenOrOpening")
                        .getAsBoolean()
                        && actual.get("doorwayBlockCollisionCount")
                            .getAsInt() > 0) {
                        if (reverseOwner()) {
                            secondApproach = index;
                            maid.setSitting(false);
                            event("release_wait_against_closed_door");
                        }
                    }
                }
                if (index == 100) {
                    setPlannedOwner(
                        contract.getAsJsonObject("door")
                            .getAsJsonArray("openOwnerPosition"));
                    power(true);
                }
                if (secondApproach >= 0 && index == secondApproach + 100) {
                    reverseOwner();
                    secondOpen = index;
                    power(true);
                }
            }
            if (maid != null && shiftPending && shiftedAt < 0) {
                shiftedAt = frames.size();
                enemy.setPosition(x + 17.5, y + 1, z + 7.5);
                event("target_moves_after_first_natural_arrow").add("actualTarget", raw(enemy, x, y, z));
            }
            if (exitPending && requestedAt < 0) requestExit();
            if (!sourceExited) super.applyOwner();
        }

        void power(boolean value) {
            helper.getWorld()
                .setBlock(x + 11, y + 1, z + 3, value ? Blocks.redstone_block : Blocks.stone, 0, 3);
            event(value ? "native_redstone_power_on" : "native_redstone_power_off")
                .add("actualDoorAfterCall", doorState());
        }

        JsonObject doorState() {
            var world = helper.getWorld();
            Block block = world.getBlock(x + 11, y + 1, z + 4);
            var row = new JsonObject();
            row.addProperty(
                "class",
                block.getClass()
                    .getName());
            row.addProperty("registry", Block.blockRegistry.getNameForObject(block));
            row.addProperty("metadata", world.getBlockMetadata(x + 11, y + 1, z + 4));
            row.addProperty(
                "nativeOpenOrOpening",
                NativeDoorState.isOpenOrOpening((BlockDoor) block, world, x + 11, y + 1, z + 4));
            row.addProperty(
                "nativePowered",
                world.isBlockIndirectlyGettingPowered(x + 11, y + 1, z + 4)
                    || world.isBlockIndirectlyGettingPowered(x + 11, y + 2, z + 4));
            row.addProperty(
                "doorwayBlockCollisionCount",
                world
                    .getCollidingBoundingBoxes(
                        maid,
                        AxisAlignedBB.getBoundingBox(x + 11.05, y + 1, z + 4.3, x + 11.95, y + 2.6, z + 4.7))
                    .size());
            var tile = world.getTileEntity(x + 11, y + 1, z + 4);
            row.addProperty(
                "tileClass",
                tile == null ? "none"
                    : tile.getClass()
                        .getName());
            if (tile != null) {
                var tag = new NBTTagCompound();
                tile.writeToNBT(tag);
                row.add("tileNBT", SleepAttributePortSnapshot.binaryNbt(tag));
            }
            return row;
        }

        @Override
        void observe() {
            if (!sourceExited) {
                super.observe();
                return;
            }
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty(
                "gameTime",
                helper.getWorld()
                    .getTotalWorldTime());
            row.addProperty("phase", "old_world_after_real_source_exit");
            row.addProperty("normalRootFrameClaimed", false);
            row.addProperty(
                "sourceRegistered",
                helper.getWorld()
                    .getEntityByID(maid.getEntityId()) == maid);
            row.addProperty("oldSourceDead", maid.isDead);
            row.addProperty(
                "oldSourceLease",
                maid.getItemUseState()
                    .peek() != null);
            row.add("oldSource", raw(maid, x, y, z));
            row.add("candidate", raw(candidate, x, y, z));
            row.add("destinationUpdateEligibility", destinationState());
            row.addProperty("candidateImpactAvailable", MaidProjectileImpactProtection.available(candidate));
            row.addProperty("candidateDimension", candidate.dimension);
            row.addProperty(
                "candidateProfile",
                candidateRuntime.getProfile()
                    .getId());
            row.addProperty(
                "candidateRuntimeStable",
                candidate.getBrainCoordinator()
                    .peekPolicyRuntime() == candidateRuntime);
            var old = new JsonArray();
            for (var arrow : arrows) {
                var state = raw(arrow, x, y, z);
                state.addProperty("sourceStopped", ((MaidArrowSourceState) arrow).tlmnh$sourceStopped());
                state.addProperty("nativeAge", arrow.ticksExisted);
                old.add(state);
                if (!arrow.isDead && helper.getWorld()
                    .getEntityByID(arrow.getEntityId()) == arrow)
                    helper.assertTrue(
                        ((MaidArrowSourceState) arrow).tlmnh$sourceStopped(),
                        "Registered old arrow did not stop after real source exit");
            }
            row.add("oldArrows", old);
            row.addProperty("freshConfirmedJoins", freshShots.size());
            var fresh = new JsonArray();
            for (var arrow : freshArrows) {
                var state = raw(arrow, x, y, z);
                state.addProperty("sourceStopped", ((MaidArrowSourceState) arrow).tlmnh$sourceStopped());
                state.addProperty("nativeAge", arrow.ticksExisted);
                fresh.add(state);
            }
            row.add("freshArrows", fresh);
            row.addProperty("oldTargetHealth", enemy.getHealth());
            helper.assertTrue(
                helper.getWorld()
                    .getEntityByID(maid.getEntityId()) != maid && shots.size() == boundaryShots
                    && maid.getItemUseState()
                        .peek() == null
                    && enemy.getHealth() == boundaryTargetHealth
                    && candidate.getBrainCoordinator()
                        .peekPolicyRuntime() == candidateRuntime
                    && candidateRuntime.getProfile() == profile
                    && MaidProjectileImpactProtection.available(candidate),
                "Old source exit consumed/damaged again or disabled the new legitimate shooter");
            helper.assertTrue(
                countArrows(candidate) + shots.size() + freshShots.size() == 24 && candidate.getHeldItem()
                    .getItemDamage() == shots.size() + freshShots.size(),
                "Native transfer or new shot lost finite arrows/payment");
            for (var arrow : freshArrows) helper.assertFalse(
                ((MaidArrowSourceState) arrow).tlmnh$sourceStopped(),
                "New legitimate arrow inherited an old-source stop");
            frames.add(row);
            livingTicks = 0;
            drain();
            if (frames.size() == frameLimit()) {
                output.addProperty("sourceRequestFrame", requestedAt);
                output.addProperty("sourceExitExercised", true);
                output.addProperty("behaviorSuccess", freshShots.size() > 0);
                output.addProperty("resourceAssertionsPassed", true);
                boolean updated = destinationEnds > 0 && candidate.ticksExisted > 0 && candidateLivingUpdates > 0;
                output.addProperty("destinationNaturalUpdateObserved", updated);
                output.add("terminalDestinationEligibility", destinationState());
                helper.assertTrue(
                    updated,
                    "Destination candidate never received an observed natural world/entity update");
                helper.assertTrue(
                    freshShots.size() > 0 && freshArrows.stream()
                        .anyMatch(a -> a.ticksExisted > 0),
                    "New registered same-UUID shooter never naturally fired/updated an arrow");
                complete = true;
                close();
            }
        }

        @Override
        void afterFrame(int index) {
            var row = frames.get(index)
                .getAsJsonObject();
            if (mechanism.equals("redstone_door_recovery")) {
                var actual = doorState();
                row.add("door", actual);
                if (row.get("walkTarget")
                    .getAsBoolean()
                    && actual.get("doorwayBlockCollisionCount")
                        .getAsInt() > 0) {
                    if (index < 100) firstBlockedSamples++;
                    if (secondApproach >= 0 && secondOpen < 0) secondBlockedSamples++;
                }
                if (index >= 100 && secondClose < 0 && maid.posX - x >= 12.35 && firstCross < 0) {
                    firstCross = index;
                    reversePending = true;
                    maid.setSitting(true);
                    endEvent(index, "first_cross_normal_wait_before_native_close");
                }
                if (secondOpen >= 0 && maid.posX - x <= 10.5 && secondCross < 0) secondCross = index;
                row.addProperty(
                    "actualAStarSearches",
                    ((com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator) maid.getNavigator()).searchCount());
            } else {
                helper.assertTrue(
                    countArrows(maid) + shots.size() == 24 && weapon.getItemDamage() == shots.size(),
                    "Finite original-world arrows/payment changed");
                row.addProperty(
                    "usingItem",
                    maid.getItemUseState()
                        .peek() != null);
                row.addProperty("inventoryArrows", countArrows(maid));
                row.addProperty("weaponWear", weapon.getItemDamage());
                if (shots.size() > 0) shiftPending = true;
                if (shiftedAt >= 0 && requestedAt < 0
                    && maid.getItemUseState()
                        .peek() != null
                    && arrows.stream()
                        .anyMatch(
                            a -> !a.isDead && helper.getWorld()
                                .getEntityByID(a.getEntityId()) == a))
                    exitPending = true;
            }
            if (index + 1 == frameLimit()) {
                output.addProperty("firstCrossFrame", firstCross);
                output.addProperty("secondCloseFrame", secondClose);
                output.addProperty("secondApproachFrame", secondApproach);
                output.addProperty("secondOpenFrame", secondOpen);
                output.addProperty("secondCrossFrame", secondCross);
                output.addProperty("firstBlockedSamples", firstBlockedSamples);
                output.addProperty("secondBlockedSamples", secondBlockedSamples);
                output.addProperty("secondOpportunityUnavailable", secondOpportunityUnavailable);
                output.addProperty("sourceRequestFrame", requestedAt);
                output.addProperty("sourceExitExercised", sourceExited);
                output.addProperty(
                    "behaviorSuccess",
                    mechanism.equals("redstone_door_recovery")
                        ? !secondOpportunityUnavailable && secondCross >= 0
                            && firstBlockedSamples > 0
                            && secondBlockedSamples > 0
                        : sourceExited && freshShots.size() > 0);
                output.addProperty("resourceAssertionsPassed", true);
            }
        }

        int countArrows(EntityMaid actor) {
            var stack = actor.getMaidInventory()
                .getStackInSlot(0);
            return stack == null ? 0 : stack.stackSize;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void arrowJoin(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityArrow arrow)) return;
            if (arrow.shootingEntity != maid && arrow.shootingEntity != candidate) return;
            boolean fresh = candidate != null && arrow.shootingEntity == candidate;
            (fresh ? freshArrows : arrows).add(arrow);
            var row = raw(arrow, x, y, z);
            row.addProperty("frame", frames.size());
            row.addProperty("actualDimension", arrow.dimension);
            (fresh ? freshShots : shots).add(row);
        }

        void requestExit() {
            if (maid.getItemUseState()
                .peek() == null || arrows.stream()
                    .noneMatch(
                        a -> !a.isDead && helper.getWorld()
                            .getEntityByID(a.getEntityId()) == a)) {
                exitPending = false;
                return;
            }
            requestedAt = frames.size();
            boundaryShots = shots.size();
            output.addProperty("sourceRequestFrame", requestedAt);
            boundaryTargetHealth = enemy.getHealth();
            destination = MinecraftServer.getServer()
                .worldServerForDimension(1);
            helper.assertTrue(destination != null, "Native End unavailable");
            var center = destination.getEntrancePortalLocation();
            helper.assertTrue(center != null, "End entrance unavailable");
            try {
                chunks.forceChunksStrict(
                    destination,
                    center.posX - 16,
                    0,
                    center.posZ - 16,
                    center.posX + 16,
                    255,
                    center.posZ + 16);
            } catch (Exception error) {
                throw new IllegalStateException("Cannot prepare actual destination chunks", error);
            }
            var platform = new JsonArray();
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 1; b++) for (int c = -2; c <= 2; c++) {
                var saved = BlockSnapshot
                    .getBlockSnapshot(destination, center.posX + a, center.posY + b, center.posZ + c);
                endPlatform.add(saved);
                var tag = new NBTTagCompound();
                saved.writeToNBT(tag);
                var entry = new JsonObject();
                entry.addProperty("x", saved.x);
                entry.addProperty("y", saved.y);
                entry.addProperty("z", saved.z);
                entry.add("fullNativeBlockSnapshot", SleepAttributePortSnapshot.binaryNbt(tag));
                platform.add(entry);
            }
            output.add("endPlatformOriginal", platform);
            var row = event("normal_maid_travel_to_dimension_request");
            row.add("destinationBeforeRequest", destinationState());
            row.add("before", raw(maid, x, y, z));
            row.addProperty(
                "usingBefore",
                maid.getItemUseState()
                    .peek() != null);
            row.addProperty("oldTargetHealthBefore", boundaryTargetHealth);
            var live = new JsonArray();
            for (var arrow : arrows) if (!arrow.isDead && helper.getWorld()
                .getEntityByID(arrow.getEntityId()) == arrow) live.add(raw(arrow, x, y, z));
            row.add("liveOldArrowsBefore", live);
            maid.travelToDimension(1);
            sourceExited = helper.getWorld()
                .getEntityByID(maid.getEntityId()) != maid;
            row.addProperty("sourceIdAlreadyRemovedAtCall", sourceExited);
            for (Object value : destination.loadedEntityList) if (value instanceof EntityMaid next && next != maid
                && next.getUniqueID()
                    .equals(maid.getUniqueID())) {
                        helper.assertTrue(candidate == null, "Duplicate transferred source UUID");
                        candidate = next;
                    }
            if (candidate != null) row.add("candidateBeforeNewCommands", raw(candidate, x, y, z));
            sourceExited = maid.isDead && !maid.isEntityAlive()
                && candidate != null
                && candidate.isEntityAlive()
                && candidate.worldObj == destination
                && candidate.dimension == 1
                && destination.getEntityByID(candidate.getEntityId()) == candidate;
            output.addProperty("sourceExitExercised", sourceExited);
            row.addProperty("sourceExited", sourceExited);
            row.add("oldSourceAfter", raw(maid, x, y, z));
            output.addProperty("nativeRequestOutcome", sourceExited ? "SOURCE_EXIT" : "REMAINED_IN_SOURCE_WORLD");
            helper.assertTrue(
                sourceExited && candidate != null && candidate.isEntityAlive(),
                "Actual normal source transfer did not establish one new live same-UUID actor");
            MinecraftServer.getServer()
                .getConfigurationManager()
                .transferPlayerToDimension(owner, 1);
            helper.assertTrue(owner.worldObj == destination, "Native owner follow-up transfer failed");
            owner.setPosition(candidate.posX, candidate.posY + 8, candidate.posZ);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            row.add("destinationOwner", raw(owner, x, y, z));
            row.addProperty("destinationOwnerDimension", owner.dimension);
            row.addProperty("destinationOwnerWorldRegistered", destination.getEntityByID(owner.getEntityId()) == owner);
            row.addProperty(
                "destinationOwnerGlobalRegistered",
                MinecraftServer.getServer()
                    .getConfigurationManager().playerEntityList.contains(owner));
            helper.assertTrue(
                candidate.getHomeArea()
                    .setEnabled(false),
                "Normal destination home-disable command rejected");
            candidate.setSitting(true);
            candidate.setSchedule(MaidSchedule.ALL);
            candidate.setTask(MaidTask.RANGED_ATTACK);
            helper.assertTrue(
                candidate.selectBrainProfileForDevelopment(profile) == MaidPolicyRuntime.SelectionResult.SELECTED,
                "New live shooter complete factory not selected");
            candidateRuntime = candidate.getBrainCoordinator()
                .peekPolicyRuntime();
            freshEnemy = target(destination, candidate.posX + 1.5, candidate.posY, candidate.posZ + 1.5);
            candidate.getAttackListData()
                .attackGroups()
                .put(com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities.id(freshEnemy), MonsterType.HOSTILE);
            row.add("freshTarget", raw(freshEnemy, x, y, z));
            row.add("newShooter", raw(candidate, x, y, z));
            row.add("destinationAfterCommands", destinationState());
            row.addProperty(
                "followupScope",
                "native owner End entry, normal home-disable/ALL/ranged/sitting commands and fresh complete factory; no direct bow tick or future target input");
        }

        @Override
        void close() {
            if (closed) return;
            try {
                for (var arrow : arrows) arrow.setDead();
                for (var arrow : freshArrows) arrow.setDead();
                if (enemy != null) enemy.setDead();
                if (freshEnemy != null) freshEnemy.setDead();
                if (candidate != null) candidate.setDead();
                if (destination != null) {
                    for (var snapshot : endPlatform)
                        helper.assertTrue(snapshot.restore(true, false), "Native End restore failed");
                    for (var snapshot : endPlatform) helper.assertTrue(
                        snapshot
                            .equals(BlockSnapshot.getBlockSnapshot(destination, snapshot.x, snapshot.y, snapshot.z)),
                        "Full End native read-back differs");
                    output.addProperty("endPlatformRestored", true);
                }
            } finally {
                super.close();
            }
        }
    }

    public static final class PantryCollector extends TerrainLifeMechanismPortTests.PantryCollector {

        int firstBite = -1, sleepAt = -1, commandAt = -1, releasedAt = -1, resumedAt = -1, resleptAt = -1;
        int firstWaitAt = -1, restAt = -1, restoredRestAt = -1;
        int lastMouths, firstWaitMouths, commandMouths, releaseMouths = -1, initialFavorability;
        int lastPrimaryBites, releasePrimaryBites = -1, waitingMouthEvents, waitingChangedFrames;
        String pendingCommand;
        int commandObservedAt = -1;

        PantryCollector(GameTestHelper h, MaidBrainProfile profile) {
            super(
                h,
                profile,
                "pantry_sleep_command",
                RESOURCE,
                CONTRACT,
                "behavior-convergence-port-v1",
                "behavior-convergence-");
        }

        @Override
        void setup() {
            super.setup();
            identity(output);
            output.addProperty(
                "pantryScope",
                "one finite cake; commands queued from completed END observation to next START; fresh release baseline; waiting transition mouths recorded, no forced sleep or cooldown reset");
            setPlannedOwner(scene.getAsJsonArray("ownerPosition"));
            helper.setBlock(10, 1, 13, MaidLifeContent.maidBed, 2);
            helper.setBlock(10, 1, 12, MaidLifeContent.maidBed, 10);
        }

        @Override
        void beforeSpawn() {
            super.beforeSpawn();
            initialFavorability = maid.getFavorability();
            boolean cold = maid.getFavorabilityManager()
                .canAdd("StealEdibleBlock");
            output.addProperty("initialPrimaryFavorability", initialFavorability);
            output.addProperty("initialPrimaryStealRewardCold", cold);
            helper.assertTrue(initialFavorability == 0 && cold, "Fresh primary steal reward input is not cold");
        }

        void queueCommand(int observedAt, String kind) {
            helper.assertTrue(pendingCommand == null, "Two pantry commands claimed one native boundary");
            pendingCommand = kind;
            commandObservedAt = observedAt;
        }

        JsonObject commandState(EntityMaid actor) {
            var state = new JsonObject();
            state.addProperty("age", actor.ticksExisted);
            state.addProperty("sitting", actor.isSitting());
            state.addProperty(
                "schedule",
                actor.getSchedule()
                    .name());
            state.addProperty("sleeping", actor.hasPose(MaidPose.SLEEPING));
            return state;
        }

        @Override
        void applyOwner() {
            super.applyOwner();
            if (maid == null || pendingCommand == null) return;
            String kind = pendingCommand;
            int index = frames.size();
            var event = new JsonObject();
            event.addProperty("frame", index);
            event.addProperty("kind", kind);
            event.addProperty("phase", "START_before_native_world_update");
            event.addProperty("triggerCompletedFrame", commandObservedAt);
            event.add("maidBefore", commandState(maid));
            event.add("secondBefore", commandState(second));
            event.addProperty("mouthsBefore", cakeBites());
            if (kind.equals("first_bite_normal_wait_both")) {
                firstWaitAt = index;
                firstWaitMouths = cakeBites();
                maid.setSitting(true);
                second.setSitting(true);
            } else if (kind.equals("release_primary_to_night_rest")) {
                restAt = index;
                maid.setSitting(false);
                maid.setSchedule(MaidSchedule.DAY);
            } else if (kind.equals("sleep_then_normal_all_and_wait")) {
                commandAt = index;
                commandMouths = cakeBites();
                maid.setSchedule(MaidSchedule.ALL);
                maid.setSitting(true);
            } else if (kind.equals("release_owner_wait_allow_natural_food")) {
                releasedAt = index;
                releaseMouths = cakeBites();
                releasePrimaryBites = lastPrimaryBites;
                event.addProperty("releasePrimaryCounterFromCompletedFrame", lastPrimaryBites);
                maid.setSitting(false);
            } else if (kind.equals("resumed_bite_restore_day_shift")) {
                restoredRestAt = index;
                maid.setSchedule(MaidSchedule.DAY);
            } else throw new IllegalStateException("Unknown queued pantry command: " + kind);
            event.add("maidAfter", commandState(maid));
            event.add("secondAfter", commandState(second));
            events.add(event);
            pendingCommand = null;
        }

        @Override
        void cakeFrame(int index, JsonObject row) {
            int mouths = cakeBites();
            helper.assertTrue(
                mouths >= lastMouths && mouths <= 7
                    && mouths - lastMouths <= 2
                    && count(maid, Items.cake) + count(second, Items.cake) == 0,
                "Finite native single cake changed");
            row.addProperty("cakeBitesConsumed", mouths);
            row.addProperty("mouthDelta", mouths - lastMouths);
            row.addProperty("sleeping", maid.hasPose(MaidPose.SLEEPING));
            row.addProperty(
                "primarySchedule",
                maid.getSchedule()
                    .name());
            int primaryBites = lifeTargets(maid).get("cakeBites")
                .getAsInt();
            row.addProperty("primaryConsumeCounter", primaryBites);
            row.addProperty("releaseMouthBaseline", releaseMouths);
            row.addProperty("releasePrimaryCounterBaseline", releasePrimaryBites);
            boolean waiting = firstWaitAt >= 0 && restAt < 0 || commandAt >= 0 && releasedAt < 0;
            row.addProperty("waitingWindow", waiting);
            if (waiting && mouths > lastMouths) waitingMouthEvents++;
            if (firstWaitAt >= 0 && restAt < 0 && mouths != firstWaitMouths
                || commandAt >= 0 && releasedAt < 0 && mouths != commandMouths) waitingChangedFrames++;
            if (firstBite < 0 && mouths > 0 && maid.getFavorability() > initialFavorability) {
                firstBite = index;
                queueCommand(index, "first_bite_normal_wait_both");
            } else if (firstWaitAt >= 0 && restAt < 0 && index >= firstWaitAt + 29) {
                queueCommand(index, "release_primary_to_night_rest");
            }
            if (sleepAt < 0 && restAt >= 0 && maid.hasPose(MaidPose.SLEEPING)) {
                sleepAt = index;
                queueCommand(index, "sleep_then_normal_all_and_wait");
            } else
                if (commandAt >= 0 && releasedAt < 0 && index >= commandAt + 39 && !maid.hasPose(MaidPose.SLEEPING)) {
                    queueCommand(index, "release_owner_wait_allow_natural_food");
                }
            if (releasedAt >= 0 && resumedAt < 0 && mouths > releaseMouths && primaryBites > releasePrimaryBites) {
                resumedAt = index;
                queueCommand(index, "resumed_bite_restore_day_shift");
            }
            if (restoredRestAt >= 0 && maid.hasPose(MaidPose.SLEEPING) && resleptAt < 0) resleptAt = index;
            lastMouths = mouths;
            lastPrimaryBites = primaryBites;
        }

        @Override
        void afterFrame(int index) {
            super.afterFrame(index);
            if (index + 1 != frameLimit()) return;
            output.addProperty("firstBiteFrame", firstBite);
            output.addProperty("naturalSleepFrame", sleepAt);
            output.addProperty("ownerWaitFrame", commandAt);
            output.addProperty("ownerReleaseFrame", releasedAt);
            output.addProperty("resumedBiteFrame", resumedAt);
            output.addProperty("restoredSleepFrame", resleptAt);
            output.addProperty("firstWaitCommandFrame", firstWaitAt);
            output.addProperty("firstRestCommandFrame", restAt);
            output.addProperty("restoredRestCommandFrame", restoredRestAt);
            output.addProperty("releaseMouthBaseline", releaseMouths);
            output.addProperty("releasePrimaryCounterBaseline", releasePrimaryBites);
            output.addProperty("waitingMouthChangeEvents", waitingMouthEvents);
            output.addProperty("waitingChangedObservationFrames", waitingChangedFrames);
            output.addProperty(
                "behaviorSuccess",
                firstBite >= 0 && sleepAt >= 0 && releasedAt >= 0 && resumedAt >= 0 && resleptAt >= 0);
            output.addProperty("resourceAssertionsPassed", true);
            output.addProperty(
                "resourceAssertionScope",
                "one world cake, monotonic0..7 mouths, no backpack cakes or external withdrawal");
        }
    }
}
