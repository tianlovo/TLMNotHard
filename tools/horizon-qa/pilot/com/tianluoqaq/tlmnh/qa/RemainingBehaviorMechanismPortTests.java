package com.tianluoqaq.tlmnh.qa;

import static com.tianluoqaq.tlmnh.qa.TerrainLifeMechanismPortTests.raw;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.BlockDoor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Four remaining mechanisms on normal factory F/N roots; native frames come from the existing collector. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class RemainingBehaviorMechanismPortTests {

    static final String RESOURCE = "/tlmnh-remaining-behavior-mechanisms/contract.json";
    static final String CONTRACT = "REMAINING_BEHAVIOR_MECHANISMS_V2";

    private RemainingBehaviorMechanismPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 620, batch = "remaining_door_f")
    public static void faithfulClosedDoorRecovery(GameTestHelper h) {
        run(h, false, "closed_door_recovery");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 620, batch = "remaining_door_n")
    public static void enhancedClosedDoorRecovery(GameTestHelper h) {
        run(h, true, "closed_door_recovery");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 500, batch = "remaining_melee_f")
    public static void faithfulMeleeBoundaryVariant(GameTestHelper h) {
        run(h, false, "melee_boundary_variant");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 500, batch = "remaining_melee_n")
    public static void enhancedMeleeBoundaryVariant(GameTestHelper h) {
        run(h, true, "melee_boundary_variant");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 380, batch = "remaining_dimension_f")
    public static void faithfulOwnerDimensionArrow(GameTestHelper h) {
        run(h, false, "owner_dimension_arrow");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 380, batch = "remaining_dimension_n")
    public static void enhancedOwnerDimensionArrow(GameTestHelper h) {
        run(h, true, "owner_dimension_arrow");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 860, batch = "remaining_competition_f")
    public static void faithfulSleepDefenseCrisis(GameTestHelper h) {
        run(h, false, "sleep_defense_crisis");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 860, batch = "remaining_competition_n")
    public static void enhancedSleepDefenseCrisis(GameTestHelper h) {
        run(h, true, "sleep_defense_crisis");
    }

    static void run(GameTestHelper h, boolean enhanced, String mechanism) {
        var c = new Collector(h, enhanced ? MaidBrainProfile.ENHANCED : MaidBrainProfile.FAITHFUL_TLM, mechanism);
        h.afterTest(c::close);
        h.onEachTick("remaining normal behavior", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
        c.setup();
    }

    public static final class Collector extends TerrainLifeMechanismPortTests.Collector {

        final JsonArray combatDamage = new JsonArray(), shots = new JsonArray();
        final List<EntityArrow> arrows = new ArrayList<>();
        final List<BlockSnapshot> endPlatform = new ArrayList<>();
        EntityLivingBase enemy;
        EntityWolf friend;
        Entity ownedMount;
        ItemStack weapon;
        MaidBowAttack bow;
        WorldServer destination;
        int firstArrival = -1, secondArrival = -1, sleepAt = -1, defenseAt = -1, crisisAt = -1, restoredAt = -1;
        int migratedAt = -1, migrationShots, migrationWear, outsideHits, friendHits;
        int secondClosedAt = -1, secondOpenedAt = -1, outsideAt = -1, reentryAt = -1;
        float migrationTargetHealth;
        boolean migrationPending, reversePending, outsidePending;

        Collector(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
            super(h, profile, mechanism, RESOURCE, CONTRACT, "remaining-behavior-port-v1", "remaining-behavior-");
            output.add("combatDamageEvents", combatDamage);
            output.add("shots", shots);
        }

        @Override
        void setup() {
            super.setup();
            try {
                output.add(
                    "remainingObserverArtifact",
                    DevelopmentArtifactIdentity.capture(RemainingBehaviorMechanismPortTests.class, Collector.class));
                output.add(
                    "remainingProductionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        MaidBowAttack.class,
                        com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.class,
                        com.tianluoqaq.tlmnh.common.combat.MaidProjectileSource.class,
                        com.tianluoqaq.tlmnh.common.combat.MaidProjectileImpactProtection.class,
                        com.tianluoqaq.tlmnh.common.lifecycle.MaidDimensionTravel.class));
            } catch (Exception error) {
                throw new IllegalStateException("Remaining mechanism identity unavailable", error);
            }
            if (!mechanism.equals("closed_door_recovery") && !mechanism.equals("owner_dimension_arrow")) {
                owner.setGameType(GameType.SURVIVAL);
                owner.capabilities.disableDamage = false;
                owner.capabilities.allowFlying = owner.capabilities.isFlying = true;
                owner.setHealth(owner.getMaxHealth());
            }
            if (mechanism.equals("closed_door_recovery")) {
                for (int a = 1; a < 32; a++) for (int b = 1; b <= 3; b++) {
                    helper.setBlock(a, b, 3, Blocks.stone);
                    helper.setBlock(a, b, 5, Blocks.stone);
                    if (b == 3) helper.setBlock(a, b, 4, Blocks.stone);
                }
                placeDoor(9, Blocks.wooden_door);
                placeDoor(11, Blocks.iron_door);
            }
            if (mechanism.equals("sleep_defense_crisis")) {
                helper.setBlock(9, 1, 5, MaidLifeContent.maidBed, 2);
                helper.setBlock(9, 1, 4, MaidLifeContent.maidBed, 2 | 8);
            }
            applyOwner();
        }

        void placeDoor(int a, net.minecraft.block.Block block) {
            helper.setBlock(a, 1, 4, block, 0);
            helper.setBlock(a, 2, 4, block, 8);
        }

        @Override
        void beforeSpawn() {
            super.beforeSpawn();
            if (!mechanism.equals("closed_door_recovery")) {
                helper.assertTrue(
                    maid.getHomeArea()
                        .setEnabled(true),
                    "Normal home entry rejected");
                maid.setTask(mechanism.equals("owner_dimension_arrow") ? MaidTask.RANGED_ATTACK : MaidTask.ATTACK);
                weapon = new ItemStack(mechanism.equals("owner_dimension_arrow") ? Items.bow : Items.iron_sword);
                maid.setCurrentItemOrArmor(0, weapon);
            }
            if (mechanism.equals("melee_boundary_variant")) {
                var zombie = new EntityZombie(helper.getWorld());
                zombie.setPosition(x + 14.5, y + 1, z + 4.5);
                zombie.func_110163_bv();
                output.add("enemyBeforeInitialization", raw(zombie, x, y, z));
                var existing = new java.util.HashSet<>(helper.getWorld().loadedEntityList);
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(zombie),
                    "Native Zombie join rejected");
                boolean handled = net.minecraftforge.event.ForgeEventFactory.doSpecialSpawn(
                    zombie,
                    helper.getWorld(),
                    (float) zombie.posX,
                    (float) zombie.posY,
                    (float) zombie.posZ);
                if (!handled) zombie.onSpawnWithEgg(null);
                zombie.setChild(true);
                helper.assertTrue(
                    helper.getWorld()
                        .getEntityByID(zombie.getEntityId()) == zombie,
                    "Native baby Zombie input was replaced/unregistered");
                enemy = zombie;
                if (zombie.ridingEntity != null && !existing.contains(zombie.ridingEntity))
                    ownedMount = zombie.ridingEntity;
                output.addProperty("nativeSpecialSpawnHandled", handled);
                friend = new EntityWolf(helper.getWorld());
                friend.setTamed(true);
                friend.func_152115_b(
                    owner.getUniqueID()
                        .toString());
                friend.setSitting(true);
                helper.spawnEntity(friend, 10.5, 1, 4.5);
                output.add("friendInitial", raw(friend, x, y, z));
            } else if (mechanism.equals("owner_dimension_arrow") || mechanism.equals("sleep_defense_crisis")) {
                var pig = new EntityPig(helper.getWorld());
                pig.func_110163_bv();
                pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .setBaseValue(0);
                pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                    .setBaseValue(1000);
                pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                    .setBaseValue(1);
                pig.setHealth(1000);
                helper.spawnEntity(pig, 17.5, 1, 4.5);
                enemy = pig;
                if (mechanism.equals("owner_dimension_arrow")) {
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, new ItemStack(Items.arrow, 24));
                    maid.getAttackListData()
                        .attackGroups()
                        .put(com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities.id(pig), MonsterType.HOSTILE);
                } else maid.setSchedule(MaidSchedule.DAY);
            }
            if (enemy != null) output.add("enemyInitial", raw(enemy, x, y, z));
            output.addProperty(
                "initialNonEmptyItemStackCount",
                mechanism.equals("closed_door_recovery") ? 0 : mechanism.equals("owner_dimension_arrow") ? 2 : 1);
        }

        @Override
        void applyOwner() {
            if (migratedAt >= 0) return;
            if (maid == null) {
                super.applyOwner();
                return;
            }
            int index = frames.size();
            if (mechanism.equals("closed_door_recovery")) {
                if (reversePending && secondClosedAt < 0) {
                    secondClosedAt = index;
                    setPlannedOwner(
                        contract.getAsJsonObject("door")
                            .getAsJsonArray("reverseOwnerPosition"));
                }
                if (index == 0 || index == 100
                    || index == secondClosedAt
                    || secondClosedAt >= 0 && index == secondClosedAt + 100) {
                    boolean open = index == 100 || secondClosedAt >= 0 && index == secondClosedAt + 100;
                    ((BlockDoor) Blocks.iron_door).func_150014_a(helper.getWorld(), x + 11, y + 1, z + 4, open);
                    event(open ? "native_iron_door_open" : "native_iron_door_closed");
                    if (open && secondClosedAt >= 0) secondOpenedAt = index;
                }
            } else if (mechanism.equals("melee_boundary_variant")) {
                if (outsidePending && outsideAt < 0) {
                    outsideAt = index;
                    event("controlled_enemy_outside_home").add("liveEnemyBefore", raw(enemy, x, y, z));
                }
                if (outsideAt >= 0 && index >= outsideAt && index < outsideAt + 60) {
                    var p = contract.getAsJsonObject("melee")
                        .getAsJsonArray("outsidePosition");
                    enemy.setPosition(
                        x + p.get(0)
                            .getAsDouble(),
                        y + p.get(1)
                            .getAsDouble(),
                        z + p.get(2)
                            .getAsDouble());
                    enemy.motionX = enemy.motionY = enemy.motionZ = 0;
                }
                if (outsideAt >= 0 && index == outsideAt + 60) {
                    reentryAt = index;
                    enemy.setPosition(x + 14.5, y + 1, z + 4.5);
                    event("release_native_enemy_inside_home");
                }
                if (index == 260) {
                    maid.setTask(MaidTask.IDLE);
                    event("normal_idle_command");
                }
            } else if (migrationPending) migrateOwner();
            if (migratedAt < 0) super.applyOwner();
        }

        JsonObject event(String kind) {
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("kind", kind);
            row.addProperty("phase", "actual_callback_boundary");
            events.add(row);
            return row;
        }

        @Override
        void observe() {
            if (migratedAt < 0) {
                super.observe();
                return;
            }
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty(
                "gameTime",
                helper.getWorld()
                    .getTotalWorldTime());
            row.addProperty("age", maid.ticksExisted);
            row.addProperty("livingTicks", livingTicks);
            helper.assertTrue(
                maid.ticksExisted == previousAge + 1 && livingTicks == 1,
                "Owner migration skipped/doubled the unchanged source actor's native tick");
            previousAge = maid.ticksExisted;
            helper.assertTrue(
                maid.getBrainCoordinator()
                    .peekPolicyRuntime() == runtime && runtime.getProfile() == profile
                    && maid.getBrainCoordinator()
                        .getFollowController()
                        .getTicks() == 0
                    && maid.getBrainCoordinator()
                        .getCombatController()
                        .getTicks() == 0,
                "Unchanged source actor switched factory runtime or entered Legacy after owner migration");
            row.addProperty("phase", "after_owner_migration");
            row.addProperty("normalRootFrameClaimed", false);
            row.addProperty("factoryRuntimeSameObject", true);
            row.addProperty("factoryRuntimeReady", runtime.isReady());
            row.addProperty("legacyTicks", 0);
            row.addProperty(
                "sourceWorldUnchanged",
                maid.worldObj == helper.getWorld() && helper.getWorld()
                    .getEntityByID(maid.getEntityId()) == maid);
            row.addProperty(
                "boundOwnerUuidUnchanged",
                owner.getUniqueID()
                    .toString()
                    .equals(maid.func_152113_b()));
            row.add("maid", raw(maid, x, y, z));
            row.add("owner", ownerSnapshot(false));
            frames.add(row);
            afterFrame(frames.size() - 1);
            livingTicks = 0;
            drain();
            if (frames.size() == frameLimit()) {
                complete = true;
                close();
            }
        }

        @Override
        void afterFrame(int index) {
            var row = frames.get(index)
                .getAsJsonObject();
            if (bow == null && mechanism.equals("owner_dimension_arrow")) {
                var actor = profile == MaidBrainProfile.ENHANCED ? maid.getBrainCoordinator()
                    .peekEnhancedCore()
                    .actor()
                    : maid.getBrainCoordinator()
                        .peekFaithfulBrain()
                        .getActor();
                bow = actor.rangedBow();
            }
            row.addProperty("sleeping", maid.hasPose(MaidPose.SLEEPING));
            row.addProperty("weaponWear", weapon == null ? 0 : weapon.getItemDamage());
            if (enemy != null) {
                row.add("enemy", raw(enemy, x, y, z));
                row.addProperty("enemySelected", maid.getAttackTarget() == enemy);
                row.addProperty("enemyWithinHome", withinHome(enemy));
            }
            if (friend != null) row.addProperty("friendHealth", friend.getHealth());
            if (mechanism.equals("closed_door_recovery")) {
                row.addProperty(
                    "ironOpen",
                    ((BlockDoor) Blocks.iron_door).func_150015_f(helper.getWorld(), x + 11, y + 1, z + 4));
                row.addProperty(
                    "woodOpen",
                    ((BlockDoor) Blocks.wooden_door).func_150015_f(helper.getWorld(), x + 9, y + 1, z + 4));
                if (index >= 100 && secondClosedAt < 0
                    && maid.getDistanceSq(ownerX, ownerY, ownerZ) <= 4
                    && firstArrival < 0) {
                    firstArrival = index;
                    reversePending = true;
                }
                if (secondClosedAt >= 0 && maid.getDistanceSq(ownerX, ownerY, ownerZ) <= 4 && secondArrival < 0)
                    secondArrival = index;
                var navigation = (com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator) maid.getNavigator();
                row.addProperty("actualAStarSearches", navigation.searchCount());
                row.addProperty("budgetDeferrals", navigation.budgetDeferrals());
            }
            if (mechanism.equals("melee_boundary_variant") && outsideAt < 0
                && enemy.isEntityAlive()
                && enemy.getHealth() < enemy.getMaxHealth()
                && weapon.getItemDamage() > 0) outsidePending = true;
            if (mechanism.equals("sleep_defense_crisis")) competition(index);
            if (mechanism.equals("owner_dimension_arrow")) {
                row.addProperty(
                    "usingItem",
                    maid.getItemUseState()
                        .peek() != null);
                row.addProperty("inventoryArrows", arrowsLeft());
                row.addProperty("confirmedJoins", shots.size());
                row.addProperty("rejectedShots", bow.getRejectedShots());
                var actual = new JsonArray();
                for (var arrow : arrows) {
                    var state = raw(arrow, x, y, z);
                    state.addProperty("sourceStopped", ((MaidArrowSourceState) arrow).tlmnh$sourceStopped());
                    actual.add(state);
                }
                row.add("arrows", actual);
                helper.assertTrue(
                    arrowsLeft() + shots.size() == 24 && weapon.getItemDamage() == shots.size(),
                    "Finite actual arrow/payment conservation changed");
                if (migratedAt < 0 && shots.size() > 0) migrationPending = true;
                // A live shooter's world/registry/owner UUID remain valid when only the bound owner moves
            }
            helper.assertTrue(friendHits == 0 && outsideHits == 0, "Accepted maid attack crossed friend/home legality");
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
                output.addProperty("resourceAssertionsPassed", true);
                output.addProperty(
                    "behaviorSuccess",
                    mechanism.equals("closed_door_recovery") ? firstArrival >= 0 && secondArrival >= 0
                        : mechanism.equals("sleep_defense_crisis") ? restoredAt >= 0
                            : mechanism.equals("owner_dimension_arrow") ? migratedAt >= 0
                                : outsideAt >= 0 && reentryAt >= 0 && enemy != null && !enemy.isEntityAlive());
            }
        }

        boolean withinHome(EntityLivingBase actor) {
            return maid.getHomeArea()
                .withinRestriction(
                    net.minecraft.util.MathHelper.floor_double(actor.posX),
                    net.minecraft.util.MathHelper.floor_double(actor.posY),
                    net.minecraft.util.MathHelper.floor_double(actor.posZ));
        }

        void competition(int index) {
            if (sleepAt < 0 && maid.hasPose(MaidPose.SLEEPING)) {
                sleepAt = index;
                event("natural_sleep_then_actual_owner_hurt");
                boolean accepted = owner.attackEntityFrom(DamageSource.causeMobDamage(enemy), 1);
                helper.assertTrue(accepted && owner.getAITarget() == enemy, "Actual owner hurt predicate rejected");
            } else if (sleepAt >= 0 && defenseAt < 0 && index == sleepAt + 1) {
                helper.assertTrue(
                    maid.attackEntityFrom(DamageSource.causeMobDamage(enemy), 1),
                    "Actual sleeping maid hurt rejected");
                maid.setSchedule(MaidSchedule.ALL);
                defenseAt = index;
                event("actual_maid_hurt_and_normal_all_schedule");
            } else if (defenseAt >= 0 && crisisAt < 0 && index == defenseAt + 60) {
                maid.setTask(MaidTask.IDLE);
                maid.setSitting(true);
                creeper = new EntityCreeper(helper.getWorld());
                creeper.func_110163_bv();
                creeper.setPosition(maid.posX + 3, maid.posY, maid.posZ);
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(creeper),
                    "Native crisis Creeper rejected");
                boolean handled = net.minecraftforge.event.ForgeEventFactory.doSpecialSpawn(
                    creeper,
                    helper.getWorld(),
                    (float) creeper.posX,
                    (float) creeper.posY,
                    (float) creeper.posZ);
                if (!handled) creeper.onSpawnWithEgg(null);
                crisisAt = index;
                event("normal_wait_idle_and_native_creeper").add("creeperInitial", raw(creeper, x, y, z));
            } else if (crisisAt >= 0 && index == crisisAt + 40) {
                creeper.setDead();
                maid.setSitting(false);
                maid.setTask(MaidTask.ATTACK);
                maid.setSchedule(MaidSchedule.DAY);
                enemy.setDead();
                event("withdraw_owned_threat_restore_night");
            }
            if (crisisAt >= 0 && index > crisisAt + 40 && maid.hasPose(MaidPose.SLEEPING) && restoredAt < 0)
                restoredAt = index;
        }

        int arrowsLeft() {
            var stack = maid.getMaidInventory()
                .getStackInSlot(0);
            return stack == null ? 0 : stack.stackSize;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void arrowJoin(EntityJoinWorldEvent e) {
            if (!(e.entity instanceof EntityArrow arrow) || arrow.shootingEntity != maid) return;
            arrows.add(arrow);
            var row = raw(arrow, x, y, z);
            row.addProperty("frame", frames.size());
            row.addProperty("sourceOriginal", arrow instanceof com.tianluoqaq.tlmnh.common.combat.MaidGravityFreeArrow);
            shots.add(row);
        }

        @Override
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent e) {
            super.hurt(e);
            if (e.source.getEntity() != maid || e.entityLiving != enemy && e.entityLiving != friend) return;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("victim", e.entityLiving == friend ? "friend" : "enemy");
            row.addProperty("amount", e.ammount);
            row.addProperty("canceled", e.isCanceled());
            boolean outside = !withinHome(e.entityLiving);
            row.addProperty("outsideHome", outside);
            combatDamage.add(row);
            if (!e.isCanceled() && e.ammount > 0) {
                if (e.entityLiving == friend) friendHits++;
                if (outside) outsideHits++;
            }
        }

        void migrateOwner() {
            destination = MinecraftServer.getServer()
                .worldServerForDimension(1);
            helper.assertTrue(destination != null, "Native End unavailable");
            var center = destination.getEntrancePortalLocation();
            helper.assertTrue(center != null, "Native End entrance unavailable");
            try {
                chunks.forceChunksStrict(
                    destination,
                    center.posX - 16,
                    center.posY - 2,
                    center.posZ - 16,
                    center.posX + 16,
                    center.posY + 3,
                    center.posZ + 16);
            } catch (Exception error) {
                throw new IllegalStateException("Cannot prepare native End platform", error);
            }
            var platform = new JsonArray();
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 1; b++) for (int c = -2; c <= 2; c++) {
                var snapshot = BlockSnapshot
                    .getBlockSnapshot(destination, center.posX + a, center.posY + b, center.posZ + c);
                endPlatform.add(snapshot);
                var row = new JsonObject();
                row.addProperty("x", center.posX + a);
                row.addProperty("y", center.posY + b);
                row.addProperty("z", center.posZ + c);
                row.addProperty(
                    "registry",
                    net.minecraft.block.Block.blockRegistry.getNameForObject(snapshot.replacedBlock));
                row.addProperty("metadata", snapshot.meta);
                var nativeSnapshot = new NBTTagCompound();
                snapshot.writeToNBT(nativeSnapshot);
                row.add("fullNativeBlockSnapshot", SleepAttributePortSnapshot.binaryNbt(nativeSnapshot));
                platform.add(row);
            }
            output.add("endPlatformOriginal", platform);
            migratedAt = frames.size();
            migrationShots = shots.size();
            migrationWear = weapon.getItemDamage();
            migrationTargetHealth = enemy.getHealth();
            var event = event("native_owner_change_dimension_End");
            event.add("before", raw(owner, x, y, z));
            MinecraftServer.getServer()
                .getConfigurationManager()
                .transferPlayerToDimension(owner, 1);
            helper.assertTrue(
                owner.worldObj == destination && owner.dimension == 1,
                "Actual native owner dimension transfer failed");
            event.add("after", raw(owner, x, y, z));
            event.addProperty("shotsAtBoundary", migrationShots);
            event.addProperty("wearAtBoundary", migrationWear);
            event.addProperty("targetHealthAtBoundary", migrationTargetHealth);
            output.addProperty("sourceExitExercised", false);
            output.addProperty(
                "ownerOnlyMigrationScope",
                "actual owner End entry; unchanged maid world/UUID/registry and bound owner UUID remain valid; no source-exit oracle");
        }

        @Override
        void close() {
            if (closed) return;
            try {
                for (var arrow : arrows) arrow.setDead();
                if (enemy != null) enemy.setDead();
                if (friend != null) friend.setDead();
                if (ownedMount != null) ownedMount.setDead();
                if (destination != null && maid != null)
                    for (Object value : new ArrayList<>(destination.loadedEntityList)) {
                        if (value instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid moved
                            && moved != maid
                            && moved.getUniqueID()
                                .equals(maid.getUniqueID()))
                            moved.setDead();
                    }
            } finally {
                try {
                    if (destination != null) {
                        for (var snapshot : endPlatform) helper
                            .assertTrue(snapshot.restore(true, false), "Full original End platform restore failed");
                        for (var snapshot : endPlatform) helper.assertTrue(
                            snapshot.equals(
                                BlockSnapshot.getBlockSnapshot(destination, snapshot.x, snapshot.y, snapshot.z)),
                            "Native End platform full state/NBT read-back differs");
                        output.addProperty("endPlatformRestored", true);
                    }
                } finally {
                    super.close();
                }
            }
        }
    }
}
