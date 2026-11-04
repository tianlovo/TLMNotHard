package com.tianluoqaq.tlmnh.reference.sleep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitTaskData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 原完整Brain四机制；复用实际PlayerList登记、同端typed初值及自然START/END采集。 */
@GameTestHolder("tlmnh_combat_mechanisms")
@PrefixGameTestTemplate(false)
public final class CombatMechanismGameTests {
    private CombatMechanismGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 420, batch = "combat_melee")
    public static void meleeDefense(GameTestHelper h) { collect(h, "melee"); }
    @GameTest(template = "cell", timeoutTicks = 360, batch = "combat_motion")
    public static void sourceMotion(GameTestHelper h) { collect(h, "motion"); }
    @GameTest(template = "cell", timeoutTicks = 280, batch = "combat_safety")
    public static void sourceSafety(GameTestHelper h) { collect(h, "safety"); }
    @GameTest(template = "cell", timeoutTicks = 420, batch = "combat_withdraw")
    public static void sourceWithdrawal(GameTestHelper h) { collect(h, "withdraw"); }

    static void collect(GameTestHelper h, String scene) {
        FollowMechanismGameTests.collect(h, new Session(h, scene), "tlmnh.combatMechanismsProbe");
    }

    static class Session extends FollowMechanismGameTests.Session {
        static final java.lang.reflect.Field OWNER_SPAWN_PROTECTION = ownerSpawnField();
        final JsonArray shots = new JsonArray(), damage = new JsonArray();
        final List<AbstractArrow> arrows = new ArrayList<>();
        final Map<AbstractArrow, JsonObject> records = new HashMap<>();
        final Map<AbstractArrow, Float> pendingHits = new HashMap<>();
        final String mechanism;
        Pig target, competitor;
        net.minecraft.world.entity.monster.Zombie zombie;
        Entity nativeMount;
        Wolf friend;
        ItemStack weapon = new ItemStack(Items.BOW), heldAmmo = ItemStack.EMPTY, heldBow = ItemStack.EMPTY;
        ItemStack externalSnapshot = ItemStack.EMPTY;
        JsonObject targetCall;
        AbstractArrow crossing;
        int phase, phaseAt, boundaryShots, beforeBreak = -1, replacementShots = -1, crossings;
        int defenseAt = -1, competitionAt = -1, exitWear = -1;
        int targetDamageEvents;
        float exitHealth, exitOther, friendHealth, ownerHealth;
        boolean wall, extrasClosed;
        String queued = "";

        Session(GameTestHelper h, String scene) {
            this(h, scene, "/tlmnh-combat-mechanisms/contract.json", "COMBAT_MECHANISMS_V2",
                "combat-mechanism-original-v1", "combat-mechanism-original-");
        }

        Session(GameTestHelper h, String scene, String resource, String expectedContract, String schema, String prefix) {
            super(h, scene, resource, expectedContract, schema, prefix);
            mechanism = scene;
            output.add("shots", shots);
            output.add("damageEvents", damage);
            output.addProperty("arm", "O");
            output.addProperty("requestedFlightProfile", "SOURCE_ORIGINAL");
            output.addProperty("refusalTelemetry", "UNKNOWN; no original release-gate observer, only actual current opportunity/draw/material/join facts");
            output.addProperty("targetInput", "controlled native persistent pigs, health from contract target.health, speed0 knockback1; classified HOSTILE for ranged only, not hostile mob AI");
            output.addProperty("inputTiming", "START current input, natural world update, END observation; melee reactive inputs occur after recorded END");
        }

        @Override String identityProperty() { return "tlmnh.combatMechanisms.buildIdentity"; }
        @Override int frameLimit() { return scene.get("frames").getAsInt(); }

        @Override void setup() {
            super.setup();
            for (int x = 0; x < 21; x++) for (int y = 0; y < 11; y++) for (int z = 0; z < 9; z++) {
                boolean solid = x == 0 || x == 20 || y == 0 || y == 10 || z == 0 || z == 8;
                helper.setBlock(x, y, z, solid ? Blocks.GLASS : Blocks.AIR);
            }
            owner.setGameMode(GameType.SURVIVAL);
            owner.getAbilities().invulnerable = false;
            owner.getAbilities().flying = owner.getAbilities().mayfly = true;
            owner.setHealth(owner.getMaxHealth());
            MinecraftForge.EVENT_BUS.register(this);
        }

        @Override void beforeSpawn() {
            super.beforeSpawn();
            maid.setTask(TaskManager.findTask(new ResourceLocation("touhou_little_maid",
                mechanism.equals("melee") ? "attack" : "ranged_attack")).orElseThrow());
            // 与原MaidConfigMessage一致：先登记三日程位置，再开启home；restrictTo本身不保存SchedulePos
            maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
            maid.setHomeModeEnable(true);
            maid.restrictTo(origin.offset(8, 1, 4), 12);
            helper.assertTrue(maid.getSchedulePos().getWorkPos().equals(maid.blockPosition())
                && maid.getSchedulePos().getIdlePos().equals(maid.blockPosition())
                && maid.getSchedulePos().getSleepPos().equals(maid.blockPosition())
                && maid.getRestrictCenter().equals(maid.blockPosition()) && maid.getRestrictRadius() == 12,
                "Normal UI schedule home initialization or actual radius differs");
            if (mechanism.equals("melee")) weapon = new ItemStack(Items.IRON_SWORD);
            if (mechanism.equals("safety")) weapon.enchant(Enchantments.FLAMING_ARROWS, 1);
            maid.setItemInHand(InteractionHand.MAIN_HAND, weapon);
            if (!mechanism.equals("melee")) maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.ARROW, initialAmmoCount()));
            target = pig(mechanism.equals("melee") ? 12.5 : 17.5, 4.5);
            friend = new Wolf(EntityType.WOLF, level);
            friend.setTame(true);
            friend.setOwnerUUID(owner.getUUID());
            friend.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
            friend.setPos(origin.getX() + 1.5, origin.getY() + 1, origin.getZ() + 1.5);
            helper.assertTrue(level.addFreshEntity(friend) && level.getEntity(friend.getUUID()) == friend,
                "Native allied pet was replaced or unregistered");
            friendHealth = friend.getHealth();
            ownerHealth = owner.getHealth();
            if (mechanism.equals("melee")) competitor = pig(10, 5.5);
            else {
                var groups = new HashMap<ResourceLocation, MonsterType>();
                groups.put(new ResourceLocation("minecraft", "pig"), MonsterType.HOSTILE);
                maid.setData(InitTaskData.ATTACK_LIST, new AttackListData(groups));
            }
            output.add("targetInitial", raw(target));
            output.add("friendInitial", raw(friend));
            if (competitor != null) output.add("competitorInitial", raw(competitor));
            output.add("targetPhysicalConditions", physical(target));
            output.add("weaponInitial", item(weapon));
        }

        Pig pig(double x, double z) {
            var p = new Pig(EntityType.PIG, level);
            p.setPersistenceRequired();
            float health = contract.getAsJsonObject("target").get("health").getAsFloat();
            p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
            p.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
            p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            p.setHealth(health);
            p.setPos(origin.getX() + x, origin.getY() + 1, origin.getZ() + z);
            helper.assertTrue(level.addFreshEntity(p) && level.getEntity(p.getUUID()) == p && p.isPersistenceRequired(),
                "Controlled native pig was replaced or unregistered");
            return p;
        }

        @Override void spawn() {
            super.spawn();
            output.add("maidPhysicalConditions", physical(maid));
        }

        @Override void beforeWorld() {
            super.beforeWorld();
            if (target == null || maid == null) return;
            int index = frames.size();
            if (!mechanism.equals("melee") && target.isAlive()) {
                double x = 17.5, y = 1, z = 4.5;
                if (mechanism.equals("motion")) {
                    if (index >= 30 && index < 90) z += 1.7 * Math.sin((index - 30) * .12);
                    else if (index >= 90 && index < 150) x += 2 * Math.sin((index - 90) * Math.PI / 30);
                    else if (index >= 150 && index < 220) z += 1.7 * Math.sin((index - 150) * (index - 150) * .003);
                    else if (index >= 220) y += Math.max(0, 1.3 * Math.sin((index - 220) * Math.PI / 12));
                }
                target.setPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                target.setDeltaMovement(Vec3.ZERO);
            }
            if (mechanism.equals("safety")) {
                if (index == 60 || index == 105) {
                    wall = index == 60;
                    helper.assertTrue(maid.getX() < origin.getX() + 13, "Wall no longer separates original shooter/target");
                    for (int y = 1; y < 10; y++) for (int z = 1; z < 8; z++)
                        helper.setBlock(13, y, z, wall ? Blocks.STONE : Blocks.AIR);
                    event(wall ? "wall_close" : "wall_open", "START_before_world");
                }
                if (index >= 120 && crossings < 2) cross();
            }
            applyQueued(index);
            targetCall = state(target);
        }

        void applyQueued(int index) {
            if (queued.isEmpty()) return;
            String action = queued;
            queued = "";
            phaseAt = index;
            boundaryShots = shots.size();
            if (action.equals("remove_ammo")) {
                heldAmmo = maid.getMaidInv().getStackInSlot(0);
                externalSnapshot = heldAmmo.copy();
                maid.getMaidInv().setStackInSlot(0, ItemStack.EMPTY);
                phase = 1;
            } else if (action.equals("restore_ammo")) {
                maid.getMaidInv().setStackInSlot(0, heldAmmo);
                heldAmmo = ItemStack.EMPTY;
                phase = 2;
            } else if (action.equals("remove_weapon")) {
                heldBow = maid.getMainHandItem();
                externalSnapshot = heldBow.copy();
                maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                phase = 3;
            } else if (action.equals("restore_weapon")) {
                maid.setItemInHand(InteractionHand.MAIN_HAND, heldBow);
                heldBow = ItemStack.EMPTY;
                phase = 4;
            } else if (action.equals("arm_break")) {
                beforeBreak = shots.size();
                weapon.setDamageValue(weapon.getMaxDamage());
                phase = 6;
            } else if (action.equals("replace_broken_bow")) {
                helper.assertTrue(weapon.isEmpty() && maid.getMainHandItem().isEmpty(), "Original native bow did not break once");
                replacementShots = shots.size();
                weapon = new ItemStack(Items.BOW);
                maid.setItemInHand(InteractionHand.MAIN_HAND, weapon);
                phase = 7;
            } else if (action.equals("remove_target")) {
                target.discard();
                phase = 5;
            } else throw new IllegalArgumentException(action);
            event(action, "START_before_world");
        }

        void cross() {
            if (crossing == null) for (var arrow : arrows) if (arrow.isAlive() && arrow.tickCount >= 1
                && arrow.getDeltaMovement().lengthSqr() > .1 && arrow.getX() < target.getX() - 1
                && !records.get(arrow).has("friendlyCrossing")) {
                crossing = arrow;
                helper.assertTrue(arrow.isOnFire(), "Original safety input requires actual flaming arrow");
                records.get(arrow).addProperty("friendlyCrossing", crossings == 0 ? "same_owner_pet" : "native_owner");
                event(crossings == 0 ? "cross_pet" : "cross_owner", "START_before_world");
                break;
            }
            if (crossing == null) return;
            LivingEntity ally = crossings == 0 ? friend : owner;
            if (!crossing.isAlive() || crossing.tickCount > records.get(crossing).get("crossingStartAge").getAsInt() + 3) {
                var row = records.get(crossing);
                row.addProperty("allyHealthAfterCrossing", ally.getHealth());
                row.addProperty("allyBurningAfterCrossing", ally.isOnFire());
                friend.setPos(origin.getX() + 1.5, origin.getY() + 1, origin.getZ() + 1.5);
                crossings++;
                crossing = null;
                return;
            }
            var at = crossing.position().add(crossing.getDeltaMovement().scale(.5));
            ally.setPos(at.x, at.y - ally.getBbHeight() * .5, at.z);
            ally.setDeltaMovement(Vec3.ZERO);
        }

        @Override void afterFrame(int index) {
            var row = frames.get(index).getAsJsonObject();
            row.addProperty("phase", phase);
            row.addProperty("wall", wall);
            row.add("restrictionCenter", relative(Vec3.atLowerCornerOf(maid.getRestrictCenter())));
            row.addProperty("restrictionRadius", maid.getRestrictRadius());
            row.add("target", state(target));
            row.add("targetInputBeforeWorldUpdate", targetCall == null ? state(target) : targetCall);
            row.addProperty("targetSelected", maid.getTarget() == target);
            row.addProperty("targetVisible", target.isAlive() && maid.hasLineOfSight(target));
            row.addProperty("observedOpportunity", target.isAlive() && maid.getMainHandItem().is(Items.BOW)
                && arrowCount() > 0 && maid.hasLineOfSight(target));
            row.addProperty("opportunityScope", "actual weapon/ammo/LOS facts; original release permissions/refusals remain UNKNOWN");
            row.addProperty("usedTicks", maid.getTicksUsingItem());
            row.addProperty("confirmedJoins", shots.size());
            row.addProperty("inventoryArrows", arrowCount());
            row.addProperty("externallyHeldArrows", heldAmmo.getCount() + additionalExternalAmmo());
            row.addProperty("weaponWear", weapon.getDamageValue());
            var arrowFrames = new JsonArray();
            for (var arrow : arrows) {
                var shot = records.get(arrow);
                var actualArrow = state(arrow);
                shot.add("lastObserved", actualArrow);
                arrowFrames.add(actualArrow);
                shot.addProperty("alive", arrow.isAlive());
                if (pendingHits.containsKey(arrow)) {
                    float before = pendingHits.remove(arrow);
                    shot.addProperty("healthBeforeCandidate", before);
                    shot.addProperty("healthAfterFrame", target.getHealth());
                    shot.addProperty("hit", target.getHealth() < before && targetDamageEvents == 1);
                    shot.addProperty("hitEvidence", targetDamageEvents == 1
                        ? "isolated same-arrow LivingHurt candidate plus END health delta"
                        : "ambiguous multiple target damage candidates; no confirmed hit assigned");
                }
            }
            row.add("arrows", arrowFrames);
            targetDamageEvents = 0;
            if (mechanism.equals("melee")) melee(index, row);
            else {
                helper.assertTrue(arrowCount() + heldAmmo.getCount() + additionalExternalAmmo() + shots.size() == ammoBudget(), "Original finite ammo changed without an actual arrow join");
                boolean wear = beforeBreak < 0 ? weapon.getDamageValue() == shots.size()
                    : replacementShots >= 0 ? weapon.getDamageValue() == shots.size() - replacementShots
                    : shots.size() == beforeBreak ? weapon.getDamageValue() == weapon.getMaxDamage()
                    : shots.size() == beforeBreak + 1 && weapon.isEmpty();
                helper.assertTrue(wear, "Original finite bow wear differs from explicit break input and actual joins");
                if (mechanism.equals("withdraw")) withdrawal(index);
            }
            if (index + 1 == frameLimit()) {
                output.addProperty("confirmedShots", shots.size());
                output.addProperty("collectedFramesComplete", true);
                output.addProperty("defenseAndCompetitionExercised", defenseAt >= 0 && competitionAt >= 0);
                output.addProperty("ownerDefenseInputFrame", defenseAt);
                output.addProperty("competitionInputFrame", competitionAt);
                output.addProperty("naturalBreakExercised", replacementShots >= 0);
                output.addProperty("resourceAssertionsPassed", true);
                output.addProperty("withdrawalSequenceExercised", phase == 5 && index - phaseAt >= 20);
                output.addProperty("friendlyCrossingInputCount", crossings);
                output.addProperty("alliedPetHealthChange", friendHealth - friend.getHealth());
                output.addProperty("ownerHealthChange", ownerHealth - owner.getHealth());
                output.add("targetFinal", raw(target));
                if (zombie != null) output.add("nativeEnemyFinal", raw(zombie));
            }
        }

        void withdrawal(int index) {
            boolean drawing = maid.isUsingItem() && maid.getUseItem().is(Items.BOW) && maid.getTicksUsingItem() >= 3;
            if (phase == 0 && drawing) queued = "remove_ammo";
            else if (phase == 1 || phase == 3) {
                helper.assertTrue(shots.size() == boundaryShots && ItemStack.matches(externalSnapshot, phase == 1 ? heldAmmo : heldBow),
                    "Original withdrawal released a phantom shot or changed external full stack");
                if (index - phaseAt >= 20) queued = phase == 1 ? "restore_ammo" : "restore_weapon";
            } else if (phase == 2 && shots.size() > boundaryShots && drawing) queued = "remove_weapon";
            else if (phase == 4 && shots.size() > boundaryShots) queued = "arm_break";
            else if (phase == 6 && shots.size() == beforeBreak + 1) queued = "replace_broken_bow";
            else if (phase == 7 && shots.size() > replacementShots) queued = "remove_target";
            else if (phase == 5) helper.assertTrue(shots.size() == boundaryShots, "Original removed target retained release permission");
        }

        void melee(int index, JsonObject row) {
            row.add("competitor", state(competitor));
            row.addProperty("competitorSelected", maid.getTarget() == competitor);
            if (zombie != null) row.add("nativeEnemy", state(zombie));
            var source = level.damageSources().mobAttack(target);
            row.addProperty("ownerNativeInvulnerableToInput", owner.isInvulnerableTo(source));
            int protection;
            try { protection = OWNER_SPAWN_PROTECTION.getInt(owner); }
            catch (IllegalAccessException error) { throw new IllegalStateException("Cannot read actual native owner protection", error); }
            row.addProperty("ownerSpawnProtection", protection);
            if (phase == 0 && index >= 6 && protection <= 0 && !owner.isInvulnerableTo(source)) {
                float before = owner.getHealth();
                boolean accepted = owner.hurt(source, 1);
                row.addProperty("actualOwnerHurtAccepted", accepted);
                row.addProperty("actualOwnerHurtHealthDelta", before - owner.getHealth());
                row.addProperty("actualOwnerDefensePredicate", owner.getLastHurtByMob() == target);
                helper.assertTrue(accepted && owner.getHealth() < before && owner.getLastHurtByMob() == target,
                    "Original native owner hurt input failed after normal protection");
                defenseAt = index;
                phase = 1;
                event("actual_owner_hurt", "END_after_recorded_frame");
            } else if (phase == 1 && weapon.getDamageValue() > 0) {
                target.setPos(maid.getX() + 5, maid.getY(), maid.getZ());
                competitor.setPos(maid.getX() + 1, maid.getY(), maid.getZ());
                owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                float before = competitor.getHealth();
                owner.attack(competitor);
                helper.assertTrue(competitor.getHealth() < before && owner.getLastHurtMob() == competitor,
                    "Original native owner attack did not establish a second neutral candidate");
                competitionAt = index;
                phase = 2;
                event("owner_attacks_closer_competitor", "END_after_recorded_frame");
            }
            if (index == 200) {
                maid.setTask(TaskManager.getIdleTask());
                exitWear = weapon.getDamageValue();
                exitHealth = target.getHealth();
                exitOther = competitor.getHealth();
                phase = 3;
                event("normal_idle_command", "END_after_recorded_frame");
            }
            if (phase == 3 && index <= 210) helper.assertTrue(weapon.getDamageValue() == exitWear && target.getHealth() == exitHealth
                && competitor.getHealth() == exitOther, "Original Idle command retained old melee damage/payment");
            if (index == 210) {
                target.discard();
                competitor.discard();
                level.setDayTime(18000);
                level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                zombie = new net.minecraft.world.entity.monster.Zombie(EntityType.ZOMBIE, level);
                zombie.setPos(origin.getX() + 14.5, origin.getY() + 1, origin.getZ() + 4.5);
                zombie.setPersistenceRequired();
                output.add("nativeEnemyBeforeInitialization", raw(zombie));
                var existing = level.getAllEntities().spliterator();
                var before = new java.util.HashSet<Entity>();
                existing.forEachRemaining(before::add);
                net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(zombie, level, level.getCurrentDifficultyAt(zombie.blockPosition()),
                    net.minecraft.world.entity.MobSpawnType.NATURAL, null, null);
                output.add("nativeEnemyAfterInitialization", raw(zombie));
                level.tryAddFreshEntityWithPassengers(zombie);
                helper.assertTrue(level.getEntity(zombie.getUUID()) == zombie,
                    "Original persistent native Zombie was replaced or rejected");
                if (zombie.getVehicle() != null && !before.contains(zombie.getVehicle())) {
                    nativeMount = zombie.getVehicle();
                    output.add("nativeEnemyInitializationMount", raw(nativeMount));
                }
                output.add("nativeEnemyRegistered", raw(zombie));
                output.add("nativeEnemyPhysicalConditions", physical(zombie));
                output.addProperty("nativeEnemyInput", "controlled native Zombie Forge onFinalizeSpawn NATURAL plus tryAddFreshEntityWithPassengers, actual NORMAL local difficulty, persistence before spawn; no target/attribute/equipment/AI overrides; night18000; not natural population/distance admission or HARD reinforcements");
                output.addProperty("nativeEnemyDifficulty", level.getDifficulty().name());
                output.addProperty("nativeEnemyLocalDifficulty", level.getCurrentDifficultyAt(zombie.blockPosition()).getEffectiveDifficulty());
                maid.setTask(TaskManager.findTask(new ResourceLocation("touhou_little_maid", "attack")).orElseThrow());
                phase = 4;
                event("native_enemy_normal_attack_entry", "END_after_recorded_frame");
            }
            if (index == 340) {
                maid.setTask(TaskManager.getIdleTask());
                phase = 5;
                exitWear = weapon.getDamageValue();
                output.add("nativeEnemyAtFinalExit", raw(zombie));
                event("final_idle_command", "END_after_recorded_frame");
            }
            if (phase == 5) helper.assertTrue(weapon.getDamageValue() == exitWear,
                "Original final Idle command retained native enemy melee payment");
        }

        static java.lang.reflect.Field ownerSpawnField() {
            try {
                var field = net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
                if (field.getType() != int.class) throw new IllegalStateException("Unexpected original owner protection field type");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Reviewed original owner spawn-protection field unavailable", error);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void joined(EntityJoinLevelEvent e) {
            if (maid == null || !(e.getEntity() instanceof AbstractArrow a) || a.getOwner() != maid) return;
            var row = raw(a);
            row.addProperty("uuid", a.getUUID().toString());
            row.addProperty("spawnFrame", frames.size());
            row.addProperty("sourceOriginal", true);
            row.addProperty("gravity", a.isNoGravity() ? 0 : .05);
            row.addProperty("drag", (double) .99F);
            row.addProperty("noGravity", a.isNoGravity());
            row.addProperty("critical", a.isCritArrow());
            row.addProperty("baseDamage", a.getBaseDamage());
            row.addProperty("ownerIsExactMaid", a.getOwner() == maid);
            row.addProperty("canceledAtLowest", e.isCanceled());
            row.addProperty("crossingStartAge", Integer.MAX_VALUE);
            arrows.add(a);
            records.put(a, row);
            shots.add(row);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent e) {
            if (maid == null) return;
            if (e.getEntity() != target && e.getEntity() != competitor && e.getEntity() != friend
                && e.getEntity() != owner && e.getEntity() != zombie && e.getEntity() != maid) return;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("victim", e.getEntity().getUUID().toString());
            row.addProperty("amount", e.getAmount());
            row.addProperty("canceled", e.isCanceled());
            row.addProperty("healthBefore", e.getEntity().getHealth());
            var direct = e.getSource().getDirectEntity();
            row.addProperty("directSource", direct == null ? "" : direct.getUUID().toString());
            damage.add(row);
            if (e.getEntity() == target) targetDamageEvents++;
            if (e.getEntity() == target && direct instanceof AbstractArrow a && records.containsKey(a) && !e.isCanceled())
                pendingHits.put(a, target.getHealth());
        }

        int initialAmmoCount() { return 24; }

        int additionalExternalAmmo() { return 0; }

        int ammoBudget() { return 24; }

        int arrowCount() {
            int count = 0;
            for (int slot = 0; slot < maid.getMaidInv().getSlots(); slot++) {
                var stack = maid.getMaidInv().getStackInSlot(slot);
                if (stack.is(Items.ARROW)) count += stack.getCount();
            }
            return count;
        }

        JsonObject event(String action, String timing) {
            var row = new JsonObject();
            row.addProperty("frame", frames.size() - (timing.startsWith("END") ? 1 : 0));
            row.addProperty("action", action);
            row.addProperty("phase", timing);
            row.addProperty("shotsBefore", shots.size());
            row.add("target", raw(target));
            if (crossing != null) records.get(crossing).addProperty("crossingStartAge", crossing.tickCount);
            events.add(row);
            return row;
        }

        JsonObject raw(Entity e) {
            var row = state(e);
            var raw = new CompoundTag();
            e.saveWithoutId(raw);
            row.add("fullNbt", SleepAttributeSnapshot.binaryNbt(raw));
            return row;
        }

        JsonObject state(Entity e) {
            var row = new JsonObject();
            row.addProperty("uuid", e.getUUID().toString());
            row.addProperty("class", e.getClass().getName());
            row.addProperty("age", e.tickCount);
            row.addProperty("dimension", level.dimension().location().toString());
            row.addProperty("providerClass", level.dimensionType().getClass().getName());
            row.addProperty("registered", level.getEntity(e.getUUID()) == e);
            row.addProperty("alive", e.isAlive());
            row.add("position", relative(e.position()));
            row.add("motion", vector(e.getDeltaMovement()));
            if (e instanceof LivingEntity living) row.addProperty("health", living.getHealth());
            if (e instanceof net.minecraft.world.entity.Mob mob) {
                row.addProperty("aiTarget", mob.getTarget() == null ? "" : mob.getTarget().getUUID().toString());
                row.addProperty("persistenceRequired", mob.isPersistenceRequired());
                row.addProperty("noAi", mob.isNoAi());
            }
            return row;
        }

        JsonObject item(ItemStack stack) {
            var raw = new CompoundTag();
            stack.save(raw);
            return SleepAttributeSnapshot.binaryNbt(raw);
        }

        JsonObject physical(LivingEntity entity) {
            var row = new JsonObject();
            row.addProperty("class", entity.getClass().getName());
            row.addProperty("width", entity.getBbWidth());
            row.addProperty("height", entity.getBbHeight());
            row.addProperty("eyeHeight", entity.getEyeHeight());
            row.addProperty("health", entity.getHealth());
            var values = new JsonObject();
            for (var attribute : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE) {
                var map = entity.getAttributes();
                if (!map.hasAttribute(attribute)) continue;
                var value = new JsonObject();
                // AttributeMap数值读取会查询supplier；不调用getInstance实化lazy默认属性
                value.addProperty("base", map.getBaseValue(attribute));
                value.addProperty("value", map.getValue(attribute));
                values.add(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString(), value);
            }
            row.add("attributes", values);
            row.addProperty("effects", entity.getActiveEffects().toString());
            row.add("fullNbt", raw(entity).get("fullNbt"));
            return row;
        }

        @Override JsonArray layout() {
            var rows = new JsonArray();
            for (int x = 0; x < 21; x++) for (int y = 0; y < 11; y++) for (int z = 0; z < 9; z++) {
                var state = level.getBlockState(origin.offset(x, y, z));
                if (state.isAir()) continue;
                var row = new JsonObject();
                row.addProperty("x", x); row.addProperty("y", y); row.addProperty("z", z);
                row.addProperty("registry", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                row.addProperty("rawBlockState", state.toString());
                rows.add(row);
            }
            return rows;
        }

        @Override void close() {
            if (!extrasClosed) {
                extrasClosed = true;
                MinecraftForge.EVENT_BUS.unregister(this);
                for (var a : arrows) a.discard();
                if (target != null) target.discard();
                if (competitor != null) competitor.discard();
                if (friend != null) friend.discard();
                if (zombie != null) zombie.discard();
                if (nativeMount != null) nativeMount.discard();
                for (int x = 0; x < 21; x++) for (int y = 0; y < 11; y++) for (int z = 0; z < 9; z++)
                    helper.setBlock(x, y, z, Blocks.AIR);
            }
            super.close();
        }
    }
}
