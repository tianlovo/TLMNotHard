package com.tianluoqaq.tlmnh.common.development;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController.Cause;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController.Outcome;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.relauncher.ReflectionHelper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import mods.battlegear2.api.shield.IShield;

/**
 * 原生伤害入口施加受控输入，时钟仅由已登记实体的真实世界tick推进
 * 不推进controller/entity/world，不证明真实箭飞行、O/F/N对比或客户端绘制
 */
public final class MaidShieldRuntimeProbe {

    private enum Phase {
        P_SETTLE,
        P_INPUT,
        P_HOLD,
        A_SETTLE,
        A_INPUT,
        A_ACQUIRE,
        A_HOLD,
        C_SETTLE,
        C_RAISE,
        C_WAIT_AXE,
        C_AXE,
        C_HOLD,
        C_REACTIVATE,
        CLEANING,
        FINISHED
    }

    private enum Termination {
        NORMAL,
        USER_ABORT,
        CONTRACT_FAILURE,
        SERVER_STOP,
        WORLD_UNLOAD
    }

    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .create();
    private static final String RUN_TAG = "TLMNHShieldRuntime";
    private static final int Y = 160, RADIUS = 10, TOTAL_TICKS = 700;
    private static final long WALL_LIMIT = 75_000_000_000L;
    private static final String[] REQUIRED_GATES = { "passiveRepeatedDoesNotRefresh", "passive98StillUsing",
        "passive99StoppedAtOne", "passive100CounterZero", "activeNaturallyAcquiredRecentThreat", "active60StillUsing",
        "active61Stopped", "active62Restarted", "sameItemNewStackStillCooling", "ordinaryCancelKeepsCooldown",
        "differentItemIndependent", "axeShooterProjectileDoesNotDisable", "returningItemKeepsOriginalExpiry",
        "cooldown99StillOne", "cooldown100Available", "cooldownReactivationAfter100" };
    private static volatile MaidShieldRuntimeProbe pending;
    private static volatile CloseReceipt previous;
    private static final TickPump PUMP = new TickPump();
    private static volatile boolean pumpRegistered;

    private final WorldServer world;
    private Thread serverThread;
    private final String run = UUID.randomUUID()
        .toString();
    private final long startedNanos = System.nanoTime();
    private int x, z;
    private final JsonArray rows = new JsonArray(), events = new JsonArray(), inputs = new JsonArray();
    private final JsonObject gates = new JsonObject(), cleanup = new JsonObject();
    private final List<Entity> owned = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();
    private final List<String> cleanupErrors = new ArrayList<>();
    private final CloseReceipt predecessor;
    private EntityPlayerMP owner;
    private EntityCow cow;
    private EntityMaid maid;
    private EmbeddedChannel channel;
    private Item woodItem, ironItem;
    private Phase phase = Phase.P_SETTLE;
    private Termination termination;
    private boolean forgeAttempted, started, requestedSuccess;
    private volatile boolean closing, completed, abortRequested;
    private volatile String publishedStatus;
    private boolean clockPrimed, repeated, sameItemChanged, differentItemChanged, ironRaised, returnedWood,
        taskCancelled;
    private boolean pRaised, pMaintained;
    private int ticks, phaseTick, actorEntityTick, initialNoProgress, cleanupTick, drainedMessages;
    private long lastWorldTime, lastMaintenance, pAnchor, cAnchor, aAnchorTime, aAnchorMaintenance, aLease;
    private String deferredFailure, failure, activeInput;
    private JsonObject runtimeIdentity;
    private JsonObject battlegearIdentity;
    private Path anchor, folder;

    private MaidShieldRuntimeProbe(WorldServer world) {
        this.world = world;
        predecessor = previous;
        var queued = new JsonObject();
        queued.addProperty("active", true);
        queued.addProperty("run", run);
        queued.addProperty("phase", "QUEUED_FOR_SERVER_START");
        queued.addProperty("passiveObservedInNativeTick", false);
        publishedStatus = queued.toString();
    }

