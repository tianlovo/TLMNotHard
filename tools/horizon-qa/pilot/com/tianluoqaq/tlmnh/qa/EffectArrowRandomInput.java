package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIArrowAttack;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent.EntityConstructing;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatProjectileEvents;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.MaidUnifiedCombatActionRunner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.combat.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.development.combat.CombatEffectPilotSession;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.runtime.MaidBehaviorProtection;
import com.tianluoqaq.tlmnh.compat.bugtorch.development.random.BugtorchArrowRandom;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 已批准v6的单个QA试点输入控制；不进入生产JAR，不改箭的物理或敌方计时器 */
public final class EffectArrowRandomInput implements AutoCloseable {

    static final String CONTRACT = "c08319698ea84adce21a1ff6e91c885b572ee24dee9f6c4734947bcd94c59b6f";
    static final String NAMESPACE = "tlmnh:effect-arrow-random-input/v6";
    static final long CANARY_A = 0x20901L, CANARY_B = 0x20902L;
    private static final int FRAME_LIMIT = 128, RECEIPT_LIMIT = 256, AUDIT_LIMIT = 768;
    private static EffectArrowRandomInput active;
    private static Platform platform;

    enum Purpose {

        TARGET_LAUNCH("target", "launch"),
        MAID_LAUNCH("maid", "launch"),
        MAID_PREVIEW("maid", "preview");

        final String role, use;

        Purpose(String role, String use) {
            this.role = role;
            this.use = use;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final WorldServer world;
    private final WorldProvider provider;
    private final int dimension;
    private final EntityMaid maid;
    private final EntitySkeleton target;
    private final EntityLivingBase ownedOwner;
    private final UUID maidId, targetId;
    private final EnhancedCoreBrain core;
    private final CombatEffectPilotSession combatSession;
    private final String run;
    private final String ownerIdentity;
    private final long logicalSeed;
    private final boolean methodCanary;
    private final Consumer<Throwable> failureSink;
    private final ThreadMXBean clock = ManagementFactory.getThreadMXBean();
    private final Listener listener = new Listener(this);
    private final long[] attempts = new long[Purpose.values().length];
    private final IdentityHashMap<EntityArrow, Receipt> receipts = new IdentityHashMap<>();
    private final List<Receipt> order = new ArrayList<>();
    private final JsonArray audit = new JsonArray();
    private JsonArray lastConstructionProof = new JsonArray();
    private Credential credential;
    private Explicit explicit;
    private long activeAt = Long.MAX_VALUE, calls, cpuNanos, wallNanos, rejected, openCpuNanos, openWallNanos;
    private boolean closed, invalid, registered, expectUnscoped, maidDeathObserved, targetDeathObserved;

    static boolean enabled() {
        return Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaCombatEffectPilot")
            && Boolean.getBoolean("tlmnh.qaEffectArrowRandomInput")
            && Loader.isModLoaded("tlmnhqapilot")
            && CONTRACT.equals(System.getProperty("tlmnh.qaEffectContractSha256", ""));
    }

    private static void gate() {
        require(enabled(), "Explicit approved v6 arrow-input gates required");
    }

    private static Platform api() {
        gate();
        if (platform == null) platform = new Platform();
        return platform;
    }

    static JsonObject capabilities() throws Exception {
        gate();
        ThreadMXBean timer = ManagementFactory.getThreadMXBean();
        require(timer.isCurrentThreadCpuTimeSupported(), "V6 preflight CPU clock unavailable");
        boolean restore = !timer.isThreadCpuTimeEnabled();
        if (restore) timer.setThreadCpuTimeEnabled(true);
        long startedWall = System.nanoTime(), startedCpu = timer.getCurrentThreadCpuTime();
        JsonObject result = new JsonObject();
        try {
            result = api().audit();
            result.add("nativeArrowRandomRevision", BugtorchArrowRandom.revision());
            result.add(
                "qaArtifacts",
                DevelopmentArtifactIdentity.capture(
                    UnifiedCombatEffectPilotTests.class,
                    EffectArrowRandomInput.class,
                    Platform.class,
                    StackBridge.class,
                    BugtorchArrowRandom.class));
            result.add(
                "productionArtifacts",
                DevelopmentArtifactIdentity
                    .capture(MaidBowAttack.class, CombatProjectileEvents.class, CombatEffectPilotSession.class));
            return result;
        } finally {
            long after = timer.getCurrentThreadCpuTime();
            result.addProperty("preflightCpuNanos", startedCpu >= 0 && after >= startedCpu ? after - startedCpu : -1);
            result.addProperty("preflightWallNanos", System.nanoTime() - startedWall);
            result.addProperty(
                "preflightCostScope",
                "Actual startup identity/API audit outside actor windows, reported with this experiment; not silently deducted");
            if (restore) timer.setThreadCpuTimeEnabled(false);
        }
    }

    static EffectArrowRandomInput open(WorldServer world, EntityMaid maid, EntitySkeleton target,
        CombatEffectPilotSession combatSession, EntityLivingBase ownedOwner, String run, long logicalSeed,
        boolean canary, Consumer<Throwable> failure) {
        gate();
        require(active == null, "Another arrow-input lease is active");
        require(
            run != null && !run.trim()
                .isEmpty(),
            "Unique nonempty v6 run required");
        require(
            world != null && !world.isRemote
                && maid != null
                && maid.getClass() == EntityMaid.class
                && target != null
                && target.getClass() == EntitySkeleton.class
                && target.ticksExisted == 0
                && maid.isEntityAlive()
                && target.isEntityAlive()
                && world.getEntityByID(maid.getEntityId()) == maid
                && world.getEntityByID(target.getEntityId()) == target
                && maid.worldObj == world
                && target.worldObj == world
                && ownedOwner != null
                && ownedOwner.worldObj == world
                && world.getEntityByID(ownedOwner.getEntityId()) == ownedOwner
                && world.playerEntities.contains(ownedOwner)
                && combatSession != null
                && CombatEffectPilotSession.current(maid) == combatSession
                && maid.dimension == world.provider.dimensionId
                && target.dimension == world.provider.dimensionId
                && maid.getBrainCoordinator()
                    .isSuspended(),
            "Fresh exact owned arrow actors required");
        ThreadMXBean timer = ManagementFactory.getThreadMXBean();
        require(timer.isThreadCpuTimeEnabled(), "V6 lease CPU clock unavailable");
        long startedWall = System.nanoTime(), startedCpu = timer.getCurrentThreadCpuTime();
        require(startedCpu >= 0, "V6 lease CPU clock has no actual reading");
        api().stack.requireServerThread();
        var result = new EffectArrowRandomInput(
            world,
            maid,
            target,
            combatSession,
            ownedOwner,
            run,
            logicalSeed,
            canary,
            failure);
        active = result;
        try {
            MinecraftForge.EVENT_BUS.register(result.listener);
            FMLCommonHandler.instance()
                .bus()
                .register(result.listener);
            result.registered = true;
            long after = timer.getCurrentThreadCpuTime();
            require(after >= startedCpu, "V6 lease CPU clock became unavailable");
            result.openCpuNanos = after - startedCpu;
            result.openWallNanos = System.nanoTime() - startedWall;
            return result;
        } catch (RuntimeException | Error rejected) {
            IntegrationGuard.throwIfFatal(rejected);
            try {
                MinecraftForge.EVENT_BUS.unregister(result.listener);
            } catch (RuntimeException | Error cleanup) {
                IntegrationGuard.throwIfFatal(cleanup);
                rejected.addSuppressed(cleanup);
            }
            try {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(result.listener);
            } catch (RuntimeException | Error cleanup) {
                IntegrationGuard.throwIfFatal(cleanup);
                rejected.addSuppressed(cleanup);
            }
            active = null;
            result.closed = true;
            throw rejected;
        }
    }

    private EffectArrowRandomInput(WorldServer world, EntityMaid maid, EntitySkeleton target,
        CombatEffectPilotSession combatSession, EntityLivingBase ownedOwner, String run, long seed, boolean canary,
        Consumer<Throwable> failure) {
        this.world = world;
        provider = world.provider;
        dimension = world.provider.dimensionId;
        this.maid = maid;
        this.target = target;
        this.ownedOwner = ownedOwner;
        maidId = maid.getUniqueID();
        targetId = target.getUniqueID();
        core = maid.getBrainCoordinator()
            .peekEnhancedCore();
        this.combatSession = combatSession;
        this.run = run;
        ownerIdentity = maid.func_152113_b();
        logicalSeed = seed;
        methodCanary = canary;
        failureSink = failure;
        require(
            clock.isThreadCpuTimeSupported() && clock.isThreadCpuTimeEnabled() && clock.getCurrentThreadCpuTime() >= 0,
            "Actual v6 control CPU clock required");
    }

    void activate(long at) {
        check();
        require(
            !methodCanary && activeAt == Long.MAX_VALUE && at > world.getTotalWorldTime(),
            "Future v6 activation required");
        activeAt = at;
        credential = null;
    }

    private void check() {
        gate();
        require(
            !closed && active == this
                && Thread.currentThread() == owner
                && world.provider == provider
                && world.provider.dimensionId == dimension
                && maid.worldObj == world
                && target.worldObj == world
                && maid.dimension == dimension
                && target.dimension == dimension
                && maid.getUniqueID()
                    .equals(maidId)
                && target.getUniqueID()
                    .equals(targetId)
                && java.util.Objects.equals(ownerIdentity, maid.func_152113_b())
                && (maidDeathObserved ? !maid.isEntityAlive()
                    : world.getEntityByID(maid.getEntityId()) == maid && maid.getBrainCoordinator()
                        .peekEnhancedCore() == core)
                && (targetDeathObserved ? !target.isEntityAlive() : world.getEntityByID(target.getEntityId()) == target)
                && api().session(maid) == combatSession,
            "Expired v6 thread/world/actor/session lease");
        require(!invalid, "v6 input lease already failed");
    }

    private void guarded(Runnable action) {
        long wall = System.nanoTime(), cpu = clock.getCurrentThreadCpuTime();
        try {
            require(cpu >= 0 && clock.isThreadCpuTimeEnabled(), "Actual v6 control CPU clock unavailable");
            check();
            action.run();
        } catch (RuntimeException | Error failure) {
            refused(failure);
        } finally {
            calls++;
            long after = clock.getCurrentThreadCpuTime();
            if (cpu >= 0 && after >= cpu) cpuNanos += after - cpu;
            else refused(new IllegalStateException("Actual v6 control CPU clock lost during callback"));
            wallNanos += System.nanoTime() - wall;
        }
    }

    private void refused(Throwable failure) {
        IntegrationGuard.throwIfFatal(failure);
        invalid = true;
        credential = null;
        explicit = null;
        if (audit.size() < AUDIT_LIMIT) {
            var row = new JsonObject();
            var causes = new JsonArray();
            Throwable cause = failure;
            for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause())
                causes.add(new JsonPrimitive(cause.toString()));
            row.addProperty("outcome", "CONTROL_REFUSED");
            row.add("causes", causes);
            audit.add(row);
        }
        failureSink.accept(failure);
    }

