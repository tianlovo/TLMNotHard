package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.MaidLifeController;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 真实实体tick驱动的平坦生活链，只观察协调器，不手动推进控制器或生物更新 */
public final class MaidLifeRuntimeProbe {

    private static final int Y = 160, RADIUS = 10, CHUNK_RADIUS = 44, TOTAL_TICKS = 3200;
    private static final long WALL_LIMIT_NANOS = 240_000_000_000L;
    private static final String SCOPE = "natural entity/server ticks on an isolated flat overworld air scaffold; no network-click, client-visual, complex-pathfinding or original-AI benchmark acceptance";
    private static final String RUN_TAG = "TLMNHLifeRuntimeRun";
    private static MaidLifeRuntimeProbe pending;

    private enum Phase {
        WARMUP,
        STEAL,
        PLACE,
        EAT,
        APPROACH_BED,
        HOLD_SLEEP,
        DAWN,
        RESLEEP,
        DANGER_WAKE,
        HOLD_DANGER,
        RECOVER_SLEEP
    }

    private final WorldServer world;
    private final int x, z;
    private final String run = UUID.randomUUID()
        .toString();
    private final long originalTime;
    private final long startedNanos = System.nanoTime();
    private final String originalMobSpawning, originalDaylight;
    private final List<SavedBlock> backup = new ArrayList<>();
    private final JsonArray observations = new JsonArray();
    private EntityPlayerMP owner;
    private EntityMaid maid;
    private EntityZombie danger;
    private EmbeddedChannel connection;
    private TileEntityMaidBed bed;
    private Phase phase = Phase.WARMUP;
    private int ticks, phaseStart, initialEntityTick, previousEntityTick, stalledEntityTicks;
    private int firstSnackSelection = -1, placeSelection = -1, initialFavor, initialHunger;
    private int snackPathBase, bedPathBase;
    private double snackX, snackZ, bedX, bedZ, snackMovementSq, bedApproachMovementSq;
    private float initialHealth;
    private long expectedTime = 6000;
    private boolean registeredFml, registeredForge, finished, changedWorld;
    private boolean stealVerified, placeVerified, eatVerified, bedMovementVerified, dawnVerified, dangerVerified,
        recoveryVerified;

    private MaidLifeRuntimeProbe(WorldServer world) {
        this.world = world;
        var spawn = world.getSpawnPoint();
        x = (spawn.posX >> 4 << 4) + 8 - 64;
        z = (spawn.posZ >> 4 << 4) + 8 - 64;
        originalTime = world.getWorldTime();
        originalMobSpawning = world.getGameRules()
            .getGameRuleStringValue("doMobSpawning");
        originalDaylight = world.getGameRules()
            .getGameRuleStringValue("doDaylightCycle");
    }

    public static void start(WorldServer world) {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development commands must be enabled");
        require(pending == null, "Another life runtime probe is active");
        require(
            world != null && !world.isRemote && world.provider.dimensionId == 0 && !AnimationLab.isLab(world),
            "Dedicated test overworld required");
        require(
            world.difficultySetting != EnumDifficulty.PEACEFUL,
            "Hostile observation requires non-peaceful difficulty");
        var probe = new MaidLifeRuntimeProbe(world);
        pending = probe;
        try {
            probe.preflight();
            FMLCommonHandler.instance()
                .bus()
                .register(probe);
            probe.registeredFml = true;
            MinecraftForge.EVENT_BUS.register(probe);
            probe.registeredForge = true;
            probe.begin();
            var started = new JsonObject();
            started.addProperty("run", probe.run);
            started.addProperty("passed", false);
            started.addProperty("state", "RUNNING");
            started.addProperty("scope", SCOPE);
            started.addProperty("dimension", world.provider.dimensionId);
            started.addProperty("x", probe.x);
            started.addProperty("y", Y);
            started.addProperty("z", probe.z);
            started.addProperty("tickLimit", TOTAL_TICKS);
            started.addProperty("wallLimitSeconds", 240);
            TLMNotHard.LOG.info("TLMNH_LIFE_RUNTIME_STARTED {}", started);
        } catch (RuntimeException | Error error) {
            probe.finish(false, error.toString());
        }
    }

    public static boolean isActive() {
        return pending != null && !pending.finished;
    }

    public static void cancel() {
        if (pending != null) pending.finish(false, "Cancelled by development command");
    }