    /** serverStarting由真实主线程调用一次，RCON命令只入队，不直接碰世界 */
    public static synchronized void registerForDevelopment() {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !pumpRegistered,
            "Development tick dispatcher already registered or disabled");
        FMLCommonHandler.instance()
            .bus()
            .register(PUMP);
        pumpRegistered = true;
    }

    public static synchronized String start(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && world != null
                && !world.isRemote
                && world.provider.dimensionId == 0,
            "Explicit test overworld required");
        require(pumpRegistered && pending == null, "Shield runtime dispatcher unavailable, active, or cleanup blocked");
        require(
            !MaidBowRuntimeProbe.isFixtureReserved() && !MaidLifeRuntimeProbe.isFixtureReserved(),
            "Another runtime fixture is active");
        var probe = new MaidShieldRuntimeProbe(world);
        pending = probe;
        return probe.run;
    }

    /** cleanup失败保留pending，不允许下一次实验盖掉残留事务 */
    public static boolean isActive() {
        return pending != null;
    }

    public static String activeRun() {
        var current = pending;
        return current == null ? "" : current.run;
    }

    public static JsonObject status() {
        var current = pending;
        if (current != null) return new JsonParser().parse(current.publishedStatus)
            .getAsJsonObject();
        var result = new JsonObject();
        result.addProperty("active", false);
        return result;
    }

    public static synchronized void abort(String expectedRun) {
        require(Boolean.getBoolean("tlmnh.devCommands") && pending != null, "No abortable shield runtime");
        require(pending.run.equals(expectedRun), "Abort run UUID does not own this transaction");
        require(!pending.completed && !pending.closing, "Transaction is already closing or cleanup failed");
        pending.abortRequested = true;
    }

    public static void cancel() {
        try {
            var current = pending;
            if (current != null && !current.completed) {
                if (current.serverThread == null) current.serverThread = Thread.currentThread();
                current.requireThread();
                current.requestFinish(false, Termination.SERVER_STOP, "Test server stopping");
                // 没有下一自然tick时不手抹世界表，也不伪称引用已移除
                if (!current.completed) current.finishResources(true);
            }
        } finally {
            if (pumpRegistered) {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(PUMP);
                pumpRegistered = false;
            }
        }
    }

    private void begin() throws Exception {
        var spawn = world.getSpawnPoint();
        x = (spawn.posX >> 4 << 4) + 8 + 64;
        z = (spawn.posZ >> 4 << 4) + 8 + 64;
        anchor = MinecraftServer.getServer()
            .getFile(".")
            .toPath()
            .toRealPath();
        Path base = checkedDirectory(anchor.resolve("tlmnh-dev"));
        base = checkedDirectory(base.resolve("shield-runtime"));
        folder = base.resolve(run);
        require(!Files.exists(folder, LinkOption.NOFOLLOW_LINKS), "Run artifact directory exists");
        checkedDirectory(folder);
        runtimeIdentity = DevelopmentArtifactIdentity.capture(
            MaidShieldRuntimeProbe.class,
            EntityMaid.class,
            MaidShieldController.class,
            MaidShieldItems.class,
            MaidItemUseState.class,
            MaidBrainCoordinator.class,
            MaidPolicyRuntime.class,
            IsolatedPlayerChannel.class,
            TickPump.class,
            MaidBowRuntimeProbe.class,
            MaidLifeRuntimeProbe.class);
        woodItem = registered("battlegear2:shield.wood");
        ironItem = registered("battlegear2:shield.iron");
        battlegearIdentity = DevelopmentArtifactIdentity.capture(woodItem.getClass(), IShield.class);
        require(
            !MaidBowRuntimeProbe.isFixtureReserved() && !MaidLifeRuntimeProbe.isFixtureReserved(),
            "Runtime fixture changed while start was queued");
        forgeAttempted = true;
        MinecraftForge.EVENT_BUS.register(this);
        for (int cx = (x - RADIUS - 32) >> 4; cx <= (x + RADIUS + 32) >> 4; cx++)
            for (int cz = (z - RADIUS - 32) >> 4; cz <= (z + RADIUS + 32) >> 4; cz++) require(
                world.getChunkProvider()
                    .chunkExists(cx, cz),
                "Fixture neighbourhood is not already loaded");
        var bounds = AxisAlignedBB.getBoundingBox(x - RADIUS, Y - 1, z - RADIUS, x + RADIUS + 1, Y + 6, z + RADIUS + 1);
        require(
            world.getEntitiesWithinAABB(Entity.class, bounds)
                .isEmpty(),
            "Fixture volume has foreign entities");
        for (int bx = x - RADIUS; bx <= x + RADIUS; bx++)
            for (int bz = z - RADIUS; bz <= z + RADIUS; bz++) for (int by = Y - 1; by <= Y + 5; by++) require(
                world.getBlock(bx, by, bz) == Blocks.air && world.getBlockMetadata(bx, by, bz) == 0
                    && world.getTileEntity(bx, by, bz) == null,
                "Fixture needs exact native air/meta0 without tile entities");
        for (int bx = x - RADIUS; bx <= x + RADIUS; bx++) for (int bz = z - RADIUS; bz <= z + RADIUS; bz++) {
            require(world.setBlock(bx, Y - 1, bz, Blocks.stone, 0, 2), "Native platform placement failed");
            placed.add(new int[] { bx, Y - 1, bz });
        }
        owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.randomUUID(), "TLMNHShieldTick"),
            new ItemInWorldManager(world));
        var manager = new NetworkManager(false);
        channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, channel);
        new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
        owner.theItemInWorldManager.setGameType(GameType.CREATIVE);
        owner.setPosition(x + 2.5, Y, z - 3.5);
        world.playerEntities.add(owner);
        world.updateAllPlayersSleepingFlag();
        require(
            !identityContains(
                MinecraftServer.getServer()
                    .getConfigurationManager().playerEntityList,
                owner),
            "Isolated owner entered real server player list");
        cow = new EntityCow(world);
        owned.add(cow);
        cow.getEntityData()
            .setString(RUN_TAG, run);
        stationary(cow);
        cow.setPosition(x + .5, Y, z + 6.5);
        require(world.spawnEntityInWorld(cow) && present(cow), "Registered vanilla cow spawn failed");
        spawnMaid(MaidTask.IDLE);
        lastWorldTime = world.getTotalWorldTime();
        record("SETUP", "actual_registered_cow_and_maid");
        TLMNotHard.LOG.info("TLMNH_SHIELD_RUNTIME_STARTED run={} scope=registered-native-timing", run);
    }

    private void spawnMaid(MaidTask task) {
        if (maid != null) {
            var retired = maid;
            world.removeEntity(retired);
            require(retired.isDead && !retired.isBlockingWithShield(), "Retired maid kept defensive use");
        }
        cow.setCurrentItemOrArmor(0, null);
        maid = new EntityMaid(world);
        owned.add(maid);
        maid.getEntityData()
            .setString(RUN_TAG, run);
        require(maid.bindTo(owner.getUniqueID()), "Owned maid binding failed");
        maid.setTask(task);
        maid.setPickupItems(false);
        maid.setPickupExperience(false);
        maid.setOffhandItem(shield(woodItem));
        if (task == MaidTask.ATTACK) maid.setCurrentItemOrArmor(0, new ItemStack(Items.wooden_sword));
        stationary(maid);
        maid.setLocationAndAngles(x + .5, Y, z + .5, 0, 0);
        cow.setPosition(x + .5, Y, z + 6.5);
        require(world.spawnEntityInWorld(maid) && present(maid), "Registered native maid spawn failed");
        actorEntityTick = maid.ticksExisted;
        lastMaintenance = maid.getShieldController()
            .getSnapshot().maintenanceTicks;
        clockPrimed = false;
        initialNoProgress = 0;
    }

    private static void stationary(EntityLivingBase entity) {
        entity.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        entity.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        entity.motionX = entity.motionY = entity.motionZ = 0;
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (completed) return;
        if (!started) {
            if (event.phase != TickEvent.Phase.START) return;
            serverThread = Thread.currentThread();
            started = true;
            try {
                begin();
            } catch (Exception | LinkageError failure) {
                TLMNotHard.LOG.error("TLMNH_SHIELD_RUNTIME_SETUP_EXCEPTION run={}", run, failure);
                requestFinish(false, Termination.CONTRACT_FAILURE, failure.toString());
            }
        }
        if (completed) return;
        requireThread();
        try {
            if (event.phase == TickEvent.Phase.START && abortRequested && !closing)
                requestFinish(false, Termination.USER_ABORT, "Explicit owned runtime abort");
            if (phase == Phase.CLEANING) {
                if (event.phase == TickEvent.Phase.END) {
                    cleanupTick++;
                    record("CLEANUP_END", "natural_reference_removal_check");
                    publishStatus();
                    if (ownedGone()) finishResources(false);
                    else if (cleanupTick >= 5) {
                        cleanupErrors.add("Owned entity references survived five natural cleanup ticks");
                        finishResources(false);
                    }
                }
                return;
            }
            require(Boolean.getBoolean("tlmnh.devCommands"), "Runtime authority revoked");
            require(System.nanoTime() - startedNanos <= WALL_LIMIT, "Runtime wall-clock deadline");
            if (event.phase == TickEvent.Phase.START) {
                startInputs();
                return;
            }
            ticks++;
            require(ticks <= TOTAL_TICKS, "Total runtime tick bound");
            validateActorClock();
            record("END", "native_world_entity_tick");
            if (clockPrimed) advance();
            publishStatus();
        } catch (RuntimeException | LinkageError | AssertionError error) {
            TLMNotHard.LOG.error("TLMNH_SHIELD_RUNTIME_EXCEPTION run={} phase={}", run, phase, error);
            if (phase == Phase.CLEANING) {
                cleanupErrors.add("cleanup-tick:" + error);
                finishResources(false);
            } else requestFinish(false, Termination.CONTRACT_FAILURE, error.toString());
        }
    }

    private void publishStatus() {
        var value = new JsonObject();
        value.addProperty("active", true);
        value.addProperty("run", run);
        value.addProperty("phase", phase.name());
        value.addProperty("ticks", ticks);
        value.addProperty("passiveRaised", pRaised);
        value.addProperty("passiveObservedInNativeTick", pMaintained);
        value.addProperty("abortQueued", abortRequested);
        value.addProperty("cleanupBlocked", completed);
        value.addProperty("naturalCleanupEndTicks", cleanupTick);
        publishedStatus = value.toString();
    }

    private void validateActorClock() {
        require(deferredFailure == null, deferredFailure);
        require(
            !MaidBowRuntimeProbe.isFixtureReserved() && !MaidLifeRuntimeProbe.isFixtureReserved(),
            "Another runtime entered the owned fixture");
        require(present(maid) && present(cow) && maid.isEntityAlive() && cow.isEntityAlive(), "Owned participant lost");
        require(cow.getHealth() == cow.getMaxHealth(), "Controlled timing target took unexpected damage");
        require(
            maid.isOwner(owner) && identityContains(world.playerEntities, owner)
                && owner.worldObj == world
                && owner.isEntityAlive()
                && !identityContains(
                    MinecraftServer.getServer()
                        .getConfigurationManager().playerEntityList,
                    owner),
            "Isolated owner identity/list changed");
        IsolatedPlayerChannel.requireUnbound(channel);
        require(
            maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .getAttributeValue() == 0
                && cow.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue() == 0
                && maid.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                    .getAttributeValue() == 1
                && cow.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                    .getAttributeValue() == 1,
            "Controlled stationary profile changed");
        require(
            Math.abs(maid.posX - x - .5) < .001 && Math.abs(maid.posZ - z - .5) < .001
                && Math.abs(maid.posY - Y) < .001,
            "Maid left controlled timing position");
        require(ticks - phaseTick <= phaseLimit(), "Runtime phase timeout: " + phase);
        long now = world.getTotalWorldTime();
        long maintenance = maid.getShieldController()
            .getSnapshot().maintenanceTicks;
        int entityDelta = maid.ticksExisted - actorEntityTick;
        long maintenanceDelta = maintenance - lastMaintenance;
        if (!clockPrimed && entityDelta == 0 && maintenanceDelta == 0) {
            require(++initialNoProgress <= 1, "Initial native entity tick missing");
        } else {
            require(entityDelta == 1 && maintenanceDelta == 1, "Entity/maintenance clocks are not exactly continuous");
            require(!clockPrimed || now == lastWorldTime + 1, "World time skipped while actor clock was armed");
            clockPrimed = true;
        }
        actorEntityTick = maid.ticksExisted;
        lastMaintenance = maintenance;
        lastWorldTime = now;
        if (clockPrimed && phase != Phase.P_SETTLE && phase != Phase.A_SETTLE && phase != Phase.C_SETTLE)
            requireLegacyPolicy();
    }

    private void requireLegacyPolicy() {
        var runtime = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        require(
            runtime != null && runtime.isReady() && runtime.getProfile() == MaidBrainProfile.LEGACY_PROTOTYPE,
            "Actual native legacy policy unavailable after settle");
    }

    private void startInputs() {
        switch (phase) {
            case P_INPUT -> {
                damage("p_first_projectile", true, 4, true);
                var state = shieldState();
                require(
                    state.using && state.cause == Cause.PASSIVE && state.passiveRemaining == 100,
                    "Initial passive activation mismatch");
                pAnchor = state.maintenanceTicks;
                pRaised = true;
                change(Phase.P_HOLD);
            }
            case P_HOLD -> {
                long n = shieldState().maintenanceTicks - pAnchor;
                if (n == 40 && !repeated) {
                    long generation = maid.getItemUseState()
                        .getSnapshot().generation;
                    require(shieldState().passiveRemaining == 60, "Passive counter before repeated input");
                    damage("p_repeat_existing_offhand", true, 4, true);
                    require(
                        shieldState().passiveRemaining == 60 && maid.getItemUseState()
                            .getSnapshot().generation == generation,
                        "Repeated input refreshed OFF use");
                    repeated = true;
                    gate("passiveRepeatedDoesNotRefresh");
                }
            }
            case A_INPUT -> {
                damage("a_recent_threat_native_melee", false, 1, false);
                require(
                    maid.getAITarget() == cow && MaidCombatTargeting.canAttack(maid, owner, cow),
                    "Controlled cow did not become an eligible native recent threat");
                change(Phase.A_ACQUIRE);
            }
            case C_RAISE -> {
                damage("c_raise_projectile", true, 4, true);
                require(shieldState().using, "Cooldown setup did not raise shield");
                change(Phase.C_WAIT_AXE);
            }
            case C_AXE -> {
                require(
                    maid.hurtResistantTime <= maid.maxHurtResistantTime / 2,
                    "Native hurt window still suppresses axe");
                cow.setCurrentItemOrArmor(0, new ItemStack(Items.iron_axe));
                damage("c_native_axe_disable", false, 4, true);
                require(
                    !shieldState().using && cooldown(woodItem) == 100
                        && maid.getShieldController()
                            .getLastBlock().cooldownApplied == 100,
                    "Axe did not commit per-Item cooldown");
                cAnchor = shieldState().maintenanceTicks;
                change(Phase.C_HOLD);
            }
            case C_HOLD -> cooldownInputs();
            case C_REACTIVATE -> {
                require(cooldown(woodItem) == 0 && maid.canUseShield(), "Expired wood Item not available");
                damage("c_reactivate_after_100", true, 4, true);
                require(
                    shieldState().using && cooldown(woodItem) == 0 && cooldown(ironItem) == 0,
                    "Expired Item did not reactivate without shared cooldown");
                gate("cooldownReactivationAfter100");
                for (String name : REQUIRED_GATES) require(
                    gates.has(name) && gates.get(name)
                        .getAsBoolean(),
                    "Missing completed runtime contract gate: " + name);
                requestFinish(true, Termination.NORMAL, "");
            }
            default -> {}
        }
    }

    private void cooldownInputs() {
        long n = shieldState().maintenanceTicks - cAnchor;
        if (n == 1 && !sameItemChanged) {
            equipment("c_same_item_new_stack", shield(woodItem));
            require(cooldown(woodItem) == 99 && !maid.canUseShield(), "Same Item new stack bypassed cooldown");
            sameItemChanged = true;
            gate("sameItemNewStackStillCooling");
        }
        if (n == 3 && !taskCancelled) {
            inputBefore("c_ordinary_task_cancel");
            maid.setTask(MaidTask.IDLE);
            inputAfter("c_ordinary_task_cancel");
            require(cooldown(woodItem) == 97, "Ordinary task cancel cleared cooldown");
            taskCancelled = true;
            gate("ordinaryCancelKeepsCooldown");
        }
        if (n == 5 && !differentItemChanged) {
            equipment("c_different_item", shield(ironItem));
            require(
                cooldown(woodItem) == 95 && cooldown(ironItem) == 0 && maid.canUseShield(),
                "Different Item inherited wood cooldown");
            differentItemChanged = true;
            gate("differentItemIndependent");
        }
        if (n == 15 && !ironRaised) {
            require(maid.hurtResistantTime <= maid.maxHurtResistantTime / 2, "Native iron activation window");
            damage("c_iron_projectile_axe_shooter", true, 4, true);
            require(
                cooldown(woodItem) == 85 && cooldown(ironItem) == 0
                    && maid.getShieldController()
                        .getLastBlock().cooldownApplied == 0,
                "Projectile shooter axe disabled shield");
            ironRaised = true;
            gate("axeShooterProjectileDoesNotDisable");
        }
        if (n == 20 && !returnedWood) {
            equipment("c_return_wood_new_stack", shield(woodItem));
            require(
                cooldown(woodItem) == 80 && !maid.canUseShield() && !shieldState().using,
                "Returning wood Item reset cooldown or retained foreign use");
            returnedWood = true;
            gate("returningItemKeepsOriginalExpiry");
        }
    }

    private void advance() {
        var state = shieldState();
        switch (phase) {
            case P_SETTLE -> {
                if (ticks - phaseTick >= 6) {
                    requireLegacyPolicy();
                    change(Phase.P_INPUT);
                }
            }
            case A_SETTLE -> {
                if (ticks - phaseTick >= 6) {
                    requireLegacyPolicy();
                    change(Phase.A_INPUT);
                }
            }
            case C_SETTLE -> {
                if (ticks - phaseTick >= 6) {
                    requireLegacyPolicy();
                    change(Phase.C_RAISE);
                }
            }
            case P_HOLD -> {
                long n = state.maintenanceTicks - pAnchor;
                require(state.passiveRemaining == 100 - n, "Passive counter is not tied to actual maintenance");
                require(state.using == (n < 99), "Passive use stopped on the wrong native maintenance");
                if (n >= 1 && n < 99) pMaintained = true;
                if (n == 98) {
                    require(state.passiveRemaining == 2, "Passive98");
                    gate("passive98StillUsing");
                }
                if (n == 99) {
                    require("passive_counter_one".equals(state.lastStop), "Passive99 stopped for another reason");
                    gate("passive99StoppedAtOne");
                }
                if (n == 100) {
                    require(
                        repeated && pMaintained
                            && gates.has("passive98StillUsing")
                            && gates.has("passive99StoppedAtOne"),
                        "Incomplete passive boundary samples");
                    gate("passive100CounterZero");
                    spawnMaid(MaidTask.ATTACK);
                    change(Phase.A_SETTLE);
                }
            }
            case A_ACQUIRE -> {
                if (state.activeTaskRunning && state.using) {
                    require(
                        maid.getAttackTarget() == cow && state.cause == Cause.ACTIVE,
                        "Active use was not native target acquisition");
                    aAnchorTime = world.getTotalWorldTime();
                    aAnchorMaintenance = state.maintenanceTicks;
                    aLease = maid.getItemUseState()
                        .getSnapshot().generation;
                    require(state.activeEndTime == aAnchorTime + 60, "Active initial deadline");
                    gate("activeNaturallyAcquiredRecentThreat");
                    record("ANCHOR", "a_first_natural_active");
                    change(Phase.A_HOLD);
                } else require(ticks - phaseTick <= 25, "Natural active target acquisition timed out");
            }
            case A_HOLD -> {
                long n = world.getTotalWorldTime() - aAnchorTime;
                require(state.maintenanceTicks - aAnchorMaintenance == n, "Active world/entity clock diverged");
                require(
                    maid.getAttackTarget() == cow && cow.isEntityAlive()
                        && maid.getDistanceSqToEntity(cow) < 64
                        && MaidCombatTargeting.recentThreat(maid, owner, cow),
                    "Active timing inputs stopped being valid");
                if (n <= 60) require(
                    state.using && state.activeTaskRunning
                        && state.cause == Cause.ACTIVE
                        && state.activeEndTime == aAnchorTime + 60
                        && maid.getItemUseState()
                            .getSnapshot().generation == aLease,
                    "Active use/deadline refreshed or stopped early");
                if (n == 60) gate("active60StillUsing");
                if (n == 61) {
                    require(
                        !state.using && !state.activeTaskRunning && "active_timed_out".equals(state.lastStop),
                        "Active61 did not stop strictly after deadline");
                    gate("active61Stopped");
                }
                if (n == 62) {
                    require(
                        gates.has("active60StillUsing") && gates.has("active61Stopped")
                            && state.using
                            && state.activeTaskRunning
                            && state.cause == Cause.ACTIVE
                            && state.activeEndTime == aAnchorTime + 122
                            && maid.getItemUseState()
                                .getSnapshot().generation > aLease,
                        "Active62 did not restart on later tick");
                    gate("active62Restarted");
                    spawnMaid(MaidTask.IDLE);
                    change(Phase.C_SETTLE);
                }
            }
            case C_WAIT_AXE -> {
                require(state.using, "Passive setup use ended before axe");
                if (maid.hurtResistantTime <= maid.maxHurtResistantTime / 2) change(Phase.C_AXE);
            }
            case C_HOLD -> {
                long n = state.maintenanceTicks - cAnchor;
                require(cooldown(woodItem) == 100 - n, "Per-Item cooldown did not follow actual maintenance");
                if (n == 99) {
                    require(!maid.canUseShield(), "Wood usable at99");
                    gate("cooldown99StillOne");
                }
                if (n == 100) {
                    require(
                        sameItemChanged && differentItemChanged
                            && ironRaised
                            && returnedWood
                            && taskCancelled
                            && gates.has("cooldown99StillOne")
                            && maid.canUseShield(),
                        "Incomplete cooldown sequence");
                    gate("cooldown100Available");
                    change(Phase.C_REACTIVATE);
                }
            }
            default -> {}
        }
    }

    private int phaseLimit() {
        return switch (phase) {
            case P_HOLD -> 110;
            case A_HOLD -> 70;
            case C_HOLD -> 110;
            case A_ACQUIRE -> 26;
            default -> 30;
        };
    }

    private void equipment(String id, ItemStack replacement) {
        inputBefore(id);
        maid.setOffhandItem(replacement);
        inputAfter(id);
    }

    private void damage(String id, boolean projectile, float amount, boolean expectBlock) {
        inputBefore(id);
        double radians = Math.toRadians(maid.rotationYaw);
        cow.setPosition(maid.posX - Math.sin(radians) * 6, Y, maid.posZ + Math.cos(radians) * 6);
        cow.motionX = cow.motionY = cow.motionZ = 0;
        EntityArrow arrow = null;
        DamageSource source;
        if (projectile) {
            arrow = new EntityArrow(world);
            owned.add(arrow);
            arrow.getEntityData()
                .setString(RUN_TAG, run);
            arrow.shootingEntity = cow;
            arrow.setPosition(maid.posX, Y + 1, maid.posZ - 5);
            source = DamageSource.causeArrowDamage(arrow, cow);
        } else source = DamageSource.causeMobDamage(cow);
        var input = new JsonObject();
        input.addProperty("id", id);
        input.addProperty("projectile", projectile);
        input.addProperty("amount", amount);
        input.addProperty("sourceType", source.getDamageType());
        input.addProperty(
            "sourceEntity",
            source.getEntity()
                .getUniqueID()
                .toString());
        input.addProperty(
            "directEntity",
            source.getSourceOfDamage()
                .getUniqueID()
                .toString());
        input.addProperty("realArrowCollisionVerified", false);
        input.add("sourcePosition", position(cow));
        input.add("directPosition", position(source.getSourceOfDamage()));
        input.add("attackerMainhand", MaidShieldFixtures.snapshot(cow.getHeldItem()));
        input.add("before", snapshot());
        long beforeSequence = maid.getShieldController()
            .getLastBlock() == null ? 0
                : maid.getShieldController()
                    .getLastBlock().sequence;
        float health = maid.getHealth();
        ItemStack actualShield = maid.getOffhandItem();
        int wearBefore = actualShield == null ? -1 : actualShield.getItemDamage();
        int beforeEvents = events.size();
        activeInput = id;
        try {
            input.addProperty("nativeReturn", maid.attackEntityFrom(source, amount));
        } finally {
            activeInput = null;
            input.add("after", snapshot());
            input.addProperty("eventStart", beforeEvents);
            input.addProperty("eventEnd", events.size());
            inputs.add(input);
        }
        if (expectBlock) {
            var block = maid.getShieldController()
                .getLastBlock();
            require(
                block != null && block.sequence > beforeSequence
                    && block.outcome == Outcome.BLOCKED
                    && block.sourceEntity.equals(
                        cow.getUniqueID()
                            .toString())
                    && block.directEntity.equals(
                        source.getSourceOfDamage()
                            .getUniqueID()
                            .toString())
                    && block.wearCallbackInvoked
                    && block.requestedWear == Math.round(amount)
                    && maid.getHealth() == health,
                "Actual native damage did not produce expected shield callback");
            require(
                maid.getOffhandItem() == actualShield && actualShield != null
                    && actualShield.stackSize == 1
                    && actualShield.getItemDamage() == wearBefore + Math.round(amount),
                "Actual BG durability delta diverged");
            require(events.size() >= beforeEvents + 4, "Native attack/hurt event evidence missing");
        } else require(maid.getHealth() < health, "Initial recent-threat native melee did not apply health damage");
        inputAfter(id);
    }

    private void inputBefore(String id) {
        record("INPUT_BEFORE", id);
    }

    private void inputAfter(String id) {
        record("INPUT_AFTER", id);
    }

    private MaidShieldController.Snapshot shieldState() {
        return maid.getShieldController()
            .getSnapshot();
    }

    private int cooldown(Item item) {
        return maid.getShieldController()
            .getCooldownRemaining(item);
    }

    private void gate(String name) {
        gates.addProperty(name, true);
    }

    private void change(Phase next) {
        phase = next;
        phaseTick = ticks;
        record("PHASE", next.name());
        TLMNotHard.LOG.info("TLMNH_SHIELD_RUNTIME_PHASE run={} phase={} tick={}", run, phase, ticks);
    }

    private void record(String stage, String id) {
        require(rows.size() < 2048, "Runtime raw row bound exceeded");
        var row = snapshot();
        row.addProperty("stage", stage);
        row.addProperty("id", id);
        rows.add(row);
    }

    private JsonObject snapshot() {
        var row = new JsonObject();
        row.addProperty("run", run);
        row.addProperty("phase", phase.name());
        row.addProperty("serverEndCount", ticks);
        row.addProperty("worldTime", world.getTotalWorldTime());
        row.addProperty("otherBowRuntimeActive", MaidBowRuntimeProbe.isActive());
        row.addProperty("otherLifeRuntimeActive", MaidLifeRuntimeProbe.isActive());
        row.addProperty("otherBowFixtureReserved", MaidBowRuntimeProbe.isFixtureReserved());
        row.addProperty("otherLifeFixtureReserved", MaidLifeRuntimeProbe.isFixtureReserved());
        if (maid != null) {
            row.addProperty(
                "actor",
                maid.getUniqueID()
                    .toString());
            row.addProperty("entityId", maid.getEntityId());
            row.addProperty("entityTick", maid.ticksExisted);
            row.addProperty("registered", present(maid));
            row.addProperty("addedToChunk", maid.addedToChunk);
            row.addProperty("isDead", maid.isDead);
            row.addProperty(
                "task",
                maid.getTask()
                    .name());
            row.addProperty("sitting", maid.isSitting());
            row.addProperty("health", Float.isFinite(maid.getHealth()) ? maid.getHealth() : null);
            row.addProperty(
                "absorption",
                Float.isFinite(maid.getAbsorptionAmount()) ? maid.getAbsorptionAmount() : null);
            row.addProperty("hurtResistantTime", maid.hurtResistantTime);
            row.addProperty("maxHurtResistantTime", maid.maxHurtResistantTime);
            row.addProperty("yaw", Float.isFinite(maid.rotationYaw) ? maid.rotationYaw : null);
            row.add("position", position(maid));
            row.addProperty(
                "movementSpeed",
                finite(
                    maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .getAttributeValue()));
            row.addProperty(
                "knockbackResistance",
                finite(
                    maid.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                        .getAttributeValue()));
            row.add("shield", GSON.toJsonTree(shieldState()));
            row.add(
                "lastBlock",
                GSON.toJsonTree(
                    maid.getShieldController()
                        .getLastBlock()));
            row.add(
                "use",
                GSON.toJsonTree(
                    maid.getItemUseState()
                        .getSnapshot()));
            row.addProperty("woodCooldown", woodItem == null ? -1 : cooldown(woodItem));
            row.addProperty("ironCooldown", ironItem == null ? -1 : cooldown(ironItem));
            row.addProperty("canUseShield", maid.canUseShield());
            row.add("mainhand", MaidShieldFixtures.snapshot(maid.getHeldItem()));
            row.add("offhand", MaidShieldFixtures.snapshot(maid.getOffhandItem()));
            row.addProperty(
                "target",
                maid.getAttackTarget() == null ? ""
                    : maid.getAttackTarget()
                        .getUniqueID()
                        .toString());
            row.addProperty(
                "revengeTarget",
                maid.getAITarget() == null ? ""
                    : maid.getAITarget()
                        .getUniqueID()
                        .toString());
            row.addProperty("revengeRecordedTick", maid.func_142015_aE());
            var brain = maid.getBrainCoordinator();
            var policy = brain.peekPolicyRuntime();
            row.addProperty(
                "policy",
                policy == null ? "absent"
                    : policy.getProfile()
                        .getId());
            row.addProperty("policyReady", policy != null && policy.isReady());
            row.addProperty("suspended", brain.isSuspended());
            row.addProperty(
                "combatStatus",
                brain.getCombatController()
                    .getStatus()
                    .name());
            row.addProperty(
                "combatTicks",
                brain.getCombatController()
                    .getTicks());
            row.addProperty(
                "selections",
                brain.getCombatController()
                    .getSelections());
            row.addProperty(
                "successfulAttacks",
                brain.getCombatController()
                    .getSuccessfulAttacks());
        }
        if (cow != null) {
            row.addProperty(
                "cow",
                cow.getUniqueID()
                    .toString());
            row.addProperty(
                "cowClass",
                cow.getClass()
                    .getName());
            row.addProperty("cowEntityId", cow.getEntityId());
            row.addProperty("cowRegistered", present(cow));
            row.add("cowPosition", position(cow));
            row.addProperty("cowHealth", Float.isFinite(cow.getHealth()) ? cow.getHealth() : null);
            row.addProperty(
                "cowMovementSpeed",
                finite(
                    cow.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .getAttributeValue()));
            row.addProperty(
                "cowKnockbackResistance",
                finite(
                    cow.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                        .getAttributeValue()));
            row.addProperty("cowEntityTick", cow.ticksExisted);
            if (maid != null) {
                double distance = maid.getDistanceSqToEntity(cow);
                row.addProperty("targetDistanceSq", Double.isFinite(distance) ? distance : null);
                row.addProperty("recentThreat", owner != null && MaidCombatTargeting.recentThreat(maid, owner, cow));
            }
        }
        if (owner != null) {
            row.addProperty(
                "owner",
                owner.getUniqueID()
                    .toString());
            row.addProperty("ownerWorld", owner.worldObj.provider.dimensionId);
            row.addProperty(
                "ownerGameType",
                owner.theItemInWorldManager.getGameType()
                    .getID());
            row.addProperty("ownerInWorldPlayers", identityContains(world.playerEntities, owner));
            row.addProperty(
                "ownerInRealServerPlayers",
                identityContains(
                    MinecraftServer.getServer()
                        .getConfigurationManager().playerEntityList,
                    owner));
            row.addProperty("ownerValidForCombat", maid != null && MaidCombatTargeting.validOwner(maid, owner));
            row.add("ownerPosition", position(owner));
        }
        if (channel != null) row.addProperty(
            "rawOwnDispatcherPresent",
            channel.attr(NetworkDispatcher.FML_DISPATCHER)
                .get() != null);
        return row;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void attackIn(LivingAttackEvent event) {
        observe("attack_highest", event.entityLiving, event.source, event.ammount, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void attackOut(LivingAttackEvent event) {
        observe("attack_lowest", event.entityLiving, event.source, event.ammount, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void hurtIn(LivingHurtEvent event) {
        observe("hurt_highest", event.entityLiving, event.source, event.ammount, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void hurtOut(LivingHurtEvent event) {
        observe("hurt_lowest", event.entityLiving, event.source, event.ammount, event.isCanceled());
    }

    private void observe(String stage, EntityLivingBase victim, DamageSource source, float amount, boolean cancelled) {
        if (completed || victim != maid) return;
        try {
            require(events.size() < 256, "Runtime event bound exceeded");
            var row = snapshot();
            row.addProperty("stage", stage);
            row.addProperty("input", activeInput == null ? "uncontrolled" : activeInput);
            row.addProperty("sourceType", source == null ? "" : source.getDamageType());
            row.addProperty("amount", Float.isFinite(amount) ? amount : null);
            row.addProperty("cancelledObserved", cancelled);
            row.addProperty(
                "sourceEntity",
                source == null || source.getEntity() == null ? ""
                    : source.getEntity()
                        .getUniqueID()
                        .toString());
            row.addProperty(
                "directEntity",
                source == null || source.getSourceOfDamage() == null ? ""
                    : source.getSourceOfDamage()
                        .getUniqueID()
                        .toString());
            events.add(row);
            if (activeInput == null) deferredFailure = "Unexpected owned-maid damage input";
        } catch (RuntimeException | LinkageError | AssertionError error) {
            // 观察失败延迟到END处理，不从只读事件旁观器改变原生伤害控制流
            deferredFailure = "event-observation:" + error;
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void death(LivingDeathEvent event) {
        if (!closing && identityContains(owned, event.entityLiving))
            deferredFailure = "Owned participant died unexpectedly";
    }

    @SubscribeEvent(receiveCanceled = true)
    public void drops(LivingDropsEvent event) {
        if (!identityContains(owned, event.entityLiving)) return;
        for (var drop : event.drops) if (!identityContains(owned, drop)) owned.add(drop);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world != world || completed) return;
        requestFinish(false, Termination.WORLD_UNLOAD, "Owned fixture world unloading");
        if (!completed) finishResources(true);
    }

    private void requestFinish(boolean success, Termination reason, String error) {
        requireThread();
        synchronized (MaidShieldRuntimeProbe.class) {
            if (completed || closing) return;
            if (abortRequested && reason == Termination.NORMAL) {
                success = false;
                reason = Termination.USER_ABORT;
                error = "Owned abort was queued before normal completion";
            }
            requestedSuccess = success;
            termination = reason;
            failure = error;
            closing = true;
        }
        phase = Phase.CLEANING;
        cleanupTick = 0;
        publishStatus();
        attempt("owned-before-observation", () -> cleanup.add("ownedBefore", ownedRemovalState()));
        try {
            record("CLEANUP_BEGIN", reason.name());
        } catch (RuntimeException | Error detail) {
            cleanupErrors.add("cleanup-begin-record:" + detail);
        }
        for (Entity entity : new ArrayList<>(owned)) attempt("mark-owned-dead", () -> {
            if (entity.worldObj != null) entity.worldObj.removeEntity(entity);
            else entity.setDead();
            require(entity.isDead, "Owned entity did not become dead");
            if (entity instanceof EntityMaid subject)
                require(!subject.isBlockingWithShield(), "Dead owned maid kept use");
        });
        var goneNow = new boolean[1];
        attempt("owned-removal-observation", () -> goneNow[0] = ownedGone());
        if (goneNow[0]) finishResources(false);
    }

    private boolean ownedGone() {
        for (Entity entity : owned) if (!gone(entity)) return false;
        return true;
    }

    private void finishResources(boolean noFurtherTick) {
        if (completed) return;
        completed = true;
        phase = Phase.FINISHED;
        var goneResult = new boolean[1];
        attempt("owned-removal-final-observation", () -> goneResult[0] = ownedGone());
        boolean gone = goneResult[0];
        cleanup.addProperty("entityReferencesGone", gone);
        attempt("owned-after-observation", () -> cleanup.add("ownedAfter", ownedRemovalState()));
        cleanup.addProperty("naturalCleanupEndTicks", cleanupTick);
        cleanup.addProperty("serverStoppedWithoutRemovalTick", noFurtherTick && !gone);
        if (!gone) cleanupErrors.add("Owned native removal still pending; no fake immediate list removal");
        if (owner != null) attempt("remove-isolated-owner", () -> {
            var iterator = world.playerEntities.iterator();
            while (iterator.hasNext()) if (iterator.next() == owner) iterator.remove();
            world.updateAllPlayersSleepingFlag();
            require(
                !identityContains(world.playerEntities, owner) && !identityContains(world.loadedEntityList, owner)
                    && !identityContains(
                        MinecraftServer.getServer()
                            .getConfigurationManager().playerEntityList,
                        owner),
                "Isolated owner reference retained or became a real player");
        });
        if (owner != null) {
            cleanup.addProperty(
                "owner",
                owner.getUniqueID()
                    .toString());
            cleanup.addProperty("ownerInWorldPlayersAfter", identityContains(world.playerEntities, owner));
            cleanup.addProperty("ownerInLoadedEntitiesAfter", identityContains(world.loadedEntityList, owner));
            cleanup.addProperty(
                "ownerInRealServerPlayersAfter",
                identityContains(
                    MinecraftServer.getServer()
                        .getConfigurationManager().playerEntityList,
                    owner));
        }
        for (int[] at : placed) attempt("remove-owned-platform", () -> {
            require(
                world.getBlock(at[0], at[1], at[2]) == Blocks.stone && world.getBlockMetadata(at[0], at[1], at[2]) == 0
                    && world.getTileEntity(at[0], at[1], at[2]) == null,
                "Platform changed externally; refusing overwrite");
            require(world.setBlockToAir(at[0], at[1], at[2]), "Owned platform removal failed");
            require(
                world.getBlock(at[0], at[1], at[2]) == Blocks.air && world.getBlockMetadata(at[0], at[1], at[2]) == 0
                    && world.getTileEntity(at[0], at[1], at[2]) == null,
                "Owned platform did not restore exact native air/meta0");
        });
        if (channel != null) {
            attempt("isolated-channel-still-unbound", () -> IsolatedPlayerChannel.requireUnbound(channel));
            attempt("finish-isolated-channel", () -> channel.finish());
            drain(true);
            drain(false);
            attempt("isolated-channel-closed", () -> require(!channel.isOpen(), "Embedded channel remains open"));
            cleanup.addProperty("channelOpenAfter", channel.isOpen());
            cleanup.addProperty(
                "rawOwnDispatcherPresentAfter",
                channel.attr(NetworkDispatcher.FML_DISPATCHER)
                    .get() != null);
        }
        if (forgeAttempted) attempt("unregister-forge", () -> MinecraftForge.EVENT_BUS.unregister(this));
        attempt("verify-listener-ownership-removed", () -> {
            boolean fml = busOwns(
                FMLCommonHandler.instance()
                    .bus(),
                this), forge = busOwns(MinecraftForge.EVENT_BUS, this);
            cleanup.addProperty("fmlProbeListenerPresentAfter", fml);
            cleanup.addProperty("forgeProbeListenerPresentAfter", forge);
            require(!fml && !forge, "Probe remains in a native EventBus listeners map");
        });
        cleanup.addProperty("drainedMessages", drainedMessages);
        cleanup.addProperty("perRunFmlListener", false);
        cleanup.addProperty("developmentTickPumpStillRegistered", pumpRegistered);
        cleanup.addProperty("errors", String.join("; ", cleanupErrors));
        cleanup.addProperty("verified", cleanupErrors.isEmpty());
        var summary = summary();
        summary.addProperty("fixtureReleased", cleanupErrors.isEmpty());
        boolean written = false;
        try {
            writeArtifacts(summary);
            written = true;
        } catch (Exception | LinkageError error) {
            summary.addProperty("passed", false);
            summary.addProperty("fixtureReleased", false);
            summary.addProperty("artifactError", error.toString());
        }
        boolean clean = cleanupErrors.isEmpty() && written;
        if (clean) {
            previous = new CloseReceipt(
                run,
                termination == Termination.USER_ABORT,
                pMaintained,
                summary.getAsJsonObject("artifact"));
            if (pending == this) pending = null;
        }
        publishStatus();
        TLMNotHard.LOG.info(
            "TLMNH_SHIELD_RUNTIME_{} {}",
            summary.get("passed")
                .getAsBoolean() && clean ? "PASS" : "FAIL",
            summary);
    }

    private void drain(boolean inbound) {
        attempt(inbound ? "drain-inbound" : "drain-outbound", () -> {
            for (int i = 0; i < 4096; i++) {
                Object message = inbound ? channel.readInbound() : channel.readOutbound();
                if (message == null) return;
                drainedMessages++;
                ReferenceCountUtil.release(message);
                if (message instanceof ReferenceCounted counted)
                    require(counted.refCnt() == 0, "Owned channel refCount remains");
            }
            throw new IllegalStateException("Bounded channel drain exceeded");
        });
    }

    private void attempt(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError | AssertionError error) {
            cleanupErrors.add(name + ":" + error);
        }
    }

    private JsonObject summary() {
        var report = new JsonObject();
        report.addProperty("schema", "shield-natural-runtime-v1");
        report.addProperty("run", run);
        report.addProperty("passed", requestedSuccess && cleanupErrors.isEmpty() && deferredFailure == null);
        report.addProperty("termination", termination.name());
        report.addProperty("failure", failure);
        report.addProperty("observationFailure", deferredFailure);
        report.addProperty("ticks", ticks);
        report.addProperty(
            "jvmStartMillis",
            ManagementFactory.getRuntimeMXBean()
                .getStartTime());
        report.addProperty("elapsedNanos", System.nanoTime() - startedNanos);
        report.addProperty("passiveRaised", pRaised);
        report.addProperty("passiveObservedInNativeTick", pMaintained);
        report.addProperty("registeredEntityTimingScope", true);
        report.addProperty("controlledRecentThreatInput", true);
        report.addProperty("directNativeArrowDamageInput", true);
        report.addProperty("realArrowCollisionVerified", false);
        report.addProperty("originalOComparisonVerified", false);
        report.addProperty("behaviourBenchmarkVerified", false);
        report.addProperty("renderingVerified", false);
        report.addProperty("strictEightBlockBoundaryVerified", false);
        report.addProperty("bowMeleeLifecycleConflictMatrixVerified", false);
        report.add("runtimeIdentity", runtimeIdentity);
        report.add("battlegearIdentity", battlegearIdentity);
        report.add("gates", gates);
        var required = new JsonArray();
        for (String name : REQUIRED_GATES) required.add(new com.google.gson.JsonPrimitive(name));
        report.add("requiredGates", required);
        report.add("cleanup", cleanup);
        if (predecessor != null) report.add("previousClosedTransaction", predecessor.json());
        return report;
    }

    private void writeArtifacts(JsonObject summary) throws Exception {
        require(folder != null && anchor != null, "Artifact directory unavailable");
        var raw = new JsonObject();
        raw.add("summary", new JsonParser().parse(GSON.toJson(summary)));
        raw.add("rows", rows);
        raw.add("events", events);
        raw.add("inputs", inputs);
        byte[] json = GSON.toJson(raw)
            .getBytes(StandardCharsets.UTF_8);
        require(json.length <= 32 * 1024 * 1024, "Bounded runtime raw size exceeded");
        Path target = folder.resolve("trace.json.gz");
        try (
            OutputStream output = Files
                .newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var gzip = new GZIPOutputStream(output)) {
            gzip.write(json);
        }
        byte[] compressed = Files.readAllBytes(target);
        var attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(
            attrs.isRegularFile() && !attrs.isSymbolicLink()
                && !attrs.isOther()
                && attrs.size() == compressed.length
                && target.toRealPath()
                    .equals(
                        target.toAbsolutePath()
                            .normalize()),
            "Runtime artifact is not the expected regular file");
        var artifact = new JsonObject();
        artifact.addProperty(
            "relativePath",
            anchor.relativize(target)
                .toString()
                .replace('\\', '/'));
        artifact.addProperty("sha256", sha(compressed));
        artifact.addProperty("gzipBytes", compressed.length);
        artifact.addProperty("jsonBytes", json.length);
        artifact.addProperty("rows", rows.size());
        artifact.addProperty("events", events.size());
        artifact.addProperty("inputs", inputs.size());
        summary.add("artifact", artifact);
        Files.write(
            folder.resolve("summary.json"),
            GSON.toJson(summary)
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
    }

    private Path checkedDirectory(Path path) throws Exception {
        try {
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException existing) {}
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(
            attrs.isDirectory() && !attrs.isOther()
                && !attrs.isSymbolicLink()
                && path.toRealPath()
                    .equals(
                        path.toAbsolutePath()
                            .normalize())
                && path.toRealPath()
                    .startsWith(anchor),
            "Unsafe runtime artifact directory");
        return path;
    }

    private static boolean busOwns(EventBus bus, Object probe) {
        Map<?, ?> listeners = ReflectionHelper.getPrivateValue(EventBus.class, bus, "listeners");
        return listeners.containsKey(probe);
    }

    private static Item registered(String id) {
        Object item = Item.itemRegistry.getObject(id);
        require(
            item instanceof Item && id.equals(Item.itemRegistry.getNameForObject(item)),
            "Missing exact shield registry item");
        return (Item) item;
    }

    private static ItemStack shield(Item item) {
        var stack = new ItemStack(item);
        require(
            MaidShieldItems.inspect(stack)
                .isReady(),
            "Actual BG shield fixture rejected");
        return stack;
    }

    private static boolean identityContains(Iterable<?> values, Object object) {
        for (Object value : values) if (value == object) return true;
        return false;
    }

    private boolean present(Entity entity) {
        if (entity == null || entity.worldObj != world
            || entity.isDead
            || !identityContains(world.loadedEntityList, entity)
            || world.getEntityByID(entity.getEntityId()) != entity
            || !entity.addedToChunk
            || !world.getChunkProvider()
                .chunkExists(entity.chunkCoordX, entity.chunkCoordZ))
            return false;
        var chunk = world.getChunkFromChunkCoords(entity.chunkCoordX, entity.chunkCoordZ);
        if (!chunk.isChunkLoaded) return false;
        for (var list : chunk.entityLists) if (identityContains(list, entity)) return true;
        return false;
    }

    private static boolean gone(Entity entity) {
        if (entity.worldObj == null) return true;
        var world = entity.worldObj;
        if (identityContains(world.loadedEntityList, entity) || world.getEntityByID(entity.getEntityId()) == entity)
            return false;
        if (world.getChunkProvider()
            .chunkExists(entity.chunkCoordX, entity.chunkCoordZ))
            for (var list : world.getChunkFromChunkCoords(entity.chunkCoordX, entity.chunkCoordZ).entityLists)
                if (identityContains(list, entity)) return false;
        return true;
    }

    private JsonArray ownedRemovalState() {
        var result = new JsonArray();
        for (Entity entity : owned) {
            var row = new JsonObject();
            row.addProperty(
                "uuid",
                entity.getUniqueID()
                    .toString());
            row.addProperty("entityId", entity.getEntityId());
            row.addProperty(
                "entityClass",
                entity.getClass()
                    .getName());
            row.addProperty("isDead", entity.isDead);
            row.addProperty(
                "fixtureRunMarker",
                entity.getEntityData()
                    .getString(RUN_TAG));
            boolean loaded = entity.worldObj != null && identityContains(entity.worldObj.loadedEntityList, entity);
            boolean indexed = entity.worldObj != null && entity.worldObj.getEntityByID(entity.getEntityId()) == entity;
            boolean chunkReference = false;
            if (entity.worldObj != null && entity.worldObj.getChunkProvider()
                .chunkExists(entity.chunkCoordX, entity.chunkCoordZ))
                for (var list : entity.worldObj
                    .getChunkFromChunkCoords(entity.chunkCoordX, entity.chunkCoordZ).entityLists)
                    if (identityContains(list, entity)) chunkReference = true;
            row.addProperty("loadedEntityReference", loaded);
            row.addProperty("worldIdReference", indexed);
            row.addProperty("chunkEntityReference", chunkReference);
            if (entity instanceof EntityMaid subject) row.addProperty("shieldUsing", subject.isBlockingWithShield());
            row.addProperty("unregisteredDirectDamageSourceOnly", entity instanceof EntityArrow);
            result.add(row);
        }
        return result;
    }

    private static JsonObject position(Entity entity) {
        var result = new JsonObject();
        result.addProperty("x", Double.isFinite(entity.posX) ? entity.posX : null);
        result.addProperty("y", Double.isFinite(entity.posY) ? entity.posY : null);
        result.addProperty("z", Double.isFinite(entity.posZ) ? entity.posZ : null);
        return result;
    }

    private static Number finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private void requireThread() {
        require(Thread.currentThread() == serverThread, "Shield runtime accessed outside owning server thread");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static String sha(byte[] bytes) throws Exception {
        var text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) {
            text.append(Character.forDigit((value >>> 4) & 15, 16));
            text.append(Character.forDigit(value & 15, 16));
        }
        return text.toString();
    }

    private static final class CloseReceipt {

        private final String run;
        private final boolean aborted, raisedAfterNativeTick;
        private final long jvmStartMillis = ManagementFactory.getRuntimeMXBean()
            .getStartTime();
        private final String artifact, sha;

        private CloseReceipt(String run, boolean aborted, boolean raised, JsonObject artifact) {
            this.run = run;
            this.aborted = aborted;
            raisedAfterNativeTick = raised;
            this.artifact = artifact.get("relativePath")
                .getAsString();
            sha = artifact.get("sha256")
                .getAsString();
        }

        private JsonObject json() {
            var result = new JsonObject();
            result.addProperty("run", run);
            result.addProperty("aborted", aborted);
            result.addProperty("raisedAfterNativeTick", raisedAfterNativeTick);
            result.addProperty("cleanupVerified", true);
            result.addProperty("jvmStartMillis", jvmStartMillis);
            result.addProperty("artifact", artifact);
            result.addProperty("sha256", sha);
            return result;
        }
    }

    /** 只读取当前事务，不持有旧actor；整个开发服务端生命周期只有一个pump */
    public static final class TickPump {

        private TickPump() {}

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            var current = pending;
            if (current != null) current.tick(event);
        }
    }
}
