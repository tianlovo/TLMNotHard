package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 完整根四机制采集；复用自然远程输入、原生玩家登记和逐箭观察器 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class CombatMechanismPortTests {

    private CombatMechanismPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "combat_melee_f")
    public static void faithfulMeleeDefense(GameTestHelper h) {
        run(h, "melee", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "combat_melee_n")
    public static void enhancedMeleeDefense(GameTestHelper h) {
        run(h, "melee", "N");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 340, batch = "combat_motion_f")
    public static void faithfulSourceMotion(GameTestHelper h) {
        run(h, "motion", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 340, batch = "combat_motion_fr")
    public static void faithfulNativeMotion(GameTestHelper h) {
        run(h, "motion", "F-R");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 340, batch = "combat_motion_ns")
    public static void enhancedStaticMotion(GameTestHelper h) {
        run(h, "motion", "N-static");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 340, batch = "combat_motion_nl")
    public static void enhancedLastSampleMotion(GameTestHelper h) {
        run(h, "motion", "N-last");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 340, batch = "combat_motion_n")
    public static void enhancedMotion(GameTestHelper h) {
        run(h, "motion", "N");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "combat_safety_f")
    public static void faithfulSourceSafety(GameTestHelper h) {
        run(h, "safety", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "combat_safety_fr")
    public static void faithfulNativeSafety(GameTestHelper h) {
        run(h, "safety", "F-R");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "combat_safety_n")
    public static void enhancedSafety(GameTestHelper h) {
        run(h, "safety", "N");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "combat_withdraw_f")
    public static void faithfulSourceWithdrawal(GameTestHelper h) {
        run(h, "withdraw", "F");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "combat_withdraw_fr")
    public static void faithfulNativeWithdrawal(GameTestHelper h) {
        run(h, "withdraw", "F-R");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "combat_withdraw_n")
    public static void enhancedWithdrawal(GameTestHelper h) {
        run(h, "withdraw", "N");
    }

    static void run(GameTestHelper h, String scene, String arm) {
        var c = scene.equals("melee") ? new MeleeCollector(h, arm) : new Collector(h, scene, arm);
        h.afterTest(c::close);
        try {
            c.setup();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare complete combat mechanism", error);
        }
        h.onEachTick("complete combat mechanism", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
    }

    static class Collector extends NaturalRangedPairTests.Collector {

        JsonObject contract;
        ItemStack brokenBow;
        long beforeBreak = -1, replacementShots = -1;

        Collector(GameTestHelper h, String scene, String arm) {
            super(
                h,
                scene,
                arm,
                arm.startsWith("N") ? MaidBrainProfile.ENHANCED : MaidBrainProfile.FAITHFUL_TLM,
                flight(arm),
                true,
                false);
        }

        static ModernFlightProfile flight(String arm) {
            return switch (arm) {
                case "F" -> ModernFlightProfile.SOURCE_ORIGINAL;
                case "F-R", "N-static" -> ModernFlightProfile.NATIVE_STATIC_COMPENSATION;
                case "N-last" -> ModernFlightProfile.NATIVE_LAST_SAMPLE_COMPENSATION;
                case "N" -> ModernFlightProfile.NATIVE_MOTION_COMPENSATION;
                default -> throw new IllegalArgumentException("Unknown complete combat arm " + arm);
            };
        }

        @Override
        void setup() throws Exception {
            super.setup();
            var world = h.getWorld();
            String daylight = world.getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            String spawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            boolean rain = world.getWorldInfo()
                .isRaining(),
                thunder = world.getWorldInfo()
                    .isThundering();
            h.afterTest(() -> {
                world.getGameRules()
                    .setOrCreateGameRule("doDaylightCycle", daylight);
                world.getGameRules()
                    .setOrCreateGameRule("doMobSpawning", spawning);
                world.getWorldInfo()
                    .setRaining(rain);
                world.getWorldInfo()
                    .setThundering(thunder);
            });
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.getWorldInfo()
                .setRaining(false);
            world.getWorldInfo()
                .setThundering(false);
            var environment = report.getAsJsonObject("environmentInitial");
            environment.addProperty("raining", false);
            environment.addProperty("thundering", false);
            target.func_110163_bv();
            friend.func_110163_bv();
            report.add("targetInitial", entity(target, true));
            report.add("friendInitial", entity(friend, true));
            try (var stream = CombatMechanismPortTests.class.getResourceAsStream(contractResource())) {
                if (stream == null) throw new IllegalStateException("Missing combat contract");
                byte[] bytes = com.google.common.io.ByteStreams.toByteArray(stream);
                contract = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
                h.assertTrue(
                    contract.get("contract")
                        .getAsString()
                        .equals(expectedContract())
                        && contract.getAsJsonObject("target")
                            .get("health")
                            .getAsFloat() == targetHealthInput(),
                    "Wrong combat contract");
                report.add("contract", contract);
                StringBuilder hex = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256")
                    .digest(bytes)) hex.append(String.format("%02x", b & 255));
                report.addProperty("contractSha256", hex.toString());
            }
            report.addProperty("schema", captureSchema());
            report.addProperty("requestedFlightProfile", requestedFlight.name());
            report.addProperty(
                "shooterInput",
                "initial sitting=true; normal complete stationary root, no suspension or actuator ticks");
            report.add(
                "combatObserverArtifact",
                DevelopmentArtifactIdentity
                    .capture(CombatMechanismPortTests.class, Collector.class, MeleeCollector.class));
            report.add(
                "sourceArrowArtifact",
                DevelopmentArtifactIdentity.capture(
                    com.tianluoqaq.tlmnh.common.combat.ballistics.MaidGravityFreeArrow.class,
                    com.tianluoqaq.tlmnh.common.combat.ballistics.SourceBowShot.class));
            report.add(
                "combatRootArtifact",
                DevelopmentArtifactIdentity.capture(
                    com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor.class,
                    com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator.class,
                    com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.class));
            report.addProperty(
                "originalRefusalTelemetry",
                "UNKNOWN; original observer cannot expose unobserved release gates");
            report.add("actualInputs", maidInputs());
            report.add("initialLayout", layout());
        }

        String contractResource() {
            return "/tlmnh-combat-mechanisms/contract.json";
        }

        String expectedContract() {
            return "COMBAT_MECHANISMS_V2";
        }

        String captureSchema() {
            return "combat-mechanism-port-v1";
        }

        com.google.gson.JsonArray layout() {
            var rows = new com.google.gson.JsonArray();
            for (int x = 0; x < 21; x++) for (int y = 0; y < 11; y++) for (int z = 0; z < 9; z++) {
                var block = h.getWorld()
                    .getBlock(f.x + x, f.y + y, f.z + z);
                if (block == net.minecraft.init.Blocks.air) continue;
                var row = new JsonObject();
                row.addProperty("x", x);
                row.addProperty("y", y);
                row.addProperty("z", z);
                row.addProperty("registry", net.minecraft.block.Block.blockRegistry.getNameForObject(block));
                row.addProperty(
                    "metadata",
                    h.getWorld()
                        .getBlockMetadata(f.x + x, f.y + y, f.z + z));
                rows.add(row);
            }
            return rows;
        }

        JsonObject maidInputs() {
            var row = new JsonObject();
            row.addProperty(
                "task",
                f.maid.getTask()
                    .getId());
            row.addProperty(
                "schedule",
                f.maid.getSchedule()
                    .name());
            row.addProperty("hunger", f.maid.getHunger());
            row.addProperty("health", f.maid.getHealth());
            row.addProperty("sitting", f.maid.isSitting());
            row.addProperty(
                "homeEnabled",
                f.maid.getHomeArea()
                    .enabled());
            row.addProperty(
                "restrictionRadius",
                f.maid.getHomeArea()
                    .radius());
            row.add("position", NaturalRangedPairTests.vector(f.maid.posX - f.x, f.maid.posY - f.y, f.maid.posZ - f.z));
            return row;
        }

        @Override
        float targetHealthInput() {
            return 1000;
        }

        @Override
        int frameLimit() {
            return scene.equals("motion") ? 300 : scene.equals("withdraw") ? 360 : 220;
        }

        @Override
        void applyInputs() {
            String special = queued;
            if (special.equals("arm_break") || special.equals("replace_broken_bow")
                || special.equals("unregister_target")) queued = "";
            super.applyInputs();
            if (scene.equals("motion")) {
                double x = 17.5, y = 1, z = 4.5;
                if (frame >= 30 && frame < 90) z += 1.7 * Math.sin((frame - 30) * .12);
                else if (frame >= 90 && frame < 150) x += 2 * Math.sin((frame - 90) * Math.PI / 30);
                else if (frame >= 150 && frame < 220) z += 1.7 * Math.sin((frame - 150) * (frame - 150) * .003);
                else if (frame >= 220) y += Math.max(0, 1.3 * Math.sin((frame - 220) * Math.PI / 12));
                target.setPosition(f.x + x, f.y + y, f.z + z);
                target.motionX = target.motionY = target.motionZ = 0;
            }
            if (special.equals("arm_break")) {
                beforeBreak = bow.getShots();
                weapon.setItemDamage(weapon.getMaxDamage());
                phase = 6;
                input(special);
            } else if (special.equals("replace_broken_bow")) {
                h.assertTrue(weapon.stackSize == 0 && f.maid.getHeldItem() == null, "Native bow did not break once");
                brokenBow = weapon;
                replacementShots = bow.getShots();
                weapon = new ItemStack(Items.bow);
                f.maid.setCurrentItemOrArmor(0, weapon);
                phase = 7;
                input(special);
            } else if (special.equals("unregister_target")) {
                // 原生卸载队列在正常World.updateEntities的remove阶段提交；不手工tick或直接改实体/ID表
                h.getWorld()
                    .unloadEntities(java.util.Collections.singletonList(target));
                phase = 5;
                phaseAt = frame;
                boundaryShots = bow.getShots();
                input(special);
            }
            targetInput = entity(target, false);
        }

        @Override
        void withdrawal() {
            if (phase == 4) {
                if (bow.getShots() > boundaryShots) queued = "arm_break";
            } else if (phase == 6) {
                h.assertTrue(bow.getShots() <= beforeBreak + 1, "Broken bow released more than one final arrow");
                if (bow.getShots() == beforeBreak + 1) queued = "replace_broken_bow";
            } else if (phase == 7) {
                if (bow.getShots() > replacementShots) queued = "unregister_target";
            } else {
                if (phase == 5) h.assertTrue(
                    target.isEntityAlive() && h.getWorld()
                        .getEntityByID(target.getEntityId()) != target,
                    "Native unload queue did not leave a live but unregistered target");
                super.withdrawal();
            }
        }

        @Override
        void validateResources() {
            h.assertTrue(
                arrowCount() + (heldAmmo == null ? 0 : heldAmmo.stackSize) + bow.getShots() == 24,
                "Finite arrows lost conservation across withdrawal/break/exit");
            boolean wear = beforeBreak < 0 ? weapon.getItemDamage() == bow.getShots()
                : replacementShots >= 0
                    ? brokenBow.stackSize == 0 && weapon.getItemDamage() == bow.getShots() - replacementShots
                    : bow.getShots() == beforeBreak ? weapon.getItemDamage() == weapon.getMaxDamage()
                        : bow.getShots() == beforeBreak + 1 && weapon.stackSize == 0;
            h.assertTrue(wear, "Explicit near-break input or subsequent finite native bow wear changed");
        }

        @Override
        void finish() {
            report.addProperty("naturalBreakExercised", replacementShots >= 0);
            report.addProperty("aliveWorldExitExercised", phase == 5 && target.isEntityAlive());
            if (scene.equals("withdraw"))
                h.assertTrue(replacementShots >= 0, "Native break/replacement was not exercised");
            super.finish();
        }

        @Override
        String outputName() {
            return "combat-mechanism-" + scene + "-" + arm + ".json";
        }
    }

    public static final class MeleeCollector extends Collector {

        EntityPig competitor;
        net.minecraft.entity.monster.EntityZombie zombie;
        net.minecraft.entity.Entity nativeMount;
        int defenseAt = -1, competitionAt = -1;
        long exitWear = -1;
        float exitHealth, exitOther;

        MeleeCollector(GameTestHelper h, String arm) {
            super(h, "melee", arm);
        }

        @Override
        void setup() throws Exception {
            super.setup();
            f.maid.setSitting(false);
            f.maid.setTask(MaidTask.ATTACK);
            f.maid.getAttackListData()
                .attackGroups()
                .clear();
            weapon = new ItemStack(Items.iron_sword);
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, null);
            target.setPosition(f.x + 12.5, f.y + 1, f.z + 4.5);
            competitor = new EntityPig(h.getWorld());
            competitor.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(targetHealthInput());
            competitor.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            competitor.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            competitor.func_110163_bv();
            competitor.setHealth(targetHealthInput());
            h.spawnEntity(competitor, 10, 1, 5.5);
            report.addProperty(
                "shooterInput",
                "normal mobile complete ATTACK root; neutral persistent pigs at contract health1000, no hostile classification override");
            report
                .addProperty("targetInput", "controlled neutral pigs are owner-defense candidates, not hostile mob AI");
            var initial = new NBTTagCompound();
            f.maid.writeToNBT(initial);
            report
                .add("maidInitialBindingBefore", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) initial.copy()));
            report.add("maidInitial", entity(f.maid, true));
            report.add("maidMappedAttributes", SleepAttributePortSnapshot.capture(f.maid));
            var after = new NBTTagCompound();
            f.maid.writeToNBT(after);
            report.add("maidInitialBindingAfter", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) after.copy()));
            h.assertTrue(initial.equals(after), "Melee initial native input changed while capturing");
            report.add("targetInitial", entity(target, true));
            report.add("competitorInitial", entity(competitor, true));
            conditions.add("maid", NaturalRangedPairTests.physicalConditions(f.maid));
            conditions.addProperty("sitting", false);
            conditions.add("weapon", NaturalRangedPairTests.item(weapon));
            conditions.add("ammunition", SleepAttributePortSnapshot.binaryNbt(new NBTTagCompound()));
            report.add("actualInputs", maidInputs());
            report.addProperty("initialAmmunitionCount", 0);
        }

        @Override
        int frameLimit() {
            return 360;
        }

        @Override
        void applyInputs() {
            owner.setPosition(f.x + 8.5, f.y + 1, f.z + 7);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            owner.fallDistance = 0;
            targetInput = entity(target, false);
            ownerInput = entity(owner, false);
        }

        @Override
        void observe() {
            h.assertTrue(
                f.maid.ticksExisted == age + 1 && livingTicks == 1
                    && f.maid.isEntityAlive()
                    && f.maid.getBrainCoordinator()
                        .peekPolicyRuntime() == runtime
                    && runtime.isReady()
                    && f.maid.getOwner() == owner
                    && f.maid.getBrainCoordinator()
                        .getCombatController()
                        .getTicks() == 0,
                "Melee collector left the complete natural root or actual owner/tick binding");
            age = f.maid.ticksExisted;
            livingTicks = 0;
            var row = new JsonObject();
            row.addProperty("frame", frame);
            row.addProperty("age", age);
            row.addProperty("livingTicks", 1);
            row.addProperty(
                "actualProfile",
                runtime.getProfile()
                    .getId());
            row.addProperty("fullRootAuthorized", runtime.isReady());
            row.addProperty(
                "legacyTicks",
                f.maid.getBrainCoordinator()
                    .getCombatController()
                    .getTicks()
                    + f.maid.getBrainCoordinator()
                        .getFollowController()
                        .getTicks());
            row.addProperty(
                "actualFlightProfile",
                bow.flightProfile()
                    .name());
            row.addProperty("phase", phase);
            row.add("maid", entity(f.maid, false));
            row.add("target", entity(target, false));
            row.add("competitor", entity(competitor, false));
            row.add("owner", entity(owner, false));
            row.add("ownerInputBeforeWorldUpdate", ownerInput);
            row.addProperty("weaponWear", weapon.getItemDamage());
            row.addProperty("acceptedMelee", actor().meleeNativeAccepted());
            row.addProperty("targetSelected", f.maid.getAttackTarget() == target);
            row.addProperty("competitorSelected", f.maid.getAttackTarget() == competitor);
            if (zombie != null) {
                row.add("nativeEnemy", entity(zombie, false));
                row.addProperty(
                    "nativeEnemyAiTarget",
                    zombie.getAttackTarget() == null ? ""
                        : zombie.getAttackTarget()
                            .getUniqueID()
                            .toString());
                row.addProperty("nativeEnemyAge", zombie.ticksExisted);
                row.addProperty("maidSelectedNativeEnemy", f.maid.getAttackTarget() == zombie);
            }
            int protection = ((Integer) cpw.mods.fml.relauncher.ReflectionHelper
                .getPrivateValue(net.minecraft.entity.player.EntityPlayerMP.class, owner, "field_147101_bU"))
                    .intValue();
            row.addProperty("ownerSpawnProtection", protection);
            frames.add(row);
            h.assertTrue(
                weapon.getItemDamage() == actor().meleeNativeAccepted() && friend.getHealth() == 20,
                "Accepted natural melee wear or allied health changed");
            if (phase == 0 && frame >= 6 && protection <= 0) {
                float before = owner.getHealth();
                boolean accepted = owner.attackEntityFrom(DamageSource.causeMobDamage(target), 1);
                row.addProperty("actualOwnerHurtAccepted", accepted);
                row.addProperty("actualOwnerHurtHealthDelta", before - owner.getHealth());
                row.addProperty("actualOwnerDefensePredicate", owner.getAITarget() == target);
                h.assertTrue(
                    accepted && owner.getHealth() < before && owner.getAITarget() == target,
                    "Native owner hurt input failed after its natural protection timer");
                phase = 1;
                defenseAt = frame;
                input("actual_owner_hurt");
            } else if (phase == 1 && actor().meleeNativeAccepted() > 0) {
                target.setPosition(f.maid.posX + 5, f.maid.posY, f.maid.posZ);
                competitor.setPosition(f.maid.posX + 1, f.maid.posY, f.maid.posZ);
                owner.inventory.mainInventory[owner.inventory.currentItem] = new ItemStack(Items.stick);
                float before = competitor.getHealth();
                owner.attackTargetEntityWithCurrentItem(competitor);
                h.assertTrue(
                    competitor.getHealth() < before && owner.getLastAttacker() == competitor,
                    "Actual native owner attack failed to establish the second neutral candidate");
                row.addProperty("ownerAttackDamage", before - competitor.getHealth());
                phase = 2;
                competitionAt = frame;
                input("owner_attacks_closer_competitor");
            }
            if (frame == 200) {
                f.maid.setTask(MaidTask.IDLE);
                exitWear = weapon.getItemDamage();
                exitHealth = target.getHealth();
                exitOther = competitor.getHealth();
                phase = 3;
                input("normal_idle_command");
            }
            if (phase == 3 && frame <= 210) h.assertTrue(
                weapon.getItemDamage() == exitWear && target.getHealth() == exitHealth
                    && competitor.getHealth() == exitOther,
                "Idle command retained old melee damage or payment");
            if (frame == 210) {
                target.setDead();
                competitor.setDead();
                h.getWorld()
                    .setWorldTime(18000);
                h.getWorld().difficultySetting = net.minecraft.world.EnumDifficulty.NORMAL;
                zombie = new net.minecraft.entity.monster.EntityZombie(h.getWorld());
                zombie.setPosition(f.x + 14.5, f.y + 1, f.z + 4.5);
                zombie.func_110163_bv();
                report.add("nativeEnemyBeforeInitialization", entity(zombie, true));
                h.assertTrue(
                    h.getWorld()
                        .spawnEntityInWorld(zombie)
                        && h.getWorld()
                            .getEntityByID(zombie.getEntityId()) == zombie,
                    "Persistent native Zombie was replaced or rejected; preserve actual spawn evidence");
                var existing = new java.util.HashSet<>(h.getWorld().loadedEntityList);
                boolean handled = net.minecraftforge.event.ForgeEventFactory.doSpecialSpawn(
                    zombie,
                    h.getWorld(),
                    (float) zombie.posX,
                    (float) zombie.posY,
                    (float) zombie.posZ);
                report.addProperty("nativeEnemySpecialSpawnHandled", handled);
                if (!handled) zombie.onSpawnWithEgg(null);
                report.add("nativeEnemyAfterInitialization", entity(zombie, true));
                h.assertTrue(
                    zombie.isEntityAlive() && h.getWorld()
                        .getEntityByID(zombie.getEntityId()) == zombie,
                    "Native enemy initialization callback revoked the original registered actor");
                if (zombie.ridingEntity != null && !existing.contains(zombie.ridingEntity)) {
                    nativeMount = zombie.ridingEntity;
                    report.add("nativeEnemyInitializationMount", entity(nativeMount, true));
                }
                report.add("nativeEnemyRegistered", entity(zombie, true));
                report.addProperty(
                    "nativeEnemyInput",
                    "controlled EntityZombie registration then normal Forge SpecialSpawn/onSpawnWithEgg(null); persistence before spawn, actual NORMAL local difficulty; no target/attribute/equipment/AI overrides; night18000; not natural population/distance admission or HARD reinforcements");
                report.addProperty("nativeEnemyDifficulty", h.getWorld().difficultySetting.name());
                report.addProperty(
                    "nativeEnemyLocalDifficulty",
                    h.getWorld()
                        .func_147462_b(zombie.posX, zombie.posY, zombie.posZ));
                f.maid.setTask(MaidTask.ATTACK);
                phase = 4;
                input("native_enemy_normal_attack_entry");
            }
            if (frame == 340) {
                f.maid.setTask(MaidTask.IDLE);
                phase = 5;
                exitWear = weapon.getItemDamage();
                report.add("nativeEnemyAtFinalExit", entity(zombie, true));
                input("final_idle_command");
            }
            if (phase == 5) h.assertTrue(
                weapon.getItemDamage() == exitWear,
                "Final Idle command retained native enemy melee payment");
            if (++frame == frameLimit()) {
                report.addProperty("ownerDefenseInputFrame", defenseAt);
                report.addProperty("competitionInputFrame", competitionAt);
                report.addProperty("defenseAndCompetitionExercised", defenseAt >= 0 && competitionAt >= 0);
                report.addProperty("collectedFramesComplete", true);
                report.addProperty("acceptedMelee", actor().meleeNativeAccepted());
                report.add("nativeEnemyFinal", entity(zombie, true));
                complete = true;
                save();
            }
        }

        @Override
        void close() {
            try {
                super.close();
            } finally {
                if (competitor != null) competitor.setDead();
                if (zombie != null) zombie.setDead();
                if (nativeMount != null) nativeMount.setDead();
            }
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent(
            priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST,
            receiveCanceled = true)
        @Override
        public void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent e) {
            super.hurt(e);
            if (e.entityLiving != zombie && e.entityLiving != f.maid) return;
            var row = new JsonObject();
            row.addProperty("frame", frame);
            row.addProperty(
                "victim",
                e.entityLiving.getUniqueID()
                    .toString());
            row.addProperty("amount", e.ammount);
            row.addProperty("canceled", e.isCanceled());
            row.addProperty("healthBefore", e.entityLiving.getHealth());
            row.addProperty(
                "source",
                e.source.getEntity() == null ? ""
                    : e.source.getEntity()
                        .getUniqueID()
                        .toString());
            damage.add(row);
        }
    }
}