    private void living(LivingUpdateEvent event) {
        credential = null;
        if (event.isCanceled() || methodCanary || activeAt == Long.MAX_VALUE || world.getTotalWorldTime() < activeAt)
            return;
        EntityLivingBase actor = event.entityLiving;
        if (actor != maid && actor != target) return;
        if (actor == maid ? maidDeathObserved : targetDeathObserved) return;
        List<Frame> frames = api().stack.capture();
        require(api().countLivingUpdates(frames) == 1, "Nested/reentrant LivingUpdate cannot authorize arrow input");
        credential = new Credential(actor, world.getTotalWorldTime(), actor.ticksExisted);
    }

    private void constructing(EntityConstructing event) {
        if (event.entity.worldObj != world || !(event.entity instanceof EntityArrow)) return;
        EntityArrow arrow = (EntityArrow) event.entity;
        require(arrow.getClass() == EntityArrow.class, "Uncertified arrow subtype in exclusive v6 world");
        require(!receipts.containsKey(arrow), "Repeated arrow construction input");
        List<Frame> frames = api().stack.capture();
        lastConstructionProof = new JsonArray();
        for (Frame frame : frames) lastConstructionProof.add(new JsonPrimitive(frame.fingerprint()));
        Purpose purpose;
        EntityLivingBase expected;
        String fingerprint;
        if (explicit != null) {
            require(
                methodCanary && explicit.tick == world.getTotalWorldTime(),
                "Expired explicit canary constructor scope");
            api().certifyCanary(frames);
            purpose = explicit.purpose;
            expected = explicit.expected;
            fingerprint = "EXPLICIT_CANARY/" + purpose;
        } else {
            try {
                sourceRequire(
                    credential != null && credential.tick == world.getTotalWorldTime()
                        && credential.actor.ticksExisted == credential.actorTick,
                    "No current native LivingUpdate evidence for arrow construction");
                purpose = api().certify(frames, credential.actor == target);
                expected = purpose == Purpose.TARGET_LAUNCH ? target : maid;
                sourceRequire(expected == credential.actor, "Arrow source differs from LivingUpdate actor");
                fingerprint = api().fingerprint(frames);
            } catch (SourceRefusal refused) {
                if (!methodCanary || !expectUnscoped) throw refused;
                require(
                    api().countLivingUpdates(frames) == 0,
                    "Negative no-scope case unexpectedly retained a native update body");
                rejected++;
                addAudit("UNSCOPED_NOT_SEEDED: " + refused.getMessage(), 0);
                return;
            }
        }
        require(order.size() < RECEIPT_LIMIT, "Bounded arrow-input receipts exhausted");
        long attempt = attempts[purpose.ordinal()]++;
        Random random = api().random(arrow);
        Random[] aliases = new Random[4 + order.size()];
        aliases[0] = world.rand;
        aliases[1] = maid.getRNG();
        aliases[2] = target.getRNG();
        aliases[3] = ownedOwner.getRNG();
        for (int i = 0; i < order.size(); i++) aliases[4 + i] = order.get(i).random;
        requireUnaliased(random, aliases);
        JsonObject randomIdentity = api().randomIdentity(random);
        long seed = seed(logicalSeed, purpose, attempt);
        var receipt = new Receipt(
            arrow,
            random,
            purpose,
            attempt,
            seed,
            expected,
            world.getTotalWorldTime(),
            frame(),
            fingerprint,
            randomIdentity);
        receipts.put(arrow, receipt);
        order.add(receipt);
        random.setSeed(seed);
        receipt.seedWrites = 1;
    }

    private void joined(EntityJoinWorldEvent event) {
        if (event.world != world || !(event.entity instanceof EntityArrow)) return;
        Receipt receipt = receipts.get(event.entity);
        require(receipt != null, "Joined arrow has no certified constructor receipt");
        require(
            !event.isCanceled() && !receipt.joined && !receipt.closed && receipt.purpose != Purpose.MAID_PREVIEW,
            "Rejected/repeated/preview arrow Join");
        verify(receipt);
        require(receipt.constructTick == world.getTotalWorldTime(), "Arrow Join tick differs from constructor receipt");
        ownership(receipt);
        receipt.joined = true;
        receipt.closed = true;
        receipt.disposition = "JOINED_NATIVE";
        receipt.settledFrame = frame();
    }

    private void end() {
        credential = null;
        require(explicit == null, "Explicit constructor scope leaked across END");
        for (Receipt receipt : order) {
            verify(receipt);
            if (receipt.closed) {
                if (!"CANARY_SHOOTER_MISMATCH_REJECTED".equals(receipt.disposition)) ownership(receipt);
                continue;
            }
            require(receipt.constructTick == world.getTotalWorldTime(), "Arrow receipt survived its native tick");
            ownership(receipt);
            require(
                receipt.arrow.isDead && world.getEntityByID(receipt.arrow.getEntityId()) != receipt.arrow
                    && !world.loadedEntityList.contains(receipt.arrow),
                "Unspawned arrow did not close dead and unregistered");
            require(receipt.purpose != Purpose.TARGET_LAUNCH, "Target launch did not reach native Join");
            receipt.closed = true;
            receipt.disposition = receipt.purpose == Purpose.MAID_PREVIEW ? "PREVIEW_DEAD_UNSPAWNED"
                : "LAUNCH_ATTEMPT_DEAD_UNSPAWNED";
            receipt.settledFrame = frame();
        }
    }

