package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.combat.ArrowInterceptSolver;
import com.tianluoqaq.tlmnh.common.combat.ArrowPhysics;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.ObservedTargetMotion;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 明确的受控靶输入与生产发射器，箭的飞行/碰撞/伤害完全由实际世界更新 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NativeAimAblationTests {

    private static final ModernFlightProfile[] ARMS = { ModernFlightProfile.NATIVE_STATIC_COMPENSATION,
        ModernFlightProfile.NATIVE_LAST_SAMPLE_COMPENSATION, ModernFlightProfile.NATIVE_MOTION_COMPENSATION };
    private static final String[] CASES = { "stationary", "lateral", "accelerating", "reverse_after_release",
        "jump_after_release" };
    private static final int[] DISTANCES = { 8, 11, 14 };

    private NativeAimAblationTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 800, batch = "native_aim_ablation")
    public static void matchedActuatorsUseTheSameRealPhysicsAndRecordAllPredeterminedShots(GameTestHelper helper) {
        var experiment = new Experiment(helper);
        MinecraftForge.EVENT_BUS.register(experiment);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(experiment);
            experiment.removeShot();
            experiment.save();
        });
        helper.onEachTick("three matched aim arms and actual native arrows", experiment::tick);
        helper.succeedWhen(() -> experiment.complete);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "native_aim_barrier")
    public static void everyNativeArmRejectsASolidBarrierWithoutChargingResources(GameTestHelper helper) {
        var f = fixture(helper);
        var target = target(helper, f, 15.5, 4.5);
        helper.afterTest(target::setDead);
        for (int y = 1; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(8, y, z, Blocks.stone);
        var weapon = new ItemStack(Items.bow);
        f.maid.setCurrentItemOrArmor(0, weapon);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        for (var profile : ARMS) {
            var bow = new MaidBowAttack(f.maid, profile);
            bow.observe(
                target,
                true,
                helper.getWorld()
                    .getTotalWorldTime());
            helper.assertTrue(
                !bow.fireForModern(target, 1, () -> true) && bow.getStatus() == MaidBowAttack.Status.BLOCKED
                    && bow.getShots() == 0
                    && weapon.getItemDamage() == 0
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 8,
                "Native ablation arm bypassed the actual wall or paid for a rejected arrow: " + profile);
        }
        helper.succeed();
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.getBrainCoordinator()
            .setSuspended(true);
        f.maid.setTask(MaidTask.RANGED_ATTACK);
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 7);
        return f;
    }

    private static EntityPig target(GameTestHelper helper, ModernCoreTests.Fixture f, double x, double z) {
        var target = new EntityPig(helper.getWorld());
        target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        target.setHealth(200);
        helper.spawnEntity(target, x, 1, z);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
        return target;
    }

    public static final class Experiment {

        private final GameTestHelper helper;
        private final ModernCoreTests.Fixture f;
        private final JsonArray shots = new JsonArray();
        private final JsonObject report = new JsonObject();
        private EntityPig target;
        private EntityArrow arrow;
        private MaidBowAttack bow;
        private ItemStack weapon;
        private JsonObject row;
        private JsonArray frames;
        private double[] previousArrow;
        private int trial, step, damageEvents;
        private boolean complete;
        private long lastTick = Long.MIN_VALUE;

        Experiment(GameTestHelper helper) {
            this.helper = helper;
            f = fixture(helper);
            report.addProperty("schema", "native-aim-ablation-v1");
            report.addProperty(
                "scope",
                "Controlled target positions and production release; native arrow world ticks, collision and damage; no natural Brain or draw-timing comparison");
            report.addProperty("expectedShots", ARMS.length * CASES.length * DISTANCES.length);
            report.addProperty("initialSpeed", 3);
            report.addProperty("spread", 0);
            report.addProperty("expectedAirDrag", ArrowPhysics.OVERWORLD_AIR.drag);
            report.addProperty("expectedGravity", ArrowPhysics.OVERWORLD_AIR.gravity);
            report.addProperty(
                "worldProvider",
                helper.getWorld().provider.getClass()
                    .getName());
            report.addProperty("worldDimension", helper.getWorld().provider.dimensionId);
            report.addProperty("fullReleasePassed", false);
            report.addProperty("statisticalAdmission", false);
            report.add("shots", shots);
            try {
                report.add(
                    "productionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        MaidBowAttack.class,
                        ObservedTargetMotion.class,
                        ArrowInterceptSolver.class,
                        ArrowPhysics.class));
                report.add(
                    "observerArtifact",
                    DevelopmentArtifactIdentity.capture(NativeAimAblationTests.class, Experiment.class));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot bind native ablation artifacts", failure);
            }
        }

        void tick() {
            long now = helper.getWorld()
                .getTotalWorldTime();
            if (complete || now == lastTick) return;
            lastTick = now;
            if (step == 0) begin();
            helper.assertTrue(
                f.maid.getHealth() == 20 && f.maid.getHeldItem() == weapon,
                "Ablation shooter health or weapon changed");
            if (arrow != null) observeFlight();
            int scenario = trial / (ARMS.length * DISTANCES.length);
            double offset = scenario == 2 ? step <= 4 ? .05 * step * (step + 1) : 1 + .4 * (step - 4)
                : scenario == 1 || scenario == 3 ? step <= 4 ? .3 * step : 1.2 + (scenario == 3 ? -.3 : .3) * (step - 4)
                    : 0;
            double jump = scenario == 4 && step > 4 ? 1.3 * Math.sin(Math.PI * (step - 4) / 8) : 0;
            int distance = DISTANCES[trial / ARMS.length % DISTANCES.length];
            target.setPosition(f.x + 1.5 + distance, f.y + 1 + jump, f.z + 2.5 + offset);
            target.motionX = target.motionY = target.motionZ = 0;
            var frame = new JsonObject();
            frame.addProperty("step", step);
            frame.add("target", vector(target.posX, target.posY, target.posZ));
            frame.addProperty("targetHealth", target.getHealth());
            if (arrow != null) {
                frame.add("arrow", vector(arrow.posX, arrow.posY, arrow.posZ));
                frame.add("arrowVelocity", vector(arrow.motionX, arrow.motionY, arrow.motionZ));
                frame.addProperty("arrowAge", arrow.ticksExisted);
                frame.addProperty("arrowDead", arrow.isDead);
            }
            frames.add(frame);
            if (step <= 4) bow.observe(target, true, now);
            if (step == 4) {
                helper.assertTrue(
                    bow.fireForModern(
                        target,
                        1,
                        () -> f.maid.isEntityAlive() && target.isEntityAlive()
                            && f.maid.getHeldItem() == weapon
                            && f.maid.getTask() == MaidTask.RANGED_ATTACK),
                    "Predetermined native shot was rejected: " + row);
                arrow = bow.getLastArrow();
                helper.assertTrue(
                    arrow != null && arrow.getClass() == EntityArrow.class
                        && arrow.shootingEntity == f.maid
                        && weapon.getItemDamage() == 1
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == 7,
                    "Matched shot changed native arrow class, origin identity or finite payment");
                previousArrow = state();
                row.add("launchPosition", vector(arrow.posX, arrow.posY, arrow.posZ));
                row.add("launchVelocity", vector(arrow.motionX, arrow.motionY, arrow.motionZ));
                row.addProperty(
                    "arrowUuid",
                    arrow.getUniqueID()
                        .toString());
                row.addProperty(
                    "shootingMaidUuid",
                    f.maid.getUniqueID()
                        .toString());
                row.addProperty("flightTicks", bow.getLastSolution().flightTicks);
                var predicted = bow.getLastSolution().predictedTarget;
                row.add("predictedTarget", vector(predicted.x, predicted.y, predicted.z));
            }
            if (++step >= 13) finish();
        }

        private void begin() {
            int scenario = trial / (ARMS.length * DISTANCES.length);
            int distance = DISTANCES[trial / ARMS.length % DISTANCES.length];
            target = target(helper, f, 1.5 + distance, 2.5);
            weapon = new ItemStack(Items.bow);
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.maid.setOffhandItem(null);
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
            bow = new MaidBowAttack(f.maid, ARMS[trial % ARMS.length]);
            row = new JsonObject();
            row.addProperty("case", CASES[scenario]);
            row.addProperty("distance", distance);
            row.addProperty(
                "profile",
                bow.flightProfile()
                    .name());
            row.addProperty(
                "targetUuid",
                target.getUniqueID()
                    .toString());
            row.addProperty("targetWidth", target.width);
            row.addProperty("targetHeight", target.height);
            row.addProperty(
                "attackBase",
                f.maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                    .getBaseValue());
            frames = new JsonArray();
            row.add("frames", frames);
            shots.add(row);
            previousArrow = null;
            damageEvents = 0;
        }

        private void observeFlight() {
            if (step <= 6) {
                var next = state();
                helper.assertTrue(
                    !arrow.isDead && Math.abs(next[0] - previousArrow[0] - previousArrow[3]) < 1E-9
                        && Math.abs(next[1] - previousArrow[1] - previousArrow[4]) < 1E-9
                        && Math.abs(next[2] - previousArrow[2] - previousArrow[5]) < 1E-9
                        && Math.abs(next[3] - previousArrow[3] * (double) .99F) < 1E-12
                        && Math.abs(next[4] - (previousArrow[4] * (double) .99F - (double) .05F)) < 1E-12
                        && Math.abs(next[5] - previousArrow[5] * (double) .99F) < 1E-12,
                    "Actual unobstructed native arrow physics differs across ablation arms");
                row.addProperty("nativeUnobstructedPhysicsSamples", step - 4);
                previousArrow = next;
            }
        }

        private double[] state() {
            return new double[] { arrow.posX, arrow.posY, arrow.posZ, arrow.motionX, arrow.motionY, arrow.motionZ };
        }

        @SubscribeEvent
        public void hurt(LivingHurtEvent event) {
            if (arrow != null && event.entityLiving == target && event.source.getSourceOfDamage() == arrow)
                damageEvents++;
        }

        private void finish() {
            boolean hit = target.getHealth() < 200 && damageEvents > 0;
            row.addProperty("nativeArrowDamageEvents", damageEvents);
            row.addProperty("finalTargetHealth", target.getHealth());
            row.addProperty("hit", hit);
            row.addProperty("bowWear", weapon.getItemDamage());
            row.addProperty(
                "remainingArrows",
                f.maid.getMaidInventory()
                    .getStackInSlot(0).stackSize);
            row.addProperty("complete", true);
            if (trial / (ARMS.length * DISTANCES.length) == 0)
                helper.assertTrue(hit, "Stationary compensated control failed its real collision baseline");
            removeShot();
            step = 0;
            complete = ++trial == ARMS.length * CASES.length * DISTANCES.length;
            if (complete) save();
        }

        void removeShot() {
            if (arrow != null) arrow.setDead();
            if (target != null) target.setDead();
            arrow = null;
            target = null;
        }

        void save() {
            report.addProperty("complete", complete);
            report.addProperty("completedShots", trial);
            try {
                var path = Paths.get(System.getProperty("horizonqa.reportDir"), "native-aim-ablation.json");
                Files.write(
                    path,
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain actual native ablation evidence", failure);
            }
        }
    }

    private static JsonArray vector(double x, double y, double z) {
        var result = new JsonArray();
        result.add(new com.google.gson.JsonPrimitive(x));
        result.add(new com.google.gson.JsonPrimitive(y));
        result.add(new com.google.gson.JsonPrimitive(z));
        return result;
    }
}