    private void preflight() {
        require(!world.restoringBlockSnapshots, "Another block restoration is active");
        chunksLoaded();
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++) for (int dy = -1; dy <= 3; dy++) {
                int bx = x + dx, by = Y + dy, bz = z + dz;
                require(
                    world.isAirBlock(bx, by, bz) && world.getTileEntity(bx, by, bz) == null,
                    "Life scaffold requires bounded untouched air");
                backup.add(new SavedBlock(bx, by, bz, world.getBlock(bx, by, bz), world.getBlockMetadata(bx, by, bz)));
            }
        require(
            world.getEntitiesWithinAABBExcludingEntity(null, bounds())
                .isEmpty(),
            "Life scaffold contains original entities");
    }

    private void begin() {
        changedWorld = true;
        world.getGameRules()
            .setOrCreateGameRule("doMobSpawning", "false");
        world.getGameRules()
            .setOrCreateGameRule("doDaylightCycle", "false");
        world.setWorldTime(expectedTime);
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            require(world.setBlock(x + dx, Y - 1, z + dz, Blocks.stone, 0, 2), "Scaffold floor placement");
            if (Math.abs(dx) == RADIUS || Math.abs(dz) == RADIUS)
                require(world.setBlock(x + dx, Y, z + dz, Blocks.fence, 0, 2), "Scaffold safety boundary");
        }
        owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.randomUUID(), "TLMNHLifeRuntime"),
            new ItemInWorldManager(world));
        var manager = new NetworkManager(false);
        connection = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, connection);
        new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        owner.capabilities.disableDamage = true;
        owner.setPosition(x + 0.5D, Y, z + 2.5D);
        owner.getEntityData()
            .setString(RUN_TAG, run);
        world.playerEntities.add(owner);
        world.updateAllPlayersSleepingFlag();

        require(world.setBlock(x + 4, Y, z, MaidLifeContent.snackCabinet, 0, 3), "Native snack cabinet");
        require(world.setBlock(x + 4, Y + 1, z, Blocks.cake, 5, 3), "Native final cake portion");
        owner.rotationYaw = 0;
        var bedItem = new ItemStack(MaidLifeContent.maidBedItem);
        require(
            bedItem.getItem()
                .onItemUse(bedItem, owner, world, x - 3, Y - 1, z + 4, 1, 0.5F, 1, 0.5F) && bedItem.stackSize == 0,
            "Native two-part bed placement");
        require(world.getTileEntity(x - 3, Y, z + 5) instanceof TileEntityMaidBed, "Actual bed head");
        bed = (TileEntityMaidBed) world.getTileEntity(x - 3, Y, z + 5);
        require(bed.complete(), "Complete paired bed");

        maid = new EntityMaid(world);
        require(maid.bindTo(owner.getUniqueID()), "Isolated owner binding");
        maid.setTask(MaidTask.ATTACK);
        maid.setSchedule(MaidSchedule.DAY);
        maid.setPickupItems(false);
        maid.setPickupExperience(false);
        protect(maid);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setSitting(false);
        maid.setLocationAndAngles(x + 0.5D, Y, z + 0.5D, 0, 0);
        require(world.spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid, world), "Actual living maid spawn");
        initialEntityTick = previousEntityTick = maid.ticksExisted;
        initialFavor = maid.getFavorability();
        initialHunger = maid.getHunger();
        initialHealth = maid.getHealth();
        snackX = maid.posX;
        snackZ = maid.posZ;
        snackPathBase = life().getPathRequests();
        record("setup");
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        try {
            ticks++;
            require(
                ticks <= TOTAL_TICKS && ticks - phaseStart <= phaseLimit(),
                "Natural tick deadline exceeded: " + phase);
            require(System.nanoTime() - startedNanos <= WALL_LIMIT_NANOS, "Wall-clock safety deadline exceeded");
            verifyParticipants();
            if (ticks % 100 == 0) record("sample");
            advance();
        } catch (RuntimeException | Error error) {
            finish(false, error.toString());
        }
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world == world && !finished) finish(false, "World unloaded during natural life probe");
    }

    private void verifyParticipants() {
        IsolatedPlayerChannel.requireUnbound(connection);
        require(
            maid != null && maid.isEntityAlive() && MaidSpawnChecks.present(maid, world) && maid.isOwner(owner),
            "Natural actor identity lost");
        require(
            owner != null && owner.isEntityAlive()
                && owner.worldObj == world
                && world.playerEntities.contains(owner)
                && owner.theItemInWorldManager.getGameType()
                    .getID() == 0
                && owner.playerNetServerHandler.netManager.isChannelOpen(),
            "Playable memory owner lost");
        require(
            maid.getTask() == MaidTask.ATTACK && maid.getHeldItem() == null
                && maid.getOffhandItem() == null
                && maid.getAttackTarget() == null
                && maid.hurtTime == 0
                && maid.getHealth() == initialHealth,
            "No-weapon, no-damage inputs changed");
        require(
            maid.posX > x - RADIUS + 0.5D && maid.posX < x + RADIUS + 0.5D
                && maid.posZ > z - RADIUS + 0.5D
                && maid.posZ < z + RADIUS + 0.5D
                && maid.posY >= Y - 0.1D
                && maid.posY <= Y + 2,
            "Actor left protected scaffold");
        if (maid.ticksExisted <= previousEntityTick) stalledEntityTicks++;
        else stalledEntityTicks = 0;
        previousEntityTick = maid.ticksExisted;
        require(stalledEntityTicks <= 3, "Server ticks did not naturally update the entity");
        require(
            !maid.isSitting() && !maid.getLeashed() && maid.ridingEntity == null && maid.riddenByEntity == null,
            "Unexpected hard constraint");
        require(
            world.getWorldTime() == expectedTime && world.getGameRules()
                .getGameRuleStringValue("doMobSpawning")
                .equals("false")
                && world.getGameRules()
                    .getGameRuleStringValue("doDaylightCycle")
                    .equals("false"),
            "Shared test clock or gamerules changed externally");
        if (ticks % 20 == 0) chunksLoaded();
        if (phase == Phase.DANGER_WAKE || phase == Phase.HOLD_DANGER) require(
            danger != null && danger.isEntityAlive() && MaidSpawnChecks.present(danger, world),
            "Visible hostile disappeared");
    }

    private void advance() {
        var life = life();
        int elapsed = ticks - phaseStart;
        switch (phase) {
            case WARMUP:
                if (elapsed < 8) return;
                require(maid.ticksExisted - initialEntityTick >= 5, "Warmup did not use real entity ticks");
                maid.getBrainCoordinator()
                    .setSuspended(false);
                change(Phase.STEAL);
                return;
            case STEAL:
                snackMovementSq = Math.max(snackMovementSq, horizontalSq(maid.posX, maid.posZ, snackX, snackZ));
                if (life.getStatus() == MaidLifeController.Status.APPROACH_SNACK && firstSnackSelection < 0)
                    firstSnackSelection = ticks;
                if (life.getBites() == 0) return;
                require(
                    life.getBites() == 1 && life.getPlacements() == 0
                        && firstSnackSelection >= 0
                        && life.getPathRequests() > snackPathBase
                        && snackMovementSq >= 1,
                    "Snack required observed natural approach");
                require(
                    world.isAirBlock(x + 4, Y + 1, z) && maid.getFavorability() == initialFavor + 1
                        && maid.getHunger() == initialHunger,
                    "Final native cake portion and original reward semantics");
                stealVerified = true;
                maid.getMaidInventory()
                    .setInventorySlotContents(0, new ItemStack(Items.cake));
                change(Phase.PLACE);
                return;
            case PLACE:
                if (life.getStatus() == MaidLifeController.Status.APPROACH_SNACK && placeSelection < 0)
                    placeSelection = ticks;
                if (life.getPlacements() == 0) {
                    require(
                        cakeCount() == 1 && world.isAirBlock(x + 4, Y + 1, z),
                        "Bag cake changed before natural placement");
                    return;
                }
                if (placeSelection < 0) placeSelection = ticks;
                require(
                    ticks - firstSnackSelection >= MaidLifeRules.SNACK_INTERVAL && life.getPlacements() == 1
                        && cakeCount() == 0
                        && world.getBlock(x + 4, Y + 1, z) == Blocks.cake
                        && world.getBlockMetadata(x + 4, Y + 1, z) == 0
                        && life.getBites() == 1,
                    "Real cooldown and native backpack placement");
                placeVerified = true;
                change(Phase.EAT);
                return;
            case EAT:
                if (life.getBites() == 1) return;
                require(
                    life.getBites() == 2 && life.getPlacements() == 1
                        && ticks - placeSelection >= MaidLifeRules.SNACK_INTERVAL
                        && world.getBlock(x + 4, Y + 1, z) == Blocks.cake
                        && world.getBlockMetadata(x + 4, Y + 1, z) == 1,
                    "Placed cake eaten through another real cooldown");
                require(
                    maid.getFavorability() == initialFavor + 1 && maid.getHunger() == initialHunger,
                    "Natural reward cooldown conserved");
                eatVerified = true;
                bedX = maid.posX;
                bedZ = maid.posZ;
                bedPathBase = life.getPathRequests();
                require(
                    horizontalSq(bedX, bedZ, bed.xCoord + 0.5D, bed.zCoord + 0.5D) >= 9,
                    "Bed fixture requires movement");
                setTime(16000);
                change(Phase.APPROACH_BED);
                return;
            case APPROACH_BED:
                if (life.getStatus() == MaidLifeController.Status.APPROACH_BED && !life.isSleeping())
                    bedApproachMovementSq = Math
                        .max(bedApproachMovementSq, horizontalSq(maid.posX, maid.posZ, bedX, bedZ));
                if (!life.isSleeping()) return;
                require(
                    life.getPathRequests() > bedPathBase && bedApproachMovementSq >= 1 && life.getSleepEntries() == 1,
                    "Bed approach moved before production sleep positioning");
                sleepState(true);
                bedMovementVerified = true;
                change(Phase.HOLD_SLEEP);
                return;
            case HOLD_SLEEP:
                sleepState(true);
                if (elapsed < 20) return;
                setTime(0);
                change(Phase.DAWN);
                return;
            case DAWN:
                if (life.isSleeping() || maid.hasPose(MaidPose.SLEEPING)) return;
                sleepState(false);
                dawnVerified = true;
                if (elapsed < 10) return;
                setTime(16000);
                change(Phase.RESLEEP);
                return;
            case RESLEEP:
                if (!life.isSleeping()) return;
                require(life.getSleepEntries() == 2, "Natural return to night rest");
                sleepState(true);
                spawnDanger();
                change(Phase.DANGER_WAKE);
                return;
            case DANGER_WAKE:
                if (life.isSleeping() || maid.hasPose(MaidPose.SLEEPING)) return;
                sleepState(false);
                require(
                    maid.getBrainCoordinator()
                        .getCombatController()
                        .getStatus()
                        .name()
                        .equals("NO_WEAPON") && life.getStatus() == MaidLifeController.Status.BLOCKED
                        && maid.getEntitySenses()
                            .canSee(danger),
                    "Visible danger interrupts sleep despite no weapon");
                dangerVerified = true;
                change(Phase.HOLD_DANGER);
                return;
            case HOLD_DANGER:
                sleepState(false);
                require(life.getStatus() == MaidLifeController.Status.BLOCKED, "Danger keeps life action interrupted");
                if (elapsed < 25) return;
                world.removeEntity(danger);
                change(Phase.RECOVER_SLEEP);
                return;
            case RECOVER_SLEEP:
                if (!life.isSleeping()) return;
                require(life.getSleepEntries() == 3, "Rest recovers after danger removal");
                sleepState(true);
                recoveryVerified = true;
                finish(true, null);
        }
    }

    private void spawnDanger() {
        danger = new EntityZombie(world);
        protect(danger);
        danger.tasks.taskEntries.clear();
        danger.targetTasks.taskEntries.clear();
        danger.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        danger.setAttackTarget(null);
        danger.setLocationAndAngles(bed.xCoord + 4.5D, Y, bed.zCoord + 0.5D, 180, 0);
        require(
            world.spawnEntityInWorld(danger) && MaidSpawnChecks.present(danger, world),
            "Actual inert visible zombie");
    }

    private void sleepState(boolean expected) {
        require(
            life().isSleeping() == expected && maid.hasPose(MaidPose.SLEEPING) == expected,
            "Controller and entity sleep pose agree");
        require(
            bed.complete() && (expected ? bed.isOwnedBy(maid) : bed.available(maid)),
            "Native bed occupancy agrees");
        require(
            ((world.getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 4) != 0) == expected,
            "Native bed occupied bit agrees");
    }

    private void protect(EntityLivingBase entity) {
        var saved = new NBTTagCompound();
        entity.writeToNBT(saved);
        saved.setBoolean("Invulnerable", true);
        entity.readFromNBT(saved);
        entity.getEntityData()
            .setString(RUN_TAG, run);
    }

    private void setTime(long time) {
        expectedTime = time;
        world.setWorldTime(time);
    }

    private MaidLifeController life() {
        return maid.getBrainCoordinator()
            .getLifeController();
    }

    private int cakeCount() {
        int result = 0;
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) {
            var stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (stack != null && stack.getItem() == Items.cake) result += stack.stackSize;
        }
        return result;
    }

    private void change(Phase next) {
        record("phase_complete");
        var progress = new JsonObject();
        progress.addProperty("run", run);
        progress.addProperty("passed", false);
        progress.addProperty("scope", SCOPE);
        progress.addProperty("tick", ticks);
        progress.addProperty("completedPhase", phase.name());
        progress.addProperty("nextPhase", next.name());
        TLMNotHard.LOG.info("TLMNH_LIFE_RUNTIME_PROGRESS {}", progress);
        phase = next;
        phaseStart = ticks;
    }

    private int phaseLimit() {
        switch (phase) {
            case PLACE:
            case EAT:
                return MaidLifeRules.SNACK_INTERVAL + 220;
            case STEAL:
            case APPROACH_BED:
            case RESLEEP:
            case RECOVER_SLEEP:
                return 260;
            case DANGER_WAKE:
            case DAWN:
                return 40;
            default:
                return 40;
        }
    }

    private void chunksLoaded() {
        for (int cx = (x - CHUNK_RADIUS) >> 4; cx <= (x + CHUNK_RADIUS) >> 4; cx++)
            for (int cz = (z - CHUNK_RADIUS) >> 4; cz <= (z + CHUNK_RADIUS) >> 4; cz++) require(
                world.getChunkProvider()
                    .chunkExists(cx, cz),
                "Natural entity update requires loaded neighbor chunks: " + cx + "," + cz);
    }

    private AxisAlignedBB bounds() {
        return AxisAlignedBB.getBoundingBox(x - RADIUS, Y - 1, z - RADIUS, x + RADIUS + 1, Y + 4, z + RADIUS + 1);
    }

    private void record(String reason) {
        var observation = new JsonObject();
        observation.addProperty("reason", reason);
        observation.addProperty("tick", ticks);
        observation.addProperty("phase", phase.name());
        observation.addProperty("x", maid.posX);
        observation.addProperty("y", maid.posY);
        observation.addProperty("z", maid.posZ);
        observation.addProperty("entityTick", maid.ticksExisted);
        observation.addProperty(
            "status",
            life().getStatus()
                .name());
        observation.addProperty("paths", life().getPathRequests());
        observation.addProperty("bites", life().getBites());
        observation.addProperty("placements", life().getPlacements());
        observation.addProperty("sleepEntries", life().getSleepEntries());
        observations.add(observation);
    }

    private void finish(boolean success, String error) {
        if (finished) return;
        finished = true;
        var errors = new ArrayList<String>();
        int entityTicks = maid == null ? 0 : maid.ticksExisted - initialEntityTick;
        int bites = maid == null ? 0 : life().getBites(), placements = maid == null ? 0 : life().getPlacements();
        int sleeps = maid == null ? 0 : life().getSleepEntries(), paths = maid == null ? 0 : life().getPathRequests();
        try {
            if (maid != null) restore(
                () -> maid.getBrainCoordinator()
                    .setSuspended(true),
                errors);
            if (danger != null) restore(() -> world.removeEntity(danger), errors);
            if (maid != null) restore(() -> world.removeEntity(maid), errors);
            if (owner != null) restore(() -> {
                world.playerEntities.remove(owner);
                world.updateAllPlayersSleepingFlag();
            }, errors);
            if (changedWorld) {
                restore(this::restoreBlocks, errors);
                restore(() -> world.setWorldTime(originalTime), errors);
                restore(
                    () -> world.getGameRules()
                        .setOrCreateGameRule("doMobSpawning", originalMobSpawning),
                    errors);
                restore(
                    () -> world.getGameRules()
                        .setOrCreateGameRule("doDaylightCycle", originalDaylight),
                    errors);
                restore(
                    () -> require(
                        world.getWorldTime() == originalTime && world.getGameRules()
                            .getGameRuleStringValue("doMobSpawning")
                            .equals(originalMobSpawning)
                            && world.getGameRules()
                                .getGameRuleStringValue("doDaylightCycle")
                                .equals(originalDaylight),
                        "Clock and gamerules not restored"),
                    errors);
            }
        } finally {
            try {
                if (connection != null) {
                    restore(() -> IsolatedPlayerChannel.requireUnbound(connection), errors);
                    restore(() -> connection.finish(), errors);
                    releaseMessages(true, errors);
                    releaseMessages(false, errors);
                }
            } finally {
                if (registeredForge) restore(() -> MinecraftForge.EVENT_BUS.unregister(this), errors);
                if (registeredFml) restore(
                    () -> FMLCommonHandler.instance()
                        .bus()
                        .unregister(this),
                    errors);
                if (pending == this) pending = null;
            }
        }
        boolean scenarios = stealVerified && placeVerified
            && eatVerified
            && bedMovementVerified
            && dawnVerified
            && dangerVerified
            && recoveryVerified;
        var result = new JsonObject();
        result.addProperty("run", run);
        result.addProperty("passed", success && scenarios && errors.isEmpty());
        result.addProperty("scope", SCOPE);
        result.addProperty("ticks", ticks);
        result.addProperty("naturalEntityTicks", entityTicks);
        result.addProperty("wallMillis", (System.nanoTime() - startedNanos) / 1_000_000L);
        result.addProperty("bites", bites);
        result.addProperty("placements", placements);
        result.addProperty("sleepEntries", sleeps);
        result.addProperty("pathRequests", paths);
        result.addProperty("snackMovementSq", snackMovementSq);
        result.addProperty("bedApproachMovementSq", bedApproachMovementSq);
        result.addProperty("cleanupRestored", errors.isEmpty());
        result.addProperty("error", error);
        result.addProperty("restoreError", errors.isEmpty() ? null : String.join("; ", errors));
        result.add("observations", observations);
        if (success && scenarios && errors.isEmpty()) TLMNotHard.LOG.info("TLMNH_LIFE_RUNTIME_TEST_PASS {}", result);
        else TLMNotHard.LOG.error("TLMNH_LIFE_RUNTIME_TEST_FAIL {}", result);
    }

    private void restoreBlocks() {
        require(
            world.getEntitiesWithinAABBExcludingEntity(null, bounds())
                .stream()
                .allMatch(value -> {
                    var entity = (Entity) value;
                    return entity.isDead || run.equals(
                        entity.getEntityData()
                            .getString(RUN_TAG));
                }),
            "Foreign entity entered scaffold; retaining floor for inspection");
        for (var block : backup) {
            require(
                world.getChunkProvider()
                    .chunkExists(block.x >> 4, block.z >> 4),
                "Cannot restore unloaded scaffold chunk");
            var current = world.getBlock(block.x, block.y, block.z);
            require(
                current == Blocks.air || current == Blocks.stone
                    || current == Blocks.fence
                    || current == Blocks.cake
                    || current == MaidLifeContent.snackCabinet
                    || current == MaidLifeContent.maidBed,
                "Foreign scaffold block; retaining for inspection");
        }
        boolean restoring = world.restoringBlockSnapshots;
        world.restoringBlockSnapshots = true;
        try {
            for (int i = backup.size() - 1; i >= 0; i--) {
                var block = backup.get(i);
                if (world.getBlock(block.x, block.y, block.z) != block.block
                    || world.getBlockMetadata(block.x, block.y, block.z) != block.metadata)
                    require(
                        world.setBlock(block.x, block.y, block.z, block.block, block.metadata, 2),
                        "Scaffold restoration rejected");
            }
            for (var block : backup) require(
                world.getBlock(block.x, block.y, block.z) == block.block
                    && world.getBlockMetadata(block.x, block.y, block.z) == block.metadata
                    && world.getTileEntity(block.x, block.y, block.z) == null,
                "Scaffold did not restore original air");
        } finally {
            world.restoringBlockSnapshots = restoring;
        }
    }

    private void releaseMessages(boolean outbound, List<String> errors) {
        try {
            Object message;
            while ((message = outbound ? connection.readOutbound() : connection.readInbound()) != null) {
                final Object current = message;
                restore(() -> ReferenceCountUtil.release(current), errors);
            }
        } catch (RuntimeException | Error failure) {
            errors.add(failure.toString());
        }
    }

    private static void restore(Runnable action, List<String> errors) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            errors.add(failure.toString());
        }
    }

    private static double horizontalSq(double x, double z, double otherX, double otherZ) {
        double dx = x - otherX, dz = z - otherZ;
        return dx * dx + dz * dz;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("TLMNH natural life probe failed: " + message);
    }

    private static final class SavedBlock {

        private final int x, y, z, metadata;
        private final Block block;

        private SavedBlock(int x, int y, int z, Block block, int metadata) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.metadata = metadata;
        }
    }
}