    private void verify(Receipt receipt) {
        require(
            receipt.arrow.worldObj == world && receipt.arrow.getClass() == EntityArrow.class,
            "Arrow identity/world/Random object or seed count changed");
        requireOriginalReference(api().random(receipt.arrow), receipt.random, receipt.seedWrites);
    }

    private static void requireUnaliased(Random value, Random[] aliases) {
        require(value != null, "Original arrow Random missing");
        for (Random alias : aliases)
            require(value != alias, "Arrow Random aliases world/owned living or retained receipt before first seed");
    }

    private static void requireOriginalReference(Random actual, Random original, int writes) {
        require(actual == original && writes == 1, "Arrow identity/world/Random object or seed count changed");
    }

    private static void appendProviderSeed(JsonObject row, JsonObject identity, long seed) {
        row.addProperty("randomInputRevision", BugtorchArrowRandom.REVISION);
        row.addProperty("randomInputDesignSha256", BugtorchArrowRandom.DESIGN);
        row.addProperty("randomInputSourceLedgerSha256", BugtorchArrowRandom.LEDGER);
        row.add("randomProviderId", identity.get("providerId"));
        row.add("randomProviderClassSha256", identity.get("classSha256"));
        row.add("seedParameterBits", identity.get("seedParameterBits"));
        row.addProperty("seedInputHex", String.format("%016x", seed));
        row.add("legacySeed48Meaning", identity.get("legacySeed48Meaning"));
    }

    private void ownership(Receipt receipt) {
        if (receipt.arrow.shootingEntity != receipt.expected)
            throw new OwnershipMismatch("Arrow shooter does not match original constructor receipt");
    }

    private long frame() {
        return activeAt == Long.MAX_VALUE ? 0 : world.getTotalWorldTime() - activeAt;
    }

    private void addAudit(String outcome, int writes) {
        require(audit.size() < AUDIT_LIMIT, "Bounded v6 audit exhausted");
        var row = new JsonObject();
        row.addProperty("outcome", outcome);
        row.addProperty("seedWrites", writes);
        audit.add(row);
    }

    JsonObject snapshot() {
        var result = new JsonObject();
        result.addProperty("run", run);
        result.addProperty("dimension", dimension);
        result.addProperty("serverThread", owner.getName());
        result.addProperty("namespace", NAMESPACE);
        result.addProperty("logicalSeed", logicalSeed);
        result.addProperty("closed", closed);
        result.addProperty("invalid", invalid);
        result.addProperty("controlCalls", calls);
        result.addProperty("controlCpuNanos", cpuNanos);
        result.addProperty("controlWallNanos", wallNanos);
        result.addProperty("unscopedRejected", rejected);
        result.addProperty("leaseOpenCpuNanos", openCpuNanos);
        result.addProperty("leaseOpenWallNanos", openWallNanos);
        result.addProperty(
            "costScope",
            "Actual QA control, included in whole-server probe; not subtracted from production cost");
        result.add("nativeArrowRandomRevision", BugtorchArrowRandom.revision());
        result.add("nativeArrowRandomProviders", BugtorchArrowRandom.observations());
        result.add("receipts", comparable());
        result.add("audit", audit);
        result.add("lastConstructionProof", lastConstructionProof);
        var coverage = new JsonObject();
        for (Purpose purpose : Purpose.values()) coverage.addProperty(
            purpose.name(),
            methodCanary ? "EXPLICIT_METHOD_CANARY"
                : attempts[purpose.ordinal()] > 0 ? "CERTIFIED_NATIVE_PATH" : "NOT_EXERCISED");
        result.add("normalPathCoverage", coverage);
        var actual = new JsonArray();
        for (Receipt receipt : order) {
            var row = new JsonObject();
            row.addProperty("receipt", receipt.index(order));
            row.addProperty(
                "arrowUuid",
                receipt.arrow.getUniqueID()
                    .toString());
            row.addProperty(
                "expectedShooterUuid",
                receipt.expected.getUniqueID()
                    .toString());
            row.addProperty(
                "actualShooterUuid",
                receipt.arrow.shootingEntity == null ? "null"
                    : receipt.arrow.shootingEntity.getUniqueID()
                        .toString());
            row.addProperty("identityHash", System.identityHashCode(receipt.arrow));
            actual.add(row);
        }
        result.add("actualArrowIdentities", actual);
        return result;
    }

    long certifiedAttempts(Purpose purpose) {
        check();
        return attempts[purpose.ordinal()];
    }

    JsonArray comparable() {
        var rows = new JsonArray();
        for (Receipt r : order) {
            var row = new JsonObject();
            row.addProperty("purpose", r.purpose.name());
            row.addProperty("role", r.purpose.role);
            row.addProperty("use", r.purpose.use);
            row.addProperty("attempt", r.attempt);
            row.addProperty(
                "expectedShooterRole",
                r.expected == maid ? "maid" : r.expected == target ? "target" : "UNKNOWN");
            row.addProperty(
                "actualShooterRole",
                r.arrow.shootingEntity == maid ? "maid" : r.arrow.shootingEntity == target ? "target" : "UNKNOWN");
            row.addProperty("seed", r.seed);
            row.addProperty("effectiveSeed48", r.seed & 0xffffffffffffL);
            appendProviderSeed(row, r.randomIdentity, r.seed);
            row.addProperty("constructFrame", r.constructFrame);
            row.addProperty("methodFingerprint", r.fingerprint);
            row.addProperty("seedWrites", r.seedWrites);
            row.addProperty("joined", r.joined);
            row.addProperty("closed", r.closed);
            row.addProperty("disposition", r.disposition);
            row.addProperty("settledFrame", r.settledFrame);
            rows.add(row);
        }
        return rows;
    }

