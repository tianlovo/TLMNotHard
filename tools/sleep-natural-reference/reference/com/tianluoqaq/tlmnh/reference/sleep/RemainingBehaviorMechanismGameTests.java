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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.init.InitTaskData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Four remaining normal-root mechanisms; reuses the frozen follow/terrain native collection boundary. */
@GameTestHolder("tlmnh_remaining_behavior_mechanisms")
@PrefixGameTestTemplate(false)
public final class RemainingBehaviorMechanismGameTests {
    static final String RESOURCE = "/tlmnh-remaining-behavior-mechanisms/contract.json";
    static final String CONTRACT = "REMAINING_BEHAVIOR_MECHANISMS_V2";

    private RemainingBehaviorMechanismGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 620, batch = "remaining_door")
    public static void closedDoorRecovery(GameTestHelper h) { run(h, "closed_door_recovery"); }
    @GameTest(template = "cell", timeoutTicks = 500, batch = "remaining_melee")
    public static void meleeBoundaryVariant(GameTestHelper h) { run(h, "melee_boundary_variant"); }
    @GameTest(template = "cell", timeoutTicks = 380, batch = "remaining_dimension")
    public static void ownerDimensionArrow(GameTestHelper h) { run(h, "owner_dimension_arrow"); }
    @GameTest(template = "cell", timeoutTicks = 860, batch = "remaining_competition")
    public static void sleepDefenseCrisis(GameTestHelper h) { run(h, "sleep_defense_crisis"); }

    static void run(GameTestHelper h, String mechanism) {
        FollowMechanismGameTests.collect(h, new Session(h, mechanism), "tlmnh.remainingBehaviorMechanismsProbe");
    }

    public static final class Session extends TerrainLifeMechanismGameTests.Session {
        final JsonArray combatDamage = new JsonArray(), shots = new JsonArray();
        final List<AbstractArrow> arrows = new ArrayList<>();
        final Map<BlockPos, BlockState> endBlocks = new HashMap<>();
        final Map<BlockPos, CompoundTag> endTiles = new HashMap<>();
        LivingEntity enemy;
        Wolf friend;
        Entity ownedMount;
        ItemStack weapon;
        ServerLevel destination;
        int firstArrival = -1, secondArrival = -1, sleepAt = -1, defenseAt = -1, crisisAt = -1, restoredAt = -1;
        int migratedAt = -1, migrationShots, migrationWear, outsideHits, friendHits;
        int secondClosedAt = -1, secondOpenedAt = -1, outsideAt = -1, reentryAt = -1;
        float migrationTargetHealth;
        boolean migrationPending, reversePending, outsidePending;

        Session(GameTestHelper h, String mechanism) {
            super(h, mechanism, RESOURCE, CONTRACT, "remaining-behavior-original-v1", "remaining-behavior-original-");
            output.add("combatDamageEvents", combatDamage);
            output.add("shots", shots);
            output.addProperty("refusalTelemetry", "UNKNOWN_original_not_instrumented");
        }

        @Override String identityProperty() { return "tlmnh.remainingBehaviorMechanisms.buildIdentity"; }

        @Override void setup() {
            super.setup();
            if (!mechanism.equals("closed_door_recovery") && !mechanism.equals("owner_dimension_arrow")) {
                owner.setGameMode(GameType.SURVIVAL);
                owner.getAbilities().invulnerable = false;
                owner.getAbilities().mayfly = owner.getAbilities().flying = true;
                owner.setHealth(owner.getMaxHealth());
            }
            if (mechanism.equals("closed_door_recovery")) {
                for (int x = 1; x < 32; x++) for (int y = 1; y <= 3; y++) {
                    helper.setBlock(x, y, 3, Blocks.STONE);
                    helper.setBlock(x, y, 5, Blocks.STONE);
                    if (y == 3) helper.setBlock(x, y, 4, Blocks.STONE);
                }
                placeDoor(9, Blocks.OAK_DOOR);
                placeDoor(11, Blocks.IRON_DOOR);
            }
            if (mechanism.equals("sleep_defense_crisis")) {
                var state = InitBlocks.MAID_BED.get().defaultBlockState()
                    .setValue(BlockMaidBed.FACING, Direction.NORTH).setValue(BlockMaidBed.OCCUPIED, false);
                helper.setBlock(9, 1, 5, state.setValue(BlockMaidBed.PART, BedPart.FOOT));
                helper.setBlock(9, 1, 4, state.setValue(BlockMaidBed.PART, BedPart.HEAD));
            }
            beforeWorld();
        }

        void placeDoor(int x, net.minecraft.world.level.block.Block block) {
            var state = block.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, false);
            helper.setBlock(x, 1, 4, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            helper.setBlock(x, 2, 4, state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            if (!mechanism.equals("closed_door_recovery")) {
                maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
                maid.setHomeModeEnable(true);
                maid.restrictTo(maid.blockPosition(), 12);
                maid.setTask(TaskManager.findTask(new ResourceLocation("touhou_little_maid", scene.get("task").getAsString())).orElseThrow());
                weapon = new ItemStack(mechanism.equals("owner_dimension_arrow") ? Items.BOW : Items.IRON_SWORD);
                maid.setItemInHand(InteractionHand.MAIN_HAND, weapon);
            }
            if (mechanism.equals("melee_boundary_variant")) {
                var zombie = new Zombie(EntityType.ZOMBIE, level);
                zombie.setPos(origin.getX() + 14.5, origin.getY() + 1, origin.getZ() + 4.5);
                zombie.setPersistenceRequired();
                output.add("enemyBeforeInitialization", raw(zombie, origin));
                var existing = new java.util.HashSet<Entity>();
                level.getAllEntities().forEach(existing::add);
                net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(zombie, level,
                    level.getCurrentDifficultyAt(zombie.blockPosition()), net.minecraft.world.entity.MobSpawnType.NATURAL, null, null);
                zombie.setBaby(true);
                level.tryAddFreshEntityWithPassengers(zombie);
                helper.assertTrue(level.getEntity(zombie.getUUID()) == zombie, "Native baby Zombie join was replaced/rejected");
                enemy = zombie;
                if (zombie.getVehicle() != null && !existing.contains(zombie.getVehicle())) ownedMount = zombie.getVehicle();
                friend = new Wolf(EntityType.WOLF, level);
                friend.setTame(true);
                friend.setOwnerUUID(owner.getUUID());
                friend.setOrderedToSit(true);
                friend.setPos(origin.getX() + 10.5, origin.getY() + 1, origin.getZ() + 4.5);
                helper.assertTrue(level.addFreshEntity(friend), "Allied seated wolf join rejected");
                output.add("friendInitial", raw(friend, origin));
            } else if (mechanism.equals("owner_dimension_arrow") || mechanism.equals("sleep_defense_crisis")) {
                var pig = new Pig(EntityType.PIG, level);
                pig.setPersistenceRequired();
                pig.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                pig.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
                pig.setHealth(1000);
                pig.setPos(origin.getX() + 17.5, origin.getY() + 1, origin.getZ() + 4.5);
                helper.assertTrue(level.addFreshEntity(pig), "Controlled native neutral input rejected");
                enemy = pig;
                if (mechanism.equals("owner_dimension_arrow")) {
                    maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.ARROW, 24));
                    var groups = new HashMap<ResourceLocation, MonsterType>();
                    groups.put(new ResourceLocation("minecraft", "pig"), MonsterType.HOSTILE);
                    maid.setData(InitTaskData.ATTACK_LIST, new AttackListData(groups));
                } else maid.setSchedule(MaidSchedule.DAY);
            }
            if (enemy != null) output.add("enemyInitial", raw(enemy, origin));
            output.addProperty("initialNonEmptyItemStackCount", mechanism.equals("closed_door_recovery") ? 0 : mechanism.equals("owner_dimension_arrow") ? 2 : 1);
        }

        @Override void beforeWorld() {
            if (migratedAt >= 0) return;
            if (maid == null) { super.beforeWorld(); return; }
            int index = frames.size();
            if (mechanism.equals("closed_door_recovery")) {
                if (reversePending && secondClosedAt < 0) {
                    secondClosedAt = index;
                    plannedOwner = absolute(contract.getAsJsonObject("door").getAsJsonArray("reverseOwnerPosition"));
                }
                if (index == 0 || index == 100 || index == secondClosedAt
                    || secondClosedAt >= 0 && index == secondClosedAt + 100) {
                    boolean open = index == 100 || secondClosedAt >= 0 && index == secondClosedAt + 100;
                    var pos = origin.offset(11, 1, 4);
                    ((DoorBlock) Blocks.IRON_DOOR).setOpen(null, level, level.getBlockState(pos), pos, open);
                    event(open ? "native_iron_door_open" : "native_iron_door_closed");
                    if (open && secondClosedAt >= 0) secondOpenedAt = index;
                }
            } else if (mechanism.equals("melee_boundary_variant")) {
                if (outsidePending && outsideAt < 0) {
                    outsideAt = index;
                    event("controlled_enemy_outside_home").add("liveEnemyBefore", raw(enemy, origin));
                }
                if (outsideAt >= 0 && index >= outsideAt && index < outsideAt + 60) {
                    var at = absolute(contract.getAsJsonObject("melee").getAsJsonArray("outsidePosition"));
                    enemy.setPos(at.x, at.y, at.z);
                    enemy.setDeltaMovement(Vec3.ZERO);
                }
                if (outsideAt >= 0 && index == outsideAt + 60) {
                    reentryAt = index;
                    enemy.setPos(origin.getX() + 14.5, origin.getY() + 1, origin.getZ() + 4.5);
                    event("release_native_enemy_inside_home");
                }
                if (index == 260) {
                    maid.setTask(TaskManager.getIdleTask());
                    event("normal_idle_command");
                }
            } else if (migrationPending) migrateOwner();
            if (migratedAt < 0) super.beforeWorld();
        }

        JsonObject event(String kind) {
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("kind", kind);
            row.addProperty("phase", "actual_callback_boundary");
            events.add(row);
            return row;
        }

        @Override void observe() throws Exception {
            if (migratedAt < 0) { super.observe(); return; }
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("gameTime", level.getGameTime());
            row.addProperty("age", maid.tickCount);
            row.addProperty("livingTicks", livingTicks);
            row.addProperty("phase", "revoked_owner_world");
            row.addProperty("normalRootFrameClaimed", false);
            row.add("maid", raw(maid, origin));
            row.add("owner", ownerSnapshot(false));
            frames.add(row);
            afterFrame(frames.size() - 1);
            livingTicks = 0;
            drain();
            if (frames.size() == frameLimit()) finishObservation("window_end");
        }

        @Override void afterFrame(int index) {
            var row = frames.get(index).getAsJsonObject();
            row.addProperty("sleeping", maid.isSleeping());
            row.addProperty("weaponWear", weapon == null ? 0 : weapon.getDamageValue());
            if (enemy != null) {
                row.add("enemy", raw(enemy, origin));
                row.addProperty("enemySelected", maid.getTarget() == enemy);
                row.addProperty("enemyWithinHome", maid.isWithinRestriction(enemy.blockPosition()));
            }
            if (friend != null) row.addProperty("friendHealth", friend.getHealth());
            if (mechanism.equals("closed_door_recovery")) {
                row.addProperty("ironOpen", level.getBlockState(origin.offset(11, 1, 4)).getValue(DoorBlock.OPEN));
                row.addProperty("woodOpen", level.getBlockState(origin.offset(9, 1, 4)).getValue(DoorBlock.OPEN));
                if (index >= 100 && secondClosedAt < 0 && maid.distanceToSqr(plannedOwner) <= 4 && firstArrival < 0) {
                    firstArrival = index;
                    reversePending = true;
                }
                if (secondClosedAt >= 0 && maid.distanceToSqr(plannedOwner) <= 4 && secondArrival < 0) secondArrival = index;
                row.addProperty("actualAStarSearches", "UNKNOWN_original_not_instrumented");
            }
            if (mechanism.equals("melee_boundary_variant") && outsideAt < 0 && enemy.isAlive()
                && enemy.getHealth() < enemy.getMaxHealth() && weapon.getDamageValue() > 0) outsidePending = true;
            if (mechanism.equals("sleep_defense_crisis")) competition(index);
            if (mechanism.equals("owner_dimension_arrow")) {
                row.addProperty("usingItem", maid.isUsingItem());
                row.addProperty("inventoryArrows", arrowsLeft());
                row.addProperty("confirmedJoins", shots.size());
                var actual = new JsonArray();
                for (var arrow : arrows) actual.add(raw(arrow, origin));
                row.add("arrows", actual);
                helper.assertTrue(arrowsLeft() + shots.size() == 24 && weapon.getDamageValue() == shots.size(), "Original finite arrow/payment conservation changed");
                if (migratedAt < 0 && !shots.isEmpty()) migrationPending = true;
            }
            if (index + 1 == frameLimit()) {
                output.addProperty("firstArrival", firstArrival);
                output.addProperty("secondArrival", secondArrival);
                output.addProperty("secondClosedFrame", secondClosedAt);
                output.addProperty("secondOpenedFrame", secondOpenedAt);
                output.addProperty("liveEnemyOutsideFrame", outsideAt);
                output.addProperty("liveEnemyReentryFrame", reentryAt);
                output.addProperty("sleepFrame", sleepAt);
                output.addProperty("defenseFrame", defenseAt);
                output.addProperty("crisisFrame", crisisAt);
                output.addProperty("restoredSleepFrame", restoredAt);
                output.addProperty("migrationFrame", migratedAt);
                output.addProperty("outsideAttackCandidates", outsideHits);
                output.addProperty("friendlyAttackCandidates", friendHits);
                output.addProperty("behaviorSuccess", mechanism.equals("closed_door_recovery") ? firstArrival >= 0 && secondArrival >= 0
                    : mechanism.equals("sleep_defense_crisis") ? restoredAt >= 0 : mechanism.equals("owner_dimension_arrow") ? migratedAt >= 0
                    : outsideAt >= 0 && reentryAt >= 0 && enemy != null && !enemy.isAlive());
                output.addProperty("resourceAssertionsPassed", true);
            }
        }

        void competition(int index) {
            if (sleepAt < 0 && maid.isSleeping()) {
                sleepAt = index;
                event("natural_sleep_then_actual_owner_hurt");
                boolean accepted = owner.hurt(level.damageSources().mobAttack(enemy), 1);
                helper.assertTrue(accepted && owner.getLastHurtByMob() == enemy, "Actual owner hurt predicate rejected");
            } else if (sleepAt >= 0 && defenseAt < 0 && index == sleepAt + 1) {
                helper.assertTrue(maid.hurt(level.damageSources().mobAttack(enemy), 1), "Actual sleeping maid hurt rejected");
                maid.setSchedule(MaidSchedule.ALL);
                defenseAt = index;
                event("actual_maid_hurt_and_normal_all_schedule");
            } else if (defenseAt >= 0 && crisisAt < 0 && index == defenseAt + 60) {
                maid.setTask(TaskManager.getIdleTask());
                maid.setInSittingPose(true);
                creeper = new Creeper(EntityType.CREEPER, level);
                creeper.setPersistenceRequired();
                creeper.setPos(maid.getX() + 3, maid.getY(), maid.getZ());
                net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(creeper, level,
                    level.getCurrentDifficultyAt(creeper.blockPosition()), net.minecraft.world.entity.MobSpawnType.NATURAL, null, null);
                helper.assertTrue(level.addFreshEntity(creeper), "Native crisis Creeper rejected");
                crisisAt = index;
                event("normal_wait_idle_and_native_creeper").add("creeperInitial", raw(creeper, origin));
            } else if (crisisAt >= 0 && index == crisisAt + 40) {
                creeper.discard();
                maid.setInSittingPose(false);
                maid.setTask(TaskManager.findTask(new ResourceLocation("touhou_little_maid", "attack")).orElseThrow());
                maid.setSchedule(MaidSchedule.DAY);
                enemy.discard();
                event("withdraw_owned_threat_restore_night");
            }
            if (crisisAt >= 0 && index > crisisAt + 40 && maid.isSleeping() && restoredAt < 0) restoredAt = index;
        }

        int arrowsLeft() { return maid.getMaidInv().getStackInSlot(0).getCount(); }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void arrowJoin(EntityJoinLevelEvent e) {
            if (!(e.getEntity() instanceof AbstractArrow arrow) || arrow.getOwner() != maid) return;
            arrows.add(arrow);
            var row = raw(arrow, origin);
            row.addProperty("frame", frames.size());
            row.addProperty("noGravity", arrow.isNoGravity());
            shots.add(row);
        }

        @Override @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent e) {
            super.hurt(e);
            if (e.getSource().getEntity() != maid || e.getEntity() != enemy && e.getEntity() != friend) return;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("victim", e.getEntity() == friend ? "friend" : "enemy");
            row.addProperty("amount", e.getAmount());
            row.addProperty("canceled", e.isCanceled());
            boolean outside = !maid.isWithinRestriction(e.getEntity().blockPosition());
            row.addProperty("outsideHome", outside);
            combatDamage.add(row);
            if (!e.isCanceled() && e.getAmount() > 0) {
                if (e.getEntity() == friend) friendHits++;
                if (outside) outsideHits++;
            }
        }

        void migrateOwner() {
            destination = level.getServer().getLevel(Level.END);
            helper.assertTrue(destination != null, "Native End unavailable");
            var center = net.minecraft.server.level.ServerLevel.END_SPAWN_POINT;
            var platform = new JsonArray();
            for (int x = -2; x <= 2; x++) for (int y = -2; y <= 1; y++) for (int z = -2; z <= 2; z++) {
                var pos = center.offset(x, y, z);
                endBlocks.put(pos, destination.getBlockState(pos));
                var tile = destination.getBlockEntity(pos);
                if (tile != null) endTiles.put(pos, tile.saveWithFullMetadata());
                var row = new JsonObject();
                row.addProperty("x", pos.getX());
                row.addProperty("y", pos.getY());
                row.addProperty("z", pos.getZ());
                row.addProperty("rawBlockState", endBlocks.get(pos).toString());
                if (tile != null) row.add("fullNativeTileNBT", SleepAttributeSnapshot.binaryNbt(endTiles.get(pos)));
                platform.add(row);
            }
            output.add("endPlatformOriginal", platform);
            migratedAt = frames.size();
            migrationShots = shots.size();
            migrationWear = weapon.getDamageValue();
            migrationTargetHealth = enemy.getHealth();
            var event = event("native_owner_change_dimension_End");
            event.add("before", raw(owner, origin));
            var moved = owner.changeDimension(destination);
            helper.assertTrue(moved == owner && owner.level() == destination, "Actual native owner dimension transfer failed");
            event.add("after", raw(owner, origin));
            event.addProperty("shotsAtBoundary", migrationShots);
            event.addProperty("wearAtBoundary", migrationWear);
            event.addProperty("targetHealthAtBoundary", migrationTargetHealth);
        }

        @Override void close() {
            if (closed) return;
            try {
                for (var arrow : arrows) arrow.discard();
                if (enemy != null) enemy.discard();
                if (friend != null) friend.discard();
                if (ownedMount != null) ownedMount.discard();
                if (destination != null && maid != null) {
                    var transferred = destination.getEntity(maid.getUUID());
                    if (transferred != null && transferred != maid) transferred.discard();
                }
            } finally {
                try { super.close(); }
                finally {
                    if (destination != null) {
                        for (var entry : endBlocks.entrySet()) destination.setBlock(entry.getKey(), entry.getValue(), 2);
                        for (var entry : endBlocks.entrySet()) {
                            var tag = endTiles.get(entry.getKey());
                            var tile = destination.getBlockEntity(entry.getKey());
                            if (tag != null) {
                                helper.assertTrue(tile != null, "Original End tile missing after native restore");
                                tile.load(tag.copy());
                            }
                            helper.assertTrue(destination.getBlockState(entry.getKey()).equals(entry.getValue())
                                && (tag == null ? tile == null : tile.saveWithFullMetadata().equals(tag)), "Full original End platform did not restore");
                        }
                        output.addProperty("endPlatformRestored", true);
                    }
                }
            }
        }
    }
}
