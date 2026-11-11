package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.EnhancedCombatTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidServerLoadProbe;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 真实增强根的短容量校准和独立持续负载观测 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class UnifiedCombatCapacityTests {

    private UnifiedCombatCapacityTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 420, batch = "unified_combat_5_capacity")
    public static void nativeThirtyTwoCombatantsShareBoundedWorldWorkAndReactWithoutForecast(GameTestHelper h)
        throws Exception {
        capacity(h, false);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2100, batch = "unified_combat_7_sustained_capacity")
    public static void nativeThirtyTwoCombatantsSustainBoundedWorkAndReactWithoutForecast(GameTestHelper h)
        throws Exception {
        capacity(h, true);
    }

    private static void capacity(GameTestHelper h, boolean sustained) throws Exception {
        h.assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Explicit development mode required");
        h.assertTrue("1".equals(System.getProperty("horizonqa.turbo", "1")), "Native capacity requires turbo=1");
        var diagnosticScope = sustained ? null
            : com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatDiagnosticScope.open(h.getWorld());
        boolean previousTrace = com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.traceEnabled();
        boolean plannerTrace = sustained && Boolean.getBoolean("tlmnh.capacityTrace");
        if (plannerTrace) {
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.setTraceEnabled(true);
            h.afterTest(
                () -> com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.setTraceEnabled(previousTrace));
        }
        if (diagnosticScope != null) h.afterTest(diagnosticScope::close);
        var run = new Attempt(h, sustained);
        h.afterTest(() -> {
            try {
                run.close();
            } finally {
                if (diagnosticScope != null) diagnosticScope.close();
            }
        });
        try {
            run.prepare();
        } catch (Exception | Error failure) {
            run.failed("prepare", failure);
            throw failure;
        }
        h.onEachTick("native 0-32-0 combat capacity and actual-request fairness", () -> {
            try {
                run.tick();
            } catch (RuntimeException | Error failure) {
                run.failed("tick", failure);
                throw failure;
            }
        });
        h.succeedWhen(() -> run.complete);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "unified_combat_6_friend_coverage")
    public static void nativeKnownFriendOcclusionAndOverflowRemainUnknown(GameTestHelper h) {
        var f = new ModernCoreTests.Fixture(h, false, 100, 24, MaidBrainProfile.ENHANCED);
        var world = h.getWorld();
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.setPickup(false);
        f.maid.setHunger(100);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        var target = new EntityPig(world);
        target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(1000);
        target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        target.setHealth(1000);
        target.setPosition(f.x + 3.7, f.y + 1, f.z + 4.5);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
        h.assertTrue(world.spawnEntityInWorld(target), "Native friend scenario target join failed");
        List<EntityMaid> friends = new ArrayList<>();
        var known = friend(h, f, f.x + 1.5, f.z + 6.8);
        friends.add(known);
        h.afterTest(() -> {
            target.setDead();
            world.removePlayerEntityDangerously(target);
            for (EntityMaid maid : friends) {
                maid.setDead();
                world.removePlayerEntityDangerously(maid);
            }
        });
        int[] phase = { 0 }, diagnosticFrames = { 0 }, diagnosticPhase = { -1 };
        boolean[] diagnosticSeen = { false };
        long[] hiddenAt = { -1 };
        com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Friend[] original = { null };
        h.onEachTick("native visible friend then occlusion/movement and bounded identity overflow", () -> {
            var core = f.maid.getBrainCoordinator()
                .peekEnhancedCore();
            h.assertTrue(
                core != null && f.maid.getOwner() == f.owner && MaidSpawnChecks.present(f.owner),
                "Full native enhanced owner/root unavailable");
            var ready = core.perception()
                .combatReadiness();
            long now = world.getTotalWorldTime();
            com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Friend sample = null;
            if (ready != null) for (var value : ready.friendSamples) if (known.getUniqueID()
                .equals(value.id)) sample = value;
            boolean firstSample = sample != null && !diagnosticSeen[0];
            int age = f.maid.ticksExisted;
            if (diagnosticFrames[0] < 20 && (diagnosticPhase[0] != phase[0] || firstSample || age % 20 == 0)) {
                diagnosticFrames[0]++;
                diagnosticPhase[0] = phase[0];
                diagnosticSeen[0] |= sample != null;
                var combat = core.combat();
                var plan = combat.lastPlan();
                var selected = combat.target();
                var spawned = new StringBuilder();
                for (int index = 1; index < friends.size(); index++) {
                    EntityMaid actor = friends.get(index);
                    UUID id = actor.getUniqueID();
                    boolean cachedFriend = false;
                    if (ready != null)
                        for (var value : ready.friendSamples) if (id.equals(value.id)) cachedFriend = true;
                    var diagnostic = core.perception()
                        .combatSampleDiagnostic(id);
                    if (spawned.length() > 0) spawned.append(';');
                    spawned.append(index - 1)
                        .append("/id=")
                        .append(id)
                        .append("/entity=")
                        .append(actor.getEntityId())
                        .append("/alive=")
                        .append(actor.isEntityAlive())
                        .append("/dead=")
                        .append(actor.isDead)
                        .append("/pos=")
                        .append(actor.posX)
                        .append(',')
                        .append(actor.posY)
                        .append(',')
                        .append(actor.posZ)
                        .append("/tamed=")
                        .append(actor.isTamed())
                        .append("/owner=")
                        .append(actor.func_152113_b())
                        .append("/cachedFriend=")
                        .append(cachedFriend)
                        .append("/sample=")
                        .append(diagnostic == null ? "none" : diagnostic.result)
                        .append("/sampleTick=")
                        .append(diagnostic == null ? "none" : diagnostic.tick)
                        .append("/deferred=")
                        .append(diagnostic != null && diagnostic.deferred)
                        .append("/friendTracked=")
                        .append(diagnostic != null && diagnostic.friendTracked)
                        .append("/friendCurrent=")
                        .append(diagnostic != null && diagnostic.friendCurrent)
                        .append("/friendAt=")
                        .append(diagnostic == null ? "none" : diagnostic.friendObservedAt)
                        .append("/targetTracked=")
                        .append(diagnostic != null && diagnostic.targetTracked)
                        .append("/targetCurrent=")
                        .append(diagnostic != null && diagnostic.targetCurrent)
                        .append("/targetAt=")
                        .append(diagnostic == null ? "none" : diagnostic.targetObservedAt)
                        .append("/livingTracked=")
                        .append(diagnostic != null && diagnostic.livingTracked)
                        .append("/livingAt=")
                        .append(diagnostic == null ? "none" : diagnostic.livingObservedAt);
                }
                // 夹具仅测量自有actor字段与cached诊断，不把实际坐标传回AI或补生产查询
                System.out.println(
                    "TLMNH_COMBAT_FRIEND_TRACE phase=" + phase[0]
                        + " frame="
                        + diagnosticFrames[0]
                        + " age="
                        + age
                        + " worldTick="
                        + now
                        + " firstSample="
                        + firstSample
                        + " maid="
                        + f.maid.getUniqueID()
                        + " maidPos="
                        + f.maid.posX
                        + ","
                        + f.maid.posY
                        + ","
                        + f.maid.posZ
                        + " known="
                        + known.getUniqueID()
                        + " knownPos="
                        + known.posX
                        + ","
                        + known.posY
                        + ","
                        + known.posZ
                        + " cachedTick="
                        + (ready == null ? "none" : ready.tick)
                        + " sample="
                        + (sample == null ? "none"
                            : sample.id + "/entity="
                                + sample.entityId
                                + "/dim="
                                + sample.dimension
                                + "/at="
                                + sample.observedAt
                                + "/current="
                                + sample.current
                                + "/round="
                                + sample.coverageRound
                                + "/pos="
                                + sample.position.x
                                + ","
                                + sample.position.y
                                + ","
                                + sample.position.z)
                        + " friendCount="
                        + (ready == null ? "none" : ready.friendCount)
                        + " overflow="
                        + (ready != null && ready.friendOverflow)
                        + " oldestCoverage="
                        + (ready == null ? "none" : ready.oldestCoverage)
                        + " coverageRound="
                        + (ready == null ? "none" : ready.coverageRound)
                        + " preparationRound="
                        + (ready == null ? "none" : ready.preparationRound)
                        + " blockedMask="
                        + (ready == null ? "none" : ready.blockedMask)
                        + " selectedKind="
                        + core.selectedKind()
                        + " target="
                        + (selected == null ? "none" : selected.getEntityId())
                        + " macro="
                        + (plan == null ? "none" : plan.macro)
                        + " result="
                        + combat.lastResult()
                        + " spawnedFriends=["
                        + spawned
                        + "]");
            }
            if (ready == null) return;
            if (phase[0] == 0) {
                if (sample == null || !sample.current || sample.observedAt != now) return;
                original[0] = sample;
                hiddenAt[0] = now;
                for (int x = 1; x < 20; x++) for (int y = 1; y <= 4; y++) h.setBlock(x, y, 6, Blocks.stone);
                known.setPosition(f.x + 2.7, f.y + 1, f.z + 7.2);
                phase[0] = 1;
            } else if (phase[0] == 1 && now > hiddenAt[0] + 1) {
                h.assertTrue(
                    sample != null && sample.id.equals(original[0].id)
                        && sample.entityId == original[0].entityId
                        && sample.dimension == original[0].dimension,
                    "Occlusion silently discarded or replaced a known friend identity");
                h.assertTrue(
                    sample.observedAt == original[0].observedAt && sample.position.x == original[0].position.x
                        && sample.position.z == original[0].position.z,
                    "Hidden friend movement refreshed its historical position or timestamp");
                h.assertTrue(
                    !sample.current && !ready.friendsComplete
                        && (ready.blockedMask & EnhancedCombatTuning.FRIENDS_INCOMPLETE) != 0,
                    "Unresolved known friend was treated as safe");
                var snapshot = core.combat()
                    .lastSnapshot();
                if (snapshot != null && snapshot.tick == now)
                    h.assertFalse(snapshot.complete, "Full forecast accepted an unresolved friend");
                // 留足西侧玻璃墙余量，防止原生碰撞后首列进入墙体而变成隐藏演员
                for (int i = 0; i < 13; i++)
                    friends.add(friend(h, f, f.x + 2.3 + i % 5 * .95, f.z + 1.5 + i / 5 * 1.1));
                phase[0] = 2;
            } else if (phase[0] == 2 && ready.friendOverflow) {
                h.assertTrue(
                    ready.friendSamples.size() <= 12 && !ready.friendsComplete,
                    "Friend overflow exceeded output capacity or became safe");
                h.assertTrue(
                    (ready.blockedMask & EnhancedCombatTuning.FRIENDS_INCOMPLETE) != 0,
                    "Overflow omitted its UNKNOWN completeness reason");
                var snapshot = core.combat()
                    .lastSnapshot();
                if (snapshot != null && snapshot.tick == now)
                    h.assertFalse(snapshot.complete, "Overflow granted a complete forecast");
                System.out.println(
                    "TLMNH_UNIFIED209_FRIEND_UNKNOWN id=" + known.getUniqueID()
                        + " originalAt="
                        + original[0].observedAt
                        + " retainedAt="
                        + sample.observedAt
                        + " boundedFriends="
                        + ready.friendSamples.size()
                        + " overflow=true");
                phase[0] = 3;
            }
        });
        h.succeedWhen(() -> phase[0] == 3);
    }

    private static EntityMaid friend(GameTestHelper h, ModernCoreTests.Fixture f, double x, double z) {
        var maid = new EntityMaid(h.getWorld());
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Native friend owner bind rejected");
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setPosition(x, f.y + 1, z);
        h.assertTrue(
            h.getWorld()
                .spawnEntityInWorld(maid),
            "Native friend join failed");
        return maid;
    }

    public static final class Attempt {

        static final int COUNT = 32, FAIR_BOUND = 32, WORK_WINDOW = 200, SUSTAINED_SAMPLES = 1200;
        static final long SEED = 2093200L;
        final boolean sustained;
        final int baseline, warmup, samples, drain;
        final float targetHealth;
        final GameTestHelper h;
        final WorldServer world;
        final ModernCoreTests.Fixture f;
        final List<EntityMaid> maids = new ArrayList<>();
        final List<EntityPig> targets = new ArrayList<>();
        final List<int[]> floor = new ArrayList<>(), water = new ArrayList<>();
        final Map<UUID, Row> rows = new HashMap<>();
        final Set<Entity> targetIdentity = new HashSet<>();
        final JsonObject report = new JsonObject(), emergency = new JsonObject();
        JsonObject latestCachedDiagnostic;
        final JsonArray tickRows = new JsonArray();
        final JsonArray workWindows = new JsonArray();
        final long started;
        final String oldSpawning, oldDaylight;
        final String run = UUID.randomUUID()
            .toString();
        MaidServerLoadProbe probe;
        EntityMaid victim;
        long emergencyInput = Long.MIN_VALUE, emergencyReleases;
        int victimAge, age;
        boolean complete, closed, emergencyObserved, capturedFinal;

        Attempt(GameTestHelper h, boolean sustained) {
            this.h = h;
            this.sustained = sustained;
            baseline = warmup = drain = sustained ? WORK_WINDOW : 40;
            samples = sustained ? SUSTAINED_SAMPLES : 160;
            targetHealth = sustained ? 10000 : 1000;
            world = h.getWorld();
            h.assertTrue(
                MinecraftServer.getServer()
                    .getConfigurationManager().playerEntityList.isEmpty(),
                "Unrelated native player retained; isolated capacity run refused");
            h.assertTrue(
                world.loadedEntityList.stream()
                    .noneMatch(value -> value instanceof EntityMaid),
                "Existing unrelated maid retained; native zero baseline refused");
            f = new ModernCoreTests.Fixture(h, false, 16, 20, MaidBrainProfile.ENHANCED);
            f.maid.getBrainCoordinator()
                .setSuspended(true);
            f.maid.setDead();
            world.removePlayerEntityDangerously(f.maid);
            started = world.getTotalWorldTime();
            oldSpawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            oldDaylight = world.getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
        }

        void prepare() throws Exception {
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            world.setWorldTime(6000);
            for (int x = f.x + 28; x <= f.x + 93; x++) for (int z = f.z + 16; z <= f.z + 49; z++) {
                for (int y = f.y; y <= f.y + 5; y++) h.assertTrue(
                    world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null,
                    "Unknown capacity course input retained");
                h.assertTrue(world.setBlock(x, f.y, z, Blocks.iron_block, 0, 2), "Native iron floor rejected");
                floor.add(new int[] { x, f.y, z });
            }
            report.addProperty(
                "schema",
                sustained ? "unified-combat-sustained-capacity-v1" : "unified-combat-capacity-feasibility-v1");
            report.addProperty("run", run);
            report.addProperty(
                "scope",
                sustained
                    ? "One sustained native ENHANCED 0-32-0 load block; final admission requires the frozen multi-block contract"
                    : "One short native ENHANCED 0-32-0 run; no formal load admission or AI superiority");
            report.addProperty("seed", SEED);
            report.addProperty(
                "nativeEntityInputs",
                "32 exact EntityMaid/EntityPig; iron sword; per-maid WORK homes; pigs " + targetHealth
                    + "hp, zero speed, knockback resistance1; native gravity remains active");
            report.addProperty(
                "phaseTicks",
                baseline + " zero baseline / "
                    + warmup
                    + " combat warmup / "
                    + samples
                    + " combat samples / "
                    + drain
                    + " zero drain");
            report.addProperty("baselineTicks", baseline);
            report.addProperty("warmupTicks", warmup);
            report.addProperty("sampleTicks", samples);
            report.addProperty("nativeTargetInitialHealth", targetHealth);
            report.addProperty("drainTicks", drain);
            report.addProperty("compactTelemetry", sustained);
            report.addProperty("productionDiagnosticScopeEnabled", !sustained);
            report.addProperty("plannerTraceEnabled", sustained && Boolean.getBoolean("tlmnh.capacityTrace"));
            report.add("worldQuotaLimits", integers(CombatWorkQuota.worldLimits()));
            if (sustained) {
                report.addProperty("workWindowTicks", WORK_WINDOW);
                report.addProperty(
                    "workloadScope",
                    "Every original actor must have actual native accepted attacks and completed FULL decisions in each of six200-tick sampling windows; no healing or replacement");
                report.addProperty(
                    "observerScope",
                    "Every-tick quota and fairness checks, compact budget rows and six bounded actor work windows; production diagnostic logging is disabled; observer and isolated owner costs remain included");
            }
            report.addProperty("fairnessBoundTicks", FAIR_BOUND);
            report.addProperty(
                "fairnessScope",
                "Both actor-level continuously actualRequested wait<=32 and same requestSerial+pending wait<=32 until actual completed(); no preparation-expiry reset");
            report.addProperty(
                "rawActorContinuityScope",
                "maxContinuousRequestWait/maxGrantGap retain actor-level requested/granted history across real request recreation; actor wait<=32 remains a hard gate");
            if (!sustained) report.addProperty(
                "observerScope",
                "Bounded per-tick budget copies and32 aggregate actor rows included in CPU/allocation; native isolated owner/network fixture and32 bounded actor references retained through zero drain; heap growth is not an AI leak verdict");
            report.addProperty("formalLoadAccepted", false);
            report.addProperty("crossAiNonInferiorityClaimed", false);
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(EntityMaid.class, CombatWorldBudget.class));
            report.add("observerArtifact", DevelopmentArtifactIdentity.capture(UnifiedCombatCapacityTests.class));
            MinecraftForge.EVENT_BUS.register(this);
            probe = MaidServerLoadProbe
                .start(run, sustained ? "unified-combat-sustained-capacity" : "unified-combat-short-capacity", world);
            probe.phase("zero-before", 0, true);
        }

        void spawn() {
            for (int i = 0; i < COUNT; i++) {
                double x = f.x + 32.5 + i % 8 * 8, z = f.z + 20.5 + i / 8 * 8;
                var target = new EntityPig(world);
                target.func_110163_bv();
                target.getRNG()
                    .setSeed(SEED + 1000 + i);
                target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                    .setBaseValue(targetHealth);
                target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .setBaseValue(0);
                target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                    .setBaseValue(1);
                target.setHealth(targetHealth);
                h.assertTrue(
                    target.getMaxHealth() == targetHealth && target.getHealth() == targetHealth,
                    "Native pig high-health input was clamped or changed");
                target.setPosition(x + 2, f.y + 1, z);
                targets.add(target);
                targetIdentity.add(target);
                h.assertTrue(
                    world.spawnEntityInWorld(target) && MaidSpawnChecks.present(target),
                    "Native pig join failed");
                var maid = new EntityMaid(world);
                maid.getRNG()
                    .setSeed(SEED + i);
                h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Native owner binding failed");
                maid.setPosition(x, f.y + 1, z);
                maid.setTask(MaidTask.ATTACK);
                maid.setSchedule(MaidSchedule.ALL);
                maid.setHunger(100);
                maid.setPickup(false);
                maid.setSitting(false);
                maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
                maid.getAttackListData()
                    .attackGroups()
                    .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
                h.assertTrue(
                    maid.getHomeArea()
                        .setEnabled(true),
                    "Native home enable rejected");
                for (MaidLifeRules.Activity activity : MaidLifeRules.Activity.values()) h.assertTrue(
                    maid.getHomeArea()
                        .capture(activity),
                    "Per-maid home center rejected");
                maids.add(maid);
                rows.put(maid.getUniqueID(), new Row(i, maid));
                h.assertTrue(
                    world.spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid),
                    "Native maid join failed");
                h.assertTrue(
                    maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                        == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Actual ENHANCED selection rejected");
                h.assertTrue(
                    maid.getBrainCoordinator()
                        .peekEnhancedCore() != null,
                    "Native enhanced root was substituted");
                h.assertFalse(
                    maid.getBrainCoordinator()
                        .getTelemetry()
                        .isEnabled(),
                    "Event ledger must remain disabled");
            }
        }

        void tick() {
            if (complete) return;
            long now = world.getTotalWorldTime();
            h.assertTrue(MaidSpawnChecks.present(f.owner), "Native registered owner disappeared");
            if (age == baseline) {
                spawn();
                probe.phase("32-warmup", COUNT, false);
            }
            if (age == baseline + warmup) probe.phase("32-sampling", COUNT, true);
            observeBudget(now);
            if (sustained) observeSustainedWork(now);
            if (age >= baseline && age < baseline + warmup + samples) {
                for (EntityMaid maid : maids) h.assertTrue(
                    MaidSpawnChecks.present(maid) && maid.isEntityAlive()
                        && maid.getBrainCoordinator()
                            .peekPolicyRuntime()
                            .isReady(),
                    "Native actor/root disappeared or faulted");
                for (EntityPig target : targets) h.assertTrue(
                    MaidSpawnChecks.present(target) && target.isEntityAlive(),
                    "Finite native threat disappeared; test never heals/replaces targets");
                emergency(now);
            }
            if (age == baseline + warmup + samples) {
                captureActors();
                restoreWater();
                retire();
                probe.phase("zero-after-drain", 0, true);
            }
            if (age == baseline + warmup + samples + drain) {
                h.assertTrue(emergencyObserved, "Native low-air interruption never became ready");
                if (sustained) h.assertTrue(
                    workWindows.size() == samples / WORK_WINDOW,
                    "Sustained capacity did not retain all original work windows");
                for (Row row : rows.values()) h.assertTrue(
                    row.requestTicks > 0 && row.grants > 0 && row.accepted > 0 && row.samplingFullDecisions > 0,
                    "Native request/grant/accepted attack/actual sampling forecast not ready for actor " + row.index);
                h.assertTrue(
                    report.get("nativeTargetHealthLoss")
                        .getAsDouble() > 0,
                    "No independently observed native damage");
                h.assertTrue(
                    report.get("forecastReady")
                        .getAsBoolean(),
                    "Full grants never yielded a complete native forecast; inspect observation/geometry readiness diagnostics");
                h.assertTrue(
                    world.loadedEntityList.stream()
                        .noneMatch(value -> value instanceof EntityMaid),
                    "Zero drain still retained a native maid");
                var drained = CombatWorldBudget.diagnostics(world, now);
                h.assertTrue(
                    drained.requests == 0 && drained.preparations.length == 0 && drained.ready.length == 0,
                    "Zero drain retained combat request/preparation/ready state");
                probe.close();
                h.assertTrue(
                    probe.snapshot()
                        .get("valid")
                        .getAsBoolean(),
                    "Native load recorder became invalid");
                complete = true;
                write(true, null);
            }
            age++;
        }

        void observeBudget(long now) {
            var diagnostic = CombatWorldBudget.diagnostics(world, now);
            if (!sustained) latestCachedDiagnostic = CombatCachedDiagnostics.budget(world, maids, now, diagnostic);
            int[] limits = CombatWorkQuota.worldLimits();
            h.assertTrue(diagnostic.preparations.length <= 8, "More than8 distinct preparation identities in one tick");
            h.assertTrue(
                diagnostic.used[CombatWorkQuota.Work.ADVANCE.ordinal()] <= 1440,
                "World forecast advance cap exceeded");
            Set<UUID> requested = new HashSet<>(), granted = new HashSet<>(), renewed = new HashSet<>();
            java.util.Collections.addAll(requested, diagnostic.requested);
            java.util.Collections.addAll(granted, diagnostic.granted);
            java.util.Collections.addAll(renewed, diagnostic.renewed);
            // 同UUID的真实取消重建、已完成和false心跳占位不能拼成一个未完成请求
            for (Row row : rows.values()) if (!requested.contains(row.maid.getUniqueID())) {
                row.waitSince = -1;
                row.trackedRequestSerial = -1;
                row.pendingContinuousSince = -1;
            }
            var preparationRows = new JsonArray();
            for (var entry : diagnostic.entries) {
                Row row = rows.get(entry.id);
                if (row == null) continue;
                h.assertTrue(entry.sampledTicks <= 6, "Preparation lease exceeded6 actual sample ticks");
                boolean recreated = row.lastRequestSerial >= 0 && row.lastRequestSerial != entry.requestSerial;
                boolean completedNow = entry.completedAt != Long.MIN_VALUE
                    && (row.lastRequestSerial != entry.requestSerial
                        || row.lastRequestCompletedAt != entry.completedAt);
                if (recreated) row.requestRecreations++;
                if (completedNow) row.requestCompletions++;
                boolean sameTracked = row.trackedRequestSerial == entry.requestSerial && row.waitSince >= 0;
                if (requested.contains(entry.id) && sameTracked && (entry.pending || completedNow)) {
                    if (!completedNow) h.assertTrue(
                        entry.since == row.waitSince,
                        "Pending request changed first age for actor " + row.index
                            + " serial="
                            + entry.requestSerial
                            + " previous="
                            + row.waitSince
                            + " actual="
                            + entry.since
                            + " round="
                            + entry.preparationRound);
                    long wait = now - Math.max(row.pendingContinuousSince, row.waitSince);
                    row.maxPendingRequestWait = Math.max(row.maxPendingRequestWait, wait);
                    if (age >= baseline + warmup) h.assertTrue(
                        wait <= FAIR_BOUND,
                        "Continuously pending actual request starved beyond32 ticks: " + row.index
                            + " serial="
                            + entry.requestSerial
                            + " wait="
                            + wait);
                }
                if (requested.contains(entry.id) && entry.pending) {
                    if (!sameTracked || completedNow) {
                        row.trackedRequestSerial = entry.requestSerial;
                        row.waitSince = entry.since;
                        row.pendingContinuousSince = now;
                    }
                } else {
                    row.trackedRequestSerial = -1;
                    row.waitSince = -1;
                    row.pendingContinuousSince = -1;
                }
                row.lastRequestSerial = entry.requestSerial;
                row.lastRequestCompletedAt = entry.completedAt;
                row.lastPreparationRound = entry.preparationRound;
                if (entry.phase == CombatWorldBudget.Phase.PREPARING) row.preparationTicks++;
                if (entry.phase == CombatWorldBudget.Phase.READY) row.readyTicks++;
                if (!sustained) {
                    var out = new JsonObject();
                    out.addProperty("index", row.index);
                    out.addProperty("phase", entry.phase.name());
                    out.addProperty("actualRequested", requested.contains(entry.id));
                    out.addProperty("requestSerial", entry.requestSerial);
                    out.addProperty("pending", entry.pending);
                    out.addProperty("completedAt", entry.completedAt);
                    out.addProperty("requestRecreated", recreated);
                    out.addProperty("completionObserved", completedNow);
                    out.addProperty("since", entry.since);
                    out.addProperty("round", entry.preparationRound);
                    out.addProperty("startedAt", entry.startedAt);
                    out.addProperty("expiresAt", entry.expiresAt);
                    out.addProperty("sampledTicks", entry.sampledTicks);
                    out.addProperty("oldestCoverage", entry.oldestCoverage);
                    preparationRows.add(out);
                }
            }
            h.assertTrue(
                diagnostic.fullGrants <= 2 && diagnostic.granted.length <= 2,
                "World granted more than2 forecasts");
            for (int i = 0; i < limits.length; i++) h.assertTrue(
                diagnostic.used[i] >= 0 && diagnostic.used[i] <= limits[i],
                "World combat work exceeded " + CombatWorkQuota.Work.values()[i]);
            var readinessRows = new JsonArray();
            for (Row row : rows.values()) {
                UUID id = row.maid.getUniqueID();
                var actualCore = row.maid.getBrainCoordinator()
                    .peekEnhancedCore();
                long actualFull = actualCore == null ? row.lastFull
                    : actualCore.combat()
                        .fullDecisions();
                if (age >= baseline + warmup && age < baseline + warmup + samples)
                    row.samplingFullDecisions += Math.max(0, actualFull - row.lastFull);
                row.lastFull = actualFull;
                if (renewed.contains(id)) row.renewedTicks++;
                if (requested.contains(id)) {
                    row.requestTicks++;
                    if (row.continuousSince < 0) row.continuousSince = now;
                    if (row.firstRequest < 0) row.firstRequest = now;
                } else row.continuousSince = -1;
                if (!granted.contains(id)) {
                    var passiveCore = row.maid.getBrainCoordinator()
                        .peekEnhancedCore();
                    var passive = passiveCore == null ? null
                        : passiveCore.perception()
                            .combatReadiness();
                    if (passive != null && passive.tick == now) {
                        row.nonFullObservations++;
                        for (int bit = 0; bit < 12; bit++)
                            if ((passive.blockedMask & (1 << bit)) != 0) row.nonFullReadinessBlockers[bit]++;
                    }
                }
                if (granted.contains(id)) {
                    row.grants++;
                    if (row.firstGrant < 0) row.firstGrant = now;
                    if (row.lastGrant >= 0) row.maxGrantGap = Math.max(row.maxGrantGap, now - row.lastGrant);
                    row.lastGrant = now;
                    var core = row.maid.getBrainCoordinator()
                        .peekEnhancedCore();
                    var ready = core == null ? null
                        : core.perception()
                            .combatReadiness();
                    var snapshot = core == null ? null
                        : core.combat()
                            .lastSnapshot();
                    var plan = core == null ? null
                        : core.combat()
                            .lastPlan();
                    var observed = new JsonObject();
                    if (!sustained) observed.addProperty("index", row.index);
                    if (ready == null || ready.tick != now) {
                        row.grantedWithoutCurrentCapture++;
                        if (!sustained) {
                            observed.addProperty("currentCapture", false);
                            observed.addProperty("blockedMask", EnhancedCombatTuning.NO_CAPTURE);
                        }
                    } else {
                        boolean currentCapture = ready.captured && snapshot != null && snapshot.tick == now;
                        if (sustained && Boolean.getBoolean("tlmnh.capacityTrace") && currentCapture) {
                            row.lastFullSnapshot = snapshot;
                            row.lastFullPlan = plan;
                        }
                        if (currentCapture) row.fullCaptures++;
                        else row.grantedWithoutCurrentCapture++;
                        if (currentCapture && ready.blockedMask == 0) row.readyCaptures++;
                        if (ready.fullContinuation) row.fullContinuations++;
                        row.lastReadinessMask = ready.blockedMask;
                        for (int bit = 0; bit < 12; bit++)
                            if ((ready.blockedMask & (1 << bit)) != 0) row.readinessBlockers[bit]++;
                        int advances = plan != null && plan.observedAt == now
                            ? plan.quota.used(CombatWorkQuota.Work.ADVANCE)
                            : 0;
                        row.forecastAdvances += advances;
                        if (advances > 0) row.forecastStarted++;
                        if (!sustained) {
                            observed.addProperty("currentCapture", currentCapture);
                            observed.addProperty("blockedMask", ready.blockedMask);
                            observed.addProperty("snapshotComplete", currentCapture && snapshot.complete);
                            observed.addProperty("fullAtStart", ready.fullAtStart);
                            observed.addProperty("fullAtCapture", ready.fullAtCapture);
                            observed.addProperty("fullContinuation", ready.fullContinuation);
                            observed.addProperty("scanCycleTicks", ready.scanCycleTicks);
                            observed.addProperty("completedScanAge", ready.completedScanAge);
                            observed.addProperty("completedScanTicks", ready.completedScanTicks);
                            observed.addProperty("scopeReset", ready.scopeReset);
                            observed.addProperty("chunkCursor", ready.chunkCursor);
                            observed.addProperty("sectionCursor", ready.sectionCursor);
                            observed.addProperty("entityCursor", ready.entityCursor);
                            observed.addProperty("chunksVisited", ready.chunksVisited);
                            observed.addProperty("deferredEntities", ready.deferredEntities);
                            observed.addProperty("permanentObservationGap", ready.permanentObservationGap);
                            observed.addProperty("permanentProjectileGap", ready.permanentProjectileGap);
                            observed.addProperty("urgentPending", ready.urgentPending);
                            observed.addProperty("scanPending", ready.scanPending);
                            observed.addProperty("targets", ready.targetCount);
                            observed.addProperty("visibleTargets", ready.visibleTargets);
                            observed.addProperty("primaryRays", ready.primaryRays);
                            observed.addProperty("backgroundRays", ready.backgroundRays);
                            observed.addProperty("secondaryRays", ready.secondaryRays);
                            observed.addProperty("weapons", ready.weaponCount);
                            observed.addProperty("preparing", ready.preparing);
                            observed.addProperty("preparationReady", ready.preparationReady);
                            observed.addProperty("preparationRound", ready.preparationRound);
                            observed.addProperty("coverageRound", ready.coverageRound);
                            observed.addProperty("oldestCoverage", ready.oldestCoverage);
                            observed.addProperty("friendsComplete", ready.friendsComplete);
                            observed.addProperty("friendCount", ready.friendCount);
                            observed.addProperty("friendRays", ready.friendRays);
                            observed.addProperty("friendOverflow", ready.friendOverflow);
                            observed.addProperty("geometryAge", ready.geometryAge);
                            observed.addProperty("geometryUnknownCells", ready.unknownGeometryCells);
                            observed.addProperty("geometryBudgetCells", ready.budgetGeometryCells);
                            observed.addProperty("forecastAdvances", advances);
                            observed.add("observationDenials", integers(ready.observationDenials));
                            observed.add("denialsAtCapture", integers(ready.denials));
                            if (plan != null && plan.observedAt == now)
                                observed.add("denialsAfterDecision", integers(plan.quota.denials()));
                        }
                    }
                    if (!sustained) readinessRows.add(observed);
                }
                if (row.continuousSince >= 0) {
                    long wait = now - Math.max(row.continuousSince, row.lastGrant);
                    row.maxWait = Math.max(row.maxWait, wait);
                    if (age >= baseline + warmup) h.assertTrue(
                        wait <= FAIR_BOUND,
                        "Continuously actual FULL-requesting actor starved beyond32 ticks: " + row.index
                            + " wait="
                            + wait);
                }
            }
            int reportLimit = sustained ? baseline + warmup + samples + drain + 1 : 300;
            if (tickRows.size() >= reportLimit) throw new IllegalStateException("Bounded capacity report overflow");
            var tick = new JsonObject();
            tick.addProperty("age", age);
            tick.addProperty("worldTick", now);
            tick.addProperty("registeredRequests", diagnostic.requests);
            tick.addProperty("actualFullRequests", requested.size());
            tick.addProperty("renewals", renewed.size());
            tick.addProperty("fullGrants", diagnostic.fullGrants);
            var used = new JsonArray();
            for (int value : diagnostic.used) used.add(new com.google.gson.JsonPrimitive(value));
            tick.add("used", used);
            if (!sustained) tick.add("grantedReadiness", readinessRows);
            tick.addProperty("preparationIdentities", diagnostic.preparations.length);
            tick.addProperty("readyRequests", diagnostic.ready.length);
            if (!sustained) {
                tick.add("preparationStates", preparationRows);
                tick.add("cachedDiagnostics", latestCachedDiagnostic);
            }
            tickRows.add(tick);
            if (age >= baseline + warmup + 40 && age < baseline + warmup + 80 && victim == null)
                for (UUID id : diagnostic.granted) {
                    Row row = rows.get(id);
                    if (row != null && row.maid.onGround && row.maid.getAir() >= 100) {
                        victim = row.maid;
                        break;
                    }
                }
        }

        void observeSustainedWork(long now) {
            int elapsed = age - baseline - warmup;
            if (elapsed < 0 || elapsed > samples || elapsed % WORK_WINDOW != 0) return;
            var actors = new JsonArray();
            JsonObject window = null;
            if (elapsed > 0) {
                window = new JsonObject();
                window.addProperty("endSamplingTick", elapsed);
                window.addProperty("worldTick", now);
                window.addProperty("complete", false);
                window.add("actors", actors);
                workWindows.add(window);
            }
            int firstMissing = -1;
            for (Row row : rows.values()) {
                var core = row.maid.getBrainCoordinator()
                    .peekEnhancedCore();
                h.assertTrue(core != null, "Sustained actor lost its enhanced root");
                long accepted = core.actor()
                    .meleeNativeAccepted(),
                    full = core.combat()
                        .fullDecisions();
                if (elapsed > 0) {
                    long attacks = accepted - row.windowAcceptedBefore, decisions = full - row.windowFullBefore;
                    var actor = new JsonObject();
                    actor.addProperty("index", row.index);
                    actor.addProperty("nativeAcceptedAttacks", attacks);
                    actor.addProperty("fullDecisions", decisions);
                    actors.add(actor);
                    if ((attacks <= 0 || decisions <= 0) && firstMissing < 0) firstMissing = row.index;
                }
                row.windowAcceptedBefore = accepted;
                row.windowFullBefore = full;
            }
            h.assertTrue(
                firstMissing < 0,
                "Sustained work missing for original actor " + firstMissing + " at sampling tick " + elapsed);
            if (window != null) window.addProperty("complete", true);
        }

        static JsonArray integers(int[] values) {
            var result = new JsonArray();
            for (int value : values) result.add(new com.google.gson.JsonPrimitive(value));
            return result;
        }

        void emergency(long now) {
            if (victim == null) return;
            if (emergencyInput == Long.MIN_VALUE) {
                emergencyInput = now;
                victimAge = victim.ticksExisted;
                emergencyReleases = victim.getBrainCoordinator()
                    .peekEnhancedCore()
                    .breathing()
                    .emergencyReleases();
                int x = MathHelper.floor_double(victim.posX), y = MathHelper.floor_double(victim.boundingBox.minY),
                    z = MathHelper.floor_double(victim.posZ);
                for (int py = y; py <= y + 1; py++) {
                    h.assertTrue(world.isAirBlock(x, py, z), "Unknown low-air source cell retained");
                    h.assertTrue(world.setBlock(x, py, z, Blocks.water, 0, 2), "Native water input rejected");
                    water.add(new int[] { x, py, z });
                }
                victim.setSitting(true);
                victim.setAir(20);
                emergency.addProperty(
                    "maid",
                    victim.getUniqueID()
                        .toString());
                emergency.addProperty("inputWorldTick", now);
                emergency.addProperty(
                    "source",
                    "Native source water at body/eyes, air20 and player sitting command; no controller call");
            } else if (!emergencyObserved && victim.ticksExisted > victimAge) {
                var core = victim.getBrainCoordinator()
                    .peekEnhancedCore();
                boolean granted = false;
                for (UUID id : CombatWorldBudget.diagnostics(world, now).granted)
                    granted |= id.equals(victim.getUniqueID());
                h.assertTrue(
                    core.selectedKind() == Kind.BREATHE && !victim.isSitting()
                        && core.breathing()
                            .emergencyReleases() > emergencyReleases,
                    "Native low-air priority did not cancel combat and release sitting on its next frame");
                h.assertFalse(
                    granted,
                    "Low-air sample coincided with FULL; no-budget emergency evidence not established");
                emergencyObserved = true;
                emergency.addProperty("reactionWorldTick", now);
                emergency.addProperty("reactionHadFullGrant", false);
                emergency.addProperty(
                    "selected",
                    core.selectedKind()
                        .name());
                restoreWater();
                victim.setAir(300);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void hurt(LivingHurtEvent event) {
            if (!targetIdentity.contains(event.entityLiving) || !(event.source.getEntity() instanceof EntityMaid maid))
                return;
            Row row = rows.get(maid.getUniqueID());
            if (row != null) {
                row.hurtEvents++;
                row.provisionalDamage += event.ammount;
            }
        }

        void captureActors() {
            if (capturedFinal) return;
            capturedFinal = true;
            var actors = new JsonArray();
            long full = 0;
            for (Row row : rows.values()) {
                var core = row.maid.getBrainCoordinator()
                    .peekEnhancedCore();
                if (core == null) {
                    var missing = row.json();
                    missing.addProperty("rootReady", false);
                    actors.add(missing);
                    continue;
                }
                row.accepted = core.actor()
                    .meleeNativeAccepted();
                full += core.combat()
                    .fullDecisions();
                var out = row.json();
                out.addProperty("rootReady", true);
                out.addProperty(
                    "fullDecisions",
                    core.combat()
                        .fullDecisions());
                out.addProperty(
                    "lightDecisions",
                    core.combat()
                        .lightDecisions());
                CombatPlan plan = core.combat()
                    .lastPlan();
                out.addProperty("lastPlan", plan == null ? "none" : plan.macro.name());
                out.addProperty("lastPlanReason", plan == null ? "none" : plan.reason);
                if (sustained) {
                    var context = new JsonObject();
                    context.add("nativePosition", position(row.maid.posX, row.maid.posY, row.maid.posZ));
                    context.add("nativeVelocity", position(row.maid.motionX, row.maid.motionY, row.maid.motionZ));
                    context.addProperty("onGround", row.maid.onGround);
                    context.addProperty("air", row.maid.getAir());
                    context.addProperty("health", row.maid.getHealth());
                    context.addProperty("hunger", row.maid.getHunger());
                    context.addProperty("sitting", row.maid.isSitting());
                    var held = row.maid.getHeldItem();
                    context.addProperty(
                        "held",
                        held == null ? "none"
                            : held.getItem()
                                .getUnlocalizedName());
                    context.addProperty("heldDamage", held == null ? 0 : held.getItemDamage());
                    if (plan != null) {
                        context.addProperty("planTarget", String.valueOf(plan.targetId));
                        context.addProperty("planObservedAt", plan.observedAt);
                        context.addProperty("planExpiresAt", plan.expiresAt);
                        context.addProperty("stageState", String.valueOf(plan.stageState));
                        context.addProperty("stageReason", plan.stageReason);
                        context.addProperty("stagePhase", plan.stagePhase);
                        context.addProperty("planFamily", String.valueOf(plan.family));
                        context.addProperty("planStrategy", String.valueOf(plan.strategy));
                        context.addProperty("stageRetryAt", plan.stageRetryAt);
                        if (plan.destination != null) context.add("destination", position(plan.destination));
                    }
                    var snapshot = core.combat()
                        .lastSnapshot();
                    if (snapshot != null) context.add("cachedSnapshot", cachedContext(snapshot));
                    if (row.lastFullSnapshot != null) {
                        context.add("lastFullSnapshot", cachedContext(row.lastFullSnapshot));
                        var json = new GsonBuilder().serializeSpecialFloatingPointValues()
                            .create();
                        context.add("lastFullPlan", json.toJsonTree(row.lastFullPlan));
                    }
                    out.add("terminalContext", context);
                }
                out.addProperty(
                    "lastSelected",
                    core.selectedKind() == null ? "none"
                        : core.selectedKind()
                            .name());
                actors.add(out);
            }
            double loss = 0;
            var nativeTargets = new JsonArray();
            for (int i = 0; i < targets.size(); i++) {
                var target = targets.get(i);
                loss += targetHealth - target.getHealth();
                if (sustained) {
                    var out = new JsonObject();
                    out.addProperty("index", i);
                    out.addProperty(
                        "uuid",
                        target.getUniqueID()
                            .toString());
                    out.addProperty("health", target.getHealth());
                    out.add("position", position(target.posX, target.posY, target.posZ));
                    nativeTargets.add(out);
                }
            }
            if (sustained) {
                report.add("terminalNativeTargets", nativeTargets);
                report.addProperty(
                    "terminalContextScope",
                    "Once before retirement or on failure; native entity fields and cached plan/snapshot only; no world query, new capture, admission or decision mutation");
            }
            report.add("actors", actors);
            report.addProperty("nativeTargetHealthLoss", loss);
            report.addProperty("actualFullDecisions", full);
            report.addProperty("forecastReady", full > 0);
            report.addProperty(
                "readinessMaskSchema",
                "1=geometry_missing,2=geometry_unknown,4=geometry_stale,8=weapons_partial,16=scan_incomplete,32=scan_stale,64=projectile_incomplete,128=observation_incomplete,256=no_snapshot_capture,512=unresolved_observation_budget_denial,1024=friends_incomplete,2048=current_selected_target_incomplete");
            report.addProperty(
                "readinessObserverScope",
                "Only cached capture fields and quota counters; no actor admission, world query, queue renewal or decision mutation");
            report.addProperty(
                "forecastReadinessDiagnostic",
                full > 0 ? "actual complete forecasts observed"
                    : "no complete forecast observed; grants do not establish usable geometry/observations");
        }

        static JsonArray position(double x, double y, double z) {
            var out = new JsonArray();
            for (double value : new double[] { x, y, z }) out.add(new com.google.gson.JsonPrimitive(value));
            return out;
        }

        static JsonArray position(CombatSnapshot.Vec value) {
            return position(value.x, value.y, value.z);
        }

        static JsonObject cachedContext(CombatSnapshot snapshot) {
            var out = new JsonObject();
            out.addProperty("tick", snapshot.tick);
            out.addProperty("complete", snapshot.complete);
            out.addProperty("bodyComplete", snapshot.bodyComplete);
            out.addProperty("friendsComplete", snapshot.friendsComplete);
            out.addProperty("selectedTarget", String.valueOf(snapshot.selectedTarget));
            out.addProperty("targetCount", snapshot.targets.size());
            out.addProperty("friendCount", snapshot.friends.size());
            if (snapshot.self != null) {
                out.add("selfPosition", position(snapshot.self.position));
                out.addProperty("cadenceRemainingTicks", snapshot.self.cadenceRemainingTicks);
                out.addProperty("equipped", snapshot.self.equipped);
                out.addProperty("grounded", snapshot.self.grounded);
                out.addProperty("reach", snapshot.self.reach);
            }
            var targets = new JsonArray();
            for (int i = 0; i < snapshot.targets.size() && i < 32; i++) {
                var target = snapshot.targets.get(i);
                var row = new JsonObject();
                row.addProperty("uuid", String.valueOf(target.id));
                row.addProperty("observedAt", target.observedAt);
                row.addProperty("visible", target.visible);
                row.addProperty("health", target.health);
                row.add("position", position(target.position));
                targets.add(row);
            }
            out.add("targets", targets);
            var friends = new JsonArray();
            for (int i = 0; i < snapshot.friends.size() && i < 64; i++) {
                var friend = snapshot.friends.get(i);
                var row = new JsonObject();
                row.addProperty("uuid", String.valueOf(friend.id));
                row.addProperty("observedAt", friend.observedAt);
                row.addProperty("current", friend.current);
                row.add("position", position(friend.position));
                friends.add(row);
            }
            out.add("friends", friends);
            return out;
        }

        void retire() {
            for (EntityMaid maid : maids) {
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.setDead();
                world.removePlayerEntityDangerously(maid);
            }
            for (EntityPig target : targets) {
                target.setDead();
                world.removePlayerEntityDangerously(target);
            }
            maids.clear();
            targets.clear();
            targetIdentity.clear();
        }

        void restoreWater() {
            for (int[] p : water)
                if (world.getBlock(p[0], p[1], p[2]) == Blocks.water && world.getBlockMetadata(p[0], p[1], p[2]) == 0)
                    world.setBlock(p[0], p[1], p[2], Blocks.air, 0, 2);
            water.clear();
        }

        void failed(String phase, Throwable failure) {
            report.addProperty("failurePhase", phase);
            write(
                false,
                failure.getClass()
                    .getName() + ": "
                    + failure.getMessage());
        }

        void write(boolean passed, String fault) {
            if (sustained) {
                long now = world.getTotalWorldTime();
                latestCachedDiagnostic = CombatCachedDiagnostics
                    .budget(world, maids, now, CombatWorldBudget.diagnostics(world, now));
                report.add("workWindows", workWindows);
            }
            report.add("latestCachedDiagnostic", latestCachedDiagnostic);
            captureActors();
            report.addProperty("passed", passed);
            report.addProperty("worldTicksElapsed", world.getTotalWorldTime() - started);
            if (fault != null) report.addProperty("failure", fault);
            report.add("ticks", tickRows);
            report.add("emergency", emergency);
            if (probe != null) report.add("metrics", probe.snapshot());
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), "unified-combat-capacity.json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain bounded capacity evidence", failure);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            if (!complete) write(
                false,
                report.has("failure") ? report.get("failure")
                    .getAsString() : "Test ended before successful native drain");
            if (probe != null) probe.close();
            MinecraftForge.EVENT_BUS.unregister(this);
            restoreWater();
            retire();
            for (int[] p : floor) if (world.getBlock(p[0], p[1], p[2]) == Blocks.iron_block
                && world.getBlockMetadata(p[0], p[1], p[2]) == 0
                && world.getTileEntity(p[0], p[1], p[2]) == null) world.setBlock(p[0], p[1], p[2], Blocks.air, 0, 2);
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", oldSpawning);
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", oldDaylight);
        }
    }

    static final class Row {

        final int index;
        final EntityMaid maid;
        long requestTicks, renewedTicks, grants, accepted, hurtEvents, firstRequest = -1, firstGrant = -1,
            lastGrant = -1, continuousSince = -1, maxWait, maxGrantGap;
        long grantedWithoutCurrentCapture, fullCaptures, readyCaptures, fullContinuations, forecastStarted,
            forecastAdvances, nonFullObservations;
        int lastReadinessMask;
        long lastFull, samplingFullDecisions, preparationTicks, readyTicks, waitSince = -1, lastPreparationRound;
        long trackedRequestSerial = -1, lastRequestSerial = -1, lastRequestCompletedAt = Long.MIN_VALUE;
        long pendingContinuousSince = -1, maxPendingRequestWait, requestRecreations, requestCompletions;
        long windowAcceptedBefore, windowFullBefore;
        CombatSnapshot lastFullSnapshot;
        CombatPlan lastFullPlan;
        final int[] readinessBlockers = new int[12], nonFullReadinessBlockers = new int[12];
        double provisionalDamage;

        Row(int index, EntityMaid maid) {
            this.index = index;
            this.maid = maid;
        }

        JsonObject json() {
            var out = new JsonObject();
            out.addProperty("index", index);
            out.addProperty(
                "uuid",
                maid.getUniqueID()
                    .toString());
            out.addProperty("actualRequestTicks", requestTicks);
            out.addProperty("renewalTicks", renewedTicks);
            out.addProperty("grants", grants);
            out.addProperty("firstRequest", firstRequest);
            out.addProperty("firstGrant", firstGrant);
            out.addProperty("maxContinuousRequestWait", maxWait);
            out.addProperty("maxPendingRequestWait", maxPendingRequestWait);
            out.addProperty("lastRequestSerial", lastRequestSerial);
            out.addProperty("lastRequestCompletedAt", lastRequestCompletedAt);
            out.addProperty("requestRecreations", requestRecreations);
            out.addProperty("requestCompletions", requestCompletions);
            out.addProperty("maxGrantGap", maxGrantGap);
            out.addProperty("nativeAcceptedAttacks", accepted);
            out.addProperty("provisionalHurtEvents", hurtEvents);
            out.addProperty("provisionalDamage", provisionalDamage);
            out.addProperty("grantedWithoutCurrentCapture", grantedWithoutCurrentCapture);
            out.addProperty("fullCaptures", fullCaptures);
            out.addProperty("readyCaptures", readyCaptures);
            out.addProperty("fullContinuations", fullContinuations);
            out.addProperty("forecastStarted", forecastStarted);
            out.addProperty("forecastAdvances", forecastAdvances);
            out.addProperty("lastReadinessMask", lastReadinessMask);
            out.addProperty("samplingFullDecisions", samplingFullDecisions);
            out.addProperty("preparationTicks", preparationTicks);
            out.addProperty("readyTicks", readyTicks);
            out.addProperty("preparationRound", lastPreparationRound);
            out.add("readinessBlockers", Attempt.integers(readinessBlockers));
            out.addProperty("nonFullObservations", nonFullObservations);
            out.add("nonFullReadinessBlockers", Attempt.integers(nonFullReadinessBlockers));
            return out;
        }
    }
}