    void assertComplete() {
        check();
        require(explicit == null, "Canary scope remains open");
        for (Receipt r : order) {
            require(r.closed && r.seedWrites == 1, "Unsettled v6 constructor receipt");
            verify(r);
            if (!"CANARY_SHOOTER_MISMATCH_REJECTED".equals(r.disposition)) ownership(r);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        require(Thread.currentThread() == owner && active == this, "Foreign v6 close");
        credential = null;
        explicit = null;
        try {
            if (!invalid) assertComplete();
        } finally {
            if (registered) {
                MinecraftForge.EVENT_BUS.unregister(listener);
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(listener);
            }
            active = null;
            closed = true;
        }
    }

    public static final class Listener {

        private final EffectArrowRandomInput input;

        Listener(EffectArrowRandomInput input) {
            this.input = input;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void living(LivingUpdateEvent event) {
            input.guarded(() -> input.living(event));
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void constructing(EntityConstructing event) {
            input.guarded(() -> input.constructing(event));
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void joined(EntityJoinWorldEvent event) {
            input.guarded(() -> input.joined(event));
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) input.guarded(() -> input.credential = null);
            else input.guarded(input::end);
        }

        @SubscribeEvent
        public void save(WorldEvent.Save event) {
            if (event.world == input.world)
                input.guarded(() -> { throw new IllegalStateException("World save invalidated v6 lease"); });
        }

        @SubscribeEvent
        public void unload(WorldEvent.Unload event) {
            if (event.world == input.world)
                input.guarded(() -> { throw new IllegalStateException("World unload invalidated v6 lease"); });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void death(LivingDeathEvent event) {
            if (!event.isCanceled() && (event.entityLiving == input.maid || event.entityLiving == input.target)) {
                // The actual zero-health native death witness can precede the coordinator's final retirement
                if (Thread.currentThread() == input.owner && event.entityLiving.worldObj == input.world
                    && !event.entityLiving.isEntityAlive()) {
                    if (event.entityLiving == input.maid) input.maidDeathObserved = true;
                    else input.targetDeathObserved = true;
                }
                input.guarded(() -> {
                    input.credential = null;
                    require(
                        event.entityLiving == input.maid ? input.maidDeathObserved : input.targetDeathObserved,
                        "LivingDeath event did not witness the original zero-health actor");
                });
            }
        }
    }

    private static final class Credential {

        final EntityLivingBase actor;
        final long tick;
        final int actorTick;

        Credential(EntityLivingBase actor, long tick, int actorTick) {
            this.actor = actor;
            this.tick = tick;
            this.actorTick = actorTick;
        }
    }

    private static final class Explicit {

        final Purpose purpose;
        final EntityLivingBase expected;
        final long tick;

        Explicit(Purpose purpose, EntityLivingBase expected, long tick) {
            this.purpose = purpose;
            this.expected = expected;
            this.tick = tick;
        }
    }

    private static final class Receipt {

        final EntityArrow arrow;
        final Random random;
        final Purpose purpose;
        final long attempt, seed, constructTick, constructFrame;
        final EntityLivingBase expected;
        final String fingerprint;
        final JsonObject randomIdentity;
        int seedWrites;
        boolean joined, closed;
        String disposition = "PENDING";
        long settledFrame = -1;

        Receipt(EntityArrow arrow, Random random, Purpose purpose, long attempt, long seed, EntityLivingBase expected,
            long tick, long frame, String fingerprint, JsonObject randomIdentity) {
            this.arrow = arrow;
            this.random = random;
            this.purpose = purpose;
            this.attempt = attempt;
            this.seed = seed;
            this.expected = expected;
            constructTick = tick;
            constructFrame = frame;
            this.fingerprint = fingerprint;
            this.randomIdentity = randomIdentity;
        }

        int index(List<Receipt> rows) {
            return rows.indexOf(this);
        }
    }

    private static long seed(long base, Purpose purpose, long attempt) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            utf8(out, NAMESPACE);
            out.writeLong(base);
            utf8(out, purpose.role);
            utf8(out, purpose.use);
            out.writeLong(attempt);
            out.flush();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes.toByteArray());
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | (digest[i] & 255L);
            return value;
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen v6 seed encoding unavailable", failure);
        }
    }

    private static void utf8(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static JsonObject emptyBoundary(WorldServer world, boolean previousCleanupSucceeded, boolean apply) {
        gate();
        ThreadMXBean timer = ManagementFactory.getThreadMXBean();
        require(timer.isCurrentThreadCpuTimeSupported(), "Empty-boundary CPU clock unavailable");
        boolean restore = !timer.isThreadCpuTimeEnabled();
        if (restore) timer.setThreadCpuTimeEnabled(true);
        long startedWall = System.nanoTime(), startedCpu = timer.getCurrentThreadCpuTime();
        var row = new JsonObject();
        try {
            Platform p = api();
            p.stack.requireServerThread();
            long before = CombatProjectileEvents.sequence(world);
            var budget = CombatWorldBudget.diagnostics(world, world.getTotalWorldTime());
            int actors = world.loadedEntityList.size(), players = world.playerEntities.size(),
                weather = world.weatherEffects.size();
            int sessions = p.sessionsIn(world);
            boolean allowed = previousCleanupSucceeded && active == null
                && actors == 0
                && players == 0
                && weather == 0
                && sessions == 0
                && budget.requests == 0
                && budget.entries.length == 0
                && budget.fullGrants == 0
                && budget.granted.length == 0
                && budget.preparations.length == 0
                && allZero(budget.used);
            row.addProperty("sequenceBefore", before);
            row.addProperty("cleanupSucceeded", previousCleanupSucceeded);
            row.addProperty("wholeWorldEntityCount", actors);
            row.addProperty("wholeWorldPlayerCount", players);
            row.addProperty("wholeWorldWeatherCount", weather);
            row.addProperty("worldCombatSessions", sessions);
            row.addProperty("arrowInputLeaseAbsent", active == null);
            row.addProperty("budgetRequests", budget.requests);
            row.addProperty("budgetEntries", budget.entries.length);
            row.addProperty("budgetFullGrants", budget.fullGrants);
            row.addProperty("budgetAllUsedZero", allZero(budget.used));
            row.addProperty("budgetGrantedActors", budget.granted.length);
            row.addProperty("budgetPreparationActors", budget.preparations.length);
            row.addProperty("pendingLeaseAbsent", active == null);
            row.addProperty("allowed", allowed);
            if (allowed && apply) CombatProjectileEvents.unload(world);
            long after = CombatProjectileEvents.sequence(world);
            var afterBudget = CombatWorldBudget.diagnostics(world, world.getTotalWorldTime());
            row.addProperty("apiCalled", allowed && apply);
            row.addProperty("sequenceAfter", after);
            require(
                actors == world.loadedEntityList.size() && players == world.playerEntities.size()
                    && weather == world.weatherEffects.size()
                    && budget.requests == afterBudget.requests
                    && budget.fullGrants == afterBudget.fullGrants
                    && Arrays.equals(budget.used, afterBudget.used)
                    && budget.entries.length == afterBudget.entries.length,
                "Own ring unload changed entities or budget");
            require(allowed && apply ? after == 0 : after == before, "Ring unload boundary result differs");
            return row;
        } finally {
            long after = timer.getCurrentThreadCpuTime();
            row.addProperty("controlCpuNanos", startedCpu >= 0 && after >= startedCpu ? after - startedCpu : -1);
            row.addProperty("controlWallNanos", System.nanoTime() - startedWall);
            if (restore) timer.setThreadCpuTimeEnabled(false);
        }
    }

    private static boolean allZero(int[] values) {
        for (int value : values) if (value != 0) return false;
        return true;
    }

    /** 仅方法canary使用的词法凭据，不可用于正常trial代射 */
    JsonObject canaryHeading(boolean previewsFirst) {
        check();
        require(methodCanary, "Explicit constructors are method-canary only");
        if (previewsFirst) for (int i = 0; i < 3; i++) canaryPreview();
        EntityArrow arrow;
        explicit = new Explicit(Purpose.TARGET_LAUNCH, target, world.getTotalWorldTime());
        try {
            arrow = canaryDirected(target);
        } finally {
            explicit = null;
        }
        Receipt receipt = receipts.get(arrow);
        require(
            receipt != null && arrow.shootingEntity == target && receipt.seedWrites == 1,
            "Canary target constructor not certified");
        var heading = heading(arrow);
        require(
            world.spawnEntityInWorld(arrow) && receipt.joined && receipt.closed,
            "Canary native arrow Join did not close");
        arrow.setDead();
        world.removePlayerEntityDangerously(arrow);
        var result = new JsonObject();
        result.add("heading", heading);
        result.addProperty("targetSeed", receipt.seed);
        result.addProperty("targetAttempt", receipt.attempt);
        result.addProperty("targetEffective48", receipt.seed & 0xffffffffffffL);
        appendProviderSeed(result, receipt.randomIdentity, receipt.seed);
        result.addProperty("previewsBefore", previewsFirst ? 3 : 0);
        return result;
    }

    private EntityArrow canaryDirected(EntityLivingBase shooter) {
        return new EntityArrow(world, shooter, maid, 1.6F, 2.0F);
    }

    private void canaryPreview() {
        explicit = new Explicit(Purpose.MAID_PREVIEW, maid, world.getTotalWorldTime());
        EntityArrow arrow;
        try {
            arrow = new EntityArrow(world, maid.posX, maid.posY + maid.getEyeHeight() - .1, maid.posZ);
        } finally {
            explicit = null;
        }
        arrow.shootingEntity = maid;
        arrow.setDead();
        Receipt receipt = receipts.get(arrow);
        require(receipt != null && receipt.purpose == Purpose.MAID_PREVIEW, "Canary preview input missing");
        end();
        require(receipt.closed && !receipt.joined, "Canary preview did not close unspawned");
    }

    JsonObject canaryRejections() {
        check();
        require(methodCanary, "Negative constructors are method-canary only");
        var result = new JsonObject();
        int before = order.size();
        long rejectedBefore = rejected;
        expectUnscoped = true;
        EntityArrow noScope;
        try {
            noScope = canaryDirected(target);
        } finally {
            expectUnscoped = false;
        }
        noScope.setDead();
        require(order.size() == before && rejected == rejectedBefore + 1, "Unscoped constructor was seeded");
        result.addProperty("noScopeSeedWrites", 0);
        try {
            explicit = new Explicit(Purpose.TARGET_LAUNCH, target, world.getTotalWorldTime());
        } finally {
            explicit = null;
        }
        expectUnscoped = true;
        EntityArrow expired;
        try {
            expired = canaryDirected(target);
        } finally {
            expectUnscoped = false;
        }
        expired.setDead();
        require(order.size() == before && rejected == rejectedBefore + 2, "Exited canary scope was reused");
        result.addProperty("expiredScopeSeedWrites", 0);
        explicit = new Explicit(Purpose.TARGET_LAUNCH, maid, world.getTotalWorldTime());
        EntityArrow mismatched;
        try {
            mismatched = canaryDirected(target);
        } finally {
            explicit = null;
        }
        Receipt receipt = receipts.get(mismatched);
        require(
            receipt != null && receipt.seedWrites == 1 && mismatched.shootingEntity != receipt.expected,
            "Deliberate shooter mismatch was not constructed");
        boolean refused = false;
        try {
            ownership(receipt);
        } catch (OwnershipMismatch expected) {
            refused = true;
        }
        require(refused, "Mismatched shooter was admitted");
        receipt.closed = true;
        receipt.disposition = "CANARY_SHOOTER_MISMATCH_REJECTED";
        receipt.settledFrame = frame();
        mismatched.setDead();
        result.addProperty("mismatchedShooterRejected", refused);
        result.addProperty("mismatchedReceiptSeedWrites", receipt.seedWrites);
        result.addProperty("validLaunchesFromMismatch", receipt.joined ? 1 : 0);
        return result;
    }

    private static JsonObject heading(EntityArrow arrow) {
        var row = new JsonObject();
        row.addProperty("motionXBits", Long.toHexString(Double.doubleToLongBits(arrow.motionX)));
        row.addProperty("motionYBits", Long.toHexString(Double.doubleToLongBits(arrow.motionY)));
        row.addProperty("motionZBits", Long.toHexString(Double.doubleToLongBits(arrow.motionZ)));
        row.addProperty("yawBits", Float.floatToIntBits(arrow.rotationYaw));
        row.addProperty("pitchBits", Float.floatToIntBits(arrow.rotationPitch));
        return row;
    }

    private static final class OwnershipMismatch extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        OwnershipMismatch(String message) {
            super(message);
        }
    }

    private static final class SourceRefusal extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        SourceRefusal(String message) {
            super(message);
        }
    }

