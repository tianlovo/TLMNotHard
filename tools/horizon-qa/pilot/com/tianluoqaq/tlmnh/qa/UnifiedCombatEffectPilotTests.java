package com.tianluoqaq.tlmnh.qa;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.CombatTracker;
import net.minecraft.util.DamageSource;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.config.MaidChatConfig;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.development.DeterministicMaidSpawn;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession.Arm;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidServerLoadProbe;
import com.tianluoqaq.tlmnh.common.development.telemetry.NativeCombatLedgerReader;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 首seed小试点，保存故障、重复性、真实效果与成本，不作正式统计不劣判定 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "battlegear2", "etfuturum" })
public final class UnifiedCombatEffectPilotTests {

    private static final TacticsInputs TACTICS = TacticsInputs.open();
    private static final long SEED = TACTICS == null ? 0x20901L : TACTICS.seed;
    private static final int OWNER_ENCHANTMENT_SEED = (int) (SEED + 1);
    private static final int WARMUP = 40, ACTIVE_TICKS = 400;
    private static final int TARGET_PREPARATION_LIMIT = 8, TARGET_STABLE_SAMPLES = 2;
    private static final int COVER_X = 8;
    private static final String PAYLOAD = "QACombatEffectPayload";
    static final String IDENTITY_REVISION = "public-maid-identity-phase-v1";
    static final String IDENTITY_DESIGN = "048c26d420338223e39a4a805d59c938159077d102692fd155c9fd715e9f0aef";
    static final String IDENTITY_LEDGER = "068a9e611ca3e7df2f13e22cfe77c42edcf4373222973c29153c6231a9c8550c";
    private static final Field FAULT_LEDGER = ReflectionHelper
        .findField(CombatTracker.class, "combatEntries", "field_94556_a");

    private UnifiedCombatEffectPilotTests() {}

    /** 只供本夹具准备主人输入，固定已核实的提供方公开API，不读写私有seed字段 */
    private static final class OwnerEnchantmentSeed {

        static final Method GET_STORAGE, GET_SEED, SET_SEED;

        static {
            try {
                Class<?> provider = Class.forName("ganymedes01.etfuturum.storage.EtFuturumPlayer");
                GET_STORAGE = provider.getMethod("get", EntityPlayer.class);
                GET_SEED = provider.getMethod("getEnchantmentSeed");
                SET_SEED = provider.getMethod("setEnchantmentSeed", int.class);
                require(
                    GET_STORAGE.getReturnType() == provider && GET_SEED.getReturnType() == int.class
                        && SET_SEED.getReturnType() == void.class,
                    "Native owner enchantment-seed API changed");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        static Object storage(EntityPlayerMP owner) throws ReflectiveOperationException {
            Object existing = owner.getExtendedProperties("etfuturum");
            require(
                GET_STORAGE.getDeclaringClass()
                    .isInstance(existing),
                "Native owner enchantment storage missing");
            Object actual = GET_STORAGE.invoke(null, owner);
            require(actual == existing, "Native owner enchantment storage identity changed");
            return actual;
        }

        static int freeze(EntityPlayerMP owner) {
            try {
                Object actual = storage(owner);
                int before = (Integer) GET_SEED.invoke(actual);
                SET_SEED.invoke(actual, OWNER_ENCHANTMENT_SEED);
                require(
                    (Integer) GET_SEED.invoke(actual) == OWNER_ENCHANTMENT_SEED,
                    "Native owner enchantment seed did not freeze");
                return before;
            } catch (ReflectiveOperationException failure) {
                throw failed(failure);
            }
        }

        static int read(EntityPlayerMP owner) {
            try {
                return (Integer) GET_SEED.invoke(storage(owner));
            } catch (ReflectiveOperationException failure) {
                throw failed(failure);
            }
        }

        private static IllegalStateException failed(ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
            IntegrationGuard.throwIfFatal(cause);
            if (cause instanceof Error error) throw error;
            return new IllegalStateException("Native owner enchantment-seed API failed", cause);
        }
    }

    @GameTest(
        template = "modern_core_cell",
        timeoutTicks = EffectSaveBoundary.TIMEOUT,
        batch = "unified_combat_effect_seed1_serial")
    public static void firstSeedCanariesAndTwelvePairedNativeRuns(GameTestHelper h) throws Exception {
        require(TACTICS == null, "Original fixed first-seed selector cannot accept tactics inputs");
        begin(h);
    }

    @GameTest(
        template = "modern_core_cell",
        timeoutTicks = EffectSaveBoundary.TIMEOUT,
        batch = "tactics_cross_seed_serial")
    public static void crossSeedSamePhysicsThreeTacticalArms(GameTestHelper h) throws Exception {
        require(TACTICS != null, "Explicit frozen tactics block inputs required");
        begin(h);
    }

    private static void begin(GameTestHelper h) throws Exception {
        var methodCost = new EffectSaveBoundary.MethodCost();
        Runner runner;
        try {
            runner = new Runner(h, methodCost);
        } catch (Exception | Error failure) {
            methodCost.close();
            throw failure;
        }
        h.afterTest(runner::close);
        runner.start();
        h.onEachTick("serial first-seed combat effect inputs, natural actors and passive costs", () -> {
            try {
                runner.tick();
            } catch (RuntimeException | Error failure) {
                runner.failed(failure);
                throw failure;
            }
        });
        h.succeedWhen(() -> runner.finished);
    }

    private static final class Runner {

        final GameTestHelper h;
        final Thread ownerThread = Thread.currentThread();
        final WorldServer world;
        final MinecraftServer server = MinecraftServer.getServer();
        final ThreadMXBean clock = ManagementFactory.getThreadMXBean();
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final Map<String, NBTTagCompound> firstInput = new HashMap<>();
        final Map<String, JsonObject> firstRepeat = new HashMap<>();
        final Map<String, JsonArray> firstRandomInput = new HashMap<>();
        final Map<String, String> firstWeapon = new HashMap<>();
        final JsonArray completed = new JsonArray();
        final JsonObject summary = new JsonObject();
        final JsonArray emptyBoundaries = new JsonArray();
        final Events events = new Events(this);
        final EffectEmptyWorldFixture emptyWorld = EffectEmptyWorldFixture.enabled() ? EffectEmptyWorldFixture.current()
            : null;
        final EffectSaveBoundary saveWindow;
        final String[] originalSpecs = { "0:A:0", "0:B:0", "0:C:0", "1:B:0", "1:C:0", "1:A:0", "0:C:1", "0:B:1",
            "0:A:1", "1:A:1", "1:C:1", "1:B:1" };
        final String[] specs = TACTICS == null ? originalSpecs : TACTICS.specs;
        final Path output;
        final int x, y, z;
        final int oldWorkRange = MaidHomeConfig.workRange, oldNonHomeRange = MaidHomeConfig.nonHomeRange;
        final boolean animals, gift, rain, thunder;
        final long day;
        final int rainTime, thunderTime;
        final EnumDifficulty difficulty;
        final Map<String, String> rules = new HashMap<>();
        Trial trial;
        int next = -6, drain;
        long normalTargetLaunches, normalMaidLaunches, normalMaidPreviews;
        Throwable asynchronousFailure;
        boolean finished, closed, registered, preparationSealed, boundarySealed;
        boolean previousCleanupSucceeded = true, activeRingRefused;
        JsonObject headingA;

        Runner(GameTestHelper h, EffectSaveBoundary.MethodCost methodCost) throws Exception {
            this.h = h;
            world = h.getWorld();
            var origin = h.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            String hash = System.getProperty("tlmnh.qaEffectContractSha256", "");
            require(
                Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaCombatEffectPilot")
                    && EffectArrowRandomInput.enabled()
                    && "1".equals(System.getProperty("horizonqa.turbo", "1")),
                "Explicit effect switches, frozen contract SHA-256 and turbo1 required");
            String directory = System.getProperty("horizonqa.reportDir", "");
            require(!directory.isEmpty(), "Dedicated HQA report directory required");
            output = Paths.get(directory);
            Files.createDirectories(output);
            animals = server.getCanSpawnAnimals();
            gift = MaidAcquisitionConfig.giveInitialSlab;
            rain = world.getWorldInfo()
                .isRaining();
            thunder = world.getWorldInfo()
                .isThundering();
            rainTime = world.getWorldInfo()
                .getRainTime();
            thunderTime = world.getWorldInfo()
                .getThunderTime();
            day = world.getWorldTime();
            difficulty = world.difficultySetting;
            for (String rule : new String[] { "doMobSpawning", "doDaylightCycle", "naturalRegeneration" }) rules.put(
                rule,
                world.getGameRules()
                    .getGameRuleStringValue(rule));
            summary.addProperty("schema", "unified-combat-effect-pilot-v1");
            summary.addProperty("contractSha256", hash);
            summary.addProperty("inputPreparationRevision", "native-end-initialization-v2");
            summary.addProperty("nativeLedgerRevision", "strict-native-timeout-v3");
            summary.addProperty("ownerInputRevision", "native-enchantment-seed-v4");
            summary.addProperty("arrowInputRevision", "certified-construction-input-v6");
            summary.add("publicMaidIdentityInputs", IdentityInputs.preflight(specs));
            summary.addProperty("qaEffectArrowRandomInput", true);
            try {
                summary.add("arrowInputCapabilities", EffectArrowRandomInput.capabilities());
            } catch (Exception | LinkageError unsupported) {
                summary.addProperty("completed", false);
                summary.addProperty("methodAdmission", "NOT_REPRODUCIBLE");
                summary.addProperty("failure", unsupported.toString());
                var causes = new JsonArray();
                Throwable cause = unsupported;
                for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause())
                    causes.add(new com.google.gson.JsonPrimitive(cause.toString()));
                summary.add("methodFailureCauses", causes);
                summary.add("wholeMethodCosts", methodCost.snapshot());
                write("v6-method-preflight-failure", summary);
                throw unsupported;
            }
            summary.add("emptyRingBoundaries", emptyBoundaries);
            summary.addProperty("seed", SEED);
            if (TACTICS != null) {
                summary.addProperty("schema", "tlmnh-tactics-block-capture-v1");
                summary.add("tacticsInputs", TACTICS.input);
                summary.addProperty("tacticsInputSha256", TACTICS.inputSha);
                summary.addProperty("tacticsContractSha256", TACTICS.contractSha);
            }
            summary.addProperty("statisticalAdmission", false);
            summary.add("noninferiorityDelta", JsonNull.INSTANCE);
            summary.addProperty(
                "scope",
                "One seed, two terrains, three decisions, two reconstructions; not real-client or benchmark admission");
            summary.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(EnhancedCombatController.class, CombatEffectPilotSession.class));
            summary.add("observerArtifact", DevelopmentArtifactIdentity.capture(UnifiedCombatEffectPilotTests.class));
            summary.addProperty(
                "rngCoverage",
                "Approved v6 exact native arrow Random input plus public world/living RNG; other-mod RNG unproven, exact repetition gate mandatory");
            saveWindow = new EffectSaveBoundary(server, world, emptyWorld, methodCost);
            summary.add("saveBoundaryProtocol", saveWindow.snapshot());
        }

        void start() throws Exception {
            server.setCanSpawnAnimals(true);
            MaidAcquisitionConfig.giveInitialSlab = false;
            MaidHomeConfig.workRange = 12;
            MaidHomeConfig.nonHomeRange = 20;
            for (String rule : rules.keySet()) world.getGameRules()
                .setOrCreateGameRule(rule, "false");
            if (TACTICS != null) {
                require(world.getSeed() == 207710291837918000L, "Actual controlled world seed differs");
                summary.addProperty("actualWorldSeed", Long.toString(world.getSeed()));
                summary.addProperty(
                    "actualNaturalRegeneration",
                    world.getGameRules()
                        .getGameRuleBooleanValue("naturalRegeneration"));
                require(
                    !world.getGameRules()
                        .getGameRuleBooleanValue("naturalRegeneration"),
                    "Actual nutrition-neutral world rule differs");
            }
            world.setWorldTime(6000);
            world.difficultySetting = EnumDifficulty.HARD;
            world.getWorldInfo()
                .setRaining(false);
            world.getWorldInfo()
                .setThundering(false);
            world.getWorldInfo()
                .setRainTime(24000);
            world.getWorldInfo()
                .setThunderTime(24000);
            if (emptyWorld != null) {
                require(
                    emptyWorld.mode()
                        .equals("RUN"),
                    "Mechanism N/P cannot enter original V6 actor runner");
                emptyWorld.preparing(world, entity -> trial != null && trial.ownsObserved(entity));
                emptyWorld.prepareArea(chunks, x - 80, z - 80, x + 100, z + 90);
            } else chunks.forceChunksStrict(world, x - 80, 0, z - 80, x + 100, 255, z + 90);
            MinecraftForge.EVENT_BUS.register(events);
            FMLCommonHandler.instance()
                .bus()
                .register(events);
            registered = true;
        }

