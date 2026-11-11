package com.tianluoqaq.tlmnh.qa;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.ballistics.ArrowInterceptSolver;
import com.tianluoqaq.tlmnh.common.combat.ballistics.ArrowPhysics;
import com.tianluoqaq.tlmnh.common.combat.ballistics.BallisticVector;
import com.tianluoqaq.tlmnh.common.combat.ballistics.NativeArrowMedium;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.physics.NativeLiquidPhysics;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics.NativeArrowGravity;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 实际provider控制输入与生产发射器；箭由真实世界推进，不手动tick演员或箭 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "GalacticraftCore" })
public final class ActualArrowPhysicsTests {

    private static final String MOON = "micdoodle8.mods.galacticraft.core.dimension.WorldProviderMoon";

    private ActualArrowPhysicsTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 45, batch = "actual_arrow_surface")
    public static void surfaceProviderUsesActualPointZeroFiveAndHitsWithFiniteResources(GameTestHelper helper) {
        var trial = new Trial(helper, false, "surface");
        helper.onEachTick("surface provider and actual native arrow flight", trial::tick);
        helper.succeedWhen(() -> trial.complete);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 45, batch = "actual_arrow_moon")
    public static void installedMoonProviderDrivesBothAimAndTerrainSafety(GameTestHelper helper) {
        var trial = new Trial(helper, true, "moon");
        helper.onEachTick("installed lunar provider and actual native arrow flight", trial::tick);
        helper.succeedWhen(() -> trial.complete);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 45, batch = "actual_arrow_water")
    public static void nativeWaterDragAndFlowTimingMatchSolverAndExactlyOncePayment(GameTestHelper helper) {
        mediumFlight(helper, false);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 45, batch = "actual_arrow_water_boundary")
    public static void nativeAirWaterAirBoundaryMatchesSolverAndSafetyOnTheSameArrow(GameTestHelper helper) {
        mediumFlight(helper, true);
    }

    private static void mediumFlight(GameTestHelper helper, boolean boundary) {
        var trial = new Trial(helper, false, boundary ? "air-water-air" : "water");
        trial.mediumCase = true;
        // 静态原生水块作为受控当前输入；水中例保留真实meta坡度与非零水流
        // flags=2不邻居更新；不手动tick水、箭或任何演员，也不声称自然水体生成
        int left = boundary ? 4 : 1, right = boundary ? 5 : 19;
        for (int x = left; x <= right; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 7; z++) helper.getWorld()
            .setBlock(trial.f.x + x, trial.f.y + y, trial.f.z + z, Blocks.water, boundary ? 0 : z - 1, 2);
        trial.target.setPosition(trial.f.x + (boundary ? 17.5 : 9.5), trial.f.y + 1, trial.f.z + 4.5);
        trial.report.addProperty(
            "mediumInput",
            boundary ? "static native water x4..5, y1..5; air on both sides"
                : "static native water x1..19, y1..5; z1..7 metadata0..6 native positive-Z flow");
        trial.report.addProperty("controlledTargetMovementBase", 0);
        trial.report.addProperty(
            "installedEfrAdjustedLiquidPhysics",
            NativeLiquidPhysics.sample()
                .adjusted());
        var initial = new NBTTagCompound();
        trial.target.writeToNBT(initial);
        trial.report.addProperty("targetInitialRawNBT", initial.toString());
        trial.report
            .add("targetInitialTypedNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) initial.copy()));
        trial.report.add("targetInitialPhysicalConditions", NaturalRangedPairTests.physicalConditions(trial.target));
        var maidInitial = new NBTTagCompound();
        trial.f.maid.writeToNBT(maidInitial);
        trial.report.addProperty("maidInitialRawNBT", maidInitial.toString());
        trial.report
            .add("maidInitialTypedNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) maidInitial.copy()));
        trial.report.add("maidInitialPhysicalConditions", NaturalRangedPairTests.physicalConditions(trial.f.maid));
        var ownerInitial = new NBTTagCompound();
        trial.f.owner.writeToNBT(ownerInitial);
        trial.report.addProperty("ownerInitialRawNBT", ownerInitial.toString());
        trial.report
            .add("ownerInitialTypedNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) ownerInitial.copy()));
        trial.report.addProperty(
            "ownerProfile",
            "Existing actuator fixture Forge FakePlayer, isolated PLAY channel; not a complete-root/native-login benchmark");
        helper.onEachTick("native water physics and actual natural collision", trial::tick);
        helper.succeedWhen(() -> {
            if (!trial.complete) return false;
            helper.assertTrue(
                trial.waterSamples > 0
                    && (boundary ? trial.airSamples >= 2 && trial.mediumTransitions >= 2 : trial.flowSamples > 0),
                "Actual samples did not traverse the required media: water=" + trial.waterSamples
                    + ", air="
                    + trial.airSamples
                    + ", transitions="
                    + trial.mediumTransitions);
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "actual_arrow_spawn_invalidation")
    public static void providerReplacementDuringRealSpawnRevokesArrowWithoutChargingResources(GameTestHelper helper) {
        var trial = new Trial(helper, true, "spawn-invalidation");
        var replacement = new WorldProviderSurface();
        replacement.setDimension(trial.provider.dimensionId);
        replacement.registerWorld(helper.getWorld());
        trial.replacement = replacement;
        var bowBefore = trial.weapon.copy();
        var arrowsBefore = trial.ammunition.copy();
        try {
            helper.assertTrue(
                !trial.bow.fireForModern(trial.target, 1, () -> true),
                "Spawn callback replaced the sampled provider but release still committed");
        } finally {
            trial.setProvider(trial.provider);
        }
        helper.assertTrue(
            trial.spawnCallbacks == 1 && trial.spawned != null
                && trial.spawned.isDead
                && trial.bow.getStatus() == MaidBowAttack.Status.SPAWN_REJECTED
                && trial.bow.getShots() == 0
                && trial.bow.getLastArrow() == null
                && trial.f.maid.getHeldItem() == trial.weapon
                && trial.f.maid.getMaidInventory()
                    .getStackInSlot(0) == trial.ammunition
                && ItemStack.areItemStacksEqual(bowBefore, trial.weapon)
                && ItemStack.areItemStacksEqual(arrowsBefore, trial.ammunition),
            "Invalidated launch retained a live arrow or changed original bow/ammunition data");
        trial.report.addProperty("spawnCallbacks", trial.spawnCallbacks);
        trial.report.addProperty("revokedArrowDead", trial.spawned.isDead);
        trial.report.addProperty(
            "replacementProvider",
            replacement.getClass()
                .getName());
        trial.report.addProperty("originalResourcesIntact", true);
        trial.complete = true;
        trial.restoreProvider();
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "actual_arrow_nbt_invalidation")
    public static void inPlaceArrowDataMutationDuringSpawnCannotRewriteTheLaunchSnapshot(GameTestHelper helper) {
        rejectSpawnMutation(helper, "nbt");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "actual_arrow_critical_invalidation")
    public static void nonSerializedCriticalMutationDuringSpawnDoesNotSpendBowOrArrows(GameTestHelper helper) {
        rejectSpawnMutation(helper, "critical");
    }

    private static void rejectSpawnMutation(GameTestHelper helper, String mutation) {
        var trial = new Trial(helper, false, "spawn-" + mutation);
        trial.mutation = mutation;
        var weaponBefore = trial.weapon.copy();
        var ammunitionBefore = trial.ammunition.copy();
        helper.assertTrue(
            !trial.bow.fireForModern(trial.target, 1, () -> true),
            "Actual arrow spawn mutation bypassed launch admission: " + mutation);
        helper.assertTrue(
            trial.spawnCallbacks == 1 && trial.spawned != null
                && trial.spawned.isDead
                && trial.bow.getStatus() == MaidBowAttack.Status.SPAWN_REJECTED
                && trial.bow.getShots() == 0
                && trial.bow.getLastArrow() == null
                && trial.f.maid.getHeldItem() == trial.weapon
                && trial.f.maid.getMaidInventory()
                    .getStackInSlot(0) == trial.ammunition
                && ItemStack.areItemStacksEqual(weaponBefore, trial.weapon)
                && ItemStack.areItemStacksEqual(ammunitionBefore, trial.ammunition),
            "Mutated arrow survived or changed original bow/ammunition resources: " + mutation);
        helper.assertTrue(
            mutation.equals("nbt") ? trial.seededArrow == trial.spawned && trial.spawned.getEntityData()
                .getCompoundTag("LaunchBoundaryProbe")
                .getString("state")
                .equals("changed") : trial.spawned.getIsCritical(),
            "Real callback did not apply the intended mutation: " + mutation);
        trial.report.addProperty("spawnMutation", mutation);
        trial.report.addProperty("spawnCallbacks", trial.spawnCallbacks);
        trial.report.addProperty("revokedArrowDead", true);
        trial.report.addProperty("originalResourcesIntact", true);
        trial.complete = true;
        helper.succeed();
    }

    public static final class Trial {

        final GameTestHelper helper;
        final ModernCoreTests.Fixture f;
        final WorldProvider originalProvider, provider;
        final Field providerField;
        final EntityPig target;
        final MaidBowAttack bow;
        final ItemStack weapon = new ItemStack(Items.bow);
        final ItemStack ammunition = new ItemStack(Items.arrow, 8);
        final JsonObject report = new JsonObject();
        final JsonArray frames = new JsonArray();
        final float gravity;
        final String name;
        EntityArrow arrow, spawned;
        EntityArrow seededArrow;
        String mutation = "";
        WorldProvider replacement;
        double[] previous;
        long previousTime = Long.MIN_VALUE;
        int previousAge, flightSamples, hurtEvents, spawnCallbacks;
        boolean complete;
        boolean mediumCase;
        int waterSamples, airSamples, mediumTransitions, flowSamples;
        Boolean previousWater;
        ArrowPhysics.State predicted;

        Trial(GameTestHelper helper, boolean lunar, String name) {
            this.helper = helper;
            this.name = name;
            providerField = providerField();
            originalProvider = helper.getWorld().provider;
            // 先登记恢复，失败时也先于复用Fixture的时间/实体/区块清理执行
            helper.afterTest(this::restoreProvider);
            f = new ModernCoreTests.Fixture(helper, false, 1, 32);
            f.maid.getBrainCoordinator()
                .setSuspended(true);
            f.maid.setTask(MaidTask.RANGED_ATTACK);
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.maid.setOffhandItem(null);
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, ammunition);
            f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 7);
            // 延长现有已加载的平地；28格使错误的.05安全轨迹会先撞地
            for (int x = 1; x <= 33; x++) for (int z = 1; z < 8; z++) {
                helper.setBlock(x, 0, z, Blocks.stone);
                if (x >= 20) for (int y = 1; y < 10; y++) helper.setBlock(x, y, z, Blocks.air);
            }
            helper.afterTest(() -> {
                for (int x = 21; x <= 33; x++)
                    for (int y = 0; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, Blocks.air);
            });
            provider = createProvider(lunar);
            provider.setDimension(originalProvider.dimensionId);
            provider.registerWorld(helper.getWorld());
            setProvider(provider);
            gravity = lunar ? .005F : .05F;
            target = new EntityPig(helper.getWorld());
            target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(200);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            target.setHealth(200);
            helper.afterTest(target::setDead);
            helper.spawnEntity(target, 29.5, 1, 4.5);
            f.maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
            bow = new MaidBowAttack(f.maid, ModernFlightProfile.NATIVE_STATIC_COMPENSATION);
            bow.observe(
                target,
                true,
                helper.getWorld()
                    .getTotalWorldTime());
            helper.assertTrue(NativeArrowGravity.available(), "Actual gravity capability was already faulted");
            report.addProperty("schema", "actual-arrow-physics-v1");
            report.addProperty(
                "scope",
                "Real installed provider bound to isolated QA world; production native actuator, natural entity ticks and collision; not full lunar dimension or natural Brain admission");
            report.addProperty(
                "provider",
                provider.getClass()
                    .getName());
            report.addProperty("dimension", provider.dimensionId);
            report.addProperty("controlledProviderFieldInput", true);
            report.addProperty("expectedGravity", (double) gravity);
            report.add("flightFrames", frames);
            try {
                report.add(
                    "productionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        MaidBowAttack.class,
                        ArrowPhysics.class,
                        ArrowInterceptSolver.class,
                        NativeArrowMedium.class,
                        NativeArrowGravity.class,
                        NativeLiquidPhysics.class));
                report.add(
                    "observerArtifact",
                    DevelopmentArtifactIdentity.capture(
                        ActualArrowPhysicsTests.class,
                        Trial.class,
                        ModernCoreTests.Fixture.class,
                        SleepAttributePortSnapshot.class,
                        NaturalRangedPairTests.class));
                report.add("connectionArtifact", DevelopmentArtifactIdentity.capture(IsolatedPlayerChannel.class));
                if (cpw.mods.fml.common.Loader.isModLoaded("etfuturum")) report.add(
                    "liquidProviderArtifact",
                    DevelopmentArtifactIdentity
                        .capture(Class.forName("ganymedes01.etfuturum.configuration.configs.ConfigMixins")));
                if (lunar) report.add("providerArtifact", DevelopmentArtifactIdentity.capture(provider.getClass()));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot bind actual arrow physics artifacts", failure);
            }
            MinecraftForge.EVENT_BUS.register(this);
            helper.afterTest(() -> {
                MinecraftForge.EVENT_BUS.unregister(this);
                if (spawned != null) spawned.setDead();
                if (arrow != null) arrow.setDead();
                save();
            });
        }

        void tick() {
            long now = helper.getWorld()
                .getTotalWorldTime();
            if (complete || now == previousTime) return;
            previousTime = now;
            helper.assertTrue(
                helper.getWorld().provider == provider && f.maid.isEntityAlive() && target.isEntityAlive(),
                "Controlled provider or live actors changed during the trajectory sample");
            if (arrow == null) {
                if (f.maid.ticksExisted < 2) return;
                helper.assertTrue(
                    bow.fireForModern(target, 1, () -> true),
                    "Actual-provider production shot rejected: " + bow.getStatus());
                arrow = bow.getLastArrow();
                helper.assertTrue(
                    arrow != null && arrow == spawned
                        && arrow.getClass() == EntityArrow.class
                        && arrow.shootingEntity == f.maid
                        && bow.getLastSolution() != null
                        && bow.getLastSolution()
                            .isSolved()
                        && bow.getLastPhysics() != null
                        && bow.getLastPhysics().gravity == (double) gravity
                        && NativeArrowGravity.forArrow(arrow) == gravity,
                    "Solver did not consume the gravity queried from the exact registered native arrow");
                report.addProperty("solverGravity", bow.getLastPhysics().gravity);
                report.addProperty("flightTicks", bow.getLastSolution().flightTicks);
                report.addProperty("spawnCallbacks", spawnCallbacks);
                if (gravity == .005F) {
                    boolean stalePathHitsGround = terrainCollisionWithGravity(arrow, .05F);
                    helper.assertTrue(
                        stalePathHitsGround,
                        "Low-gravity case does not distinguish actual safety from fixed .05 terrain prediction");
                    report.addProperty("sameLaunchWithWrongGravityHitsTerrain", stalePathHitsGround);
                }
                previous = state(arrow);
                previousAge = arrow.ticksExisted;
                recordFrame();
                predictNext();
                return;
            }
            if (hurtEvents > 0) {
                // 终局事实先落盘；近水靶第三tick即撞膨胀身体盒，只有两个完整未碰撞步进
                report.addProperty("nativeDamageEvents", hurtEvents);
                report.addProperty("targetHealth", target.getHealth());
                report.addProperty("confirmedShots", bow.getShots());
                report.addProperty("nativePhysicsSamples", flightSamples);
                report.addProperty("bowWear", weapon.getItemDamage());
                report.addProperty("remainingArrows", ammunition.stackSize);
                report.addProperty(
                    "sameAmmunitionObject",
                    f.maid.getMaidInventory()
                        .getStackInSlot(0) == ammunition);
                report.addProperty("minimumUncollidedSamples", name.equals("water") ? 2 : 3);
                boolean sampleCountPassed = flightSamples >= (name.equals("water") ? 2 : 3);
                boolean collisionPassed = hurtEvents == 1 && target.getHealth() < 200;
                boolean paymentPassed = bow.getShots() == 1 && weapon.getItemDamage() == 1
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0) == ammunition
                    && ammunition.stackSize == 7;
                report.addProperty("flightSampleCountPassed", sampleCountPassed);
                report.addProperty("nativeCollisionPassed", collisionPassed);
                report.addProperty("finitePaymentPassed", paymentPassed);
                helper.assertTrue(sampleCountPassed, "Natural flight has too few complete uncollided physics samples");
                helper.assertTrue(
                    collisionPassed,
                    "Actual native arrow collision did not damage this target exactly once");
                helper.assertTrue(
                    paymentPassed,
                    "Actual native arrow did not pay its finite bow/ammunition exactly once");
                complete = true;
                report.addProperty("nativeDamageEvents", hurtEvents);
                report.addProperty("targetHealth", target.getHealth());
                report.addProperty("nativePhysicsSamples", flightSamples);
                report.addProperty("bowWear", weapon.getItemDamage());
                report.addProperty("remainingArrows", ammunition.stackSize);
                report.addProperty("waterSamples", waterSamples);
                report.addProperty("airSamples", airSamples);
                report.addProperty("mediumTransitions", mediumTransitions);
                report.addProperty("flowSamples", flowSamples);
                restoreProvider();
                return;
            }
            if (arrow.ticksExisted == previousAge) return;
            var next = state(arrow);
            if (mediumCase) {
                // 先保存失败的真实帧，避免assert抛出后只剩上一个起点而丢失首差
                recordFrame();
                var row = frames.get(frames.size() - 1)
                    .getAsJsonObject();
                row.addProperty("previousArrowAge", previousAge);
                row.addProperty("nativeInWater", arrow.isInWater());
                row.addProperty("nativeDead", arrow.isDead);
                if (predicted != null) {
                    double[] expected = { predicted.position.x, predicted.position.y, predicted.position.z,
                        predicted.velocity.x, predicted.velocity.y, predicted.velocity.z };
                    var expectedValues = new JsonArray();
                    var deltas = new JsonArray();
                    int firstNonExact = -1;
                    for (int i = 0; i < next.length; i++) {
                        expectedValues.add(new JsonPrimitive(expected[i]));
                        deltas.add(new JsonPrimitive(next[i] - expected[i]));
                        if (firstNonExact < 0
                            && Double.doubleToLongBits(next[i]) != Double.doubleToLongBits(expected[i]))
                            firstNonExact = i;
                    }
                    row.add("expectedPositionAndVelocity", expectedValues);
                    row.add("actualMinusExpected", deltas);
                    row.addProperty("firstNonExactComponent", firstNonExact);
                    row.addProperty(
                        "positionResidual",
                        new BallisticVector(next[0], next[1], next[2]).subtract(predicted.position)
                            .length());
                    row.addProperty(
                        "velocityResidual",
                        new BallisticVector(next[3], next[4], next[5]).subtract(predicted.velocity)
                            .length());
                }
                helper.assertTrue(
                    !arrow.isDead && arrow.ticksExisted == previousAge + 1
                        && predicted != null
                        && new BallisticVector(next[0], next[1], next[2]).subtract(predicted.position)
                            .length() < 1E-9
                        && new BallisticVector(next[3], next[4], next[5]).subtract(predicted.velocity)
                            .length() < 1E-11,
                    "Actual native start-box water/flow, movement, drag or gravity differs from shared step: " + row);
                previous = next;
                previousAge = arrow.ticksExisted;
                flightSamples++;
                predictNext();
                return;
            }
            helper.assertTrue(
                !arrow.isDead && !arrow.isInWater()
                    && arrow.ticksExisted == previousAge + 1
                    && Math.abs(next[0] - previous[0] - previous[3]) < 1E-9
                    && Math.abs(next[1] - previous[1] - previous[4]) < 1E-9
                    && Math.abs(next[2] - previous[2] - previous[5]) < 1E-9
                    && Math.abs(next[3] - previous[3] * (double) .99F) < 1E-12
                    && Math.abs(next[4] - (previous[4] * (double) .99F - (double) gravity)) < 1E-12
                    && Math.abs(next[5] - previous[5] * (double) .99F) < 1E-12,
                "Real EntityArrow movement, drag or gravity differs from the sampled provider: " + name);
            previous = next;
            previousAge = arrow.ticksExisted;
            flightSamples++;
            recordFrame();
        }

        private void predictNext() {
            if (!mediumCase) return;
            // 发射事务快照的许可已结束；观察器独立读取此真实箭的当前盒，不复用已失效的发射租约
            var medium = new NativeArrowMedium(
                arrow,
                () -> !arrow.isDead && arrow.worldObj == helper.getWorld() && helper.getWorld().provider == provider);
            var position = new BallisticVector(arrow.posX, arrow.posY, arrow.posZ);
            var sample = medium.at(position);
            boolean water = sample.drag == (double) .8F;
            if (water) waterSamples++;
            else airSamples++;
            if (sample.push.length() > 0) flowSamples++;
            if (previousWater != null && previousWater.booleanValue() != water) mediumTransitions++;
            previousWater = water;
            predicted = ArrowPhysics.OVERWORLD_AIR.withGravity(gravity)
                .step(
                    new ArrowPhysics.State(position, new BallisticVector(arrow.motionX, arrow.motionY, arrow.motionZ)),
                    sample);
            var row = frames.get(frames.size() - 1)
                .getAsJsonObject();
            row.addProperty("nextNativeDrag", sample.drag);
            row.addProperty("nextWaterPushX", sample.push.x);
            row.addProperty("nextWaterPushY", sample.push.y);
            row.addProperty("nextWaterPushZ", sample.push.z);
        }

        private boolean terrainCollisionWithGravity(EntityArrow arrow, float wrongGravity) {
            double x = arrow.posX, y = arrow.posY, z = arrow.posZ;
            double vx = arrow.motionX, vy = arrow.motionY, vz = arrow.motionZ;
            double remaining = bow.getLastSolution().flightTicks;
            while (remaining > 0) {
                double fraction = Math.min(1, remaining);
                double nx = x + vx * fraction, ny = y + vy * fraction, nz = z + vz * fraction;
                if (helper.getWorld()
                    .func_147447_a(
                        Vec3.createVectorHelper(x, y, z),
                        Vec3.createVectorHelper(nx, ny, nz),
                        false,
                        true,
                        false)
                    != null) return true;
                x += vx;
                y += vy;
                z += vz;
                vx *= (double) .99F;
                vy = vy * (double) .99F - (double) wrongGravity;
                vz *= (double) .99F;
                remaining -= 1;
            }
            return false;
        }

        private void recordFrame() {
            var frame = new JsonObject();
            frame.addProperty(
                "worldTime",
                helper.getWorld()
                    .getTotalWorldTime());
            frame.addProperty("arrowAge", arrow.ticksExisted);
            var values = new JsonArray();
            for (double value : state(arrow)) values.add(new JsonPrimitive(value));
            frame.add("positionAndVelocity", values);
            frames.add(frame);
        }

        @SubscribeEvent
        public void constructing(EntityEvent.EntityConstructing event) {
            if (!mutation.equals("nbt") || !(event.entity instanceof EntityArrow candidate)
                || candidate.worldObj != helper.getWorld()
                || seededArrow != null) return;
            var nested = new NBTTagCompound();
            nested.setString("state", "original");
            candidate.getEntityData()
                .setTag("LaunchBoundaryProbe", nested);
            seededArrow = candidate;
        }

        @SubscribeEvent
        public void spawning(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityArrow candidate) || candidate.shootingEntity != f.maid) return;
            spawnCallbacks++;
            spawned = candidate;
            report.addProperty(
                "spawnedArrowUuid",
                candidate.getUniqueID()
                    .toString());
            if (replacement != null) {
                helper.assertTrue(event.world == helper.getWorld(), "Spawn callback escaped the isolated QA world");
                setProvider(replacement);
            }
            if (mutation.equals("nbt")) {
                helper.assertTrue(candidate == seededArrow, "Spawn did not use the exact constructed arrow");
                var nested = candidate.getEntityData()
                    .getCompoundTag("LaunchBoundaryProbe");
                helper.assertTrue(
                    nested.getString("state")
                        .equals("original"),
                    "Initial arrow data changed before spawn");
                nested.setString("state", "changed");
            } else if (mutation.equals("critical")) {
                helper.assertTrue(!candidate.getIsCritical(), "Native modern shot unexpectedly starts critical");
                candidate.setIsCritical(true);
            }
        }

        @SubscribeEvent
        public void hurting(LivingHurtEvent event) {
            if (arrow != null && event.entityLiving == target && event.source.getSourceOfDamage() == arrow)
                hurtEvents++;
        }

        void restoreProvider() {
            setProvider(originalProvider);
        }

        private void setProvider(WorldProvider value) {
            try {
                // World.provider在目标Forge中为final；仅为此夹具设置受控provider输入
                providerField.set(helper.getWorld(), value);
                helper.assertTrue(helper.getWorld().provider == value, "Controlled provider write did not take effect");
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot bind or restore the isolated QA provider", failure);
            }
        }

        private void save() {
            report.addProperty("assertionsComplete", complete);
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), "actual-arrow-physics-" + name + ".json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain actual arrow physics evidence", failure);
            }
        }
    }

    private static WorldProvider createProvider(boolean lunar) {
        if (!lunar) return new WorldProviderSurface();
        try {
            return (WorldProvider) Class.forName(MOON)
                .getConstructor()
                .newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Installed Galacticraft lunar provider unavailable", failure);
        }
    }

    private static Field providerField() {
        for (String name : new String[] { "provider", "field_73011_w" }) {
            try {
                var field = World.class.getDeclaredField(name);
                if (field.getType() != WorldProvider.class)
                    throw new IllegalStateException("Actual World provider field type differs");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                // 只接受目标MCP/SRG字段名，不猜其他平台的布局
            }
        }
        throw new IllegalStateException("Actual World provider field unavailable");
    }

    private static double[] state(EntityArrow arrow) {
        return new double[] { arrow.posX, arrow.posY, arrow.posZ, arrow.motionX, arrow.motionY, arrow.motionZ };
    }
}