    private static void sourceRequire(boolean okay, String message) {
        if (!okay) throw new SourceRefusal(message);
    }

    static final class Frame {

        final Class<?> type;
        final String method, descriptor;

        Frame(Class<?> type, String method, String descriptor) {
            this.type = type;
            this.method = method;
            this.descriptor = descriptor;
        }

        boolean same(Frame other) {
            return type == other.type && method.equals(other.method) && descriptor.equals(other.descriptor);
        }

        String fingerprint() {
            return type.getName() + "#" + method + descriptor;
        }
    }

    /** 只反射Java25公开声明接口，编译时不引用Java9+类型 */
    static final class StackBridge {

        final Object walker;
        final Method walk, declaring, name, descriptor;

        StackBridge() {
            require(
                "25".equals(System.getProperty("java.specification.version")),
                "V6 requires the frozen Java25 runtime");
            try {
                Class<?> type = Class.forName("java.lang.StackWalker"),
                    frame = Class.forName("java.lang.StackWalker$StackFrame");
                Class<?> option = Class.forName("java.lang.StackWalker$Option");
                Set<Object> options = new HashSet<>();
                for (Object value : option.getEnumConstants()) if (((Enum<?>) value).name()
                    .equals("RETAIN_CLASS_REFERENCE")) options.add(value);
                require(options.size() == 1, "StackWalker class-reference option unavailable");
                Method get = type.getMethod("getInstance", Set.class);
                walk = type.getMethod("walk", Function.class);
                declaring = frame.getMethod("getDeclaringClass");
                name = frame.getMethod("getMethodName");
                descriptor = frame.getMethod("getDescriptor");
                require(
                    get.getReturnType() == type && walk.getReturnType() == Object.class
                        && declaring.getReturnType() == Class.class
                        && name.getReturnType() == String.class
                        && descriptor.getReturnType() == String.class
                        && get.getDeclaringClass() == type
                        && walk.getDeclaringClass() == type
                        && declaring.getDeclaringClass() == frame
                        && name.getDeclaringClass() == frame
                        && descriptor.getDeclaringClass() == frame
                        && frame.isInterface()
                        && Modifier.isPublic(get.getModifiers())
                        && Modifier.isStatic(get.getModifiers())
                        && Modifier.isPublic(walk.getModifiers())
                        && Modifier.isPublic(declaring.getModifiers())
                        && Modifier.isPublic(name.getModifiers())
                        && Modifier.isPublic(descriptor.getModifiers()),
                    "StackWalker public signatures changed");
                walker = get.invoke(null, options);
            } catch (ReflectiveOperationException failure) {
                throw reflection("Java25 public StackWalker unavailable", failure);
            }
        }

        @SuppressWarnings("unchecked")
        List<Frame> capture() {
            try {
                return (List<Frame>) walk.invoke(walker, (Function<Stream<?>, List<Frame>>) stream -> {
                    var result = new ArrayList<Frame>();
                    Iterator<?> iterator = stream.limit(FRAME_LIMIT + 1L)
                        .iterator();
                    while (iterator.hasNext()) {
                        Object frame = iterator.next();
                        require(result.size() < FRAME_LIMIT, "StackWalker proof was truncated");
                        try {
                            result.add(
                                new Frame(
                                    (Class<?>) declaring.invoke(frame),
                                    (String) name.invoke(frame),
                                    (String) descriptor.invoke(frame)));
                        } catch (ReflectiveOperationException failure) {
                            throw reflection("StackFrame public API failed", failure);
                        }
                    }
                    return result;
                });
            } catch (ReflectiveOperationException failure) {
                throw reflection("StackWalker proof failed", failure);
            }
        }

        void requireServerThread() {
            boolean run = false, tick = false;
            for (Frame f : capture()) {
                if (f.type == net.minecraft.server.MinecraftServer.class && f.method.equals("run")
                    && f.descriptor.equals("()V")) run = true;
                if (f.type == net.minecraft.server.MinecraftServer.class
                    && (f.method.equals("tick") || f.method.equals("func_71217_p"))
                    && f.descriptor.equals("()V")) tick = true;
            }
            require(run && tick, "V6 lease is not on the actual native server run/tick thread");
        }
    }

    private static final class Platform {

        final StackBridge stack = new StackBridge();
        final Field randomField, sessions;
        final JsonObject randomFieldAccess;
        final Frame entityCtor, directedCtor, positionCtor, livingUpdate;
        final List<Frame> targetPath, maidLaunchPath, maidBody, worldTail;
        final Map<Class<?>, JsonObject> identities = new HashMap<>();
        final Map<Path, String> artifactHashes = new HashMap<>();
        Object nativeNaming;
        Method unmapNativeName, mapNativeName;