        void tick() {
            if (asynchronousFailure != null)
                throw new IllegalStateException("Native observer failed", asynchronousFailure);
            if (finished) return;
            saveWindow.end();
            if (emptyWorld != null) {
                emptyWorld.checkpoint();
                if (!preparationSealed) {
                    if (!emptyWorld.ready()) return;
                    preparationSealed = true;
                    summary.add("emptyWorldInitialSeal", emptyWorld.snapshot());
                }
            }
            if (drain > 0) {
                drain--;
                return;
            }
            if (trial == null) {
                if (!boundarySealed) {
                    if (emptyWorld != null && next > -6) emptyWorld.emptyAgain();
                    saveWindow.returnedToBoundary();
                    boundarySealed = true;
                }
                if (next == 0) for (String canary : new String[] { "methodHeadingCanaryDetected",
                    "methodOwnershipCanaryDetected", "methodEmptyRingCanaryDetected", "initialNbtCanaryDetected",
                    "nativeLedgerUnexpectedClearCanaryDetected", "wait80CanaryDetected" })
                    require(
                        summary.has(canary) && summary.get(canary)
                            .getAsBoolean(),
                        "Required v6 preflight mechanism incomplete: " + canary);
                var readOnlyBoundary = EffectArrowRandomInput.emptyBoundary(world, previousCleanupSucceeded, false);
                require(
                    readOnlyBoundary.get("allowed")
                        .getAsBoolean()
                        && !readOnlyBoundary.get("apiCalled")
                            .getAsBoolean(),
                    "Exclusive whole-world read-only empty boundary failed");
                if (activeRingRefused && readOnlyBoundary.get("sequenceBefore")
                    .getAsLong() > 0) summary.addProperty("methodEmptyRingCanaryDetected", true);
                if (next >= specs.length) {
                    saveWindow.finalBoundary(readOnlyBoundary);
                    emptyBoundaries.add(readOnlyBoundary);
                    require(completed.size() == 12 && firstRepeat.size() == 6, "Incomplete first-seed matrix");
                    var coverage = new JsonObject();
                    coverage.addProperty("targetLaunchAttempts", normalTargetLaunches);
                    coverage.addProperty("maidLaunchAttempts", normalMaidLaunches);
                    coverage.addProperty("maidPreviewAttempts", normalMaidPreviews);
                    coverage.addProperty(
                        "maidPreviewStatus",
                        normalMaidPreviews == 0 ? "NOT_EXERCISED" : "CERTIFIED_NATIVE_PATH");
                    summary.add("normalArrowInputCoverage", coverage);
                    if (TACTICS == null) require(
                        normalTargetLaunches > 0 && normalMaidLaunches > 0,
                        "NOT_EXERCISED: normal target and maid launch sources both required");
                    summary.addProperty("normalRuns", completed.size());
                    summary.addProperty("completed", true);
                    summary.add("runs", completed);
                    if (emptyWorld != null) summary.add("emptyWorldFinalSeal", emptyWorld.snapshot());
                    summary.add("saveBoundaryProtocol", saveWindow.snapshot());
                    write("summary", summary);
                    finished = true;
                    return;
                }
                if (!saveWindow.admit(next, readOnlyBoundary, asynchronousFailure)) return;
                var boundary = EffectArrowRandomInput.emptyBoundary(world, previousCleanupSucceeded, true);
                emptyBoundaries.add(boundary);
                require(
                    boundary.get("allowed")
                        .getAsBoolean()
                        && boundary.get("apiCalled")
                            .getAsBoolean(),
                    "Exclusive whole-world empty boundary did not authorize own ring unload");
                JsonObject launchEvidence = saveWindow.launch(next, boundary);
                previousCleanupSucceeded = false;
                if (emptyWorld != null) emptyWorld.activate();
                boundarySealed = false;
                trial = new Trial(
                    this,
                    next < 0 ? "0:A:0" : specs[next],
                    next == -2,
                    next == -1,
                    next < -2 ? next + 7 : 0);
                trial.report.add("saveBoundaryLaunch", launchEvidence);
                try {
                    trial.prepare();
                } catch (Exception failure) {
                    throw new IllegalStateException("Effect fixture prepare failed", failure);
                }
            }
            if (!trial.tick()) return;
            trial.finish();
            if (trial.inputCanary) {
                summary.addProperty("initialNbtCanaryDetected", true);
                summary.addProperty("nativeLedgerUnexpectedClearCanaryDetected", true);
            }
            if (trial.waitCanary) summary.addProperty("wait80CanaryDetected", true);
            if (!trial.inputCanary && !trial.waitCanary && trial.methodCase == 0) checkRepeat(trial);
            if (emptyWorld != null) emptyWorld.drain();
            trial.close();
            previousCleanupSucceeded = true;
            trial = null;
            next++;
            drain = 40;
        }

        void checkRepeat(Trial value) {
            String pair = value.terrain + ":" + value.arm;
            JsonObject comparable = value.comparable(), previous = firstRepeat.get(pair);
            if (previous == null) firstRepeat.put(pair, comparable);
            else require(
                previous.equals(comparable),
                "Behavior repetition mismatch " + pair + "; raw retained, no expansion");
            JsonArray random = value.arrowInput.comparable(), oldRandom = firstRandomInput.get(pair);
            if (oldRandom == null) firstRandomInput.put(pair, random);
            else require(
                oldRandom.equals(random),
                "V6 constructor-receipt repetition mismatch " + pair + "; no expansion");
            if (value.arm == Arm.REACTIVE) firstWeapon.put(value.terrain + ":A", value.firstSelectedWeapon);
            if (value.arm == Arm.WEAPON_SELECTION) firstWeapon.put(value.terrain + ":B", value.firstSelectedWeapon);
            if (TACTICS == null) {
                if (firstWeapon.containsKey(value.terrain + ":A") && firstWeapon.containsKey(value.terrain + ":B"))
                    require(
                        !firstWeapon.get(value.terrain + ":A")
                            .equals(firstWeapon.get(value.terrain + ":B")),
                        "NOT_EXERCISED: static weapon arm did not differ from reactive arm");
                if (value.arm == Arm.THREAT_ROLLOUTS) require(
                    value.session.snapshot()
                        .get("fullDecisions")
                        .getAsLong() > 0,
                    "NOT_EXERCISED: production arm never completed a full forecast");
                if (value.arm == Arm.THREAT_ROLLOUTS && value.terrain == 1) require(
                    value.knownCoverFrames > 0,
                    "NOT_EXERCISED: side cover never entered the actual bounded collision snapshot");
            } else {
                var coverage = new JsonObject();
                coverage.addProperty("run", value.name);
                coverage.addProperty(
                    "weaponDifference",
                    firstWeapon.containsKey(value.terrain + ":A") && firstWeapon.containsKey(value.terrain + ":B")
                        && !firstWeapon.get(value.terrain + ":A")
                            .equals(firstWeapon.get(value.terrain + ":B")));
                coverage.addProperty(
                    "fullForecast",
                    value.session.snapshot()
                        .get("fullDecisions")
                        .getAsLong() > 0);
                coverage.addProperty("knownCover", value.knownCoverFrames > 0);
                coverage.addProperty("actualPlannerProjectile", value.plannerProjectileFrames > 0);
                value.report.add("tacticsCoverage", coverage);
            }
            normalTargetLaunches += value.arrowInput.certifiedAttempts(EffectArrowRandomInput.Purpose.TARGET_LAUNCH);
            normalMaidLaunches += value.arrowInput.certifiedAttempts(EffectArrowRandomInput.Purpose.MAID_LAUNCH);
            normalMaidPreviews += value.arrowInput.certifiedAttempts(EffectArrowRandomInput.Purpose.MAID_PREVIEW);
            var row = new JsonObject();
            row.addProperty("run", value.name);
            row.addProperty("terrain", value.terrain);
            row.addProperty("arm", value.arm.name());
            row.addProperty("repetition", value.repetition);
            row.addProperty("repeatedExactly", previous != null);
            row.addProperty("traceDigest", digest(comparable.toString()));
            completed.add(row);
        }

        void failed(Throwable failure) {
            summary.addProperty("completed", false);
            summary.addProperty("failure", failure.toString());
            summary.add("completedRuns", completed);
            summary.add("saveBoundaryProtocol", saveWindow.snapshot());
            if (trial != null) try {
                trial.retain("failure");
            } catch (RuntimeException extra) {
                failure.addSuppressed(extra);
            }
            try {
                write("failure-summary", summary);
            } catch (RuntimeException extra) {
                failure.addSuppressed(extra);
            }
        }

