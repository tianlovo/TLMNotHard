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
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.common.MinecraftForge;
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
import com.tianluoqaq.tlmnh.common.combat.ArrowPhysics;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
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
        WorldProvider replacement;
        double[] previous;
        long previousTime = Long.MIN_VALUE;
        int previousAge, flightSamples, hurtEvents, spawnCallbacks;
        boolean complete;

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
                    DevelopmentArtifactIdentity
                        .capture(MaidBowAttack.class, ArrowPhysics.class, NativeArrowGravity.class));
                report.add("observerArtifact", DevelopmentArtifactIdentity.capture(ActualArrowPhysicsTests.class));
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
                return;
            }
            if (hurtEvents > 0) {
                helper.assertTrue(
                    flightSamples >= 3 && hurtEvents == 1
                        && target.getHealth() < 200
                        && bow.getShots() == 1
                        && weapon.getItemDamage() == 1
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0) == ammunition
                        && ammunition.stackSize == 7,
                    "Actual native collision or exactly-once finite payment failed");
                complete = true;
                report.addProperty("nativeDamageEvents", hurtEvents);
                report.addProperty("targetHealth", target.getHealth());
                report.addProperty("nativePhysicsSamples", flightSamples);
                report.addProperty("bowWear", weapon.getItemDamage());
                report.addProperty("remainingArrows", ammunition.stackSize);
                restoreProvider();
                return;
            }
            if (arrow.ticksExisted == previousAge) return;
            var next = state(arrow);
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
