package com.tianluoqaq.tlmnh.reference.sleep;

import static com.tianluoqaq.tlmnh.reference.sleep.TerrainLifeMechanismGameTests.raw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.init.InitTaskData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Normal roots and existing collection boundaries; a refused native request remains an outcome. */
@GameTestHolder("tlmnh_behavior_convergence")
@PrefixGameTestTemplate(false)
public final class BehaviorConvergenceGameTests {
    static final String RESOURCE = "/tlmnh-behavior-convergence/contract.json";
    static final String CONTRACT = "BEHAVIOR_CONVERGENCE_V2";

    private BehaviorConvergenceGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 840, batch = "convergence_door")
    public static void redstoneDoorRecovery(GameTestHelper h) { collect(h, "redstone_door_recovery"); }

    @GameTest(template = "cell", timeoutTicks = 460, batch = "convergence_source")
    public static void sourceExitArrow(GameTestHelper h) { collect(h, "source_exit_arrow"); }

    @GameTest(template = "cell", timeoutTicks = 2340, batch = "convergence_pantry")
    public static void pantrySleepCommand(GameTestHelper h) { collect(h, "pantry_sleep_command"); }

    static void collect(GameTestHelper h, String mechanism) {
        FollowMechanismGameTests.Session session = mechanism.equals("pantry_sleep_command")
            ? new PantrySession(h) : new Session(h, mechanism);
        FollowMechanismGameTests.collect(h, session, "tlmnh.behaviorConvergenceProbe");
    }

    public static final class Session extends TerrainLifeMechanismGameTests.Session {
        final JsonArray shots = new JsonArray();
        final List<AbstractArrow> arrows = new ArrayList<>();
        final Map<BlockPos, BlockState> endBlocks = new HashMap<>();
        final Map<BlockPos, CompoundTag> endTiles = new HashMap<>();
        Pig enemy;
        ItemStack weapon;
        ServerLevel destination;
        int firstCross = -1, secondClose = -1, secondApproach = -1, secondOpen = -1, secondCross = -1;
        int firstBlockedSamples, secondBlockedSamples;
        int shiftedAt = -1, requestedAt = -1;
        boolean reversePending, shiftPending, exitPending, sourceExited, secondOpportunityUnavailable;

        Session(GameTestHelper h, String mechanism) {
            super(h, mechanism, RESOURCE, CONTRACT, "behavior-convergence-original-v1", "behavior-convergence-original-");
            output.add("shots", shots);
            output.addProperty("refusalTelemetry", "UNKNOWN_original_not_instrumented");
        }

        @Override String identityProperty() { return "tlmnh.behaviorConvergence.buildIdentity"; }

        @Override void setup() {
            super.setup();
            if (mechanism.equals("redstone_door_recovery")) {
                for (int x = 1; x < 32; x++) for (int y = 1; y <= 3; y++) {
                    helper.setBlock(x, y, 3, Blocks.STONE);
                    helper.setBlock(x, y, 5, Blocks.STONE);
                    if (y == 3) helper.setBlock(x, y, 4, Blocks.STONE);
                }
                var door = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
                helper.setBlock(11, 1, 4, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                helper.setBlock(11, 2, 4, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            if (!mechanism.equals("source_exit_arrow")) return;
            maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
            maid.setHomeModeEnable(true);
            maid.restrictTo(maid.blockPosition(), 12);
            maid.setTask(TaskManager.findTask(new ResourceLocation("touhou_little_maid:ranged_attack")).orElseThrow());
            weapon = new ItemStack(Items.BOW);
            maid.setItemInHand(InteractionHand.MAIN_HAND, weapon);
            maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.ARROW, 24));
            enemy = new Pig(EntityType.PIG, level);
            enemy.setPersistenceRequired();
            enemy.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
            enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            enemy.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            enemy.setHealth(1000);
            enemy.setPos(origin.getX() + 17.5, origin.getY() + 1, origin.getZ() + 4.5);
            helper.assertTrue(level.addFreshEntity(enemy), "Controlled finite arrow target rejected");
            var groups = new HashMap<ResourceLocation, MonsterType>();
            groups.put(new ResourceLocation("minecraft:pig"), MonsterType.HOSTILE);
            maid.setData(InitTaskData.ATTACK_LIST, new AttackListData(groups));
            output.add("enemyInitial", raw(enemy, origin));
            output.addProperty("initialNonEmptyItemStackCount", 2);
        }

        @Override void beforeWorld() {
            if (maid != null && mechanism.equals("redstone_door_recovery")) {
                int index = frames.size();
                if (reversePending && secondClose < 0) {
                    secondClose = index;
                    power(false);
                }
                if (secondClose >= 0 && secondApproach < 0 && !secondOpportunityUnavailable && index >= secondClose + 20) {
                    var actual = doorState();
                    if (!actual.get("nativeOpen").getAsBoolean() && actual.get("doorwayBlockCollisionCount").getAsInt() > 0) {
                        if (reverseOwner()) {
                            secondApproach = index;
                            maid.setInSittingPose(false); event("release_wait_against_closed_door");
                        }
                    }
                }
                if (index == 100) {
                    plannedOwner = absolute(contract.getAsJsonObject("door").getAsJsonArray("openOwnerPosition"));
                    power(true);
                }
                if (secondApproach >= 0 && index == secondApproach + 100) { reverseOwner(); secondOpen = index; power(true); }
            }
            if (maid != null && shiftPending && shiftedAt < 0) {
                shiftedAt = frames.size();
                enemy.setPos(origin.getX() + 17.5, origin.getY() + 1, origin.getZ() + 7.5);
                event("target_moves_after_first_natural_arrow").add("actualTarget", raw(enemy, origin));
            }
            if (exitPending && requestedAt < 0) requestExit();
            if (!sourceExited) super.beforeWorld();
        }

        JsonObject event(String kind) {
            var row = new JsonObject();
            row.addProperty("frame", frames.size()); row.addProperty("kind", kind);
            row.addProperty("phase", "START_before_native_world_update"); events.add(row); return row;
        }

        void endEvent(int index, String kind) {
            var row = event(kind); row.addProperty("frame", index); row.addProperty("phase", "END_after_recorded_frame");
        }

        boolean reverseOwner() {
            double relativeX = maid.getX() - origin.getX() - 8;
            var position = new Vec3(origin.getX() + relativeX, origin.getY() + 1, origin.getZ() + 4.5);
            double distance = maid.distanceToSqr(position);
            boolean formed = relativeX >= 1 && relativeX <= 10.5 && distance >= 36 && distance < 100;
            var row = event(formed ? "bounded_reverse_owner_input" : "second_opportunity_not_geometrically_formed");
            row.add("actualMaid", relative(maid.position())); row.add("plannedOwner", relative(position));
            row.addProperty("distanceSquared", distance); row.addProperty("formed", formed);
            if (formed) plannedOwner = position;
            else secondOpportunityUnavailable = true;
            return formed;
        }

        void power(boolean powered) {
            level.setBlock(origin.offset(11, 1, 3), (powered ? Blocks.REDSTONE_BLOCK : Blocks.STONE).defaultBlockState(), 3);
            var row = event(powered ? "native_redstone_power_on" : "native_redstone_power_off");
            row.add("actualDoorAfterCall", doorState());
        }

        JsonObject doorState() {
            var pos = origin.offset(11, 1, 4); var state = level.getBlockState(pos); var row = new JsonObject();
            row.addProperty("class", state.getBlock().getClass().getName()); row.addProperty("rawBlockState", state.toString());
            row.addProperty("nativeOpen", state.getValue(DoorBlock.OPEN));
            row.addProperty("nativePowered", state.getValue(DoorBlock.POWERED));
            int collisions = 0;
            for (var ignored : level.getBlockCollisions(maid, new AABB(pos.getX() + .05, pos.getY(), pos.getZ() + .3,
                pos.getX() + .95, pos.getY() + 1.6, pos.getZ() + .7))) collisions++;
            row.addProperty("doorwayBlockCollisionCount", collisions);
            var tile = level.getBlockEntity(pos);
            row.addProperty("tileClass", tile == null ? "none" : tile.getClass().getName());
            if (tile != null) row.add("tileNBT", SleepAttributeSnapshot.binaryNbt(tile.saveWithFullMetadata()));
            return row;
        }

        @Override void afterFrame(int index) {
            var row = frames.get(index).getAsJsonObject();
            if (mechanism.equals("redstone_door_recovery")) {
                var actual = doorState(); row.add("door", actual);
                if (row.get("walkTarget").getAsBoolean() && actual.get("doorwayBlockCollisionCount").getAsInt() > 0) {
                    if (index < 100) firstBlockedSamples++;
                    if (secondApproach >= 0 && secondOpen < 0) secondBlockedSamples++;
                }
                if (index >= 100 && secondClose < 0 && maid.getX() - origin.getX() >= 12.35 && firstCross < 0) {
                    firstCross = index; reversePending = true; maid.setInSittingPose(true); endEvent(index, "first_cross_normal_wait_before_native_close");
                }
                if (secondOpen >= 0 && maid.getX() - origin.getX() <= 10.5 && secondCross < 0) secondCross = index;
            } else {
                row.addProperty("usingItem", maid.isUsingItem()); row.addProperty("inventoryArrows", countArrows());
                row.addProperty("weaponWear", weapon.getDamageValue()); row.addProperty("confirmedJoins", shots.size());
                helper.assertTrue(countArrows() + shots.size() == 24 && weapon.getDamageValue() == shots.size(), "Finite original arrows/payment changed");
                if (!shots.isEmpty()) shiftPending = true;
                if (shiftedAt >= 0 && requestedAt < 0 && maid.isUsingItem()
                    && arrows.stream().anyMatch(a -> a.isAlive() && level.getEntity(a.getUUID()) == a)) exitPending = true;
                row.addProperty("sourceExited", sourceExited);
            }
            if (index + 1 == frameLimit()) {
                output.addProperty("firstCrossFrame", firstCross); output.addProperty("secondCloseFrame", secondClose);
                output.addProperty("secondApproachFrame", secondApproach);
                output.addProperty("secondOpenFrame", secondOpen); output.addProperty("secondCrossFrame", secondCross);
                output.addProperty("firstBlockedSamples", firstBlockedSamples); output.addProperty("secondBlockedSamples", secondBlockedSamples);
                output.addProperty("secondOpportunityUnavailable", secondOpportunityUnavailable);
                output.addProperty("sourceRequestFrame", requestedAt); output.addProperty("sourceExitExercised", sourceExited);
                output.addProperty("behaviorSuccess", mechanism.equals("redstone_door_recovery") ? !secondOpportunityUnavailable && secondCross >= 0 && firstBlockedSamples > 0 && secondBlockedSamples > 0 : requestedAt >= 0);
                output.addProperty("resourceAssertionsPassed", true);
            }
        }

        int countArrows() { return maid.getMaidInv().getStackInSlot(0).getCount(); }

        @SubscribeEvent(priority = EventPriority.LOWEST) public void arrowJoin(EntityJoinLevelEvent event) {
            if (!(event.getEntity() instanceof AbstractArrow arrow) || arrow.getOwner() != maid) return;
            arrows.add(arrow); var row = raw(arrow, origin); row.addProperty("frame", frames.size()); shots.add(row);
        }

        void requestExit() {
            if (!maid.isUsingItem() || arrows.stream().noneMatch(a -> a.isAlive() && level.getEntity(a.getUUID()) == a)) {
                exitPending = false;
                return;
            }
            requestedAt = frames.size(); destination = level.getServer().getLevel(ServerLevel.END);
            output.addProperty("sourceRequestFrame", requestedAt);
            helper.assertTrue(destination != null, "Native End unavailable");
            var center = ServerLevel.END_SPAWN_POINT;
            var platform = new JsonArray();
            for (int x = -2; x <= 2; x++) for (int y = -2; y <= 1; y++) for (int z = -2; z <= 2; z++) {
                var pos = center.offset(x, y, z); endBlocks.put(pos, destination.getBlockState(pos));
                var tile = destination.getBlockEntity(pos); if (tile != null) endTiles.put(pos, tile.saveWithFullMetadata());
                var saved = new JsonObject(); saved.addProperty("x", pos.getX()); saved.addProperty("y", pos.getY()); saved.addProperty("z", pos.getZ());
                saved.addProperty("rawBlockState", endBlocks.get(pos).toString());
                if (tile != null) saved.add("fullNativeTileNBT", SleepAttributeSnapshot.binaryNbt(endTiles.get(pos)));
                platform.add(saved);
            }
            output.add("endPlatformOriginal", platform);
            var row = event("normal_maid_change_dimension_request"); row.add("before", raw(maid, origin));
            row.addProperty("usingBefore", maid.isUsingItem());
            var live = new JsonArray();
            for (var arrow : arrows) if (arrow.isAlive() && level.getEntity(arrow.getUUID()) == arrow) live.add(raw(arrow, origin));
            row.add("liveOldArrowsBefore", live);
            var result = maid.changeDimension(destination);
            sourceExited = level.getEntity(maid.getUUID()) != maid || maid.level() != level;
            output.addProperty("sourceExitExercised", sourceExited);
            row.addProperty("returnClass", result == null ? "null" : result.getClass().getName());
            row.addProperty("sourceExited", sourceExited); row.add("after", raw(maid, origin));
            output.addProperty("nativeRequestOutcome", sourceExited ? "SOURCE_EXIT" : "REMAINED_IN_SOURCE_WORLD");
            helper.assertTrue(!sourceExited, "Fixed upstream maid override unexpectedly transferred; retain capture and review the native scope");
        }

        @Override void close() {
            if (closed) return;
            try { for (var arrow : arrows) arrow.discard(); if (enemy != null) enemy.discard(); }
            finally {
                try { super.close(); }
                finally {
                    if (destination != null) {
                        for (var e : endBlocks.entrySet()) destination.setBlock(e.getKey(), e.getValue(), 2);
                        for (var e : endBlocks.entrySet()) {
                            var tile = destination.getBlockEntity(e.getKey()); var tag = endTiles.get(e.getKey());
                            if (tag != null) { helper.assertTrue(tile != null, "Original End tile missing"); tile.load(tag.copy()); }
                            helper.assertTrue(destination.getBlockState(e.getKey()).equals(e.getValue())
                                && (tag == null ? tile == null : tile.saveWithFullMetadata().equals(tag)), "Original End state restore failed");
                        }
                        output.addProperty("endPlatformRestored", true);
                    }
                }
            }
        }
    }

    public static final class PantrySession extends TerrainLifeMechanismGameTests.PantrySession {
        int firstBite = -1, sleepAt = -1, commandAt = -1, releaseAt = -1, resumedAt = -1, resleptAt = -1;
        int lastMouths, firstWaitMouths, commandMouths, waitViolations, initialFavorability;

        PantrySession(GameTestHelper h) {
            super(h, "pantry_sleep_command", RESOURCE, CONTRACT, "behavior-convergence-original-v1", "behavior-convergence-original-");
        }

        @Override String identityProperty() { return "tlmnh.behaviorConvergence.buildIdentity"; }

        @Override void setup() {
            super.setup();
            plannedOwner = absolute(scene.getAsJsonArray("ownerPosition"));
            output.addProperty("pantryScope", "one finite cake; normal wait/rest/ALL/release/rest sequence; no forced sleep or cooldown reset");
            var bed = InitBlocks.MAID_BED.get().defaultBlockState().setValue(BlockMaidBed.FACING, Direction.NORTH);
            helper.setBlock(10, 1, 13, bed.setValue(BlockMaidBed.PART, BedPart.FOOT));
            helper.setBlock(10, 1, 12, bed.setValue(BlockMaidBed.PART, BedPart.HEAD));
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            initialFavorability = maid.getFavorability();
            boolean cold = maid.getFavorabilityManager().canAdd("StealEdibleBlock");
            output.addProperty("initialPrimaryFavorability", initialFavorability); output.addProperty("initialPrimaryStealRewardCold", cold);
            helper.assertTrue(initialFavorability == 0 && cold, "Fresh primary steal reward input is not cold");
        }

        @Override void cakeFrame(int index, JsonObject row) {
            int mouths = cakeBites();
            helper.assertTrue(mouths >= lastMouths && mouths <= 7 && mouths - lastMouths <= 2
                && count(maid, Items.CAKE) + count(second, Items.CAKE) == 0, "Finite original single cake changed");
            row.addProperty("cakeBitesConsumed", mouths); row.addProperty("sleeping", maid.isSleeping());
            row.addProperty("primarySchedule", maid.getSchedule().name());
            if (firstBite < 0 && mouths > 0 && maid.getFavorability() > initialFavorability) {
                firstBite = index; firstWaitMouths = mouths; maid.setInSittingPose(true); second.setInSittingPose(true);
                event(index, "first_bite_normal_wait_both");
            } else if (firstBite >= 0 && index == firstBite + 30) {
                maid.setInSittingPose(false); maid.setSchedule(MaidSchedule.DAY); event(index, "release_primary_to_night_rest");
            }
            if (firstBite >= 0 && index < firstBite + 30 && mouths != firstWaitMouths) waitViolations++;
            if (sleepAt < 0 && firstBite >= 0 && maid.isSleeping()) {
                sleepAt = index; commandAt = index; commandMouths = mouths;
                maid.setSchedule(MaidSchedule.ALL); maid.setInSittingPose(true); event(index, "sleep_then_normal_all_and_wait");
            } else if (commandAt >= 0 && releaseAt < 0 && index >= commandAt + 40 && !maid.isSleeping()) {
                releaseAt = index; maid.setInSittingPose(false); event(index, "release_owner_wait_allow_natural_food");
            }
            if (commandAt >= 0 && releaseAt < 0 && mouths != commandMouths) waitViolations++;
            if (releaseAt >= 0 && resumedAt < 0 && mouths > commandMouths) {
                resumedAt = index; maid.setSchedule(MaidSchedule.DAY); event(index, "resumed_bite_restore_day_shift");
            }
            if (resumedAt >= 0 && maid.isSleeping() && resleptAt < 0) resleptAt = index;
            lastMouths = mouths;
        }

        @Override void afterFrame(int index) {
            super.afterFrame(index);
            if (index + 1 != frameLimit()) return;
            output.addProperty("firstBiteFrame", firstBite); output.addProperty("naturalSleepFrame", sleepAt);
            output.addProperty("ownerWaitFrame", commandAt); output.addProperty("ownerReleaseFrame", releaseAt);
            output.addProperty("resumedBiteFrame", resumedAt); output.addProperty("restoredSleepFrame", resleptAt);
            output.addProperty("waitConsumptionViolations", waitViolations);
            output.addProperty("behaviorSuccess", firstBite >= 0 && sleepAt >= 0 && releaseAt >= 0 && resumedAt >= 0 && resleptAt >= 0 && waitViolations == 0);
            output.addProperty("resourceAssertionsPassed", true);
            output.addProperty("resourceAssertionScope", "one world cake, monotonic0..7 mouths, no backpack cakes or external withdrawal");
        }
    }
}
