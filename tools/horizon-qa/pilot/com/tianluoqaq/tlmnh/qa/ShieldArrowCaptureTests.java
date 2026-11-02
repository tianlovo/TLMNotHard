package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldArrowCapture;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.tconstruct.common.tool.TConstructToolData;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.development.shield.MaidShieldFixtures;
import com.tianluoqaq.tlmnh.core.MixinCompatibilityPlugin;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 注册的原生箭由真实世界推进和碰撞；直接伤害是独立反例，不手动tick实体或箭 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ShieldArrowCaptureTests {

    private static final String WOOD = "battlegear2:shield.wood", ROUND = "tinkersdefense:Round Shield",
        HEATER = "tinkersdefense:Heater Shield";

    private ShieldArrowCaptureTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "shield_capture_three_types")
    public static void threeRealShieldTypesCatchNaturalArrowsOnce(GameTestHelper helper) {
        var environment = new Environment(helper, "normal");
        String[] shields = { WOOD, ROUND, HEATER };
        int[] index = { 0 };
        helper.succeedWhen(() -> {
            if (environment.maid == null) environment.start(shields[index[0]], Mode.CAPTURE);
            if (!environment.finished()) return false;
            environment.checkCapture();
            environment.finishTrial();
            if (++index[0] != shields.length) return false;
            environment.output.addProperty("assertionsComplete", true);
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 110, batch = "shield_capture_capacity_and_direct")
    public static void capacityUsesProviderSaturationAndDirectDamageHasNoCollisionAuthority(GameTestHelper helper) {
        var environment = new Environment(helper, "capacity-direct");
        int[] index = { 0 };
        helper.succeedWhen(() -> {
            if (environment.maid == null) environment.start(WOOD, index[0] == 0 ? Mode.CAPACITY : Mode.DIRECT);
            if (!environment.finished()) return false;
            if (index[0] == 0) environment.checkCapture();
            else environment.checkDirect();
            environment.finishTrial();
            if (++index[0] != 2) return false;
            environment.output.addProperty("assertionsComplete", true);
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 110, batch = "shield_capture_cancel_and_direction")
    public static void cancelledAndRearNaturalImpactsDoNotCaptureOrWearShield(GameTestHelper helper) {
        var environment = new Environment(helper, "rejections");
        int[] index = { 0 };
        helper.succeedWhen(() -> {
            if (environment.maid == null) environment.start(WOOD, index[0] == 0 ? Mode.CANCELED : Mode.REAR);
            if (!environment.finished()) return false;
            environment.checkRejected();
            environment.finishTrial();
            if (++index[0] != 2) return false;
            environment.output.addProperty("assertionsComplete", true);
            return true;
        });
    }

    private enum Mode {
        CAPTURE,
        CAPACITY,
        DIRECT,
        CANCELED,
        REAR
    }

    public static final class Environment {

        final GameTestHelper helper;
        final JsonObject output = new JsonObject();
        final JsonArray trials = new JsonArray();
        final Map<String, ItemStack> prototypes;
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final boolean oldAnimals;
        final String name;
        final int x, y, z;
        EntityMaid maid;
        EntityCow shooter;
        EntityArrow arrow;
        ItemStack shield;
        Mode mode;
        JsonObject trial;
        JsonArray frames;
        int beforeWear, initialCount, attacks, hurts, livingTicks, previousAge, naturalFrames;
        long spawnTime, firstAttackTime = -1;
        boolean attackCanceled, hurtCanceled;
        RuntimeException failure;

        Environment(GameTestHelper helper, String name) {
            this.helper = helper;
            this.name = name;
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            oldAnimals = MinecraftServer.getServer()
                .getCanSpawnAnimals();
            output.addProperty("schema", "shield-arrow-capture-natural-v1");
            output.addProperty("candidateHashExpectedByRun", "bound externally to the actual installed main JAR");
            output.addProperty("hookApplied", MixinCompatibilityPlugin.hasShieldCaptureHook());
            output.addProperty("manualEntityOrArrowTicks", false);
            output.addProperty("fullReleasePassed", false);
            output.add("trials", trials);
            helper.afterTest(this::close);
            helper.assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Development mode required");
            helper.assertTrue(IntegrationGuard.available(ExternalFeature.SHIELDS), "Shield feature already faulted");
            helper.assertTrue(MixinCompatibilityPlugin.hasShieldCaptureHook(), "181 actual native capture hook absent");
            var construction = new JsonObject();
            output.add("construction", construction);
            try {
                prototypes = MaidShieldFixtures.createWoodShields(construction);
                chunks.forceChunksStrict(helper.getWorld(), x - 16, y, z - 16, x + 37, y + 10, z + 25);
                output.add(
                    "productionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        EntityMaid.class,
                        MaidShieldController.class,
                        MaidShieldItems.class,
                        MaidShieldArrowCapture.class,
                        MixinCompatibilityPlugin.class));
                output.add(
                    "observerArtifact",
                    DevelopmentArtifactIdentity.capture(ShieldArrowCaptureTests.class, Environment.class, Mode.class));
            } catch (Exception failure) {
                throw new IllegalStateException("Capture fixture construction failed", failure);
            }
            MinecraftServer.getServer()
                .setCanSpawnAnimals(true);
            for (int a = 0; a < 21; a++) for (int b = 0; b < 6; b++)
                for (int c = 0; c < 9; c++) helper.setBlock(a, b, c, b == 0 ? Blocks.stone : Blocks.air);
            MinecraftForge.EVENT_BUS.register(this);
            FMLCommonHandler.instance()
                .bus()
                .register(this);
        }

        void start(String id, Mode mode) {
            this.mode = mode;
            attacks = hurts = livingTicks = previousAge = naturalFrames = 0;
            firstAttackTime = -1;
            attackCanceled = hurtCanceled = false;
            shield = prototypes.get(id)
                .copy();
            initialCount = mode == Mode.CAPACITY ? Short.MAX_VALUE : 0;
            if (!shield.hasTagCompound()) shield.setTagCompound(new NBTTagCompound());
            shield.getTagCompound()
                .setShort("arrows", (short) initialCount);
            beforeWear = wear(shield);
            trial = new JsonObject();
            frames = new JsonArray();
            trial.addProperty("shieldId", id);
            trial.addProperty("mode", mode.name());
            trial.addProperty("initialCount", initialCount);
            trial.addProperty("initialWear", beforeWear);
            trial.add("initialShield", MaidShieldFixtures.snapshot(shield));
            trial.add("frames", frames);
            trials.add(trial);
            maid = new EntityMaid(helper.getWorld());
            helper.assertTrue(maid.bindTo(UUID.randomUUID()), "Fresh offline owner binding failed");
            maid.setPickup(false);
            maid.setSitting(true);
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.setOffhandItem(shield);
            maid.rotationYaw = maid.rotationYawHead = maid.renderYawOffset = mode == Mode.REAR ? -90 : 90;
            maid.prevRotationYaw = maid.prevRotationYawHead = maid.prevRenderYawOffset = maid.rotationYaw;
            helper.spawnEntity(maid, 8.5, 1, 4.5);
            helper.assertTrue(
                maid.getArrowCountInEntity() == 0 && maid.getHealth() == maid.getMaxHealth(),
                "Fresh actor body/health input differs");
            helper
                .assertTrue(MaidShieldArrowCapture.available(maid), "Capture capability unavailable for actual actor");
            shooter = new EntityCow(helper.getWorld());
            shooter.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            helper.spawnEntity(shooter, 2.5, 1, 4.5);
            arrow = new EntityArrow(helper.getWorld(), x + 3.5, y + 1.8, z + 4.5);
            arrow.shootingEntity = shooter;
            arrow.setDamage(2);
            arrow.setIsCritical(false);
            arrow.setThrowableHeading(
                mode == Mode.DIRECT ? 0 : 1,
                mode == Mode.DIRECT ? 1 : 0,
                0,
                mode == Mode.DIRECT ? .01F : 1.5F,
                0);
            helper.assertTrue(
                helper.getWorld()
                    .spawnEntityInWorld(arrow)
                    && helper.getWorld()
                        .getEntityByID(arrow.getEntityId()) == arrow,
                "Real native arrow registration failed");
            spawnTime = helper.getWorld()
                .getTotalWorldTime();
            trial.addProperty(
                "maidUuid",
                maid.getUniqueID()
                    .toString());
            trial.addProperty(
                "arrowUuid",
                arrow.getUniqueID()
                    .toString());
            trial.addProperty("spawnWorldTime", spawnTime);
            if (mode == Mode.DIRECT) {
                trial.addProperty(
                    "directNativeResult",
                    maid.attackEntityFrom(DamageSource.causeArrowDamage(arrow, shooter), 3));
                helper.assertTrue(
                    maid.getArrowCountInEntity() == 0,
                    "Registered direct damage left a negative/wrapped body arrow count");
            }
        }

        boolean finished() {
            if (failure != null) throw failure;
            helper.assertTrue(
                helper.getWorld()
                    .getTotalWorldTime() - spawnTime < 35,
                "Natural arrow produced no expected settled impact: " + trial);
            return firstAttackTime >= 0 && helper.getWorld()
                .getTotalWorldTime() >= firstAttackTime + 3 && naturalFrames >= 3;
        }

        void checkCapture() {
            var block = maid.getShieldController()
                .getLastBlock();
            helper.assertTrue(
                attacks == 1 && hurts == 1 && !attackCanceled && hurtCanceled,
                "Expected one actual attack and canceled shield hurt event");
            helper.assertTrue(
                block != null && block.blocked && block.outcome == MaidShieldController.Outcome.BLOCKED,
                "Actual natural impact was not confirmed blocked");
            helper.assertTrue(
                arrow.ticksExisted >= 2 && arrow.isDead
                    && maid.getShieldController()
                        .getSnapshot().caughtArrows == 1,
                "Native arrow did not fly and get captured exactly once");
            helper.assertTrue(
                count(shield) == Math.min(Short.MAX_VALUE, initialCount + 1) && maid.getArrowCountInEntity() == 0
                    && maid.getHealth() == maid.getMaxHealth(),
                "Shield count/body count/health after native callback differs");
            helper.assertTrue(
                wear(shield) - beforeWear == Math.max(0, block.requestedWear),
                "Actual shield durability was not settled exactly once");
        }

        void checkDirect() {
            helper.assertTrue(
                attacks == 1 && hurts == 1 && hurtCanceled && !arrow.isDead,
                "Direct damage did not remain an ordinary blocked call with its live registered arrow");
            helper.assertTrue(
                maid.getShieldController()
                    .getSnapshot().caughtArrows == 0 && count(shield) == initialCount
                    && maid.getArrowCountInEntity() == 0,
                "Direct registered arrow damage gained collision capture authority");
        }

        void checkRejected() {
            helper.assertTrue(
                attacks == 1 && maid.getShieldController()
                    .getSnapshot().caughtArrows == 0 && count(shield) == initialCount && wear(shield) == beforeWear,
                "Canceled or rear impact changed shield count/wear");
            if (mode == Mode.CANCELED) helper.assertTrue(
                attackCanceled && hurts == 0
                    && maid.getHealth() == maid.getMaxHealth()
                    && maid.getArrowCountInEntity() == 0,
                "Canceled attack entered hurt or changed target body/health");
            else helper.assertTrue(
                !attackCanceled && hurts == 1
                    && !hurtCanceled
                    && maid.getShieldController()
                        .getLastBlock().outcome == MaidShieldController.Outcome.DIRECTION_REJECTED
                    && maid.getHealth() < maid.getMaxHealth(),
                "Rear physical collision did not preserve native damage");
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void attack(LivingAttackEvent event) {
            if (maid == null || event.entityLiving != maid || event.source.getSourceOfDamage() != arrow) return;
            attacks++;
            if (firstAttackTime < 0) firstAttackTime = helper.getWorld()
                .getTotalWorldTime();
            if (mode == Mode.CANCELED) event.setCanceled(true);
            attackCanceled = event.isCanceled();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (maid == null || event.entityLiving != maid || event.source.getSourceOfDamage() != arrow) return;
            hurts++;
            hurtCanceled = event.isCanceled();
            trial.addProperty("actualHurtAmount", event.ammount);
            trial.addProperty("actualYawAtImpact", maid.rotationYaw);
        }

        @SubscribeEvent
        public void living(LivingEvent.LivingUpdateEvent event) {
            if (maid != null && event.entityLiving == maid) livingTicks++;
        }

        @SubscribeEvent
        public void frame(TickEvent.WorldTickEvent event) {
            if (maid == null || event.world != helper.getWorld()
                || event.phase != TickEvent.Phase.END
                || maid.ticksExisted == 0
                || failure != null) return;
            try {
                captureFrame(event);
            } catch (Exception | LinkageError | AssertionError error) {
                failure = new IllegalStateException("Natural shield capture observation failed", error);
                output.addProperty("observerError", error.toString());
            }
        }

        private void captureFrame(TickEvent.WorldTickEvent event) {
            helper.assertTrue(
                maid.ticksExisted == previousAge + 1 && livingTicks == 1
                    && helper.getWorld()
                        .getEntityByID(maid.getEntityId()) == maid,
                "Measured actor did not keep one registered natural Living tick per world frame");
            var frame = new JsonObject();
            frame.addProperty("worldTime", event.world.getTotalWorldTime());
            frame.addProperty("maidAge", maid.ticksExisted);
            frame.addProperty("livingTicks", livingTicks);
            frame.addProperty("arrowAge", arrow.ticksExisted);
            frame.addProperty("arrowX", arrow.posX - x);
            frame.addProperty("arrowY", arrow.posY - y);
            frame.addProperty("arrowDead", arrow.isDead);
            frame.addProperty("bodyArrowCount", maid.getArrowCountInEntity());
            frame.addProperty("shieldArrowCount", count(shield));
            frame.addProperty("shieldWear", wear(shield));
            frame.addProperty(
                "caughtArrows",
                maid.getShieldController()
                    .getSnapshot().caughtArrows);
            frames.add(frame);
            previousAge = maid.ticksExisted;
            livingTicks = 0;
            naturalFrames++;
        }

        void finishTrial() {
            trial.addProperty("attacks", attacks);
            trial.addProperty("hurts", hurts);
            trial.addProperty("naturalFrames", naturalFrames);
            trial.addProperty("finalBodyArrowCount", maid.getArrowCountInEntity());
            trial.addProperty(
                "caughtArrows",
                maid.getShieldController()
                    .getSnapshot().caughtArrows);
            trial.add("finalShield", MaidShieldFixtures.snapshot(shield));
            trial.add(
                "lastBlock",
                new GsonBuilder().serializeNulls()
                    .create()
                    .toJsonTree(
                        maid.getShieldController()
                            .getLastBlock()));
            trial.addProperty("assertionsPassed", true);
            removeActors();
        }

        private void removeActors() {
            if (arrow != null) {
                arrow.setDead();
                helper.getWorld()
                    .removeEntity(arrow);
            }
            if (shooter != null) {
                shooter.setDead();
                helper.getWorld()
                    .removeEntity(shooter);
            }
            if (maid != null) {
                maid.setDead();
                helper.getWorld()
                    .removeEntity(maid);
            }
            arrow = null;
            shooter = null;
            maid = null;
        }

        void close() {
            MinecraftForge.EVENT_BUS.unregister(this);
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            removeActors();
            chunks.releaseAll();
            MinecraftServer.getServer()
                .setCanSpawnAnimals(oldAnimals);
            output.addProperty("cleanupComplete", true);
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), "shield-arrow-capture-" + name + ".json"),
                    new GsonBuilder().setPrettyPrinting()
                        .serializeNulls()
                        .create()
                        .toJson(output)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Capture evidence write failed", failure);
            }
        }

        private static int count(ItemStack stack) {
            return stack.hasTagCompound() ? stack.getTagCompound()
                .getShort("arrows") : 0;
        }

        private static int wear(ItemStack stack) {
            var tool = TConstructToolData.read(stack);
            return tool == null ? stack.getItemDamage() : tool.getInteger("Damage");
        }
    }
}
