package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.perception.budget.MaidWorldSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidServerLoadProbe;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** One internally serial breadth calibration; the memory owner is not real multiplayer. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ServerLoadProfileTests {

    private ServerLoadProfileTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 23000, batch = "server_load_serial")
    public static void serialNativeCapacityAndActualRequestFairness(GameTestHelper helper) throws Exception {
        collect(helper, false);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 650, batch = "server_load_cake_diagnostic")
    public static void nativeThirtyFaithfulCakeActorsDiagnostic(GameTestHelper helper) throws Exception {
        collect(helper, true);
    }

    private static void collect(GameTestHelper helper, boolean cakeDiagnostic) throws Exception {
        helper.assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Explicit development mode required");
        helper.assertTrue("1".equals(System.getProperty("horizonqa.turbo", "1")), "Native saves require turbo=1");
        var attempt = new Attempt(helper, cakeDiagnostic);
        helper.afterTest(attempt::close);
        try {
            attempt.prepare();
        } catch (Exception | Error failure) {
            attempt.failed("prepare", failure);
            throw failure;
        }
        helper.onEachTick("serial five-activity native load", () -> {
            try {
                attempt.tick();
            } catch (RuntimeException | Error failure) {
                attempt.failed("tick", failure);
                throw failure;
            }
        });
        helper.succeedWhen(() -> attempt.complete);
    }

    enum Activity {

        IDLE(13000),
        FOLLOW(6000),
        COMBAT(6000),
        CAKE(6000),
        SLEEP(6000);

        final int time;

        Activity(int time) {
            this.time = time;
        }
    }

    public static final class Attempt {

        static final int WARMUP = 100, SAMPLES = 400;
        static final int CAKE_INITIAL_FOOD = 16, OTHER_INITIAL_FOOD = 20;
        static final float CAKE_INITIAL_SATURATION = 4.0F, OTHER_INITIAL_SATURATION = 5.0F;
        static final int[] COUNTS = { 0, 1, 10, 30, 1, 10, 30, 0 };
        final GameTestHelper h;
        final WorldServer world;
        final MinecraftServer server = MinecraftServer.getServer();
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final List<EntityMaid> actors = new ArrayList<>();
        final List<UUID> retired = new ArrayList<>();
        final List<EntityPig> targets = new ArrayList<>();
        final Map<EntityMaid, Duty> duties = new IdentityHashMap<>();
        final List<Prop> props = new ArrayList<>();
        final List<int[]> floor = new ArrayList<>();
        final JsonObject report = new JsonObject();
        final JsonArray stages = new JsonArray(), inputs = new JsonArray(), admissions = new JsonArray(),
            activityFrames = new JsonArray(), fairnessFrames = new JsonArray(), damage = new JsonArray(),
            cleanupErrors = new JsonArray();
        final String run = UUID.randomUUID()
            .toString();
        final int x, y, z;
        final boolean oldAnimals, oldGift;
        final boolean cakeDiagnostic;
        final String oldSpawning, oldDaylight;
        final long oldTime, started;
        EntityPlayerMP owner;
        EmbeddedChannel channel;
        MaidServerLoadProbe probe;
        int stage, age, fairnessAge, drainAge;
        float targetHealthBefore;
        boolean fairness, draining, complete, closed;

        Attempt(GameTestHelper h, boolean cakeDiagnostic) {
            this.h = h;
            this.cakeDiagnostic = cakeDiagnostic;
            if (cakeDiagnostic) stage = Activity.CAKE.ordinal() * COUNTS.length + 3;
            world = h.getWorld();
            var origin = h.absolute(0, 0, 0);
            x = origin.x() + 64;
            y = origin.y();
            z = origin.z() + 64;
            oldAnimals = server.getCanSpawnAnimals();
            oldGift = MaidAcquisitionConfig.giveInitialSlab;
            oldSpawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            oldDaylight = world.getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            oldTime = world.getWorldTime();
            started = world.getTotalWorldTime();
        }

        Activity activity() {
            return fairness ? Activity.FOLLOW : Activity.values()[stage / COUNTS.length];
        }

        int scale() {
            return stage % COUNTS.length;
        }

        String label() {
            int i = scale();
            return activity().name()
                .toLowerCase(java.util.Locale.ROOT) + "/"
                + (i == 0 ? "zero-before" : i == 7 ? "zero-after" : (i < 4 ? "F-" : "N-") + COUNTS[i]);
        }

        void prepare() throws Exception {
            h.assertTrue(
                server.getConfigurationManager().playerEntityList.isEmpty(),
                "No other CPU-run player allowed");
            h.assertTrue(
                world.loadedEntityList.stream()
                    .noneMatch(e -> e instanceof EntityMaid),
                "Unrelated existing maids retained; baseline refused");
            server.setCanSpawnAnimals(true);
            MaidAcquisitionConfig.giveInitialSlab = false;
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            // Native owner initialization may run GT world generation. Place the non-ore floor afterwards.
            owner = new EntityPlayerMP(
                server,
                world,
                new GameProfile(UUID.randomUUID(), "LoadOwner"),
                new ItemInWorldManager(world));
            var manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            var handler = new NetHandlerPlayServer(server, manager, owner);
            IsolatedPlayerChannel.verify(manager, channel);
            moveOwner(0);
            server.getConfigurationManager()
                .initializeConnectionToPlayer(manager, owner, handler);
            owner.setGameType(GameType.CREATIVE);
            owner.capabilities.allowFlying = owner.capabilities.isFlying = owner.capabilities.disableDamage = true;
            moveOwner(0);
            h.assertTrue(
                MaidSpawnChecks.present(owner) && world.playerEntities.contains(owner),
                "Full native isolated owner registration failed");
            chunks.forceChunksStrict(world, x - 32, y, z - 32, x + 111, y + 8, z + 79);
            for (int bx = x; bx < x + 80; bx++) for (int bz = z; bz < z + 48; bz++) {
                for (int by = y; by < y + 6; by++) h.assertTrue(
                    world.isAirBlock(bx, by, bz) && world.getTileEntity(bx, by, bz) == null,
                    "Unknown capacity course block retained");
                h.assertTrue(
                    world.setBlock(bx, y, bz, Blocks.iron_block, 0, 2) && world.getBlock(bx, y, bz) == Blocks.iron_block
                        && world.getBlockMetadata(bx, y, bz) == 0,
                    "Native iron floor placement failed");
                floor.add(new int[] { bx, y, bz });
                h.assertFalse(
                    Blocks.iron_block.isReplaceableOreGen(world, bx, y, bz, Blocks.stone),
                    "Capacity floor must not be a native stone ore host");
            }
            report.addProperty("schema", "server-load-profile-v2");
            report.addProperty("run", run);
            report.addProperty(
                "world",
                world.getWorldInfo()
                    .getWorldName());
            report.addProperty(
                "scope",
                "One isolated JVM, five internally serial activity families, complete F/N roots; memory owner is not real multiplayer or a formal independent replication");
            report.addProperty("cpuIsolationRequired", "No other game JVM, build, reference sampling or benchmark");
            report.addProperty("activityReadOnlyObservationSchema", "finite-cake-native-phase-v2");
            report.addProperty("cakeFixtureSchema", "floor-embedded-cabinet-v2");
            report.addProperty(
                "cakeFixtureScope",
                "Only CAKE changes: the owned iron floor cell becomes the finite cabinet, cake is one block lower; actor XYZ, horizontal distance2, open floor, search/movement/RNG and all windows remain. Cabinet removal restores that exact iron cell. F/N share geometry. Old elevated-cabinet CPU samples have different inputs.");
            report.addProperty(
                "cakePhaseScope",
                "Read-only Forge LivingUpdate pre-AI/physics and HQA callback snapshots. No injected after-navigation/before-physics hook. Per-actor transitions are bounded, overflow is explicit; native lastStop.path retains the actually stopped route.");
            if (cakeDiagnostic) {
                report.addProperty("diagnosticOnly", true);
                report.addProperty(
                    "scope",
                    "One cake/F-30 diagnostic stage with the original finite inputs and 400 measured ticks");
            }
            report.addProperty(
                "finiteCakeScope",
                "One originally admitted cake per actor, no replenishment or forced behavior. A bite is event coverage, not sustained eating. All original actors and 400 measured ticks remain the denominator; observer work is included in CPU/allocation measurements");
            report.addProperty(
                "cakeWorkMemoryScope",
                "Shared actor WORK_TARGET peeks describe F work memory. Enhanced SNACK selection is recorded separately; an empty shared target is not proof of no Enhanced life intent");
            report.addProperty("warmupTicksPerStage", WARMUP);
            report.addProperty("sampleTicksPerStage", SAMPLES);
            report.addProperty("nutritionInputSchema", "stable-real-nutrition-v1");
            report.addProperty("cakeInitialFood", CAKE_INITIAL_FOOD);
            report.addProperty("cakeInitialSaturation", CAKE_INITIAL_SATURATION);
            report.addProperty("otherInitialFood", OTHER_INITIAL_FOOD);
            report.addProperty("otherInitialSaturation", OTHER_INITIAL_SATURATION);
            report.addProperty(
                "nutritionScope",
                "Food and saturation are set once before natural ticks; no replenishment. World-cake stealing grants favor, not ItemFood nutrition. Starvation is not an admitted workload.");
            report.addProperty("capacityStages", cakeDiagnostic ? 1 : 40);
            report.addProperty("fairnessWindowTicks", cakeDiagnostic ? 0 : 240);
            report.addProperty("serverCanSpawnAnimalsBefore", oldAnimals);
            report.addProperty("periodicNativeSaveTicks", 900);
            report.addProperty(
                "floor",
                "Native iron_block, full collision, not a stone ore replacement target; no GT mod or generator disabled");
            report.addProperty(
                "activityInputScope",
                "Idle uses DAY at13000; sleep NIGHT at6000; other activities ALL. Cake/sleep/combat wait sitting for100 warmup ticks, then finite native resource/threat admission and natural task selection; duty counts and all original actors retained as denominator");
            report.addProperty(
                "ownerTrajectory",
                "FOLLOW only: native MP.setPosition .18 block/tick shuttle21.6/reversal120; other activities use captured home areas and a fixed native owner");
            report.addProperty(
                "observerRetention",
                "Retired maid UUID only; previous entity objects and per-actor duties are released between stages");
            report.addProperty(
                "observerHeapCaveat",
                "Bounded actor input NBT, admission NBT,20tick duty rows and native tick samples accumulate in this JVM; heap deltas alone are not an AI leak verdict");
            report.addProperty(
                "retirementIoScope",
                "Measured phase closes before roster retirement. Normal empty cabinet removal can use the production tile-return durability path; transition CPU/save events remain separately recorded");
            report.addProperty(
                "damageScope",
                "LivingHurt events are provisional; aggregate registered target health loss independently confirms actual native damage");
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity
                    .capture(MaidServerLoadProbe.class, EntityMaid.class, MaidPathNavigator.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(
                    ServerLoadProfileTests.class,
                    Attempt.class,
                    Duty.class,
                    Prop.class,
                    SleepAttributePortSnapshot.class));
            report.add("spawnInputArtifact", DevelopmentArtifactIdentity.capture(IsolatedPlayerChannel.class));
            MinecraftForge.EVENT_BUS.register(this);
            probe = MaidServerLoadProbe.start(run, "five-activity-capacity", world);
            beginStage();
            progress();
        }

        void beginStage() {
            age = 0;
            world.setWorldTime(activity().time);
            moveOwner(0);
            spawnActors(COUNTS[scale()], scale() < 4 ? MaidBrainProfile.FAITHFUL_TLM : MaidBrainProfile.ENHANCED);
            probe.phase(label() + "-warmup", actors.size(), false);
            progress();
        }

        void spawnActors(int count, MaidBrainProfile profile) {
            h.assertTrue(
                actors.isEmpty() && targets.isEmpty() && props.isEmpty(),
                "Previous owned activity not cleared");
            for (int i = 0; i < count; i++) {
                var maid = new EntityMaid(world);
                h.assertTrue(maid.bindTo(owner.getUniqueID()), "Native owner binding failed");
                maid.getEntityData()
                    .setString("TLMNHLoadRun", run);
                maid.setTask(activity() == Activity.COMBAT ? MaidTask.ATTACK : MaidTask.IDLE);
                maid.setSchedule(
                    activity() == Activity.SLEEP ? MaidSchedule.NIGHT
                        : activity() == Activity.IDLE ? MaidSchedule.DAY : MaidSchedule.ALL);
                maid.setPickup(false);
                maid.setHunger(activity() == Activity.CAKE ? CAKE_INITIAL_FOOD : OTHER_INITIAL_FOOD);
                maid.getHungerState()
                    .setSaturation(activity() == Activity.CAKE ? CAKE_INITIAL_SATURATION : OTHER_INITIAL_SATURATION);
                h.assertTrue(
                    maid.getHungerState()
                        .getExhaustion() == 0.0F
                        && maid.getHungerState()
                            .getStarveTicks() == 0,
                    "Fresh native nutrition input differs");
                maid.setSitting(
                    !fairness && (activity() == Activity.CAKE || activity() == Activity.SLEEP
                        || activity() == Activity.COMBAT));
                maid.setPosition(
                    activity() == Activity.FOLLOW ? x + 22 + (i % 6) * 1.2 : cellX(i) + .5,
                    y + 1,
                    activity() == Activity.FOLLOW ? z + 18 + (i / 6) * 1.2 : cellZ(i) + 2.5);
                if (activity() != Activity.FOLLOW) {
                    h.assertTrue(
                        maid.getHomeArea()
                            .setEnabled(true),
                        "Home input refused");
                    for (var value : MaidLifeRules.Activity.values()) h.assertTrue(
                        maid.getHomeArea()
                            .capture(value),
                        "Home center refused");
                }
                actors.add(maid);
                h.assertTrue(
                    world.spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid),
                    "Native actor join failed");
                h.assertTrue(
                    maid.selectBrainProfileForDevelopment(profile) == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Complete F/N root selection failed");
                h.assertTrue(
                    profile == MaidBrainProfile.ENHANCED ? maid.getBrainCoordinator()
                        .peekEnhancedCore() != null
                        : maid.getBrainCoordinator()
                            .peekFaithfulBrain() != null,
                    "Complete root was substituted");
                h.assertFalse(
                    maid.getBrainCoordinator()
                        .getTelemetry()
                        .isEnabled(),
                    "Capacity event ledger must stay disabled");
                duties.put(maid, new Duty(maid));
                var input = new JsonObject();
                input.addProperty("stage", fairness ? "follow/N-30-fairness" : label());
                input.addProperty("profile", profile.getId());
                input.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                input.add("nativeInput", SleepAttributePortSnapshot.capture(maid));
                input.add("nutrition", nutrition(maid));
                if (activity() == Activity.CAKE) input.add("cakeInitialGeometry", duties.get(maid).initialCakeGeometry());
                inputs.add(input);
            }
        }

        int cellX(int i) {
            return x + 12 + i % 6 * 9;
        }

        JsonObject nutrition(EntityMaid maid) {
            var row = new JsonObject();
            var value = maid.getHungerState();
            row.addProperty("food", value.getFood());
            row.addProperty("saturation", value.getSaturation());
            row.addProperty("exhaustion", value.getExhaustion());
            row.addProperty("regenTicks", value.getRegenTicks());
            row.addProperty("starveTicks", value.getStarveTicks());
            row.addProperty("health", maid.getHealth());
            return row;
        }

        int cellZ(int i) {
            return z + 6 + i / 6 * 8;
        }

        void activateInputs() {
            for (int i = 0; i < actors.size(); i++) {
                var maid = actors.get(i);
                int bx = cellX(i), bz = cellZ(i);
                if (activity() == Activity.CAKE) {
                    placeFloorCabinet(bx, bz);
                    place(bx, y + 1, bz, Blocks.cake, 0);
                    duties.get(maid).verifyCakeAdmission();
                } else if (activity() == Activity.SLEEP) {
                    place(bx, y + 1, bz + 1, MaidLifeContent.maidBed, 2);
                    place(bx, y + 1, bz, MaidLifeContent.maidBed, 10);
                } else if (activity() == Activity.COMBAT) {
                    var pig = new EntityPig(world);
                    pig.func_110163_bv();
                    pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .setBaseValue(0);
                    pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                        .setBaseValue(1000);
                    pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                        .setBaseValue(1);
                    pig.setHealth(1000);
                    pig.getEntityData()
                        .setString("TLMNHLoadRun", run);
                    pig.setPosition(bx + 2.5, y + 1, bz + 2.5);
                    targets.add(pig);
                    h.assertTrue(
                        world.spawnEntityInWorld(pig) && MaidSpawnChecks.present(pig),
                        "Actual combat target join failed");
                    maid.getMaidInventory()
                        .setInventorySlotContents(MaidInventoryLayout.MAIN_HAND, new ItemStack(Items.iron_sword));
                    maid.getAttackListData()
                        .attackGroups()
                        .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
                }
                maid.setSitting(false);
                duties.get(maid)
                    .reset();
                var admitted = new JsonObject();
                admitted.addProperty("label", label());
                admitted.addProperty("worldTick", world.getTotalWorldTime());
                addClocks(admitted);
                admitted.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                var raw = new NBTTagCompound();
                maid.writeToNBT(raw);
                admitted.add("actorNativeNbt", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) raw.copy()));
                admitted.add("nutrition", nutrition(maid));
                if (activity() == Activity.COMBAT) {
                    var threat = targets.get(targets.size() - 1);
                    var threatRaw = new NBTTagCompound();
                    threat.writeToNBT(threatRaw);
                    admitted.add(
                        "finiteNativeThreatNbt",
                        SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) threatRaw.copy()));
                }
                if (activity() == Activity.CAKE) admitted.add(
                    "cakeReadOnlyState",
                    duties.get(maid)
                        .cakeState());
                admissions.add(admitted);
            }
            targetHealthBefore = targetHealth();
        }

        void place(int bx, int by, int bz, Block block, int metadata) {
            h.assertTrue(
                world.isAirBlock(bx, by, bz) && world.getTileEntity(bx, by, bz) == null,
                "Unknown activity prop source");
            h.assertTrue(
                world.setBlock(bx, by, bz, block, metadata, 2) && world.getBlock(bx, by, bz) == block
                    && world.getBlockMetadata(bx, by, bz) == metadata,
                "Native activity prop placement failed");
            props.add(new Prop(bx, by, bz, block, metadata));
        }

        void placeFloorCabinet(int bx, int bz) {
            h.assertTrue(
                bx >= x && bx < x + 80 && bz >= z && bz < z + 48
                    && world.getBlock(bx, y, bz) == Blocks.iron_block
                    && world.getBlockMetadata(bx, y, bz) == 0
                    && world.getTileEntity(bx, y, bz) == null,
                "Embedded cabinet requires the exact owned unmodified iron floor cell");
            h.assertTrue(
                world.setBlock(bx, y, bz, MaidLifeContent.snackCabinet, 6, 2)
                    && world.getBlock(bx, y, bz) == MaidLifeContent.snackCabinet
                    && world.getBlockMetadata(bx, y, bz) == 6,
                "Native embedded cabinet placement failed");
            props.add(new Prop(bx, y, bz, MaidLifeContent.snackCabinet, 6, true));
        }

        Point cakePoint(int rosterIndex) {
            return new Point(cellX(rosterIndex), y + 1, cellZ(rosterIndex));
        }

        void tick() {
            if (complete) return;
            h.assertTrue(
                MaidSpawnChecks.present(owner) && owner.worldObj == world && server.getCanSpawnAnimals(),
                "Native owner/world/animal eligibility changed");
            for (var maid : actors) h.assertTrue(
                MaidSpawnChecks.present(maid) && maid.isOwner(owner) && maid.isEntityAlive(),
                "Actual capacity actor disappeared or lost ownership");
            for (var pig : targets) h.assertTrue(
                MaidSpawnChecks.present(pig) && pig.isEntityAlive(),
                "Finite actual threat died or disappeared; no replacement or healing is injected");
            drainChannel();
            if (draining) {
                if (++drainAge >= 4) {
                    h.assertTrue(
                        MaidWorldNavigationBudget.forWorld(world)
                            .queued() == 0
                            && MaidWorldSensingBudget.forWorld(world)
                                .queued() == 0,
                        "Owned queues did not drain");
                    probe.close();
                    complete = true;
                    save("measured");
                }
                return;
            }
            if (fairness) {
                observeFairness();
                moveOwner(++fairnessAge);
                if (fairnessAge >= 240) {
                    finishFairness();
                    removeActors();
                    draining = true;
                    probe.phase("queue-drain", 0, false);
                }
                return;
            }
            if (age >= WARMUP) observeActivity();
            moveOwner(++age);
            if (age == WARMUP) {
                activateInputs();
                probe.phase(label(), actors.size(), true);
            }
            if (age < WARMUP + SAMPLES) return;
            // Close the measured CPU boundary before file observation, retirement and the next roster's construction.
            probe.phase(label() + "-transition", actors.size(), false);
            finishStage();
            removeActors();
            removeProps();
            stage++;
            if (cakeDiagnostic) {
                draining = true;
                probe.phase("queue-drain", 0, false);
                return;
            }
            if (stage < 40) beginStage();
            else {
                fairness = true;
                moveOwner(0);
                spawnActors(30, MaidBrainProfile.ENHANCED);
                probe.phase("follow/N-30-fairness", 30, false);
                progress();
            }
        }

        void moveOwner(int tick) {
            int cycle = tick % 240;
            double offset = activity() == Activity.FOLLOW ? (cycle <= 120 ? cycle : 240 - cycle) * .18 : 10;
            owner.setPosition(x + 30 + offset, y + 1, z + 24);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            owner.fallDistance = 0;
        }

        void observeActivity() {
            for (var value : duties.values()) value.observe();
            if ((age - WARMUP) % 20 != 0) return;
            var frame = new JsonObject();
            frame.addProperty("label", label());
            frame.addProperty("worldTick", world.getTotalWorldTime());
            addClocks(frame);
            frame.add("actors", dutyRows());
            activityFrames.add(frame);
        }

        void addClocks(JsonObject row) {
            row.addProperty("worldTime", world.getWorldTime());
            row.addProperty("worldTotalTime", world.getTotalWorldTime());
            row.addProperty(
                "doDaylightCycle",
                world.getGameRules()
                    .getGameRuleStringValue("doDaylightCycle"));
        }

        JsonObject point(Point point) {
            if (point == null) return null;
            var row = new JsonObject();
            row.addProperty("x", point.x);
            row.addProperty("y", point.y);
            row.addProperty("z", point.z);
            return row;
        }

        boolean samePoint(Point left, Point right) {
            return left == right
                || left != null && right != null && left.x == right.x && left.y == right.y && left.z == right.z;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void beforeLivingUpdate(LivingUpdateEvent event) {
            if (closed || fairness || stage >= 40 || activity() != Activity.CAKE || age < WARMUP
                || !(event.entityLiving instanceof EntityMaid maid)) return;
            var duty = duties.get(maid);
            if (duty != null) duty.observeCakePhase(0, "FORGE_LIVING_UPDATE_PRE_AI_PHYSICS");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void hurt(LivingHurtEvent event) {
            if (fairness || stage >= 40
                || activity() != Activity.COMBAT
                || age < WARMUP
                || !targets.contains(event.entityLiving)
                || !(event.source.getSourceOfDamage() instanceof EntityMaid maid)) return;
            var duty = duties.get(maid);
            if (duty == null) return;
            duty.provisionalHits++;
            var row = new JsonObject();
            row.addProperty("worldTick", world.getTotalWorldTime());
            row.addProperty(
                "actor",
                maid.getUniqueID()
                    .toString());
            row.addProperty(
                "target",
                event.entityLiving.getUniqueID()
                    .toString());
            row.addProperty("provisionalAmount", event.ammount);
            damage.add(row);
        }

        float targetHealth() {
            float total = 0;
            for (var pig : targets) total += pig.getHealth();
            return total;
        }

        JsonArray dutyRows() {
            var out = new JsonArray();
            for (var maid : actors) out.add(
                duties.get(maid)
                    .json());
            return out;
        }

        void finishStage() {
            var row = new JsonObject();
            row.addProperty("label", label());
            row.addProperty("activity", activity().name());
            row.addProperty("actors", actors.size());
            row.addProperty("sampleTicks", SAMPLES);
            row.addProperty("actualTargetHealthLoss", targetHealthBefore - targetHealth());
            int triggered = 0;
            var values = new JsonArray();
            for (var maid : actors) {
                var duty = duties.get(maid);
                var value = duty.json();
                boolean actual = duty.triggered();
                if (actual) triggered++;
                value.addProperty("coverage", actual ? "ACTUAL_ACTIVITY_OBSERVED" : "NOT_TRIGGERED_OR_NOT_COMMITTED");
                values.add(value);
            }
            row.add("actorObservations", values);
            row.addProperty("triggeredActors", triggered);
            row.addProperty("workloadVerified", actors.isEmpty() || triggered == actors.size());
            row.addProperty(
                "coverage",
                actors.isEmpty() ? "ZERO_BASELINE"
                    : triggered == actors.size() ? "ALL_ACTORS_TRIGGERED" : "PARTIAL_KEEP_ORIGINAL_DENOMINATOR");
            row.add("nativeStorage", storage());
            stages.add(row);
            for (var duty : duties.values()) h.assertTrue(
                duty.ticks == SAMPLES && duty.maid.ticksExisted - duty.nativeAgeBefore >= SAMPLES - 1,
                "Activity observation lost actual native actor ticks");
        }

        void observeFairness() {
            long now = world.getTotalWorldTime();
            var nav = MaidWorldNavigationBudget.forWorld(world);
            var sense = MaidWorldSensingBudget.forWorld(world);
            var row = new JsonObject();
            row.addProperty("worldTick", now);
            row.addProperty("navigationUsed", nav.used(now));
            row.addProperty("navigationQueued", nav.queued());
            row.addProperty("sensingUsed", sense.used(now));
            row.addProperty("sensingQueued", sense.queued());
            var samples = new JsonArray();
            for (var maid : actors) {
                var core = maid.getBrainCoordinator()
                    .peekEnhancedCore();
                var value = duties.get(maid)
                    .json();
                value.addProperty(
                    "navigationOutcome",
                    core.navigation()
                        .outcome()
                        .name());
                value.addProperty(
                    "navigationWaitTicks",
                    core.navigation()
                        .waitingCount());
                samples.add(value);
            }
            row.add("actors", samples);
            fairnessFrames.add(row);
            h.assertTrue(
                nav.used(now) <= MaidNavigationConfig.queriesPerTick
                    && sense.used(now) <= MaidPerceptionConfig.scansPerTick,
                "Native per-world admission exceeded configured budgets");
        }

        void finishFairness() {
            var rows = new JsonArray();
            var unserved = new JsonArray();
            int requesters = 0;
            for (var maid : actors) {
                var duty = duties.get(maid);
                long queries = navigator(maid).searchCount() - duty.searchBefore;
                long deferred = navigator(maid).budgetDeferrals() - duty.deferBefore;
                var row = duty.json();
                row.addProperty(
                    "coverage",
                    queries > 0 ? "ACTUAL_MODERN_QUERY_SERVED"
                        : deferred > 0 ? "QUEUED_WITHOUT_MODERN_SERVICE" : "NO_REQUEST_NOT_COVERED");
                rows.add(row);
                if (queries > 0 || deferred > 0) requesters++;
                if (deferred > 0 && queries == 0) unserved.add(
                    new com.google.gson.JsonPrimitive(
                        maid.getUniqueID()
                            .toString()));
            }
            report.add("fairnessActors", rows);
            report.add("fairnessUnservedActors", unserved);
            report.addProperty("fairnessActualRequesters", requesters);
            report.addProperty(
                "fairnessScope",
                "240 actual ticks; real MaidPathNavigator.searchCount/budgetDeferrals deltas, not legacy bounded-planner telemetry. Query service does not imply a successful path; no request is not covered");
            save("fairness-observed-before-assertions");
            h.assertTrue(requesters > 0, "Natural fairness exercised no modern requests");
            h.assertTrue(unserved.size() == 0, "Naturally queued actor received no actual modern service in240 ticks");
        }

        ModernFollowCoreActor actor(EntityMaid maid) {
            var core = maid.getBrainCoordinator()
                .peekEnhancedCore();
            return core != null ? core.actor()
                : maid.getBrainCoordinator()
                    .peekFaithfulBrain()
                    .getActor();
        }

        int cakeBites(EntityMaid maid) {
            var core = maid.getBrainCoordinator()
                .peekEnhancedCore();
            return core == null ? actor(maid).cakeBites()
                : core.life()
                    .bites();
        }

        MaidPathNavigator navigator(EntityMaid maid) {
            h.assertTrue(maid.getNavigator() instanceof MaidPathNavigator, "Actual modern navigator required");
            return (MaidPathNavigator) maid.getNavigator();
        }

        final class Duty {

            final EntityMaid maid;
            final JsonObject selections = new JsonObject();
            final int rosterIndex;
            final JsonArray cakeWorkTransitions = new JsonArray();
            final JsonArray cakePhaseTransitions = new JsonArray();
            final String[] previousCakePhase = new String[2];
            final PathEntity[] previousCakePath = new PathEntity[2];
            JsonObject cakeAdmission;
            int cakePhaseOverflow;
            Point previousWorkTarget;
            Object previousEdibleAction;
            boolean cakeWorkObserved, previousWalkPresent;
            int cakeTransitionOverflow, cakeWorkTargetTicks, cakeWorkWithinUseRadiusTicks;
            long searchBefore, deferBefore;
            int bitesBefore, nativeAgeBefore, ticks, working, moving, sleeping, provisionalHits;
            double px, py, pz;

            Duty(EntityMaid maid) {
                this.maid = maid;
                rosterIndex = actors.indexOf(maid);
                reset();
            }

            void reset() {
                searchBefore = navigator(maid).searchCount();
                deferBefore = navigator(maid).budgetDeferrals();
                bitesBefore = cakeBites(maid);
                nativeAgeBefore = maid.ticksExisted;
                px = maid.posX;
                py = maid.posY;
                pz = maid.posZ;
            }

            void observe() {
                ticks++;
                h.assertTrue(
                    maid.getHungerState()
                        .getFood() > 0
                        && maid.getHungerState()
                            .getStarveTicks() == 0,
                    "Starvation would change the admitted activity workload");
                if (activity() == Activity.CAKE) observeCakeWork();
                double delta = Math.pow(maid.posX - px, 2) + Math.pow(maid.posY - py, 2) + Math.pow(maid.posZ - pz, 2);
                boolean moved = delta > 1e-8, sleep = maid.hasPose(MaidPose.SLEEPING);
                if (moved) moving++;
                if (sleep) sleeping++;
                px = maid.posX;
                py = maid.posY;
                pz = maid.posZ;
                var core = maid.getBrainCoordinator()
                    .peekEnhancedCore();
                String selected = core == null ? maid.getBrainCoordinator()
                    .peekFaithfulBrain()
                    .getActivities()
                    .active()
                    .name()
                    : core.selectedKind() == null ? "NONE"
                        : core.selectedKind()
                            .name();
                selections.addProperty(
                    selected,
                    selections.has(selected) ? selections.get(selected)
                        .getAsInt() + 1 : 1);
                boolean active = switch (activity()) {
                    case IDLE -> core == null ? "IDLE".equals(selected)
                        : !("MELEE".equals(selected) || "RANGED".equals(selected)
                            || "SLEEP".equals(selected)
                            || "SNACK".equals(selected));
                    case FOLLOW -> moved && !maid.getHomeArea()
                        .enabled();
                    case COMBAT -> targets.contains(maid.getAttackTarget());
                    case CAKE -> core == null ? actor(maid).peekWorkTarget() != null : "SNACK".equals(selected);
                    case SLEEP -> sleep;
                };
                if (active) working++;
            }

            void observeCakeWork() {
                observeCakePhase(1, "HQA_TICK_CALLBACK");
                var view = actor(maid);
                var work = view.peekWorkTarget();
                var edible = view.peekEdibleAction();
                boolean walkPresent = view.peekWalkTarget() != null;
                if (work != null) {
                    cakeWorkTargetTicks++;
                    if (maid.getDistanceSq(work.x + .5, work.y + .5, work.z + .5) <= 4) cakeWorkWithinUseRadiusTicks++;
                }
                if (!cakeWorkObserved || !samePoint(previousWorkTarget, work)
                    || previousEdibleAction != edible
                    || previousWalkPresent != walkPresent) {
                    // Peek only: guarded actor/WalkTarget methods are behavior-frame APIs, not observer APIs.
                    if (cakeWorkTransitions.size() < 8) cakeWorkTransitions.add(cakeState());
                    else cakeTransitionOverflow++;
                    previousWorkTarget = work;
                    previousEdibleAction = edible;
                    previousWalkPresent = walkPresent;
                    cakeWorkObserved = true;
                }
            }

            JsonObject cakeState() {
                var row = new JsonObject();
                addClocks(row);
                row.addProperty("nativeAge", maid.ticksExisted);
                row.addProperty("measuredActorTicks", ticks);
                row.addProperty("hunger", maid.getHunger());
                row.add("nutrition", nutrition(maid));
                row.addProperty(
                    "schedule",
                    maid.getSchedule()
                        .name());
                row.addProperty(
                    "scheduledActivity",
                    MaidLifeRules.activity(maid.getSchedule(), world.getWorldTime())
                        .name());
                row.addProperty("sitting", maid.isSitting());
                row.addProperty("riding", maid.ridingEntity != null);
                row.addProperty("ridden", maid.riddenByEntity != null);
                row.addProperty("leashed", maid.getLeashed());
                row.addProperty("hurtTime", maid.hurtTime);
                row.addProperty("health", maid.getHealth());
                row.addProperty(
                    "homeEnabled",
                    maid.getHomeArea()
                        .enabled());
                var cake = cakePoint(rosterIndex);
                row.add("originalCakePosition", point(cake));
                row.addProperty("originalCakeStillPresent", world.getBlock(cake.x, cake.y, cake.z) == Blocks.cake);
                row.addProperty("originalCakeAir", world.isAirBlock(cake.x, cake.y, cake.z));
                row.addProperty("originalCakeMetadata", world.getBlockMetadata(cake.x, cake.y, cake.z));
                row.addProperty("originalCakeHasTile", world.getTileEntity(cake.x, cake.y, cake.z) != null);
                row.addProperty(
                    "originalCabinetStillPresent",
                    world.getBlock(cake.x, cake.y - 1, cake.z) == MaidLifeContent.snackCabinet);
                row.addProperty("originalCabinetMetadata", world.getBlockMetadata(cake.x, cake.y - 1, cake.z));
                row.addProperty(
                    "homeRestrictionContainsOriginalCake",
                    maid.getHomeArea()
                        .withinRestriction(cake.x, cake.y, cake.z));
                row.addProperty(
                    "distanceSqToOriginalCakeCenter",
                    maid.getDistanceSq(cake.x + .5, cake.y + .5, cake.z + .5));
                row.add("feet", feet());
                row.add("nativePath", pathState(maid.getNavigator().getPath(), cake));
                var view = actor(maid);
                var work = view.peekWorkTarget();
                row.add("workTarget", point(work));
                row.addProperty("workTargetIsOriginalCake", samePoint(work, cake));
                row.addProperty("walkTargetPresent", view.peekWalkTarget() != null);
                var edible = view.peekEdibleAction();
                row.addProperty("edibleAction", edible == null ? null : edible.name());
                if (work != null) {
                    double distance = maid.getDistanceSq(work.x + .5, work.y + .5, work.z + .5);
                    row.addProperty("distanceSqToWorkTargetCenter", distance);
                    row.addProperty("withinUseRadius", distance <= 4);
                    row.addProperty("workTargetCakePresent", world.getBlock(work.x, work.y, work.z) == Blocks.cake);
                    row.addProperty("workTargetCakeMetadata", world.getBlockMetadata(work.x, work.y, work.z));
                    row.addProperty(
                        "workTargetCabinetPresent",
                        world.getBlock(work.x, work.y - 1, work.z) == MaidLifeContent.snackCabinet);
                }
                row.addProperty(
                    "nativeNoPath",
                    maid.getNavigator()
                        .noPath());
                var stopped = navigator(maid).lastStop();
                row.addProperty("lastNativeStopCause", stopped == null ? null : stopped.cause.name());
                if (stopped != null) {
                    row.addProperty("lastNativeStopTotalTime", stopped.worldTick);
                    row.add("lastStoppedNativePath", pathState(stopped.path, cake));
                }
                return row;
            }

            JsonObject feet() {
                var row = new JsonObject();
                row.addProperty("x", maid.posX);
                row.addProperty("y", maid.posY);
                row.addProperty("z", maid.posZ);
                row.addProperty("boundingBoxMinY", maid.boundingBox.minY);
                row.addProperty("width", maid.width);
                row.addProperty("height", maid.height);
                row.addProperty("onGround", maid.onGround);
                row.addProperty("motionX", maid.motionX);
                row.addProperty("motionY", maid.motionY);
                row.addProperty("motionZ", maid.motionZ);
                return row;
            }

            JsonObject box(AxisAlignedBB value) {
                var row = new JsonObject();
                if (value != null) {
                    row.addProperty("minX", value.minX);
                    row.addProperty("minY", value.minY);
                    row.addProperty("minZ", value.minZ);
                    row.addProperty("maxX", value.maxX);
                    row.addProperty("maxY", value.maxY);
                    row.addProperty("maxZ", value.maxZ);
                }
                return row;
            }

            void verifyCakeAdmission() {
                var cake = cakePoint(rosterIndex);
                var cabinetBox = MaidLifeContent.snackCabinet.getCollisionBoundingBoxFromPool(
                    world, cake.x, cake.y - 1, cake.z);
                var cakeBox = Blocks.cake.getCollisionBoundingBoxFromPool(world, cake.x, cake.y, cake.z);
                h.assertTrue(
                    cabinetBox != null && cabinetBox.minX == cake.x && cabinetBox.maxX == cake.x + 1
                        && cabinetBox.minY == y && cabinetBox.maxY == cake.y
                        && cabinetBox.minZ == cake.z && cabinetBox.maxZ == cake.z + 1,
                    "Embedded native cabinet must retain full-block floor support");
                h.assertTrue(
                    cakeBox != null && cakeBox.minY == cake.y && cakeBox.maxY == cake.y + 7.0 / 16.0,
                    "Native finite cake collision must match its 7/16-block height: " + cakeBox);
                cakeAdmission = new JsonObject();
                cakeAdmission.addProperty("schema", "floor-embedded-cabinet-v2");
                cakeAdmission.add("feet", feet());
                cakeAdmission.add("cakePosition", point(cake));
                cakeAdmission.add("nativeCabinetCollision", box(cabinetBox));
                cakeAdmission.add("nativeCakeCollision", box(cakeBox));
                cakeAdmission.addProperty(
                    "actualAdmissionDistanceSq", maid.getDistanceSq(cake.x + .5, cake.y + .5, cake.z + .5));
                cakeAdmission.addProperty("actualAdmissionWorkPresent", actor(maid).peekWorkTarget() != null);
                cakeAdmission.addProperty("actualAdmissionWalkPresent", actor(maid).peekWalkTarget() != null);
            }

            JsonObject initialCakeGeometry() {
                var cake = cakePoint(rosterIndex);
                h.assertTrue(
                    maid.width == .6F && maid.height == 1.5F
                        && maid.posX == cake.x + .5 && maid.posZ == cake.z + 2.5 && maid.posY == cake.y
                        && maid.getDistanceSq(cake.x + .5, cake.y + .5, cake.z + .5) == 4.25,
                    "Cake birth must preserve original actor XYZ and horizontal travel outside use radius");
                h.assertTrue(
                    actor(maid).peekWorkTarget() == null && actor(maid).peekWalkTarget() == null,
                    "Cake birth must not prefill work or walk memory");
                var row = new JsonObject();
                row.add("feet", feet());
                row.add("futureCakePosition", point(cake));
                row.addProperty("horizontalDistanceSq", 4);
                row.addProperty("distanceSq", 4.25);
                row.addProperty("workWalkEmpty", true);
                return row;
            }

            JsonObject pathState(PathEntity path, Point cake) {
                var row = new JsonObject();
                row.addProperty("present", path != null);
                if (path == null) return row;
                row.addProperty("index", path.getCurrentPathIndex());
                row.addProperty("length", path.getCurrentPathLength());
                row.addProperty("finished", path.isFinished());
                var end = path.getFinalPathPoint();
                if (end != null) row.add("lastNode", point(new Point(end.xCoord, end.yCoord, end.zCoord)));
                if (path.getCurrentPathIndex() < path.getCurrentPathLength()) {
                    var node = path.getPathPointFromIndex(path.getCurrentPathIndex());
                    row.add("currentNode", point(new Point(node.xCoord, node.yCoord, node.zCoord)));
                }
                if (path instanceof MaidModernPath modern) {
                    row.addProperty("modern", true);
                    row.addProperty("canReach", modern.canReach());
                    row.add("requestedTarget", point(modern.requestedTarget));
                    row.addProperty("visited", modern.result.visited);
                    boolean requestedCake = samePoint(modern.requestedTarget, cake);
                    row.addProperty("requestedOriginalCake", requestedCake);
                    if (requestedCake && end != null) {
                        double offset = (double) (maid.width + 1F) / 2;
                        double tolerance = maid.width > .75F ? maid.width / 2F : .75F - maid.width / 2F;
                        int horizontal = Math.abs(end.xCoord - cake.x) + Math.abs(end.zCoord - cake.z);
                        boolean floorNode = end.yCoord == cake.y && horizontal <= 1;
                        boolean cakeTopNode = end.yCoord == cake.y + 1 && horizontal == 0;
                        row.addProperty("servicePlaneEndpointClass", floorNode ? "FLOOR_CARDINAL_OR_TARGET"
                            : cakeTopNode ? "ABOVE_CAKE" : "UNPROVEN");
                        if (floorNode || cakeTopNode) {
                            double maxX = Math.abs(end.xCoord + offset - (cake.x + .5)) + tolerance;
                            double maxZ = Math.abs(end.zCoord + offset - (cake.z + .5)) + tolerance;
                            double maxY = floorNode ? .5 : 1.5;
                            double bound = maxX * maxX + maxY * maxY + maxZ * maxZ;
                            row.addProperty("arrivalDistanceSqUpperBound", bound);
                            row.addProperty("arrivalWithinUseSphereProven", bound <= 4);
                        }
                    }
                }
                return row;
            }

            void observeCakePhase(int index, String phase) {
                if (cakeAdmission == null || cakeBites(maid) > bitesBefore) return;
                var view = actor(maid);
                var work = view.peekWorkTarget();
                var path = maid.getNavigator().getPath();
                var stop = navigator(maid).lastStop();
                String signature = (work == null ? "none" : work.x + "/" + work.y + "/" + work.z)
                    + ":" + (view.peekWalkTarget() != null) + ":" + view.peekEdibleAction()
                    + ":" + (path == null ? -1 : path.getCurrentPathIndex())
                    + ":" + (stop == null ? -1 : stop.worldTick)
                    + ":" + (work != null && maid.getDistanceSq(work.x + .5, work.y + .5, work.z + .5) <= 4);
                if (signature.equals(previousCakePhase[index]) && path == previousCakePath[index]) return;
                previousCakePhase[index] = signature;
                previousCakePath[index] = path;
                if (cakePhaseTransitions.size() >= 64) {
                    cakePhaseOverflow++;
                    return;
                }
                var row = cakeState();
                row.addProperty("nativeObservationPhase", phase);
                cakePhaseTransitions.add(row);
            }

            boolean triggered() {
                return switch (activity()) {
                    case CAKE -> cakeBites(maid) > bitesBefore;
                    case COMBAT -> provisionalHits > 0 && targetHealthBefore > targetHealth();
                    default -> working > 0;
                };
            }

            JsonObject json() {
                var row = new JsonObject();
                row.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                row.addProperty("observedTicks", ticks);
                row.addProperty("workingTicks", working);
                row.addProperty("dutyFraction", ticks == 0 ? 0 : (double) working / ticks);
                row.addProperty("movingTicks", moving);
                row.addProperty("sleepingTicks", sleeping);
                row.addProperty("cakeBites", cakeBites(maid) - bitesBefore);
                row.addProperty("provisionalDamageEvents", provisionalHits);
                row.addProperty("actualModernQueries", navigator(maid).searchCount() - searchBefore);
                row.addProperty("actualModernDeferrals", navigator(maid).budgetDeferrals() - deferBefore);
                row.addProperty("nativeAge", maid.ticksExisted);
                row.addProperty("nativeTicksSinceInputAdmission", maid.ticksExisted - nativeAgeBefore);
                row.addProperty("x", maid.posX);
                row.addProperty("y", maid.posY);
                row.addProperty("z", maid.posZ);
                row.add("selectionTicks", new com.google.gson.JsonParser().parse(selections.toString()));
                if (activity() == Activity.CAKE) {
                    row.add("cakeFixtureAdmission", cakeAdmission);
                    row.add("cakeNativePhaseTransitions", cakePhaseTransitions);
                    row.addProperty("cakeNativePhaseOverflow", cakePhaseOverflow);
                    row.add("cakeReadOnlyState", cakeState());
                    row.add(
                        "cakeWorkTransitions",
                        new com.google.gson.JsonParser().parse(cakeWorkTransitions.toString()));
                    row.addProperty("cakeTransitionOverflow", cakeTransitionOverflow);
                    row.addProperty("cakeWorkTargetTicks", cakeWorkTargetTicks);
                    row.addProperty("cakeWorkWithinUseRadiusTicks", cakeWorkWithinUseRadiusTicks);
                }
                return row;
            }
        }

        final class Prop {

            final int bx, by, bz, metadata;
            final Block block;
            final TileEntity tile;
            final NBTTagCompound original;
            final boolean restoreIronFloor;
            boolean removed;

            Prop(int bx, int by, int bz, Block block, int metadata) {
                this(bx, by, bz, block, metadata, false);
            }

            Prop(int bx, int by, int bz, Block block, int metadata, boolean restoreIronFloor) {
                this.bx = bx;
                this.by = by;
                this.bz = bz;
                this.block = block;
                this.metadata = metadata;
                this.restoreIronFloor = restoreIronFloor;
                tile = world.getTileEntity(bx, by, bz);
                original = tileNbt(tile);
            }

            boolean owned() {
                Block actual = world.getBlock(bx, by, bz);
                if (block == Blocks.cake && actual == Blocks.air && world.getTileEntity(bx, by, bz) == null)
                    return true;
                if (actual != block || world.getTileEntity(bx, by, bz) != tile || !original.equals(tileNbt(tile)))
                    return false;
                int data = world.getBlockMetadata(bx, by, bz);
                return block == Blocks.cake ? data >= 0 && data <= 5
                    : block == MaidLifeContent.maidBed ? (data & ~4) == (metadata & ~4) : data == metadata;
            }
        }

        NBTTagCompound tileNbt(TileEntity tile) {
            var raw = new NBTTagCompound();
            if (tile != null) tile.writeToNBT(raw);
            return (NBTTagCompound) raw.copy();
        }

        void removeProps() {
            boolean unknown = false;
            for (int i = props.size() - 1; i >= 0; i--) {
                var prop = props.get(i);
                if (prop.removed) continue;
                if (!prop.owned()) {
                    cleanupError("Unknown activity prop retained", prop.bx, prop.by, prop.bz);
                    unknown = true;
                    continue;
                }
                Block restored = prop.restoreIronFloor ? Blocks.iron_block : Blocks.air;
                h.assertTrue(
                    (world.setBlock(prop.bx, prop.by, prop.bz, restored, 0, 2)
                        || world.getBlock(prop.bx, prop.by, prop.bz) == restored)
                        && world.getBlock(prop.bx, prop.by, prop.bz) == restored
                        && world.getBlockMetadata(prop.bx, prop.by, prop.bz) == 0
                        && world.getTileEntity(prop.bx, prop.by, prop.bz) == null,
                    "Native exact prop cleanup failed");
                prop.removed = true;
            }
            h.assertFalse(unknown, "Unknown activity prop retained; inspect raw cleanup evidence");
            props.clear();
        }

        void removeActors() {
            for (var maid : new ArrayList<>(actors)) {
                h.assertTrue(
                    maid.worldObj == world && MaidSpawnChecks.present(maid)
                        && run.equals(
                            maid.getEntityData()
                                .getString("TLMNHLoadRun")),
                    "Unknown actor successor retained");
                for (int i = 0; i < maid.getMaidInventory()
                    .getSizeInventory(); i++) {
                    var stack = maid.getMaidInventory()
                        .getStackInSlot(i);
                    h.assertTrue(
                        stack == null || i == MaidInventoryLayout.MAIN_HAND && stack.getItem() == Items.iron_sword
                            && stack.stackSize == 1
                            && !stack.hasTagCompound(),
                        "Unknown actor inventory retained");
                }
                maid.getBrainCoordinator()
                    .setSuspended(true);
                world.removePlayerEntityDangerously(maid);
                maid.setDead();
                h.assertTrue(
                    !world.loadedEntityList.contains(maid) && world.getEntityByID(maid.getEntityId()) != maid,
                    "Exact actor remained registered");
                retired.add(maid.getUniqueID());
                actors.remove(maid);
                duties.remove(maid);
            }
            for (var pig : new ArrayList<>(targets)) {
                h.assertTrue(
                    pig.worldObj == world && MaidSpawnChecks.present(pig)
                        && run.equals(
                            pig.getEntityData()
                                .getString("TLMNHLoadRun")),
                    "Unknown native threat successor retained");
                world.removePlayerEntityDangerously(pig);
                pig.setDead();
                targets.remove(pig);
            }
        }

        JsonObject storage() {
            var out = new JsonObject();
            long bytes = 0, files = 0;
            try (var paths = Files.walk(
                world.getSaveHandler()
                    .getWorldDirectory()
                    .toPath())) {
                for (var path : (Iterable<java.nio.file.Path>) paths::iterator) if (Files.isRegularFile(path)) {
                    String name = path.getFileName()
                        .toString();
                    if (name.endsWith(".dat") || name.endsWith(".mca")) {
                        bytes += Files.size(path);
                        files++;
                    }
                }
            } catch (Exception failure) {
                throw new IllegalStateException("Native saved file observation failed", failure);
            }
            out.addProperty("nativeDataFiles", files);
            out.addProperty("nativeDataBytes", bytes);
            out.addProperty(
                "scope",
                "Native dat/MCA allocation observed before this stage's retirement. Includes preceding periodic saves and normal earlier tile-return cleanup I/O; observation itself does not force a save and is not a leak verdict");
            return out;
        }

        void drainChannel() {
            if (channel == null) return;
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        }

        void failed(String phase, Throwable failure) {
            if (!report.has("primaryFailure")) {
                var row = new JsonObject();
                row.addProperty("phase", phase);
                row.addProperty(
                    "type",
                    failure.getClass()
                        .getName());
                row.addProperty("message", failure.toString());
                row.addProperty("worldTick", world.getTotalWorldTime());
                row.addProperty("stageIndex", stage);
                var trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                row.addProperty("stackTrace", trace.toString());
                report.add("primaryFailure", row);
            }
            try {
                save("failed-before-cleanup");
            } catch (RuntimeException saveFailure) {
                failure.addSuppressed(saveFailure);
            }
        }

        void progress() {
            var row = new JsonObject();
            row.addProperty("stageIndex", stage);
            row.addProperty("completedStages", stages.size());
            row.addProperty("phase", fairness ? "follow/N-30-fairness" : label());
            row.addProperty("worldTick", world.getTotalWorldTime());
            write("server-load-progress.json", row);
        }

        void save(String state) {
            report.addProperty("state", state);
            report.add("stages", stages);
            report.add("actorInputs", inputs);
            report.add("activityAdmissions", admissions);
            report.add("activityFrames", activityFrames);
            report.add("fairnessFrames", fairnessFrames);
            report.add("provisionalDamageEvents", damage);
            report.add("cleanupErrors", cleanupErrors);
            boolean covered = stages.size() == 40;
            for (var value : stages) covered &= value.getAsJsonObject()
                .get("workloadVerified")
                .getAsBoolean();
            report.addProperty("workloadCoverageComplete", covered);
            report.addProperty("fullB14Accepted", false);
            if (probe != null) report.add("metrics", probe.snapshot());
            write("server-load-profile.json", report);
        }

        void write(String name, JsonObject value) {
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), name),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(value)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain native load evidence", failure);
            }
        }

        void cleanupError(String message, int bx, int by, int bz) {
            var row = new JsonObject();
            row.addProperty("message", message);
            row.addProperty("x", bx);
            row.addProperty("y", by);
            row.addProperty("z", bz);
            row.addProperty("actualBlock", Block.getIdFromBlock(world.getBlock(bx, by, bz)));
            row.addProperty("actualMetadata", world.getBlockMetadata(bx, by, bz));
            row.add("actualTile", SleepAttributePortSnapshot.binaryNbt(tileNbt(world.getTileEntity(bx, by, bz))));
            cleanupErrors.add(row);
        }

        void close() {
            if (closed) return;
            closed = true;
            Throwable first = null;
            try {
                if (probe != null) probe.close();
            } catch (Throwable failure) {
                first = failure;
            }
            try {
                removeActors();
            } catch (Throwable failure) {
                first = append(first, failure);
            }
            boolean knownRosterGone = actors.isEmpty() && targets.isEmpty();
            if (knownRosterGone) try {
                removeProps();
            } catch (Throwable failure) {
                first = append(first, failure);
            }
            try {
                if (owner != null) {
                    if (server.getConfigurationManager().playerEntityList.contains(owner))
                        server.getConfigurationManager()
                            .playerLoggedOut(owner);
                    if (owner.worldObj == world) world.removePlayerEntityDangerously(owner);
                    owner.setDead();
                }
                if (channel != null) {
                    channel.finish();
                    drainChannel();
                }
            } catch (Throwable failure) {
                first = append(first, failure);
            }
            try {
                if (knownRosterGone && props.isEmpty()) for (var position : floor) {
                    int bx = position[0], by = position[1], bz = position[2];
                    if (world.getBlock(bx, by, bz) != Blocks.iron_block || world.getBlockMetadata(bx, by, bz) != 0
                        || world.getTileEntity(bx, by, bz) != null) {
                        cleanupError("Unknown floor successor retained", bx, by, bz);
                        continue;
                    }
                    if (!world.setBlock(bx, by, bz, Blocks.air, 0, 2))
                        cleanupError("Exact floor removal failed", bx, by, bz);
                }
            } catch (Throwable failure) {
                first = append(first, failure);
            }
            try {
                MinecraftForge.EVENT_BUS.unregister(this);
                chunks.releaseAll();
                server.setCanSpawnAnimals(oldAnimals);
                MaidAcquisitionConfig.giveInitialSlab = oldGift;
                world.getGameRules()
                    .setOrCreateGameRule("doMobSpawning", oldSpawning);
                world.getGameRules()
                    .setOrCreateGameRule("doDaylightCycle", oldDaylight);
                world.setWorldTime(oldTime + world.getTotalWorldTime() - started);
            } catch (Throwable failure) {
                first = append(first, failure);
            }
            boolean leaked = world.loadedEntityList.stream()
                .anyMatch(e -> e instanceof EntityMaid maid && retired.contains(maid.getUniqueID()));
            report.addProperty(
                "exactOwnedCleanup",
                first == null && cleanupErrors.size() == 0 && knownRosterGone && !leaked);
            if (first != null) report.addProperty("cleanupException", first.toString());
            try {
                save(complete ? "measured-and-restored" : "failed-and-restored");
            } catch (Throwable failure) {
                first = append(first, failure);
            }
            if (first != null) throw new IllegalStateException(
                "Native load cleanup failed; primaryFailure retained separately",
                first);
            h.assertTrue(
                report.get("exactOwnedCleanup")
                    .getAsBoolean(),
                "Exact load cleanup failed; raw retained");
        }

        Throwable append(Throwable first, Throwable next) {
            if (first == null) return next;
            first.addSuppressed(next);
            return first;
        }
    }
}