        void write(String name, JsonObject value) {
            try {
                Files.write(
                    output.resolve("unified-effect-" + name + ".json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(value)
                        .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain unique effect artifact " + name, failure);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                if (trial != null) trial.close();
            } finally {
                if (registered) {
                    MinecraftForge.EVENT_BUS.unregister(events);
                    FMLCommonHandler.instance()
                        .bus()
                        .unregister(events);
                }
                chunks.releaseAll();
                server.setCanSpawnAnimals(animals);
                MaidAcquisitionConfig.giveInitialSlab = gift;
                MaidHomeConfig.workRange = oldWorkRange;
                MaidHomeConfig.nonHomeRange = oldNonHomeRange;
                world.setWorldTime(day);
                world.difficultySetting = difficulty;
                world.getWorldInfo()
                    .setRaining(rain);
                world.getWorldInfo()
                    .setThundering(thunder);
                world.getWorldInfo()
                    .setRainTime(rainTime);
                world.getWorldInfo()
                    .setThunderTime(thunderTime);
                for (String rule : rules.keySet()) world.getGameRules()
                    .setOrCreateGameRule(rule, rules.get(rule));
                try {
                    if (emptyWorld != null) emptyWorld.closeRun(finished);
                } finally {
                    saveWindow.close();
                }
            }
        }
    }

    private static final class Trial {

        final Runner r;
        final String name;
        final int terrain, repetition;
        final Arm arm;
        final boolean inputCanary, waitCanary;
        final int methodCase;
        final List<Entity> owned = new ArrayList<>();
        final IdentityHashMap<EntityItem, EntityLivingBase> witnessedDrops = new IdentityHashMap<>();
        final List<EntityArrow> arrows = new ArrayList<>();
        final Map<EntityLivingBase, NativeCombatLedgerReader.Cursor> ledgers = new IdentityHashMap<>();
        final List<NBTTagCompound> itemValues = new ArrayList<>();
        final JsonArray frames = new JsonArray(), events = new JsonArray(), items = new JsonArray(),
            arrowIds = new JsonArray(), targetPreparation = new JsonArray();
        final JsonArray identityReadbacks = new JsonArray(), identitySidecars = new JsonArray(),
            healRequests = new JsonArray();
        final JsonObject report = new JsonObject();
        final int initialFaultCount;
        EntityPlayerMP owner;
        EntityMaid maid;
        EntitySkeleton enemy;
        EmbeddedChannel channel;
        EnhancedCoreBrain core;
        CombatEffectPilotSession session;
        MaidServerLoadProbe probe;
        EffectArrowRandomInput arrowInput;
        boolean methodConstructing;
        NBTTagCompound initial;
        long preparedAt, activeAt = Long.MAX_VALUE, lastOwnerCounter = Long.MIN_VALUE, ownerCpu, observerCpu;
        long enemyPreparedAt, lastSuppressedEnemyTick = Long.MIN_VALUE;
        NBTTagCompound previousEnemyMechanics;
        int targetStableSamples, targetSuppressedUpdates, preparationDamageRequests;
        int activeFrames, noProgress, maximumNoProgress, switches, weaponWear, shieldWear, arrowsRemaining = 32,
            knownCoverFrames;
        int pre, accepted, posts, outgoingRequests, goalCompletedAt = -1, maidDiedAt = -1, ownerDiedAt = -1;
        long lastShieldSequence;
        float previousMaid = 20, previousOwner = 20, previousTarget = 40, maidLost, ownerLost, maidHealed, ownerHealed;
        int plannerProjectileFrames, queueWaitFrames;
        long maximumQueueAge;
        final JsonArray passiveTacticsObservations = new JsonArray();
        ItemStack lastHeld;
        String firstSelectedWeapon = "NONE";
        final UUID expectedMaidIdentity;
        int healRequestsSeen, healRequestsTruncated, healDiagnosticUnavailable;
        volatile boolean healForeignThreadObserved;
        long healDiagnosticCpuNanos, healDiagnosticWallNanos;
        boolean closed, retained;
        RuntimeException cleanupFailure;
        OwnedSoulProtocol ownedSoul;
        long ownedSoulObserverCpuNanos, ownedSoulObserverWallNanos, ownedSoulObserverCalls;

        Trial(Runner r, String spec, boolean inputCanary, boolean waitCanary, int methodCase) {
            this.r = r;
            this.inputCanary = inputCanary;
            this.waitCanary = waitCanary;
            this.methodCase = methodCase;
            String[] parts = spec.split(":");
            terrain = Integer.parseInt(parts[0]);
            repetition = Integer.parseInt(parts[2]);
            arm = parts[1].equals("A") ? Arm.REACTIVE
                : parts[1].equals("B") ? Arm.WEAPON_SELECTION : Arm.THREAT_ROLLOUTS;
            name = methodCase > 0 ? "method-v6-" + methodCase
                : inputCanary ? "input-canary"
                    : waitCanary ? "wait-canary" : trialPrefix() + "-g" + terrain + "-" + parts[1] + "-r" + repetition;
            expectedMaidIdentity = IdentityInputs.maid(name);
            openOwnedSoul();
            initialFaultCount = IntegrationGuard.faults(Side.SERVER)
                .size();
            report.addProperty("schema", "unified-combat-effect-run-v1");
            report.addProperty("run", name);
            report.addProperty("seed", SEED);
            report.addProperty("terrain", terrain);
            report.addProperty("arm", arm.name());
            report.addProperty("repetition", repetition);
            report.addProperty("statisticalAdmission", false);
            report.addProperty("methodCanaryCase", methodCase);
            report.addProperty(
                "expectedFault",
                inputCanary ? "initial_nbt_mismatch_and_unexplained_ledger_clear"
                    : waitCanary ? "wait80_no_goal_progress" : "none");
            report.addProperty(
                "costScope",
                "Whole-pack native cost and exclusive combat phases; shared sensing, owner native control and observer costs separate");
            report.addProperty(
                "ownerControlScope",
                "One public onUpdateEntity per FML START, no C03, no fake player, no manual vertical integration");
        }

        void openOwnedSoul() {
            if (r.emptyWorld == null || r.emptyWorld.ownedSoulBinding() == null) return;
            meterOwnedSoul(() -> ownedSoul = new OwnedSoulProtocol(r.emptyWorld.ownedSoulBinding(),
                new OwnedSoulProtocol.Host() {
                    public EntityPlayerMP owner() { return owner; }
                    public WorldServer world() { return r.world; }
                    public MinecraftServer server() { return r.server; }
                    public Thread thread() { return r.ownerThread; }
                    public boolean active() { return !closed && activeAt != Long.MAX_VALUE; }
                    public boolean owned(Entity entity) { return owned.contains(entity); }
                    public void adopt(Entity entity) {
                        require(!owned.contains(entity), "Duplicate exact Soul adoption");
                        owned.add(entity);
                    }
                    public void harvestOwner() { ledger(owner, false); }
                    public int frame() { return Trial.this.frame(); }
                }));
        }

        void meterOwnedSoul(Runnable action) {
            r.emptyWorld.meterObservedProtocol(() -> {
                long wall = System.nanoTime(), cpu = r.clock.getCurrentThreadCpuTime();
                ownedSoulObserverCalls++;
                try {
                    action.run();
                } finally {
                    long after = r.clock.getCurrentThreadCpuTime();
                    require(cpu >= 0 && after >= cpu, "Soul observer CPU clock invalidated");
                    ownedSoulObserverCpuNanos += after - cpu;
                    ownedSoulObserverWallNanos += System.nanoTime() - wall;
                }
            });
        }

        boolean ownsObserved(Entity entity) {
            if (owned.contains(entity)) return true;
            // Reuse the original cleanup authority; observation does not add an object to owned
            if (entity instanceof MaidTombstoneEntity grave && maid != null && entity.worldObj == r.world) {
                UUID token = MaidLifecycleData.get(r.world)
                    .deathToken(maid.getUniqueID());
                return token != null && token.equals(grave.getToken());
            }
            return false;
        }

        void prepare() throws Exception {
            owner = new EntityPlayerMP(
                r.server,
                r.world,
                new GameProfile(id(name + ":owner"), "EffectPilotOwner"),
                new ItemInWorldManager(r.world));
            owned.add(owner);
            var manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            var handler = new NetHandlerPlayServer(r.server, manager, owner);
            IsolatedPlayerChannel.verify(manager, channel);
            owner.setPosition(r.x + 2.5, r.y + 1, r.z + 4.5);
            r.server.getConfigurationManager()
                .initializeConnectionToPlayer(manager, owner, handler);
            IsolatedPlayerChannel.verify(manager, channel);
            int enchantmentSeedBefore = OwnerEnchantmentSeed.freeze(owner);
            var ownerSeed = new JsonObject();
            ownerSeed.addProperty("before", enchantmentSeedBefore);
            ownerSeed.addProperty("after", OwnerEnchantmentSeed.read(owner));
            ownerSeed.addProperty("expected", OWNER_ENCHANTMENT_SEED);
            ownerSeed.addProperty("scope", "Public provider API once after owner connection/load, before warmup");
            report.add("ownerEnchantmentSeedPreparation", ownerSeed);
            if (!r.summary.has("ownerEnchantmentSeedProvider")) r.summary.add(
                "ownerEnchantmentSeedProvider",
                DevelopmentArtifactIdentity.capture(OwnerEnchantmentSeed.GET_STORAGE.getDeclaringClass()));
            owner.setGameType(GameType.SURVIVAL);
            owner.capabilities.allowFlying = owner.capabilities.isFlying = owner.capabilities.disableDamage = false;
            owner.inventory.clearInventory(null, -1);
            owner.clearActivePotions();
            owner.setHealth(20);
            var food = new NBTTagCompound();
            food.setInteger("foodLevel", 20);
            food.setInteger("foodTickTimer", 0);
            food.setFloat("foodSaturationLevel", 5);
            food.setFloat("foodExhaustionLevel", 0);
            owner.getFoodStats()
                .readNBT(food);
            owner.setPosition(r.x + 2.5, r.y + 1, r.z + 4.5);
            owner.getRNG()
                .setSeed(SEED + 1);
            orientation(owner);
            buildCell();
            maid = new EntityMaid(r.world);
            owned.add(maid);
            require(maid.bindTo(owner.getUniqueID()), "Effect owner binding failed");
            var identity = entityNbt(maid);
            identity.setLong("UUIDMost", expectedMaidIdentity.getMostSignificantBits());
            identity.setLong("UUIDLeast", expectedMaidIdentity.getLeastSignificantBits());
            maid.readFromNBT(identity);
            verifyMaidIdentity("afterPublicNbtRead");
            DeterministicMaidSpawn.freezeInitialState(maid);
            maid.setTask(MaidTask.ATTACK);
            maid.setSchedule(MaidSchedule.ALL);
            maid.setPickup(false);
            maid.setFavorability(0);
            maid.setHealth(20);
            maid.setHunger(20);
            maid.clearActivePotions();
            maid.getCombatTaskState()
                .consumePreference();
            maid.setCurrentItemOrArmor(0, tagged(new ItemStack(Items.stick), "unknown"));
            maid.getMaidInventory()
                .setInventorySlotContents(0, tagged(new ItemStack(Items.iron_sword), "sword"));
            maid.getMaidInventory()
                .setInventorySlotContents(1, tagged(new ItemStack(Items.bow), "bow"));
            maid.getMaidInventory()
                .setInventorySlotContents(2, new ItemStack(Items.arrow, 32));
            Object shield = Item.itemRegistry.getObject("battlegear2:shield.wood");
            require(shield instanceof Item, "Actual wood shield provider missing");
            maid.setOffhandItem(tagged(new ItemStack((Item) shield), "shield"));
            maid.setPosition(r.x + 5.5, r.y + 1, r.z + 4.5);
            maid.getRNG()
                .setSeed(SEED + 2);
            require(r.world.spawnEntityInWorld(maid), "Effect maid spawn rejected");
            verifyMaidIdentity("afterNativeSpawn");
            require(
                maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                    == MaidPolicyRuntime.SelectionResult.SELECTED,
                "Complete Enhanced effect root did not select");
            maid.getBrainCoordinator()
                .setSuspended(true);
            core = maid.getBrainCoordinator()
                .peekEnhancedCore();
            require(core != null && maid.getOwner() == owner, "Effect actor root/registered owner mismatch");
            session = CombatEffectPilotSession.bind(maid, name, arm, waitCanary);
            probe = MaidServerLoadProbe.start(name, "unified-combat-effect", r.world);
            probe.phase("warmup", 1, false);
            preparedAt = r.world.getTotalWorldTime();
            report.addProperty(
                "initialOwnerUuid",
                owner.getUniqueID()
                    .toString());
            report.addProperty(
                "initialMaidUuid",
                maid.getUniqueID()
                    .toString());
        }

        void buildCell() {
            for (int x = 0; x <= 20; x++) for (int y = 0; y <= 10; y++) for (int z = 0; z <= 8; z++) {
                boolean side = x == 0 || x == 20 || z == 0 || z == 8;
                r.h.setBlock(x, y, z, y == 0 || y == 10 ? Blocks.stone : side ? Blocks.glass : Blocks.air);
            }
            if (terrain == 1)
                for (int y = 1; y <= 3; y++) for (int z = 5; z <= 6; z++) r.h.setBlock(COVER_X, y, z, Blocks.stone);
        }

        void driveOwner() {
            if (closed || owner == null || owner.isDead || session == null) return;
            long counter = r.server.getTickCounter();
            if (counter == lastOwnerCounter) return;
            lastOwnerCounter = counter;
            long before = r.clock.getCurrentThreadCpuTime();
            owner.onUpdateEntity();
            long after = r.clock.getCurrentThreadCpuTime();
            require(before >= 0 && after >= before, "Native owner CPU clock unavailable");
            ownerCpu += after - before;
            drainChannel();
        }

        boolean tick() {
            if (methodCase > 0) return methodCanary();
            if (inputCanary) {
                NBTTagCompound before = inputs(false);
                maid.getHeldItem()
                    .getTagCompound()
                    .setInteger("FaultByte", 1);
                NBTTagCompound after = inputs(false);
                boolean caught = false;
                try {
                    requireSameInput(before, after);
                } catch (IllegalStateException expected) {
                    caught = true;
                }
                require(caught, "Initial-NBT mismatch canary not detected");
                r.summary.addProperty("initialNbtCanaryDetected", true);
                report.addProperty("initialNbtMismatchDetected", true);
                report.add("expectedInputs", SleepAttributePortSnapshot.binaryNbt(before));
                report.add("actualInputs", SleepAttributePortSnapshot.binaryNbt(after));
                unexplainedLedgerClearCanary();
                report.addProperty("faultDetected", true);
                report.addProperty("activeFrames", 0);
                return true;
            }
            if (activeAt == Long.MAX_VALUE) {
                if (r.world.getTotalWorldTime() - preparedAt < WARMUP) return false;
                if (enemy == null) {
                    prepareEnemy();
                    return false;
                }
                if (!enemyInitializationStable()) return false;
                startActive();
                return false;
            }
            int frame = frame();
            if (frame < 0) return false;
            require(frame == activeFrames && frame < ACTIVE_TICKS, "Activity frames skipped or duplicated");
            long beforeObserve = r.clock.getCurrentThreadCpuTime();
            observe(frame);
            long afterObserve = r.clock.getCurrentThreadCpuTime();
            require(beforeObserve >= 0 && afterObserve >= beforeObserve, "QA observer CPU clock invalidated");
            observerCpu += afterObserve - beforeObserve;
            if (ownedSoul != null) meterOwnedSoul(ownedSoul::observe);
            activeFrames++;
            if (waitCanary && activeFrames == 80) {
                recordWaitCanaryChecks();
                require(
                    maximumNoProgress >= 80 && enemy.getHealth() == 40
                        && accepted == 0
                        && outgoingRequests == 0
                        && weaponWear == 0
                        && arrowsRemaining == 32,
                    "WAIT canary did not expose no-progress/offensive-payment facts");
                report.addProperty("faultDetected", true);
                report.addProperty(
                    "canaryResourceScope",
                    "Offensive weapon wear/ammunition only; passive shield wear is separately observed");
                return true;
            }
            return activeFrames == ACTIVE_TICKS;
        }

        boolean methodCanary() {
            if (r.world.getTotalWorldTime() - preparedAt < WARMUP) return false;
            if (enemy == null) {
                prepareEnemy();
                return false;
            }
            if (!enemyInitializationStable()) return false;
            methodConstructing = true;
            enemy.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(40);
            enemy.setHealth(40);
            report.add("methodFixtureInputs", SleepAttributePortSnapshot.binaryNbt(inputs(true)));
            report.addProperty(
                "methodPurpose",
                "V6 input-method canary only, excluded from three-arm outcomes and sample count");
            if (methodCase <= 3) {
                JsonObject actual = arrowInput.canaryHeading(methodCase == 2);
                report.add("methodHeading", actual);
                if (methodCase == 1) r.headingA = actual;
                else {
                    require(r.headingA != null, "Fresh first heading canary missing");
                    boolean sameHeading = r.headingA.get("heading")
                        .equals(actual.get("heading"));
                    if (methodCase == 2) {
                        require(
                            sameHeading && r.headingA.get("targetSeed")
                                .equals(actual.get("targetSeed"))
                                && actual.get("targetAttempt")
                                    .getAsLong() == 0,
                            "Same target seed did not repeat exactly after independent maid previews");
                        report.addProperty("sameSeedHeadingAndDomainIsolationDetected", true);
                    } else {
                        require(
                            !sameHeading && !r.headingA.get("targetSeed")
                                .equals(actual.get("targetSeed")),
                            "Pre-frozen different seed did not change native heading; no seed reselection allowed");
                        r.summary.addProperty("methodHeadingCanaryDetected", true);
                        report.addProperty("differentFrozenSeedHeadingDetected", true);
                    }
                }
            } else {
                report.add("methodOwnershipNegative", arrowInput.canaryRejections());
                r.summary.addProperty("methodOwnershipCanaryDetected", true);
            }
            // This is an ordinary QA boundary call; the API must not run while actors or a control lease exist
            var refused = EffectArrowRandomInput.emptyBoundary(r.world, true, true);
            report.add("activeRingResetNegative", refused);
            require(
                !refused.get("allowed")
                    .getAsBoolean()
                    && !refused.get("apiCalled")
                        .getAsBoolean()
                    && refused.get("sequenceBefore")
                        .equals(refused.get("sequenceAfter")),
                "Active-world ring reset was not rejected");
            r.activeRingRefused = true;
            arrowInput.assertComplete();
            report.addProperty("faultDetected", true);
            report.addProperty("activeFrames", 0);
            return true;
        }

        void prepareEnemy() {
            enemy = new EntitySkeleton(r.world);
            owned.add(enemy);
            enemy.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
            enemy.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(40);
            enemy.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            enemy.setHealth(40);
            enemy.func_110163_bv();
            var saved = entityNbt(enemy);
            saved.setLong("UUIDMost", id(name + ":enemy").getMostSignificantBits());
            saved.setLong("UUIDLeast", id(name + ":enemy").getLeastSignificantBits());
            enemy.readFromNBT(saved);
            enemy.setPosition(r.x + 15.5, r.y + 1, r.z + 4.5);
            enemy.getRNG()
                .setSeed(SEED + 3);
            orientation(enemy);
            r.world.rand.setSeed(SEED);
            require(r.world.spawnEntityInWorld(enemy), "Native enemy spawn rejected");
            arrowInput = EffectArrowRandomInput.open(
                r.world,
                maid,
                enemy,
                session,
                owner,
                name,
                methodCase == 3 ? EffectArrowRandomInput.CANARY_B : SEED,
                methodCase > 0,
                failure -> { if (r.asynchronousFailure == null) r.asynchronousFailure = failure; });
            enemyPreparedAt = r.world.getTotalWorldTime();
            probe.phase("target-native-initialization", 1, false);
            report.add("targetBeforeNativeInitialization", SleepAttributePortSnapshot.binaryNbt(entityNbt(enemy)));
            report.addProperty(
                "targetPreparationScope",
                "Real server/world END callbacks run; only this target's LivingUpdate is cancelled before activity, so this is not full target physics warmup");
        }

        boolean preparingEnemy() {
            return !closed && enemy != null && activeAt == Long.MAX_VALUE && !methodConstructing;
        }

        void suppressPreparedEnemyUpdate(LivingUpdateEvent event) {
            require(preparingEnemy() && event.entityLiving == enemy, "Target preparation update identity changed");
            require(!event.isCanceled(), "Another listener cancelled the preparation target update");
            event.setCanceled(true);
            long tick = r.server.getTickCounter();
            if (tick != lastSuppressedEnemyTick) {
                lastSuppressedEnemyTick = tick;
                targetSuppressedUpdates++;
            }
        }

        NBTTagCompound enemyMechanics() {
            var nativeNbt = entityNbt(enemy);
            var mechanics = new NBTTagCompound();
            mechanics.setFloat("Health", enemy.getHealth());
            mechanics.setFloat("MaxHealth", enemy.getMaxHealth());
            mechanics.setFloat("Absorption", enemy.getAbsorptionAmount());
            mechanics.setTag(
                "Attributes",
                nativeNbt.getTagList("Attributes", 10)
                    .copy());
            mechanics.setTag(
                "Equipment",
                nativeNbt.getTagList("Equipment", 10)
                    .copy());
            mechanics.setTag(
                "ActiveEffects",
                nativeNbt.getTagList("ActiveEffects", 10)
                    .copy());
            return mechanics;
        }

        boolean enemyInitializationStable() {
            long elapsed = r.world.getTotalWorldTime() - enemyPreparedAt;
            require(
                elapsed > 0 && elapsed <= TARGET_PREPARATION_LIMIT,
                "Native target initialization did not stabilize within eight world ticks");
            require(
                enemy.isEntityAlive() && maid.getHealth() == 20 && owner.getHealth() == 20,
                "Actor health changed during noncombat target preparation");
            require(
                arrows.isEmpty() && preparationDamageRequests == 0
                    && pre == 0
                    && accepted == 0
                    && outgoingRequests == 0,
                "Combat occurred during target preparation");
            var mechanics = enemyMechanics();
            targetStableSamples = mechanics.equals(previousEnemyMechanics) ? targetStableSamples + 1 : 1;
            previousEnemyMechanics = mechanics;
            var sample = new JsonObject();
            sample.addProperty("worldTicksAfterSpawn", elapsed);
            sample.addProperty("suppressedNativeUpdates", targetSuppressedUpdates);
            sample.addProperty("stableSamples", targetStableSamples);
            sample.addProperty("damageRequests", preparationDamageRequests);
            sample.addProperty("offensiveArrows", arrows.size());
            sample.add("mechanics", SleepAttributePortSnapshot.binaryNbt(mechanics));
            targetPreparation.add(sample);
            report.add("targetPreparationSamples", targetPreparation);
            if (targetStableSamples >= TARGET_STABLE_SAMPLES && targetSuppressedUpdates >= TARGET_STABLE_SAMPLES)
                return true;
            require(
                elapsed < TARGET_PREPARATION_LIMIT,
                "Native target initialization did not stabilize within eight world ticks");
            return false;
        }

        void startActive() {
            require(
                preparingEnemy() && targetStableSamples >= TARGET_STABLE_SAMPLES
                    && targetSuppressedUpdates >= TARGET_STABLE_SAMPLES,
                "Target initialization gate not complete");
            int ownerEnchantmentSeed = OwnerEnchantmentSeed.read(owner);
            report.addProperty("ownerEnchantmentSeedBeforeActivity", ownerEnchantmentSeed);
            require(
                ownerEnchantmentSeed == OWNER_ENCHANTMENT_SEED,
                "Native owner enchantment seed changed during preparation");
            report.add("targetAfterNativeInitialization", SleepAttributePortSnapshot.binaryNbt(entityNbt(enemy)));
            // Fixture health is frozen once, after deferred native END modifiers and before the active window
            enemy.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(40);
            enemy.setHealth(40);
            require(
                enemy.getHealth() == 40 && enemy.getMaxHealth() == 40
                    && enemy.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .getAttributeValue() == 0,
                "Declared target fixture mechanics unavailable after native initialization");
            report.addProperty("targetPreparationTicks", r.world.getTotalWorldTime() - enemyPreparedAt);
            report.addProperty("targetSuppressedUpdates", targetSuppressedUpdates);
            report.addProperty("targetPreparationDamageRequests", preparationDamageRequests);
            report.addProperty("targetPreparationOffensiveArrows", arrows.size());
            report.add("targetFrozenBeforeActivity", SleepAttributePortSnapshot.binaryNbt(entityNbt(enemy)));
            enemy.setAttackTarget(TACTICS != null && terrain == 1 ? maid : owner);
            if (TACTICS != null) report.addProperty("actualTargetIntent", terrain == 1 ? "maid" : "owner");
            maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(enemy), MonsterType.HOSTILE);
            require(maid.canEntityBeSeen(enemy), "Initial target not actually visible");
            initial = inputs(true);
            report.add("initialInputs", SleepAttributePortSnapshot.binaryNbt(initial));
            report.add("rawInitialInputs", SleepAttributePortSnapshot.binaryNbt(rawInputs(true)));
            report.addProperty(
                "inputNormalization",
                "Only these three synthetic actual UUIDs map to stable logical actor UUIDs; raw typed inputs retained");
            report.addProperty("initialInputsDigest", digest(initial.toString()));
            if (!waitCanary) {
                String key = "G" + terrain;
                NBTTagCompound expected = r.firstInput.get(key);
                if (expected == null) r.firstInput.put(key, (NBTTagCompound) initial.copy());
                else requireSameInput(expected, initial);
            }
            for (EntityLivingBase actor : new EntityLivingBase[] { maid, owner, enemy })
                ledgers.put(actor, NativeCombatLedgerReader.watch(actor));
            lastHeld = maid.getHeldItem();
            activeAt = r.world.getTotalWorldTime() + 1;
            arrowInput.activate(activeAt);
            report.addProperty("targetUpdateGateReleased", true);
            report.addProperty("targetUpdateGateReleasedForFirstActiveTick", activeAt);
            session.activate(activeAt);
            probe.phase("active", 1, true);
            ownerCpu = 0;
            maid.getBrainCoordinator()
                .setSuspended(false);
        }

        void recordWaitCanaryChecks() {
            var checks = new JsonObject();
            checks.addProperty("maximumNoProgress", maximumNoProgress);
            checks.addProperty("targetHealth", enemy.getHealth());
            checks.addProperty("acceptedMelee", accepted);
            checks.addProperty("outgoingDamageRequests", outgoingRequests);
            checks.addProperty("offensiveWeaponWear", weaponWear);
            checks.addProperty("arrowsRemaining", arrowsRemaining);
            checks.addProperty("noProgressAtLeast80", maximumNoProgress >= 80);
            checks.addProperty("targetHealthIs40", enemy.getHealth() == 40);
            checks.addProperty("noAcceptedMelee", accepted == 0);
            checks.addProperty("noOutgoingDamageRequests", outgoingRequests == 0);
            checks.addProperty("noOffensiveWeaponWear", weaponWear == 0);
            checks.addProperty("all32ArrowsRetained", arrowsRemaining == 32);
            report.add("waitCanaryChecks", checks);
        }

        NBTTagCompound inputs(boolean withEnemy) {
            verifyMaidIdentity("originalTypedInputBoundary");
            var result = rawInputs(withEnemy);
            normalizeIds(result);
            return result;
        }

        NBTTagCompound rawInputs(boolean withEnemy) {
            var data = new NBTTagCompound();
            data.setInteger("Terrain", terrain);
            data.setLong("Seed", SEED);
            data.setInteger("CoverX", COVER_X);
            data.setInteger("WorkRange", MaidHomeConfig.workRange);
            data.setInteger("NonHomeRange", MaidHomeConfig.nonHomeRange);
            data.setString("Difficulty", r.world.difficultySetting.name());
            data.setLong("DayTime", r.world.getWorldTime());
            data.setBoolean(
                "Raining",
                r.world.getWorldInfo()
                    .isRaining());
            data.setBoolean(
                "Thundering",
                r.world.getWorldInfo()
                    .isThundering());
            data.setTag("Maid", entityNbt(maid));
            data.setTag("Owner", entityNbt(owner));
            if (withEnemy) data.setTag("Enemy", entityNbt(enemy));
            data.setTag(
                "Rules",
                r.world.getGameRules()
                    .writeGameRulesToNBT());
            return data;
        }

        void normalizeIds(NBTTagCompound data) {
            if (data.hasKey("UUIDMost", 4) && data.hasKey("UUIDLeast", 4)) {
                UUID actual = new UUID(data.getLong("UUIDMost"), data.getLong("UUIDLeast"));
                UUID logical = logicalId(actual.toString());
                if (logical != null) {
                    data.setLong("UUIDMost", logical.getMostSignificantBits());
                    data.setLong("UUIDLeast", logical.getLeastSignificantBits());
                }
            }
            for (Object raw : new ArrayList<>(data.func_150296_c())) {
                String key = (String) raw;
                var value = data.getTag(key);
                if (value instanceof NBTTagCompound child) normalizeIds(child);
                else if (value instanceof NBTTagString string) {
                    UUID logical = logicalId(string.func_150285_a_());
                    if (logical != null) data.setString(key, logical.toString());
                } else if (value instanceof NBTTagList list) {
                    for (int i = 0; i < list.tagCount(); i++) {
                        if (list.func_150303_d() == 10) normalizeIds(list.getCompoundTagAt(i));
                        else if (list.func_150303_d() == 8) {
                            UUID logical = logicalId(list.getStringTagAt(i));
                            if (logical != null) list.func_150304_a(i, new NBTTagString(logical.toString()));
                        }
                    }
                }
            }
        }

        UUID logicalId(String actual) {
            if (owner != null && owner.getUniqueID()
                .toString()
                .equals(actual)) return id("logical-owner");
            if (maid != null && maid.getUniqueID()
                .toString()
                .equals(actual)) return id("logical-maid");
            if (enemy != null && enemy.getUniqueID()
                .toString()
                .equals(actual)) return id("logical-target");
            return null;
        }

        void observe(int frame) {
            require(
                !r.world.getWorldInfo()
                    .isRaining()
                    && !r.world.getWorldInfo()
                        .isThundering(),
                "Declared clear-weather input changed");
            require(
                IntegrationGuard.faults(Side.SERVER)
                    .size() == initialFaultCount,
                "Protected fallback during effect run invalidates measurement");
            require(
                !maid.getCombatHitReceipt()
                    .blocksTransfer(),
                "Unclosed receipt invalidates measurement");
            for (EntityLivingBase actor : new EntityLivingBase[] { maid, owner, enemy }) ledger(actor, false);
            if (maid.getHealth() < previousMaid) maidLost += previousMaid - maid.getHealth();
            else maidHealed += maid.getHealth() - previousMaid;
            if (owner.getHealth() < previousOwner) ownerLost += previousOwner - owner.getHealth();
            else ownerHealed += owner.getHealth() - previousOwner;
            if (enemy.getHealth() < previousTarget) noProgress = 0;
            else if (enemy.isEntityAlive()) noProgress++;
            maximumNoProgress = Math.max(maximumNoProgress, noProgress);
            previousMaid = maid.getHealth();
            previousOwner = owner.getHealth();
            previousTarget = enemy.getHealth();
            if (!enemy.isEntityAlive() && goalCompletedAt < 0) goalCompletedAt = frame;
            if (!maid.isEntityAlive() && maidDiedAt < 0) maidDiedAt = frame;
            if (!owner.isEntityAlive() && ownerDiedAt < 0) ownerDiedAt = frame;
            ItemStack held = maid.getHeldItem();
            if (held != lastHeld && held != null && lastHeld != null && maid.isEntityAlive()) switches++;
            lastHeld = held;
            if (firstSelectedWeapon.equals("NONE") && held != null
                && (held.getItem() == Items.iron_sword || held.getItem() == Items.bow))
                firstSelectedWeapon = held.getItem() == Items.iron_sword ? "SWORD" : "BOW";
            arrowsRemaining = 0;
            weaponWear = 0;
            shieldWear = 0;
            var inventory = new JsonArray();
            for (int slot = 0; slot < MaidInventoryLayout.TOTAL_SIZE; slot++) {
                var stack = maid.getMaidInventory()
                    .getStackInSlot(slot);
                inventory.add(new com.google.gson.JsonPrimitive(item(stack)));
                resources(stack);
            }
            var graveInventory = new JsonArray();
            if (!maid.isEntityAlive()) {
                var death = deathEntry();
                var stored = death.getTagList("TombstoneItems", 10);
                for (int i = 0; i < stored.tagCount(); i++) {
                    var stack = ProtectedItemCodec.decode(stored.getCompoundTagAt(i));
                    graveInventory.add(new com.google.gson.JsonPrimitive(item(stack)));
                    resources(stack);
                }
            }
            var block = maid.getShieldController()
                .getLastBlock();
            if (block != null && block.sequence != lastShieldSequence) {
                lastShieldSequence = block.sequence;
                var event = event("shield", maid, null);
                event.addProperty("outcome", block.outcome.name());
                event.addProperty("requestedWear", block.requestedWear);
                event.addProperty("blocked", block.blocked);
                events.add(event);
            }
            var row = new JsonObject();
            row.addProperty("frame", frame);
            row.add("maid", state(maid));
            row.add("owner", state(owner));
            row.add("target", state(enemy));
            row.add("inventory", inventory);
            row.add("graveInventory", graveInventory);
            row.addProperty("noGoalProgressTicks", noProgress);
            row.addProperty(
                "cadenceRemaining",
                maid.getCombatCadence()
                    .remainingTicks());
            row.addProperty(
                "receipt",
                maid.getCombatHitReceipt()
                    .stage()
                    .name());
            row.addProperty("pre", pre);
            row.addProperty("posts", posts);
            row.addProperty("acceptedMelee", accepted);
            row.addProperty(
                "shots",
                core.actor()
                    .rangedBow()
                    .getShots());
            row.addProperty(
                "refusedShots",
                core.actor()
                    .rangedBow()
                    .getRejectedShots());
            var plan = core.combat()
                .lastPlan();
            row.addProperty("macro", plan == null ? "NONE" : plan.macro.name());
            row.addProperty("decisionReason", plan == null ? "NONE" : plan.reason);
            row.addProperty("simulated", plan != null && plan.simulated);
            row.addProperty(
                "runnerResult",
                core.combat()
                    .lastResult() == null ? "NONE"
                        : core.combat()
                            .lastResult()
                            .name());
            var snapshot = core.combat()
                .lastSnapshot();
            if (TACTICS != null) {
                var projectiles = new JsonArray();
                if (snapshot != null) for (var projectile : snapshot.projectiles) {
                    var threat = new JsonObject();
                    threat.addProperty("observedAtRelative", projectile.observedAt - activeAt);
                    threat.addProperty("damageSource", projectile.damageSource.name());
                    projectiles.add(threat);
                }
                if (projectiles.size() > 0) plannerProjectileFrames++;
                row.add("actualPlannerProjectiles", projectiles);
                var passive = new JsonObject();
                passive.addProperty("frame", frame);
                var nativeIds = new JsonArray();
                if (snapshot != null) for (var projectile : snapshot.projectiles) nativeIds
                    .add(new com.google.gson.JsonPrimitive(projectile.id == null ? "" : projectile.id.toString()));
                passive.add("nativeProjectileIds", nativeIds);
                long now = r.world.getTotalWorldTime();
                for (var request : CombatWorldBudget.diagnostics(r.world, now).entries)
                    if (request.id.equals(maid.getUniqueID())) {
                        row.addProperty("budgetPhase", request.phase.name());
                        row.addProperty("budgetPending", request.pending);
                        row.addProperty("budgetRequestSinceActive", request.since - activeAt);
                        passive.addProperty("budgetRequestSerial", request.requestSerial);
                        if (request.pending && request.phase != CombatWorldBudget.Phase.FULL) {
                            queueWaitFrames++;
                            maximumQueueAge = Math.max(maximumQueueAge, now - request.since);
                        }
                    }
                passiveTacticsObservations.add(passive);
                row.addProperty(
                    "actualFood",
                    maid.getHungerState()
                        .getFood());
                row.addProperty(
                    "actualSaturation",
                    maid.getHungerState()
                        .getSaturation());
                row.addProperty(
                    "actualExhaustion",
                    maid.getHungerState()
                        .getExhaustion());
            }
            boolean coverKnown = terrain == 1 && snapshot != null
                && snapshot.geometry != null
                && (snapshot.geometry.at(r.x + COVER_X, r.y + 1, r.z + 5) & 1) != 0;
            if (coverKnown) knownCoverFrames++;
            row.addProperty("sideCoverInCollisionSnapshot", coverKnown);
            var selected = snapshot == null ? null : snapshot.target(snapshot.selectedTarget);
            row.addProperty("snapshotFrame", snapshot == null ? -1 : snapshot.tick - activeAt);
            row.addProperty("snapshotComplete", snapshot != null && snapshot.complete);
            row.addProperty("targetVisibleToPlanner", selected != null && selected.visible);
            row.addProperty(
                "rangedStatus",
                core.actor()
                    .rangedBow()
                    .getStatus()
                    .name());
            var lease = maid.getItemUseState()
                .peek();
            row.addProperty(
                "usingKind",
                lease == null ? "NONE"
                    : lease.getKind()
                        .name());
            row.addProperty("distanceToTarget", maid.getDistanceToEntity(enemy));
            String reason = !enemy.isEntityAlive() ? "RESOLVED"
                : !maid.isEntityAlive() ? "MAID_DEAD"
                    : plan == null ? "NO_COMBAT_PLAN"
                        : selected == null || !selected.visible ? "NOT_VISIBLE"
                            : core.combat()
                                .lastResult() != null && core.combat()
                                    .lastResult()
                                    .name()
                                    .startsWith("WAIT_") ? core.combat()
                                        .lastResult()
                                        .name()
                                        : lease != null ? "ITEM_USE_" + lease.getKind()
                                            .name()
                                            : maid.getCombatCadence()
                                                .remainingTicks() > 0 ? "CADENCE" : plan.macro.name();
            row.addProperty("noProgressContext", reason);
            if (plan != null) {
                row.addProperty("planQuotaFrame", plan.quota.tick - activeAt);
                var work = new JsonArray();
                for (int count : plan.quota.counts()) work.add(new com.google.gson.JsonPrimitive(count));
                row.add("planQuotaCounters", work);
            }
            var paths = new JsonArray();
            for (int i = 0; i < arrows.size(); i++) {
                var flight = state(arrows.get(i));
                flight.addProperty("arrow", i);
                flight.addProperty(
                    "critical",
                    arrows.get(i)
                        .getIsCritical());
                paths.add(flight);
            }
            row.add("arrows", paths);
            frames.add(row);
        }

        void resources(ItemStack stack) {
            if (stack == null) return;
            if (stack.getItem() == Items.arrow) arrowsRemaining += stack.stackSize;
            if (stack.getItem() == Items.iron_sword || stack.getItem() == Items.bow)
                weaponWear += stack.getItemDamage();
            if (stack.hasTagCompound() && "shield".equals(
                stack.getTagCompound()
                    .getString(PAYLOAD)))
                shieldWear += stack.getItemDamage();
        }

        int item(ItemStack stack) {
            if (stack == null) return -1;
            NBTTagCompound saved = stack.writeToNBT(new NBTTagCompound());
            for (int i = 0; i < itemValues.size(); i++) if (saved.equals(itemValues.get(i))) return i;
            require(itemValues.size() < 512, "Effect inventory snapshot limit exceeded");
            itemValues.add((NBTTagCompound) saved.copy());
            items.add(SleepAttributePortSnapshot.binaryNbt(saved));
            return itemValues.size() - 1;
        }

        void ledger(EntityLivingBase actor, boolean death) {
            var cursor = ledgers.get(actor);
            if (cursor == null) return;
            NativeCombatLedgerReader.Harvest harvest;
            try {
                harvest = cursor.harvest(death);
            } catch (RuntimeException | LinkageError failure) {
                var diagnostic = new JsonObject();
                diagnostic.addProperty("actor", role(actor));
                diagnostic.addProperty("frame", frame());
                diagnostic.addProperty("victimTick", actor.ticksExisted);
                diagnostic.addProperty("failure", failure.toString());
                report.add("nativeLedgerReadFailure", diagnostic);
                throw failure;
            }
            boolean complete = completeHarvest(harvest);
            if (harvest.resetObserved || !complete) {
                var boundary = event("native-ledger-boundary", actor, null);
                boundary.add("nativeObservation", ledgerObservation(harvest));
                events.add(boundary);
                report.add("lastNativeLedgerBoundary", boundary);
            }
            require(
                complete,
                "Native acceptance ledger incomplete for " + role(actor) + " at activity frame " + frame());
            for (var entry : harvest.entries) {
                var row = event("native-ledger", actor, entry.source);
                row.addProperty("victimTick", entry.victimTick);
                row.addProperty("nativeRecordedDamage", entry.nativeRecordedDamage);
                row.addProperty("healthBefore", entry.healthBefore);
                row.addProperty("clampedNativeLossEstimate", entry.clampedNativeLossEstimate);
                row.addProperty("estimateIsExactHealthAfter", false);
                events.add(row);
            }
            if (ownedSoul != null && actor == owner && (death || ownedSoul.awaitingPostLedger())) meterOwnedSoul(() -> {
                JsonObject observation = ledgerObservation(harvest);
                ownedSoul.ledger(harvest, observation, death);
                ownedSoul.postLedger(observation);
            });
        }

        boolean completeHarvest(NativeCombatLedgerReader.Harvest harvest) {
            return !harvest.tailIncomplete && (!harvest.resetObserved || harvest.resetWasExpected);
        }

        JsonObject ledgerObservation(NativeCombatLedgerReader.Harvest harvest) {
            var result = new JsonObject();
            result.addProperty("previousNativeSize", harvest.previousNativeSize);
            result.addProperty("previousSampleComplete", harvest.previousSampleComplete);
            result.addProperty("previousLastEntryVictimTick", harvest.previousLastEntryVictimTick);
            result.addProperty("nativeSize", harvest.nativeSize);
            result.addProperty("inspected", harvest.inspected);
            result.addProperty("freshEntries", harvest.entries.size());
            result.addProperty("resetObserved", harvest.resetObserved);
            result.addProperty("resetWasExpected", harvest.resetWasExpected);
            result.addProperty("resetReason", harvest.resetReason.name());
            result.addProperty("tailIncomplete", harvest.tailIncomplete);
            result.addProperty("complete", completeHarvest(harvest));
            result.addProperty("sameNativeTrackerAndLedgerIdentityChecked", true);
            result.add("previousNativeSample", nativeLedgerState(harvest.previousSample));
            result.add("currentNativeSample", nativeLedgerState(harvest.currentSample));
            var entries = new JsonArray();
            for (var entry : harvest.entries) {
                var sample = new JsonObject();
                sample.addProperty("victimTick", entry.victimTick);
                sample.addProperty("sourceType", entry.source.getDamageType());
                sample.addProperty("attacker", role(entry.source.getEntity()));
                sample.addProperty("direct", role(entry.source.getSourceOfDamage()));
                sample.addProperty("nativeRecordedDamage", entry.nativeRecordedDamage);
                sample.addProperty("healthBefore", entry.healthBefore);
                entries.add(sample);
            }
            result.add("freshNativeEntrySamples", entries);
            return result;
        }

        JsonObject nativeLedgerState(NativeCombatLedgerReader.NativeState sample) {
            var result = new JsonObject();
            result.addProperty("victimTick", sample.victimTick);
            result.addProperty("alive", sample.alive);
            result.addProperty("lastHitTick", sample.lastHitTick);
            result.addProperty("combatStartTick", sample.combatStartTick);
            result.addProperty("combatEndTick", sample.combatEndTick);
            result.addProperty("inCombat", sample.inCombat);
            result.addProperty("trackingDamage", sample.trackingDamage);
            return result;
        }

        void unexplainedLedgerClearCanary() {
            var target = new EntitySkeleton(r.world);
            owned.add(target);
            target.setPosition(r.x + 15.5, r.y + 1, r.z + 4.5);
            require(r.world.spawnEntityInWorld(target), "Native ledger negative-canary target spawn rejected");
            var cursor = NativeCombatLedgerReader.watch(target);
            require(
                target.attackEntityFrom(new DamageSource("tlmnh_qa_ledger_seed"), 1),
                "Native ledger negative-canary seed call rejected");
            var actual = cursor.harvest(false);
            require(
                completeHarvest(actual) && actual.entries.size() == 1 && actual.entries.get(0).nativeRecordedDamage > 0,
                "Native ledger negative-canary did not harvest one actual native entry");
            var canary = new JsonObject();
            canary.addProperty(
                "faultInjection",
                "QA clears this owned target's combatEntries early in the same victim tick");
            canary.addProperty("actualNativeEntriesBeforeFault", actual.entries.size());
            canary.addProperty("seedNativeAmount", actual.entries.get(0).nativeRecordedDamage);
            canary.addProperty("seedEntryVictimTick", actual.entries.get(0).victimTick);
            canary.add("beforeFault", ledgerObservation(actual));
            report.add("unexplainedLedgerClearCanary", canary);
            try {
                Object value = FAULT_LEDGER.get(target.func_110142_aN());
                require(value instanceof List<?>, "Native ledger negative-canary container changed");
                ((List<?>) value).clear();
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Native ledger negative-canary fault injection unavailable", failure);
            }
            var missing = cursor.harvest(false);
            canary.add("afterFault", ledgerObservation(missing));
            require(
                missing.previousSample.victimTick == missing.currentSample.victimTick && missing.resetObserved
                    && !missing.resetWasExpected
                    && missing.resetReason == NativeCombatLedgerReader.ResetReason.UNEXPLAINED
                    && missing.entries.isEmpty()
                    && !completeHarvest(missing),
                "Unexplained native ledger clear was incorrectly admitted");
            canary.addProperty("faultDetected", true);
        }

        JsonObject event(String kind, Entity victim, DamageSource source) {
            require(events.size() < 4096, "Bounded effect event records exceeded");
            var row = new JsonObject();
            row.addProperty("frame", frame());
            row.addProperty("kind", kind);
            row.addProperty("victim", role(victim));
            if (source != null) {
                row.addProperty("sourceType", source.getDamageType());
                row.addProperty("attacker", role(source.getEntity()));
                row.addProperty("direct", role(source.getSourceOfDamage()));
            }
            return row;
        }

        String role(Entity entity) {
            if (entity == null) return "none";
            if (entity == maid) return "maid";
            if (entity == owner) return "owner";
            if (entity == enemy) return "target";
            if (entity instanceof EntityArrow arrow) return "arrow-" + arrows.indexOf(arrow);
            return entity.getClass()
                .getName();
        }

        int frame() {
            return activeAt == Long.MAX_VALUE ? -1 : (int) (r.world.getTotalWorldTime() - activeAt);
        }

        NBTTagCompound deathEntry() {
            UUID token = MaidLifecycleData.get(r.world)
                .deathToken(maid.getUniqueID());
            var saved = new NBTTagCompound();
            MaidLifecycleData.get(r.world)
                .writeToNBT(saved);
            var entries = saved.getTagList("Entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) if (token != null && token.toString()
                .equals(
                    entries.getCompoundTagAt(i)
                        .getString("Token")))
                return entries.getCompoundTagAt(i);
            return new NBTTagCompound();
        }

        void finish() {
            if (arrowInput != null) arrowInput.assertComplete();
            probe.close();
            report.addProperty("activeFrames", activeFrames);
            report.addProperty("firstSelectedWeapon", firstSelectedWeapon);
            report.addProperty("maximumNoGoalProgressTicks", maximumNoProgress);
            report.addProperty("switches", switches);
            report.addProperty("knownCoverFrames", knownCoverFrames);
            report.addProperty(
                "switchScope",
                "Native hand identity transitions each END; one apply/frame, normalization excluded");
            report.addProperty("maidPositiveHpLoss", maidLost);
            report.addProperty("ownerPositiveHpLoss", ownerLost);
            report.addProperty("maidHealed", maidHealed);
            report.addProperty("ownerHealed", ownerHealed);
            report.addProperty("maidEndHp", maid.getHealth());
            report.addProperty("ownerEndHp", owner.getHealth());
            report.addProperty("targetEndHp", enemy == null ? 40 : enemy.getHealth());
            report.addProperty("maidDeathFrame", maidDiedAt);
            report.addProperty("ownerDeathFrame", ownerDiedAt);
            if (goalCompletedAt >= 0) report.addProperty("timeToResolution", goalCompletedAt);
            else {
                report.add("timeToResolution", JsonNull.INSTANCE);
                report.addProperty("rightCensoredAt", activeFrames);
            }
            report.addProperty("offensiveWeaponWearObserved", weaponWear);
            report.addProperty("shieldWearObserved", shieldWear);
            report.addProperty("arrowsRemainingObserved", arrowsRemaining);
            report.addProperty("nativeOwnerControlCpuNanos", ownerCpu);
            report.addProperty("qaEndObserverCpuNanos", observerCpu);
            if (TACTICS != null) {
                report.addProperty("plannerProjectileFrames", plannerProjectileFrames);
                report.addProperty("queueWaitFrames", queueWaitFrames);
                report.addProperty("maximumQueueAge", maximumQueueAge);
                report.add("tacticsInputs", TACTICS.input);
                report.addProperty("tacticsContractSha256", TACTICS.contractSha);
                report.add("passiveTacticsObservations", passiveTacticsObservations);
            }
            retain("complete");
        }

        JsonObject comparable() {
            var row = new JsonObject();
            row.add("frames", frames);
            row.add("events", events);
            var typed = new JsonArray();
            for (var value : itemValues) typed.add(new com.google.gson.JsonPrimitive(value.toString()));
            row.add("inventoryValueOrder", typed);
            return row;
        }

        void verifyMaidIdentity(String stage) {
            require(expectedMaidIdentity.equals(maid.getUniqueID()), "Actual public maid identity changed at " + stage);
            JsonObject row = IdentityInputs.row(name);
            row.addProperty("stage", stage);
            row.addProperty(
                "actualUuid",
                maid.getUniqueID()
                    .toString());
            row.addProperty("ticksExisted", maid.ticksExisted);
            identityReadbacks.add(row);
            // 仅解释状态，不是原比较/准入条件；不从Random读取下一个值
            JsonObject sidecar = new JsonObject();
            try {
                sidecar.addProperty("stage", stage);
                sidecar.addProperty("serverTick", r.server.getTickCounter());
                sidecar.addProperty("worldTick", r.world.getTotalWorldTime());
                sidecar.addProperty("ticksExisted", maid.ticksExisted);
                sidecar.addProperty("livingSoundTime", maid.livingSoundTime);
                var random = maid.getRNG();
                sidecar.addProperty(
                    "randomClass",
                    random.getClass()
                        .getName());
                sidecar.addProperty("randomObjectIdentity", System.identityHashCode(random));
                var bubbles = maid.getChatBubbleManager()
                    .getChatBubbleDataCollection();
                sidecar.addProperty("bubbleCount", bubbles.size());
                sidecar.addProperty("bubbleEmpty", bubbles.isEmpty());
                int rate = MaidChatConfig.checkRate;
                sidecar.addProperty("emojiCheckRate", rate);
                sidecar.addProperty(
                    "emojiAgeEligible",
                    rate > 0 && (maid.ticksExisted + maid.getUniqueID()
                        .getLeastSignificantBits() % rate) % rate == 0);
                sidecar.addProperty("status", "OBSERVED_PUBLIC_READ_ONLY");
            } catch (Throwable unavailable) {
                IntegrationGuard.throwIfFatal(unavailable);
                sidecar.addProperty("status", "UNAVAILABLE");
                sidecar.addProperty(
                    "failureClass",
                    unavailable.getClass()
                        .getName());
            }
            if (identitySidecars.size() < 16) identitySidecars.add(sidecar);
        }

        void retain(String suffix) {
            if (retained) return;
            report.add("frames", frames);
            report.add("events", events);
            report.add("inventorySnapshots", items);
            report.add("actualArrowIdentities", arrowIds);
            report.add("publicMaidIdentityReadbacks", identityReadbacks);
            report.add("passiveIdentitySidecars", identitySidecars);
            if (ownedSoul != null) {
                meterOwnedSoul(() -> report.add("ownedSoulProtocol", ownedSoul.snapshot()));
                report.addProperty("ownedSoulObserverCalls", ownedSoulObserverCalls);
                report.addProperty("ownedSoulObserverCpuNanos", ownedSoulObserverCpuNanos);
                report.addProperty("ownedSoulObserverWallNanos", ownedSoulObserverWallNanos);
                report.addProperty("ownedSoulCostScope", "Fixture metered closure and continuous active window; existing END observer field unchanged");
            }
            report.add("passiveHealRequests", healRequests);
            report.addProperty("passiveHealRequestsSeen", healRequestsSeen);
            report.addProperty("passiveHealRequestsTruncated", healRequestsTruncated);
            report.addProperty("passiveHealDiagnosticUnavailable", healDiagnosticUnavailable);
            report.addProperty("passiveHealForeignThreadObserved", healForeignThreadObserved);
            report.addProperty("passiveHealDiagnosticCpuNanos", healDiagnosticCpuNanos);
            report.addProperty("passiveHealDiagnosticWallNanos", healDiagnosticWallNanos);
            report.addProperty(
                "passiveHealScope",
                "LOWEST observed requests before native heal result; original END HP remains result, not added to comparable/events or used for admission");
            if (arrowInput != null) report.add("arrowRandomInput", arrowInput.snapshot());
            if (session != null) report.add("developmentArm", session.snapshot());
            if (probe != null) report.add("cost", probe.snapshot());
            try {
                if (enemy != null) report.add("targetFinalNbt", SleepAttributePortSnapshot.binaryNbt(entityNbt(enemy)));
                if (maid != null) {
                    report.add("maidFinalNbt", SleepAttributePortSnapshot.binaryNbt(entityNbt(maid)));
                    report.add("ownDeathEntry", SleepAttributePortSnapshot.binaryNbt(deathEntry()));
                }
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("finalSnapshotRetentionFailure", failure.toString());
            }
            r.write(name + "-" + suffix, report);
            retained = true;
        }

        void drainChannel() {
            if (channel == null) return;
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        }

        void close() {
            if (closed) return;
            closed = true;
            cleanup(() -> { if (arrowInput != null) arrowInput.close(); });
            cleanup(
                () -> {
                    if (maid != null) maid.getBrainCoordinator()
                        .setSuspended(true);
                });
            cleanup(() -> { if (probe != null) probe.close(); });
            cleanup(() -> { if (session != null) session.close(); });
            cleanup(() -> {
                if (maid == null) return;
                require(CombatEffectPilotSession.current(maid) == null, "Development arm lease leaked");
                CombatWorldBudget.cancel(r.world, maid.getUniqueID());
                UUID token = MaidLifecycleData.get(r.world)
                    .deathToken(maid.getUniqueID());
                for (Object raw : r.world.loadedEntityList.toArray())
                    if (raw instanceof MaidTombstoneEntity grave && token != null && token.equals(grave.getToken()))
                        owned.add(grave);
            });
            for (Entity entity : owned) if (entity != owner && entity.worldObj == r.world) {
                cleanup(entity::setDead);
                cleanup(() -> r.world.removePlayerEntityDangerously(entity));
            }
            cleanup(() -> {
                if (owner != null && r.server.getConfigurationManager().playerEntityList.contains(owner))
                    r.server.getConfigurationManager()
                        .playerLoggedOut(owner);
            });
            cleanup(() -> {
                if (owner != null) {
                    r.world.playerEntities.remove(owner);
                    r.server.getConfigurationManager().playerEntityList.remove(owner);
                    owner.setDead();
                    r.world.removePlayerEntityDangerously(owner);
                }
            });
            cleanup(() -> {
                if (channel != null) {
                    channel.finish();
                    drainChannel();
                }
            });
            cleanup(() -> {
                for (Entity entity : owned) require(
                    r.world.getEntityByID(entity.getEntityId()) != entity && !r.world.loadedEntityList.contains(entity),
                    "Exact effect actor leaked at cleanup");
            });
            cleanup(() -> {
                for (int x = 0; x <= 20; x++)
                    for (int y = 0; y <= 10; y++) for (int z = 0; z <= 8; z++) r.h.setBlock(x, y, z, Blocks.air);
            });
            if (ownedSoul != null) cleanup(() -> meterOwnedSoul(() -> {
                ownedSoul.observe();
                JsonObject finalSoul = ownedSoul.snapshot();
                finalSoul.addProperty("cleanupSucceeded", cleanupFailure == null);
                finalSoul.addProperty("observerCpuNanos", ownedSoulObserverCpuNanos);
                finalSoul.addProperty("observerWallNanos", ownedSoulObserverWallNanos);
                r.write(name + "-owned-soul-final", finalSoul);
            }));
            if (cleanupFailure != null) throw cleanupFailure;
        }

        void cleanup(Runnable operation) {
            try {
                operation.run();
            } catch (RuntimeException | Error failure) {
                IntegrationGuard.throwIfFatal(failure);
                if (cleanupFailure == null)
                    cleanupFailure = new IllegalStateException("Effect cleanup failed", failure);
                else cleanupFailure.addSuppressed(failure);
            }
        }
    }