        Platform() {
            gate();
            try {
                Field random = null;
                for (String candidate : new String[] { "rand", "field_70146_Z" }) try {
                    random = Entity.class.getDeclaredField(candidate);
                    break;
                } catch (NoSuchFieldException missing) { /* exact second frozen mapping only */ }
                require(
                    random != null && random.getDeclaringClass() == Entity.class
                        && random.getType() == Random.class
                        && !Modifier.isStatic(random.getModifiers()),
                    "Native Entity Random field identity changed; fixedCandidateMetadata=" + randomFieldMetadata());
                randomFieldAccess = attestRandomFieldAccess(random);
                random.setAccessible(true);
                randomField = random;
                sessions = CombatEffectPilotSession.class.getDeclaredField("ACTIVE");
                require(
                    sessions.getDeclaringClass() == CombatEffectPilotSession.class && sessions.getType() == Map.class
                        && Modifier.isPrivate(sessions.getModifiers())
                        && Modifier.isStatic(sessions.getModifiers())
                        && Modifier.isFinal(sessions.getModifiers()),
                    "Native QA session owner map changed");
                sessions.setAccessible(true);
            } catch (ReflectiveOperationException failure) {
                throw reflection("Fixed v6 platform mapping unavailable", failure);
            }
            entityCtor = new Frame(Entity.class, "<init>", "(Lnet/minecraft/world/World;)V");
            directedCtor = new Frame(
                EntityArrow.class,
                "<init>",
                "(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/entity/EntityLivingBase;FF)V");
            positionCtor = new Frame(EntityArrow.class, "<init>", "(Lnet/minecraft/world/World;DDD)V");
            livingUpdate = key(EntityLivingBase.class, "onUpdate|func_70071_h_", void.class);
            targetPath = Arrays.asList(
                key(
                    EntitySkeleton.class,
                    "attackEntityWithRangedAttack|func_82196_d",
                    void.class,
                    EntityLivingBase.class,
                    float.class),
                key(EntityAIArrowAttack.class, "updateTask|func_75246_d", void.class),
                key(EntityAITasks.class, "onUpdateTasks|func_75774_a", void.class),
                key(EntityLiving.class, "updateAITasks|func_70619_bc", void.class),
                key(EntityLivingBase.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntityLiving.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntityMob.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntitySkeleton.class, "onLivingUpdate|func_70636_d", void.class),
                livingUpdate);
            Class<?> combatResult = MaidPolicyRuntime.TickResult.class;
            maidBody = Arrays.asList(
                key(EnhancedCoreBrain.class, "tick", void.class, MaidPolicyContext.Token.class),
                key(
                    MaidPolicyRuntime.class,
                    "tick",
                    combatResult,
                    boolean.class,
                    boolean.class,
                    java.util.function.BooleanSupplier.class),
                key(MaidPolicyRuntime.class, "tickAfterNativeNavigation", combatResult),
                key(MaidBrainCoordinator.class, "executePolicy", void.class, MaidPolicyRuntime.class, boolean.class),
                key(MaidBrainCoordinator.class, "afterNativeNavigationTick", void.class),
                key(EntityMaid.class, "updateAITick|func_70629_bd", void.class),
                key(EntityLiving.class, "updateAITasks|func_70619_bc", void.class),
                key(EntityMaid.class, "lambda$new$0", void.class),
                key(MaidBehaviorProtection.class, "run", void.class, Runnable.class),
                key(EntityMaid.class, "updateAITasks|func_70619_bc", void.class),
                key(EntityLivingBase.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntityLiving.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntityAgeable.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntityAnimal.class, "onLivingUpdate|func_70636_d", void.class),
                key(EntityMaid.class, "onLivingUpdate|func_70636_d", void.class),
                livingUpdate);
            maidLaunchPath = Arrays.asList(
                key(
                    MaidBowAttack.class,
                    "release",
                    boolean.class,
                    EntityLivingBase.class,
                    net.minecraft.item.ItemStack.class,
                    int.class,
                    java.util.function.BooleanSupplier.class,
                    boolean.class,
                    float.class),
                key(
                    MaidBowAttack.class,
                    "fireForModern",
                    boolean.class,
                    EntityLivingBase.class,
                    float.class,
                    java.util.function.BooleanSupplier.class),
                key(
                    ModernFollowCoreActor.class,
                    "fireRangedAttackForPolicy",
                    boolean.class,
                    MaidCombatActor.Target.class,
                    float.class,
                    java.util.function.BooleanSupplier.class),
                key(
                    MaidUnifiedCombatActionRunner.class,
                    "bow",
                    CombatPlan.Result.class,
                    EntityLivingBase.class,
                    MaidCombatActor.Target.class,
                    CombatPlan.class,
                    com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.class,
                    com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile.class),
                key(MaidUnifiedCombatActionRunner.class, "applyCurrent", CombatPlan.Result.class, CombatPlan.class),
                key(MaidUnifiedCombatActionRunner.class, "apply", CombatPlan.Result.class, CombatPlan.class),
                key(ModernFollowCoreActor.class, "tryApplyCombatPlan", CombatPlan.Result.class, CombatPlan.class),
                key(EnhancedCombatController.class, "execute", void.class, EntityLivingBase.class));
            worldTail = Arrays.asList(
                key(World.class, "updateEntityWithOptionalForce|func_72866_a", void.class, Entity.class, boolean.class),
                key(
                    WorldServer.class,
                    "updateEntityWithOptionalForce|func_72866_a",
                    void.class,
                    Entity.class,
                    boolean.class),
                key(World.class, "updateEntity|func_72870_g", void.class, Entity.class),
                key(World.class, "updateEntities|func_72939_s", void.class));
            for (Class<?> type : new Class<?>[] { MaidBowAttack.class, CombatProjectileEvents.class,
                CombatEffectPilotSession.class, UnifiedCombatEffectPilotTests.class, EffectArrowRandomInput.class,
                StackBridge.class })
                require(
                    identity(type).get("classMajor")
                        .getAsInt() == 52,
                    "Frozen production/QA bridge class must be Java8 major52: " + type.getName());
            identity(Entity.class);
            identity(EntityArrow.class);
        }

        private JsonObject attestRandomFieldAccess(Field field) {
            var row = new JsonObject();
            int mask = field.getModifiers();
            row.addProperty("fieldMapping", field.getName());
            row.addProperty(
                "declaringClass",
                field.getDeclaringClass()
                    .getName());
            row.addProperty(
                "type",
                field.getType()
                    .getName());
            row.addProperty("modifierMask", mask);
            row.addProperty("modifiers", Modifier.toString(mask));
            if (Modifier.isProtected(mask)) {
                row.addProperty("accessForm", "ORIGINAL_PROTECTED");
                return row;
            }
            require(
                mask == Modifier.PUBLIC && "field_70146_Z".equals(field.getName()),
                "Uncertified native Random access form; fixedCandidateMetadata=" + randomFieldMetadata());
            try {
                var container = Loader.instance()
                    .getIndexedModList()
                    .get("TwilightForest");
                require(
                    container != null && "TwilightForest".equals(container.getModId())
                        && container.getSource() != null
                        && container.getMod() != null,
                    "Loaded TwilightForest provider unavailable for the exact Random AT form");
                Path source = container.getSource()
                    .toPath()
                    .toRealPath();
                require(Files.isRegularFile(source), "Loaded TwilightForest source is not a packaged JAR");
                String sourceHash = artifactHashes.get(source);
                if (sourceHash == null) {
                    sourceHash = hex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(source)));
                    artifactHashes.put(source, sourceHash);
                }
                require(
                    "84eb70c61c8e73e2dc7c5e5a332789f379ed1b31bf70b51f25146affb182a818".equals(sourceHash),
                    "Loaded TwilightForest source differs from the certified Random AT provider");
                String atName = "twilightforest_at.cfg", atEntry = "META-INF/" + atName;
                String atHash;
                try (JarFile jar = new JarFile(source.toFile())) {
                    require(
                        jar.getManifest() != null && atName.equals(
                            jar.getManifest()
                                .getMainAttributes()
                                .getValue("FMLAT")),
                        "Loaded TwilightForest manifest does not declare the exact Random AT resource");
                    var entry = jar.getJarEntry(atEntry);
                    require(entry != null && !entry.isDirectory(), "Certified TwilightForest Random AT entry missing");
                    byte[] bytes;
                    try (InputStream in = jar.getInputStream(entry)) {
                        var buffer = new ByteArrayOutputStream();
                        byte[] part = new byte[4096];
                        int count;
                        while ((count = in.read(part)) >= 0) {
                            if (count > 0) buffer.write(part, 0, count);
                            require(
                                buffer.size() <= 65536,
                                "TwilightForest Random AT resource exceeds its audit bound");
                        }
                        bytes = buffer.toByteArray();
                    }
                    atHash = hex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(bytes));
                    require(
                        "9ab95d5749c5861555147f576c15f07828c3090ec3d00924c4f4eb70e968ff4f".equals(atHash),
                        "Loaded TwilightForest Random AT resource differs from the certified bytes");
                    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n", -1);
                    require(
                        lines.length >= 6 && "public net.minecraft.entity.Entity field_70146_Z #rand".equals(lines[5]),
                        "Loaded TwilightForest Random AT rule does not match the certified exact Entity field");
                }
                row.addProperty("accessForm", "CERTIFIED_TWILIGHTFOREST_2_7_45_PUBLIC_AT");
                row.addProperty("providerModId", container.getModId());
                row.addProperty("providerVersion", container.getVersion());
                row.addProperty(
                    "providerContainerClass",
                    container.getClass()
                        .getName());
                row.addProperty(
                    "providerModClass",
                    container.getMod()
                        .getClass()
                        .getName());
                row.addProperty(
                    "providerSourceAuthority",
                    "Actual Loader indexed ModContainer.getSource; not filename inference");
                row.addProperty(
                    "providerArtifactFile",
                    source.getFileName()
                        .toString());
                row.addProperty("providerArtifactSha256", sourceHash);
                row.addProperty("manifestFmlAt", atName);
                row.addProperty("atEntry", atEntry);
                row.addProperty("atSha256", atHash);
                row.addProperty("atRuleLine", 6);
                row.addProperty("atRule", "public net.minecraft.entity.Entity field_70146_Z #rand");
                return row;
            } catch (Exception failure) {
                throw new IllegalStateException("Certified native Random public-AT provenance unavailable", failure);
            }
        }

        /** Fixed-name field metadata only; does not read a Random object or change admission */
        private static String randomFieldMetadata() {
            var rows = new JsonArray();
            for (String candidate : new String[] { "rand", "field_70146_Z" }) {
                var row = new JsonObject();
                row.addProperty("candidate", candidate);
                try {
                    Field field = Entity.class.getDeclaredField(candidate);
                    row.addProperty("present", true);
                    row.addProperty("name", field.getName());
                    row.addProperty(
                        "declaringClass",
                        field.getDeclaringClass()
                            .getName());
                    row.addProperty(
                        "type",
                        field.getType()
                            .getName());
                    row.addProperty("modifierMask", field.getModifiers());
                    row.addProperty("modifiers", Modifier.toString(field.getModifiers()));
                } catch (NoSuchFieldException missing) {
                    row.addProperty("present", false);
                }
                rows.add(row);
            }
            return rows.toString();
        }

        Frame key(Class<?> type, String aliases, Class<?> result, Class<?>... parameters) {
            Method found = null;
            for (String name : aliases.split("\\|")) try {
                found = type.getDeclaredMethod(name, parameters);
                break;
            } catch (NoSuchMethodException absent) { /* exact MCP/SRG alias only */ }
            require(
                found != null && found.getDeclaringClass() == type && found.getReturnType() == result,
                "Reviewed source method mapping unavailable: " + type.getName() + "#" + aliases);
            identity(type);
            return new Frame(
                type,
                found.getName(),
                MethodType.methodType(result, parameters)
                    .toMethodDescriptorString());
        }

        Random random(EntityArrow arrow) {
            try {
                Object value = randomField.get(arrow);
                try {
                    Random random = (Random) value;
                    BugtorchArrowRandom.attest(random, Entity.class.getClassLoader());
                    return random;
                } catch (RuntimeException | LinkageError failure) {
                    IntegrationGuard.throwIfFatal(failure);
                    throw new IllegalStateException(
                        "Uncertified native arrow Random implementation; actualRandomMetadata=" + randomMetadata(value),
                        failure);
                }
            } catch (IllegalAccessException failure) {
                throw reflection("Native protected Random is not readable", failure);
            }
        }

        JsonObject randomIdentity(Random value) {
            return BugtorchArrowRandom.attest(value, Entity.class.getClassLoader());
        }

        /** Rejection-only metadata; never draws, reads seed state, changes or replaces the Random object. */
        private static String randomMetadata(Object value) {
            var row = new JsonObject();
            row.addProperty("present", value != null);
            if (value == null) return row.toString();
            try {
                Class<?> type = value.getClass();
                row.addProperty("class", type.getName());
                row.addProperty(
                    "superclass",
                    type.getSuperclass() == null ? null
                        : type.getSuperclass()
                            .getName());
                ClassLoader loader = type.getClassLoader();
                row.addProperty(
                    "loaderClass",
                    loader == null ? "BOOTSTRAP"
                        : loader.getClass()
                            .getName());
                row.addProperty("loaderIdentity", loader == null ? 0 : System.identityHashCode(loader));
                row.addProperty(
                    "scope",
                    "Original failed Random object type and CodeSource metadata only; no source attestation");
                var domain = type.getProtectionDomain();
                var source = domain == null ? null : domain.getCodeSource();
                var location = source == null ? null : source.getLocation();
                row.addProperty("codeSourceUrl", location == null ? null : location.toExternalForm());
            } catch (Exception | LinkageError | AssertionError unavailable) {
                IntegrationGuard.throwIfFatal(unavailable);
                row.addProperty(
                    "metadataReadFailureClass",
                    unavailable.getClass()
                        .getName());
            }
            return row.toString();
        }

        @SuppressWarnings("unchecked")
        Map<EntityMaid, CombatEffectPilotSession> sessions() {
            try {
                Object value = sessions.get(null);
                require(
                    value != null && value.getClass() == IdentityHashMap.class,
                    "QA session map implementation changed");
                Map<?, ?> map = (Map<?, ?>) value;
                for (Map.Entry<?, ?> row : map.entrySet()) require(
                    row.getKey() instanceof EntityMaid && row.getValue() != null
                        && row.getValue()
                            .getClass() == CombatEffectPilotSession.class,
                    "Unknown QA session accounting row");
                return (Map<EntityMaid, CombatEffectPilotSession>) value;
            } catch (IllegalAccessException failure) {
                throw reflection("QA session ownership accounting unavailable", failure);
            }
        }

        CombatEffectPilotSession session(EntityMaid maid) {
            return sessions().get(maid);
        }

        int sessionsIn(World world) {
            int count = 0;
            for (EntityMaid maid : sessions().keySet()) if (maid.worldObj == world) count++;
            return count;
        }

        int countLivingUpdates(List<Frame> frames) {
            int count = 0;
            for (Frame f : frames) if (f.same(livingUpdate)) count++;
            return count;
        }

        int constructor(List<Frame> frames) {
            int at = -1;
            for (int i = 0; i < frames.size(); i++) if (frames.get(i)
                .same(entityCtor)) {
                    require(at < 0, "Nested Entity constructors");
                    at = i;
                }
            require(at >= 0 && at + 2 < frames.size(), "No exact native Entity/Arrow constructor boundary");
            return at;
        }

        int match(List<Frame> frames, int at, List<Frame> expected) {
            for (Frame next : expected) {
                require(
                    at < frames.size() && frames.get(at)
                        .same(next),
                    "Unreviewed v6 source frame at " + at
                        + ": "
                        + (at < frames.size() ? frames.get(at)
                            .fingerprint() : "TRUNCATED")
                        + "; expected="
                        + next.fingerprint());
                at++;
            }
            return at;
        }

        Purpose certify(List<Frame> frames, boolean target) {
            sourceRequire(countLivingUpdates(frames) == 1, "Arrow source is outside a unique real LivingBase body");
            int at = constructor(frames) + 1;
            Purpose purpose;
            if (target) {
                require(
                    frames.get(at++)
                        .same(directedCtor),
                    "Target did not use native directed constructor");
                at = match(frames, at, targetPath);
                purpose = Purpose.TARGET_LAUNCH;
            } else {
                require(
                    frames.get(at++)
                        .same(positionCtor),
                    "Maid did not use native position constructor");
                if (frames.get(at)
                    .same(maidLaunchPath.get(0))) {
                    at = match(frames, at, maidLaunchPath);
                    at = match(frames, at, maidBody);
                    purpose = Purpose.MAID_LAUNCH;
                } else {
                    Frame preview = key(
                        MaidBowAttack.class,
                        "preview",
                        MaidBowAttack.Status.class,
                        EntityLivingBase.class,
                        com.tianluoqaq.tlmnh.common.combat.ballistics.BallisticVector.class,
                        java.util.function.BooleanSupplier.class);
                    require(
                        frames.get(at)
                            .same(preview),
                        "Unreviewed maid arrow constructor caller");
                    throw new IllegalStateException(
                        "Normal preview has no installed reviewed unified caller path; NOT_EXERCISED");
                }
            }
            at = match(
                frames,
                at,
                Arrays.asList(
                    key(EntityLiving.class, "onUpdate|func_70071_h_", void.class),
                    target ? key(EntityMob.class, "onUpdate|func_70071_h_", void.class)
                        : key(EntityMaid.class, "onUpdate|func_70071_h_", void.class)));
            match(frames, at, worldTail);
            return purpose;
        }

        void certifyCanary(List<Frame> frames) {
            int at = constructor(frames) + 1;
            Frame ctor = frames.get(at++);
            require(ctor.same(directedCtor) || ctor.same(positionCtor), "Canary native constructor changed");
            Frame source = frames.get(at);
            Frame expected = ctor.same(directedCtor)
                ? key(EffectArrowRandomInput.class, "canaryDirected", EntityArrow.class, EntityLivingBase.class)
                : key(EffectArrowRandomInput.class, "canaryPreview", void.class);
            require(
                source.same(expected) && countLivingUpdates(frames) == 0,
                "Canary lexical constructor path changed");
        }

        String fingerprint(List<Frame> frames) {
            int from = constructor(frames) + 1;
            var value = new StringBuilder();
            for (int i = from; i < frames.size(); i++) {
                Frame f = frames.get(i);
                value.append(f.fingerprint())
                    .append(';');
                if (f.same(livingUpdate)) break;
            }
            return value.toString();
        }

        /** Metadata only for the native type observed in the failed preflight; opens no URL */
        private static String fixedCodeSourceMetadata(Class<?> type) {
            var row = new JsonObject();
            row.addProperty("class", type.getName());
            if (type != EntityLivingBase.class) {
                row.addProperty("metadataScope", "NOT_REQUESTED_FOR_THIS_TYPE");
                return row.toString();
            }
            try {
                var domain = type.getProtectionDomain();
                row.addProperty("protectionDomainPresent", domain != null);
                var source = domain == null ? null : domain.getCodeSource();
                row.addProperty("codeSourcePresent", source != null);
                var location = source == null ? null : source.getLocation();
                row.addProperty("locationPresent", location != null);
                if (location != null) {
                    row.addProperty("url", location.toExternalForm());
                    row.addProperty("protocol", location.getProtocol());
                    row.addProperty(
                        "uri",
                        location.toURI()
                            .toString());
                }
            } catch (Exception unavailable) {
                row.addProperty("metadataReadFailure", unavailable.toString());
            }
            return row.toString();
        }

        JsonObject identity(Class<?> type) {
            JsonObject known = identities.get(type);
            if (known != null) return known;
            String logical = type.getName()
                .replace('.', '/') + ".class";
            try {
                require(
                    type.getProtectionDomain() != null && type.getProtectionDomain()
                        .getCodeSource() != null,
                    "Reviewed class has no exact CodeSource: " + type.getName());
                Path artifact = PackagedClassResourceResolver.artifactOf(type);
                require(Files.isRegularFile(artifact), "Reviewed class CodeSource is not a frozen packaged JAR");
                String physical = logical;
                try (JarFile jar = new JarFile(artifact.toFile())) {
                    if (jar.getJarEntry(physical) == null) {
                        require(
                            type.getName()
                                .startsWith("net.minecraft."),
                            "Missing exact non-native JAR class entry: " + logical);
                        physical = nativePhysicalName(type) + ".class";
                        require(
                            jar.getManifest() == null || !"true".equalsIgnoreCase(
                                jar.getManifest()
                                    .getMainAttributes()
                                    .getValue("Multi-Release")),
                            "Mapped native MR class resource requires an explicit reviewed binding");
                    } else {
                        physical = PackagedClassResourceResolver.resolve(type, artifact).physicalEntry;
                    }
                    var entry = jar.getJarEntry(physical);
                    require(entry != null && !entry.isDirectory(), "Exact mapped JAR entry unavailable: " + physical);
                    try (InputStream in = jar.getInputStream(entry)) {
                        var bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            if (n > 0) bytes.write(buffer, 0, n);
                            require(bytes.size() <= 2_000_000, "Class resource exceeds audit bound");
                        }
                        byte[] raw = bytes.toByteArray();
                        require(
                            raw.length >= 8 && raw[0] == (byte) 0xca
                                && raw[1] == (byte) 0xfe
                                && raw[2] == (byte) 0xba
                                && raw[3] == (byte) 0xbe,
                            "Invalid reviewed class resource");
                        String artifactHash = artifactHashes.get(artifact);
                        if (artifactHash == null) {
                            artifactHash = hex(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(artifact)));
                            artifactHashes.put(artifact, artifactHash);
                        }
                        var row = new JsonObject();
                        row.addProperty("class", type.getName());
                        row.addProperty("logicalEntry", logical);
                        row.addProperty("physicalEntry", physical);
                        row.addProperty(
                            "artifactFile",
                            artifact.getFileName()
                                .toString());
                        row.addProperty("artifactSha256", artifactHash);
                        row.addProperty("classMajor", ((raw[6] & 255) << 8) | (raw[7] & 255));
                        row.addProperty(
                            "resourceSha256",
                            hex(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(raw)));
                        row.addProperty("transformedDefinitionBytesVerified", false);
                        identities.put(type, row);
                        return row;
                    }
                }
            } catch (Exception failure) {
                throw new IllegalStateException(
                    "Reviewed class identity audit failed: " + type.getName()
                        + "; fixedCodeSourceMetadata="
                        + fixedCodeSourceMetadata(type),
                    failure);
            }
        }

        String nativePhysicalName(Class<?> type) {
            try {
                if (nativeNaming == null) {
                    Class<?> api = Class.forName("cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper");
                    Field singleton = api.getField("INSTANCE");
                    unmapNativeName = api.getMethod("unmap", String.class);
                    mapNativeName = api.getMethod("map", String.class);
                    require(
                        singleton.getDeclaringClass() == api && singleton.getType() == api
                            && Modifier.isPublic(singleton.getModifiers())
                            && Modifier.isStatic(singleton.getModifiers())
                            && Modifier.isFinal(singleton.getModifiers())
                            && unmapNativeName.getDeclaringClass() == api
                            && mapNativeName.getDeclaringClass() == api
                            && unmapNativeName.getReturnType() == String.class
                            && mapNativeName.getReturnType() == String.class,
                        "Public native FML naming API changed");
                    nativeNaming = singleton.get(null);
                    require(nativeNaming != null && nativeNaming.getClass() == api, "Native FML naming owner changed");
                }
                String logical = type.getName()
                    .replace('.', '/');
                String physical = (String) unmapNativeName.invoke(nativeNaming, logical);
                require(
                    physical != null && !physical.isEmpty()
                        && logical.equals(mapNativeName.invoke(nativeNaming, physical)),
                    "Native logical/physical mapping did not round-trip");
                return physical;
            } catch (ReflectiveOperationException failure) {
                throw reflection("Exact public FML native-name mapping failed", failure);
            }
        }

        JsonObject audit() {
            var result = new JsonObject();
            result.addProperty("runtimeJava", System.getProperty("java.specification.version"));
            result.addProperty("approvedMethodSha256", CONTRACT);
            result.addProperty(
                "approvedReportSha256",
                "d738de7b2fcebc88adcee9c271d440737e1d5a08a46b100153fa312c0085ecf6");
            result.addProperty(
                "fieldDeclaringClass",
                randomField.getDeclaringClass()
                    .getName());
            result.addProperty("fieldMapping", randomField.getName());
            result.addProperty("fieldModifiers", Modifier.toString(randomField.getModifiers()));
            result.add("fieldAccessProvenance", randomFieldAccess);
            result.addProperty(
                "fieldType",
                randomField.getType()
                    .getName());
            result.addProperty(
                "sessionMapDeclaringClass",
                sessions.getDeclaringClass()
                    .getName());
            result.addProperty("sessionMapMapping", sessions.getName());
            result.addProperty("stackFrameLimit", FRAME_LIMIT);
            result.addProperty("classReferencesRetained", true);
            result.addProperty(
                "nativeNamingApi",
                "FMLDeobfuscatingRemapper.INSTANCE.unmap/map(String), exact round-trip and CodeSource JAR entry");
            var rows = new JsonArray();
            for (JsonObject identity : identities.values()) rows.add(identity);
            result.add("reviewedClassResources", rows);
            return result;
        }
    }

    private static IllegalStateException reflection(String message, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
        IntegrationGuard.throwIfFatal(cause);
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(message, cause);
    }

    private static String hex(byte[] bytes) {
        var value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    private static void require(boolean okay, String message) {
        if (!okay) throw new IllegalStateException(message);
    }
}