    /** Forge ASM接收器必须public，观察器异常留给HQA，不反抛污染真实伤害回调 */
    public static final class Events {

        private final Runner r;
        private EffectArrowRandomInput.StackBridge healStack;

        Events(Runner r) {
            this.r = r;
        }

        private void guarded(Runnable action) {
            if (r.asynchronousFailure != null) return;
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                IntegrationGuard.throwIfFatal(failure);
                r.asynchronousFailure = failure;
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void beginOwnedDeath(LivingDeathEvent event) {
            guarded(() -> {
                Trial t = r.trial;
                if (t != null && t.ownedSoul != null) t.meterOwnedSoul(() -> t.ownedSoul.begin(event));
            });
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void settleOwnedSoul(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            guarded(() -> {
                Trial t = r.trial;
                if (t != null && t.ownedSoul != null) t.meterOwnedSoul(t.ownedSoul::settle);
            });
        }

        @SubscribeEvent
        public void server(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START && r.trial != null) guarded(r.trial::driveOwner);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void save(WorldEvent.Save event) {
            guarded(() -> r.saveWindow.save(event.world, r.trial == null, r.previousCleanupSucceeded));
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void heal(LivingHealEvent event) {
            Trial t = r.trial;
            if (t == null || t.closed
                || (event.entityLiving != t.maid && event.entityLiving != t.owner && event.entityLiving != t.enemy))
                return;
            if (Thread.currentThread() != r.ownerThread) {
                t.healForeignThreadObserved = true;
                return;
            }
            if (event.entityLiving.worldObj != r.world) return;
            t.healRequestsSeen++;
            if (t.healRequests.size() >= 256) {
                t.healRequestsTruncated++;
                return;
            }
            long wall = System.nanoTime(), cpu = r.clock.getCurrentThreadCpuTime();
            JsonObject row = new JsonObject();
            try {
                row.addProperty("actor", t.role(event.entityLiving));
                row.addProperty(
                    "uuid",
                    event.entityLiving.getUniqueID()
                        .toString());
                row.addProperty("serverTick", r.server.getTickCounter());
                row.addProperty("worldTick", r.world.getTotalWorldTime());
                row.addProperty("frame", t.activeAt == Long.MAX_VALUE ? -1 : t.frame());
                row.addProperty("ticksExisted", event.entityLiving.ticksExisted);
                IdentityInputs.diagnosticFloat(row, "requestedAmountAtObserver", "requestedAmountBits", event.amount);
                row.addProperty("cancelledAtObserver", event.isCanceled());
                IdentityInputs.diagnosticFloat(
                    row,
                    "healthBeforeNativeHeal",
                    "healthBeforeNativeHealBits",
                    event.entityLiving.getHealth());
                if (healStack == null) healStack = new EffectArrowRandomInput.StackBridge();
                var captured = healStack.capture();
                JsonArray frames = new JsonArray();
                for (int i = 0; i < captured.size() && i < 32; i++) {
                    var frame = captured.get(i);
                    JsonObject raw = new JsonObject();
                    raw.addProperty("class", frame.type.getName());
                    raw.addProperty("classIdentity", System.identityHashCode(frame.type));
                    raw.addProperty("method", frame.method);
                    raw.addProperty("descriptor", frame.descriptor);
                    frames.add(raw);
                }
                row.add("rawTypedFrames", frames);
                row.addProperty("frameCountCaptured", captured.size());
                row.addProperty("status", captured.size() > 32 ? "TRUNCATED" : "OBSERVED_REQUEST_ONLY");
            } catch (Throwable unavailable) {
                IntegrationGuard.throwIfFatal(unavailable);
                t.healDiagnosticUnavailable++;
                row.addProperty("status", "UNAVAILABLE");
                row.addProperty(
                    "failureClass",
                    unavailable.getClass()
                        .getName());
            } finally {
                t.healRequests.add(row);
                long after = r.clock.getCurrentThreadCpuTime();
                if (cpu >= 0 && after >= cpu) t.healDiagnosticCpuNanos += after - cpu;
                t.healDiagnosticWallNanos += System.nanoTime() - wall;
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void prepareTargetUpdate(LivingUpdateEvent event) {
            Trial t = r.trial;
            if (t != null && t.preparingEnemy() && event.entityLiving == t.enemy)
                guarded(() -> t.suppressPreparedEnemyUpdate(event));
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void spawn(EntityJoinWorldEvent event) {
            guarded(() -> {
                Trial t = r.trial;
                if (t == null || t.closed || event.world != r.world) return;
                if (t.ownedSoul != null) t.meterOwnedSoul(() -> t.ownedSoul.join(event));
                if (event.isCanceled()) return;
                if (event.entity instanceof EntityArrow arrow
                    && (arrow.shootingEntity == t.maid || arrow.shootingEntity == t.enemy)) {
                    require(t.arrows.size() < 128, "Effect projectile records exceeded");
                    t.owned.add(arrow);
                    t.arrows.add(arrow);
                    var row = t.event("arrow-spawn", arrow, null);
                    row.addProperty("shooter", t.role(arrow.shootingEntity));
                    row.add("initial", state(arrow));
                    t.events.add(row);
                    var identity = new JsonObject();
                    identity.addProperty("arrow", t.arrows.size() - 1);
                    identity.addProperty(
                        "uuid",
                        arrow.getUniqueID()
                            .toString());
                    t.arrowIds.add(identity);
                    require(!t.preparingEnemy(), "Offensive arrow appeared during target preparation");
                } else if (event.entity instanceof EntityItem drop) {
                    EntityLivingBase source = t.witnessedDrops.get(drop);
                    var row = t.event("item-spawn-source", drop, null);
                    row.addProperty("hasOwnedLivingDropsWitness", source != null);
                    if (source != null) row.addProperty("sourceActor", t.role(source));
                    t.events.add(row);
                    require(
                        source != null,
                        "Unknown item in exclusive effect world; retained and not adopted for cleanup");
                    require(!t.owned.contains(drop), "Owned drop joined more than once");
                    t.owned.add(drop);
                }
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void drops(LivingDropsEvent event) {
            guarded(() -> {
                Trial t = r.trial;
                if (t == null || t.closed
                    || event.entityLiving.worldObj != r.world
                    || (event.entityLiving != t.maid && event.entityLiving != t.owner && event.entityLiving != t.enemy))
                    return;
                var row = t.event("owned-living-drops", event.entityLiving, event.source);
                row.addProperty("cancelled", event.isCanceled());
                row.addProperty("count", event.drops.size());
                t.events.add(row);
                if (event.isCanceled()) return;
                require(event.entityLiving.getHealth() <= 0, "LivingDrops source has no native death state");
                for (EntityItem drop : event.drops) {
                    require(
                        drop != null && drop.worldObj == r.world
                            && !t.witnessedDrops.containsKey(drop)
                            && !t.owned.contains(drop)
                            && !r.world.loadedEntityList.contains(drop)
                            && r.world.getEntityByID(drop.getEntityId()) != drop,
                        "LivingDrops did not provide a fresh exact owned-world item");
                    require(t.witnessedDrops.size() < 128, "Owned LivingDrops witnesses exceeded");
                    t.witnessedDrops.put(drop, event.entityLiving);
                }
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void attack(LivingAttackEvent event) {
            guarded(() -> {
                Trial t = r.trial;
                if (t != null && t.preparingEnemy()
                    && (event.entityLiving == t.maid || event.entityLiving == t.owner
                        || event.entityLiving == t.enemy)) {
                    t.preparationDamageRequests++;
                    t.report.addProperty("targetPreparationDamageRequests", t.preparationDamageRequests);
                    throw new IllegalStateException("Native damage request during noncombat target preparation");
                }
                if (!watch(t, event.entityLiving)) return;
                var row = t.event("damage-request", event.entityLiving, event.source);
                row.addProperty("amount", event.ammount);
                row.addProperty("cancelled", event.isCanceled());
                t.events.add(row);
                if (event.source.getEntity() == t.maid) t.outgoingRequests++;
                require(
                    event.entityLiving != t.owner || event.source.getEntity() != t.maid,
                    "Maid issued friendly owner damage request");
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            guarded(() -> {
                Trial t = r.trial;
                if (!watch(t, event.entityLiving)) return;
                var row = t.event("native-hurt-stage", event.entityLiving, event.source);
                row.addProperty("amount", event.ammount);
                row.addProperty("cancelled", event.isCanceled());
                row.addProperty("isAttackEntityFromReturn", false);
                t.events.add(row);
                require(
                    event.entityLiving != t.enemy || event.source.getEntity() == t.maid,
                    "Undeclared environmental damage changed target");
            });
        }

        @SubscribeEvent
        public void pre(MaidHurtTarget.Pre event) {
            guarded(() -> {
                Trial t = r.trial;
                if (t != null && event.getMaid() == t.maid) t.pre++;
            });
        }

        @SubscribeEvent
        public void post(MaidHurtTarget.Post event) {
            guarded(() -> {
                Trial t = r.trial;
                if (t == null || event.getMaid() != t.maid) return;
                t.posts++;
                if (event.isHurt()) t.accepted++;
                var row = t.event("melee-post", event.getTarget(), null);
                row.addProperty("accepted", event.isHurt());
                t.events.add(row);
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void death(LivingDeathEvent event) {
            guarded(() -> {
                Trial t = r.trial;
                if (!watch(t, event.entityLiving)) {
                    if (t != null && t.ownedSoul != null) t.meterOwnedSoul(() -> t.ownedSoul.end(event));
                    return;
                }
                t.ledger(event.entityLiving, true);
                var row = t.event("native-death", event.entityLiving, event.source);
                row.addProperty("cancelled", event.isCanceled());
                t.events.add(row);
                if (t.ownedSoul != null) t.meterOwnedSoul(() -> t.ownedSoul.end(event));
            });
        }

        private static boolean watch(Trial t, EntityLivingBase actor) {
            return t != null && !t.closed
                && t.activeAt != Long.MAX_VALUE
                && (actor == t.maid || actor == t.owner || actor == t.enemy);
        }
    }

    private static JsonObject state(Entity entity) {
        var row = new JsonObject();
        row.addProperty("x", entity.posX);
        row.addProperty("y", entity.posY);
        row.addProperty("z", entity.posZ);
        row.addProperty("xBits", Long.toHexString(Double.doubleToLongBits(entity.posX)));
        row.addProperty("yBits", Long.toHexString(Double.doubleToLongBits(entity.posY)));
        row.addProperty("zBits", Long.toHexString(Double.doubleToLongBits(entity.posZ)));
        row.addProperty("motionXBits", Long.toHexString(Double.doubleToLongBits(entity.motionX)));
        row.addProperty("motionYBits", Long.toHexString(Double.doubleToLongBits(entity.motionY)));
        row.addProperty("motionZBits", Long.toHexString(Double.doubleToLongBits(entity.motionZ)));
        row.addProperty("yawBits", Float.floatToIntBits(entity.rotationYaw));
        row.addProperty("pitchBits", Float.floatToIntBits(entity.rotationPitch));
        row.addProperty("fallDistanceBits", Float.floatToIntBits(entity.fallDistance));
        row.addProperty("ground", entity.onGround);
        row.addProperty("dead", entity.isDead);
        if (entity instanceof EntityLivingBase living) {
            row.addProperty("hp", living.getHealth());
            row.addProperty("hpBits", Float.floatToIntBits(living.getHealth()));
            row.addProperty("absorptionBits", Float.floatToIntBits(living.getAbsorptionAmount()));
        }
        return row;
    }

    private static NBTTagCompound entityNbt(Entity entity) {
        var saved = new NBTTagCompound();
        entity.writeToNBT(saved);
        return (NBTTagCompound) saved.copy();
    }

    private static void orientation(EntityLivingBase entity) {
        entity.rotationYaw = entity.rotationYawHead = entity.renderYawOffset = 0;
        entity.prevRotationYaw = entity.prevRotationYawHead = entity.prevRenderYawOffset = 0;
        entity.rotationPitch = entity.prevRotationPitch = 0;
    }

    private static void requireSameInput(NBTTagCompound expected, NBTTagCompound actual) {
        if (!expected.equals(actual))
            throw new IllegalStateException("Initial typed NBT mismatch; pairing refused before first active tick");
    }

    private static String trialPrefix() {
        return TACTICS == null ? "s20901" : "tactics-" + Long.toHexString(SEED);
    }

    private static final class TacticsInputs {

        final JsonObject input;
        final long seed;
        final String[] specs;
        final String inputSha, contractSha;

        private TacticsInputs(JsonObject input, String inputSha, String contractSha) {
            this.input = input;
            this.inputSha = inputSha;
            this.contractSha = contractSha;
            require(
                "tlmnh-tactics-block-v1".equals(
                    input.get("schema")
                        .getAsString()),
                "Wrong tactics input schema");
            seed = Long.parseLong(
                input.get("logicalSeed")
                    .getAsString());
            require(seed > 0 && seed < Long.MAX_VALUE - 4 && seed != 0x20902L, "Invalid sealed tactics seed");
            require(
                contractSha.equals(
                    input.get("contractSha256")
                        .getAsString()),
                "Tactics contract binding differs");
            String mode = input.get("samplingMode")
                .getAsString();
            require(mode.equals("calibration") || mode.equals("formal"), "Wrong tactics sampling mode");
            var order = input.getAsJsonArray("specs");
            require(order.size() == 12, "Twelve frozen within-block runs required");
            specs = new String[12];
            var unique = new HashSet<String>();
            for (int index = 0; index < specs.length; index++) {
                specs[index] = order.get(index)
                    .getAsString();
                require(
                    specs[index].matches("[01]:[ABC]:[01]") && unique.add(specs[index]),
                    "Frozen tactics order contains missing or duplicate cells");
            }
        }

        static TacticsInputs open() {
            if (!Boolean.getBoolean("tlmnh.qaTactics")) return null;
            try {
                Path input = Paths.get(System.getProperty("tlmnh.qaTacticsInput"));
                String text = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
                String hash = digest(text);
                require(hash.equals(System.getProperty("tlmnh.qaTacticsInputSha256")), "Tactics block input drift");
                Path contract = Paths.get(System.getProperty("tlmnh.qaTacticsContract"));
                String contractText = new String(Files.readAllBytes(contract), StandardCharsets.UTF_8);
                String contractSha = digest(contractText);
                require(
                    contractSha.equals(System.getProperty("tlmnh.qaTacticsContractSha256")),
                    "Tactics contract drift");
                var parser = new com.google.gson.JsonParser();
                JsonObject actual = parser.parse(text)
                    .getAsJsonObject();
                JsonObject policy = parser.parse(contractText)
                    .getAsJsonObject();
                require(
                    actual.get("samplingMode")
                        .equals(policy.get("samplingMode")),
                    "Tactics mode binding differs");
                return new TacticsInputs(actual, hash, contractSha);
            } catch (Exception problem) {
                throw new IllegalStateException("Cannot bind frozen tactics inputs", problem);
            }
        }
    }

    private static UUID id(String role) {
        String namespace = TACTICS == null ? "tlmnh-effect-20901-" : "tlmnh-tactics-" + Long.toHexString(SEED) + "-";
        return UUID.nameUUIDFromBytes((namespace + role).getBytes(StandardCharsets.UTF_8));
    }

    /** 原18格的公开构造期身份，保持完整UUID唯一；不拥有RNG或运行期身份变更 */
    static final class IdentityInputs {

        static UUID maid(String run) {
            return new UUID(id(run + ":maid").getMostSignificantBits(), id("logical-maid").getLeastSignificantBits());
        }

        static JsonObject row(String run) {
            UUID old = id(run + ":maid"), next = maid(run), logical = id("logical-maid");
            JsonObject row = new JsonObject();
            row.addProperty("run", run);
            row.addProperty("oldMaidUuid", old.toString());
            row.addProperty("maidUuid", next.toString());
            row.addProperty("logicalMaidUuid", logical.toString());
            row.addProperty("leastSigned", next.getLeastSignificantBits());
            row.addProperty("emojiEnabled", MaidChatConfig.enableEmoji);
            row.addProperty("emojiCheckRate", MaidChatConfig.checkRate);
            row.addProperty("imageWeight", MaidChatConfig.imageWeight);
            row.addProperty("kaomojiWeight", MaidChatConfig.kaomojiWeight);
            if (MaidChatConfig.checkRate > 0)
                row.addProperty("signedRemainder", next.getLeastSignificantBits() % MaidChatConfig.checkRate);
            row.addProperty(
                "periodicEmojiCoverage",
                MaidChatConfig.enableEmoji && MaidChatConfig.checkRate == 1200
                    && MaidChatConfig.imageWeight == 10
                    && MaidChatConfig.kaomojiWeight == 10
                        ? "NOT_EXERCISED_UNDER_THE_FROZEN_1200_RATE_AND_MAX448_ENTITY_TICKS"
                        : "UNKNOWN_CONFIG_DIFFERS");
            return row;
        }

        static void diagnosticFloat(JsonObject row, String valueKey, String bitsKey, float value) {
            row.addProperty(bitsKey, Float.floatToRawIntBits(value));
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                row.add(valueKey, JsonNull.INSTANCE);
                row.addProperty(valueKey + "Status", "NON_FINITE_BITS_ONLY");
            } else row.addProperty(valueKey, value);
        }

        static JsonObject preflight(String[] specs) {
            require(specs.length == 12, "Original identity preflight requires twelve specs");
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= 4; i++) names.add("method-v6-" + i);
            names.add("input-canary");
            names.add("wait-canary");
            for (String spec : specs) {
                String[] parts = spec.split(":");
                names.add(trialPrefix() + "-g" + parts[0] + "-" + parts[1] + "-r" + parts[2]);
            }
            var all = new HashSet<UUID>();
            var maids = new HashSet<UUID>();
            JsonArray identities = new JsonArray();
            for (String name : names) {
                UUID maid = maid(name), owner = id(name + ":owner"), enemy = id(name + ":enemy");
                require(
                    maids.add(maid) && all.add(maid) && all.add(owner) && all.add(enemy),
                    "Declared actor UUID collision; no nonce or salt retry");
                JsonObject row = row(name);
                row.addProperty("ownerUuidUnchanged", owner.toString());
                row.addProperty("enemyUuidUnchanged", enemy.toString());
                identities.add(row);
            }
            require(names.size() == 18 && maids.size() == 18 && all.size() == 54, "Incomplete fixed identity set");
            JsonObject result = new JsonObject();
            result.addProperty("revision", IDENTITY_REVISION);
            result.addProperty("designSha256", IDENTITY_DESIGN);
            result.addProperty("sourceLedgerSha256", IDENTITY_LEDGER);
            result.addProperty("maidCount", maids.size());
            result.addProperty("allActorCount", all.size());
            result.addProperty(
                "scope",
                "Original run-specific maid MSB plus preexisting logical-maid LSB; unique only within this fixed fresh18-case suite, not a full128-bit name hash");
            result.add("identities", identities);
            return result;
        }
    }

    private static ItemStack tagged(ItemStack stack, String value) {
        var data = new NBTTagCompound();
        data.setString(PAYLOAD, value);
        data.setByteArray("FullPayload", new byte[] { 2, 0, 9 });
        stack.setTagCompound(data);
        return stack;
    }

    private static String digest(String text) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder();
            for (byte value : bytes) result.append(Character.forDigit((value & 255) >>> 4, 16))
                .append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
