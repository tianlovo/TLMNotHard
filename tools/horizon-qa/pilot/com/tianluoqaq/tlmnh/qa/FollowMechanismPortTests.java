package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
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
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.development.DeterministicMaidSpawn;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 完整F/N的自然观察；受控原生玩家有内存连接，不冒充真实登录或多人验收 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class FollowMechanismPortTests {

    private FollowMechanismPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "follow_obstacle_f")
    public static void faithfulTemporaryObstacle(GameTestHelper h) {
        collect(h, MaidBrainProfile.FAITHFUL_TLM, "temporary_obstacle");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "follow_obstacle_n")
    public static void enhancedTemporaryObstacle(GameTestHelper h) {
        collect(h, MaidBrainProfile.ENHANCED, "temporary_obstacle");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "follow_reversal_f")
    public static void faithfulOwnerReversal(GameTestHelper h) {
        collect(h, MaidBrainProfile.FAITHFUL_TLM, "owner_reversal");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "follow_reversal_n")
    public static void enhancedOwnerReversal(GameTestHelper h) {
        collect(h, MaidBrainProfile.ENHANCED, "owner_reversal");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "follow_waiting_f")
    public static void faithfulWaitingOwnerFood(GameTestHelper h) {
        collect(h, MaidBrainProfile.FAITHFUL_TLM, "waiting_owner_food");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "follow_waiting_n")
    public static void enhancedWaitingOwnerFood(GameTestHelper h) {
        collect(h, MaidBrainProfile.ENHANCED, "waiting_owner_food");
    }

    private static void collect(GameTestHelper helper, MaidBrainProfile profile, String mechanism) {
        helper
            .assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Authorized complete-root development mode required");
        var collector = new Collector(helper, profile, mechanism);
        helper.afterTest(collector::close);
        helper.onEachTick(
            "follow mechanism observer failures",
            () -> { if (collector.failure != null) throw collector.failure; });
        helper.succeedWhen(() -> collector.complete);
        collector.setup();
    }

    public static class Collector {

        final GameTestHelper helper;
        final MaidBrainProfile profile;
        final JsonObject contract, scene, output = new JsonObject();
        final JsonArray frames = new JsonArray(), events = new JsonArray();
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final int x, y, z;
        final String outputName, oldDaylight, oldSpawning, expectedContract;
        final long oldTime;
        final EnumDifficulty oldDifficulty;
        final boolean oldRain, oldThunder, oldAnimals;
        final int oldRainTime, oldThunderTime;
        EntityMaid maid;
        EntityPlayerMP owner;
        EntityItem food;
        NetworkManager manager;
        EmbeddedChannel channel;
        MaidPolicyRuntime runtime;
        JsonObject ownerCall;
        double ownerX, ownerY, ownerZ;
        int warmup, previousAge, livingTicks;
        boolean complete, closed, nativeLoginComplete;
        RuntimeException failure;

        Collector(GameTestHelper helper, MaidBrainProfile profile, String mechanism) {
            this(
                helper,
                profile,
                mechanism,
                "/tlmnh-follow-mechanisms/contract.json",
                "FOLLOW_PAIRED_MECHANISMS_V1",
                "follow-paired-port-mechanisms-v1",
                "follow-mechanism-");
        }

        Collector(GameTestHelper helper, MaidBrainProfile profile, String mechanism, String resource,
            String expectedContract, String schema, String prefix) {
            this.helper = helper;
            this.profile = profile;
            this.expectedContract = expectedContract;
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            try (var stream = FollowMechanismPortTests.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing follow mechanism contract");
                byte[] bytes = com.google.common.io.ByteStreams.toByteArray(stream);
                contract = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                var hash = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256")
                    .digest(bytes)) hash.append(String.format("%02x", value & 255));
                output.addProperty("contractSha256", hash.toString());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            JsonObject found = null;
            for (var value : contract.getAsJsonArray("mechanisms")) if (value.getAsJsonObject()
                .get("id")
                .getAsString()
                .equals(mechanism)) found = value.getAsJsonObject();
            if (found == null) throw new IllegalArgumentException("Unknown follow mechanism");
            scene = found;
            var world = helper.getWorld();
            oldTime = world.getWorldTime();
            oldDifficulty = world.difficultySetting;
            oldDaylight = world.getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            oldSpawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            oldRain = world.getWorldInfo()
                .isRaining();
            oldThunder = world.getWorldInfo()
                .isThundering();
            oldAnimals = MinecraftServer.getServer()
                .getCanSpawnAnimals();
            oldRainTime = world.getWorldInfo()
                .getRainTime();
            oldThunderTime = world.getWorldInfo()
                .getThunderTime();
            outputName = prefix + profile.getId() + "-" + mechanism + ".json";
            output.addProperty("schema", schema);
            output.addProperty("profile", profile.getId());
            output.addProperty("mechanism", mechanism);
            output.add("contract", contract);
            output.add("scene", scene);
            output.add("frames", frames);
            output.add("events", events);
            output.addProperty("benchmarkVerdict", "NOT_EVALUATED");
            output.addProperty("statisticalAdmission", false);
            output.addProperty("complete14ClassBenchmarkAccepted", false);
            output.addProperty("randomSourcesReset", false);
            output.addProperty("realLoggedInOwner", false);
            output.addProperty(
                "ownerProfile",
                contract.get("ownerProfile")
                    .getAsString());
        }

        void setup() {
            helper.assertTrue(
                contract.get("contract")
                    .getAsString()
                    .equals(expectedContract),
                "Wrong follow contract");
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            MinecraftForge.EVENT_BUS.register(this);
            try {
                chunks.forceChunksStrict(helper.getWorld(), x - 16, y, z - 16, x + 33, y + 10, z + 25);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot prepare native follow mechanism chunks", failure);
            }
            var world = helper.getWorld();
            // CI默认关闭动物资格，旧WorldServer会直接删除EntityAnimal，而不只是阻止自然生成
            MinecraftServer.getServer()
                .setCanSpawnAnimals(true);
            output.addProperty("serverCanSpawnAnimalsBefore", oldAnimals);
            output.addProperty(
                "serverCanSpawnAnimalsDuring",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.setWorldTime(
                contract.get("dayTime")
                    .getAsLong());
            world.difficultySetting = EnumDifficulty.valueOf(
                contract.get("difficulty")
                    .getAsString());
            world.getWorldInfo()
                .setRaining(false);
            world.getWorldInfo()
                .setThundering(false);
            for (int a = 0; a < 21; a++)
                for (int b = 0; b < 11; b++) for (int c = 0; c < 9; c++) helper.setBlock(a, b, c, Blocks.air);
            for (int a = 0; a < 17; a++) for (int b = 0; b < 8; b++) for (int c = 0; c < 9; c++)
                helper.setBlock(a, b, c, b == 0 || (c == 2 || c == 6) && b <= 3 ? Blocks.stone : Blocks.air);
            var start = contract.getAsJsonArray("ownerStart");
            setPlannedOwner(start);
            var server = MinecraftServer.getServer();
            owner = new EntityPlayerMP(
                server,
                world,
                new GameProfile(UUID.randomUUID(), "FollowOwner"),
                new ItemInWorldManager(world));
            manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            var handler = new NetHandlerPlayServer(server, manager, owner);
            IsolatedPlayerChannel.verify(manager, channel);
            owner.setPosition(ownerX, ownerY, ownerZ);
            helper.assertTrue(
                server.worldServerForDimension(owner.dimension) == world,
                "SCM registration must use the actual controlled world");
            // 完整原生登录初始化负责spawn、全局表和FML登录事件，不只登记SCM半登录玩家
            server.getConfigurationManager()
                .initializeConnectionToPlayer(manager, owner, handler);
            nativeLoginComplete = true;
            IsolatedPlayerChannel.verify(manager, channel);
            owner.setGameType(GameType.CREATIVE);
            owner.capabilities.allowFlying = owner.capabilities.isFlying = owner.capabilities.disableDamage = true;
            helper.assertTrue(
                owner.getClass() == EntityPlayerMP.class && world.getEntityByID(owner.getEntityId()) == owner
                    && world.playerEntities.contains(owner)
                    && server.getConfigurationManager().playerEntityList.contains(owner),
                "Exact isolated native owner registration failed");
            try {
                output.add(
                    "productionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        EntityMaid.class,
                        MaidFaithfulBrain.class,
                        EnhancedCoreBrain.class,
                        ModernFollowCoreActor.class,
                        MaidPolicyRuntime.class,
                        com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator.class));
                output.add(
                    "observerArtifact",
                    DevelopmentArtifactIdentity
                        .capture(FollowMechanismPortTests.class, Collector.class, SleepAttributePortSnapshot.class));
                output.add(
                    "spawnInputArtifact",
                    DevelopmentArtifactIdentity.capture(DeterministicMaidSpawn.class, IsolatedPlayerChannel.class));
            } catch (Exception error) {
                throw new IllegalStateException("Follow observation identity unavailable", error);
            }
            applyOwner();
        }

        void setPlannedOwner(JsonArray values) {
            ownerX = x + values.get(0)
                .getAsDouble();
            ownerY = y + values.get(1)
                .getAsDouble();
            ownerZ = z + values.get(2)
                .getAsDouble();
        }

        void applyOwner() {
            if (owner == null) return;
            ownerCall = new JsonObject();
            ownerCall.add("before", ownerSnapshot(false));
            ownerCall.addProperty("method", "EntityPlayerMP.setPosition");
            ownerCall.add("plannedPosition", vector(ownerX - x, ownerY - y, ownerZ - z));
            owner.setPosition(ownerX, ownerY, ownerZ);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            owner.fallDistance = 0;
            ownerCall.add("afterCall", ownerSnapshot(false));
        }

        @SubscribeEvent
        public void living(LivingEvent.LivingUpdateEvent event) {
            onLiving(event.entityLiving);
        }

        void onLiving(net.minecraft.entity.Entity entity) {
            if (entity == maid) livingTicks++;
        }

        @SubscribeEvent
        public void tick(TickEvent.WorldTickEvent event) {
            if (closed || complete || failure != null || event.world != helper.getWorld()) return;
            try {
                if (event.phase == TickEvent.Phase.START) applyOwner();
                else if (maid == null) {
                    if (++warmup >= contract.get("warmupTicks")
                        .getAsInt()) spawn();
                } else observe();
            } catch (Exception | LinkageError | AssertionError error) {
                com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.throwIfFatal(error);
                failure = new IllegalStateException("Follow mechanism observation failed", error);
                output.addProperty("error", error.toString());
            }
        }

        void spawn() {
            maid = new EntityMaid(helper.getWorld());
            helper.assertTrue(maid.bindTo(owner.getUniqueID()), "Native owner binding rejected");
            maid.setTask(MaidTask.IDLE);
            maid.setSchedule(MaidSchedule.ALL);
            maid.setHunger(
                contract.get("hunger")
                    .getAsInt());
            maid.setPickup(
                scene.get("foodPresent")
                    .getAsBoolean());
            maid.setSitting(
                scene.get("initialSitting")
                    .getAsBoolean());
            maid.setModelId(
                contract.get("model")
                    .getAsString());
            DeterministicMaidSpawn.freezeInitialState(maid);
            var p = contract.getAsJsonArray("maidSpawn");
            maid.setPosition(
                x + p.get(0)
                    .getAsDouble(),
                y + p.get(1)
                    .getAsDouble(),
                z + p.get(2)
                    .getAsDouble());
            if (scene.get("foodPresent")
                .getAsBoolean()) {
                var at = contract.getAsJsonObject("food")
                    .getAsJsonArray("position");
                food = new EntityItem(
                    helper.getWorld(),
                    x + at.get(0)
                        .getAsDouble(),
                    y + at.get(1)
                        .getAsDouble(),
                    z + at.get(2)
                        .getAsDouble(),
                    new ItemStack(Items.apple));
                food.motionX = food.motionY = food.motionZ = 0;
                food.delayBeforeCanPickup = 0;
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(food),
                    "Food input join rejected");
            }
            beforeSpawn();
            helper.assertTrue(
                helper.getWorld()
                    .spawnEntityInWorld(maid),
                "Natural maid join rejected");
            var selected = maid.selectBrainProfileForDevelopment(profile);
            helper.assertTrue(selected == MaidPolicyRuntime.SelectionResult.SELECTED, "Full root selection refused");
            runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            livingTicks = previousAge = 0;
            if (profile == MaidBrainProfile.FAITHFUL_TLM) helper.assertTrue(
                maid.getBrainCoordinator()
                    .peekFaithfulBrain()
                    .hasFlightContract()
                    && maid.getBrainCoordinator()
                        .peekFaithfulBrain()
                        .getFlightProfile()
                        == com.tianluoqaq.tlmnh.common.ai.faithful.combat.MaidFaithfulFlightProfile.SOURCE_ORIGINAL,
                "Full F source contract changed");
            output.addProperty("selection", selected.name());
            output.addProperty(
                "maidUuid",
                maid.getUniqueID()
                    .toString());
            output.addProperty("bindingEntry", "entity_protected_profile_selection");
            output.addProperty(
                "entityClass",
                maid.getClass()
                    .getName());
            output.add("attributeInputs", SleepAttributePortSnapshot.capture(maid));
            var inputs = new JsonObject();
            var raw = new NBTTagCompound();
            maid.writeToNBT(raw);
            inputs.addProperty("rawInitialNBT", raw.toString());
            inputs.add("rawInitialNbtBinary", SleepAttributePortSnapshot.binaryNbt(raw));
            inputs.addProperty("model", maid.getModelId());
            inputs.addProperty(
                "task",
                maid.getTask()
                    .getId());
            inputs.addProperty(
                "schedule",
                maid.getSchedule()
                    .name());
            inputs.addProperty("hunger", maid.getHunger());
            inputs.addProperty("health", maid.getHealth());
            inputs.addProperty("width", maid.width);
            inputs.addProperty("height", maid.height);
            inputs.addProperty("pickup", maid.isPickup());
            inputs.addProperty("sitting", maid.isSitting());
            inputs.addProperty(
                "homeEnabled",
                maid.getHomeArea()
                    .enabled());
            inputs.addProperty(
                "restrictionRadius",
                maid.getHomeArea()
                    .restrictionRadius());
            inputs.add("position", vector(maid.posX - x, maid.posY - y, maid.posZ - z));
            inputs.add("owner", ownerSnapshot(true));
            inputs.add("layout", layout());
            inputs.add("food", foodSnapshot());
            inputs.addProperty("difficulty", helper.getWorld().difficultySetting.name());
            inputs.addProperty(
                "dayTime",
                helper.getWorld()
                    .getWorldTime());
            inputs.addProperty(
                "raining",
                helper.getWorld()
                    .isRaining());
            inputs.addProperty(
                "thundering",
                helper.getWorld()
                    .isThundering());
            inputs.addProperty(
                "worldSeed",
                helper.getWorld()
                    .getSeed());
            inputs.addProperty("dimension", helper.getWorld().provider.dimensionId);
            output.add("actualInputs", inputs);
        }

        void beforeSpawn() {
            int nonEmpty = 0;
            var inventory = maid.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                if (inventory.getStackInSlot(slot) != null) nonEmpty++;
            output.addProperty("initialNonEmptyItemStackCount", nonEmpty);
            output.addProperty(
                "initialActiveEffectCount",
                maid.getActivePotionEffects()
                    .size());
            helper.assertTrue(
                nonEmpty == 0 && maid.getActivePotionEffects()
                    .isEmpty(),
                "Fresh maid inventory/effects changed");
            helper.assertTrue(
                maid.getHomeArea()
                    .restrictionRadius()
                    == contract.get("restrictionRadius")
                        .getAsInt(),
                "Actual non-home radius differs from the follow input contract");
        }

        void observe() {
            var coordinator = maid.getBrainCoordinator();
            var boundary = new JsonObject();
            boundary.addProperty("age", maid.ticksExisted);
            boundary.addProperty("previousAge", previousAge);
            boundary.addProperty("livingTicks", livingTicks);
            boundary.addProperty("alive", maid.isEntityAlive());
            boundary.addProperty(
                "registered",
                helper.getWorld()
                    .getEntityByID(maid.getEntityId()) == maid);
            boundary.addProperty("ownerMatches", maid.getOwner() == owner);
            boundary.addProperty("runtimeMatches", runtime == coordinator.peekPolicyRuntime());
            boundary.addProperty("runtimeReady", runtime.isReady());
            boundary.addProperty(
                "actualProfile",
                runtime.getProfile()
                    .getId());
            boundary.addProperty("invalidation", String.valueOf(runtime.getLastInvalidationReason()));
            boundary.addProperty(
                "legacyFollowTicks",
                coordinator.getFollowController()
                    .getTicks());
            boundary.addProperty(
                "legacyCombatTicks",
                coordinator.getCombatController()
                    .getTicks());
            boundary.addProperty("faithfulPresent", coordinator.peekFaithfulBrain() != null);
            boundary.addProperty("enhancedPresent", coordinator.peekEnhancedCore() != null);
            boundary.addProperty(
                "serverCanSpawnAnimals",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
            output.add("lastActorBoundary", boundary);
            helper.assertTrue(
                maid.getClass() == EntityMaid.class && maid.isEntityAlive()
                    && helper.getWorld()
                        .getEntityByID(maid.getEntityId()) == maid
                    && maid.getOwner() == owner
                    && maid.ticksExisted == previousAge + 1
                    && livingTicks == 1
                    && runtime == coordinator.peekPolicyRuntime()
                    && runtime.isReady()
                    && runtime.getProfile() == profile
                    && coordinator.getFollowController()
                        .getTicks() == 0
                    && coordinator.getCombatController()
                        .getTicks() == 0
                    && (profile == MaidBrainProfile.ENHANCED
                        ? coordinator.peekEnhancedCore() != null && coordinator.peekFaithfulBrain() == null
                        : coordinator.peekFaithfulBrain() != null && coordinator.peekEnhancedCore() == null),
                "Natural full root, owner or tick phase changed: " + boundary);
            var actor = profile == MaidBrainProfile.ENHANCED ? coordinator.peekEnhancedCore()
                .actor()
                : coordinator.peekFaithfulBrain()
                    .getActor();
            var f = new JsonObject();
            int index = frames.size();
            f.addProperty("frame", index);
            f.addProperty("age", maid.ticksExisted);
            f.addProperty("livingTicks", livingTicks);
            f.addProperty(
                "gameTime",
                helper.getWorld()
                    .getTotalWorldTime());
            f.addProperty(
                "dayTime",
                helper.getWorld()
                    .getWorldTime());
            f.add("position", vector(maid.posX - x, maid.posY - y, maid.posZ - z));
            f.add("motion", vector(maid.motionX, maid.motionY, maid.motionZ));
            f.addProperty("health", maid.getHealth());
            f.addProperty("sitting", maid.isSitting());
            f.add("ownerInput", ownerCall);
            f.add("owner", ownerSnapshot(false));
            f.addProperty(
                "actualProfile",
                runtime.getProfile()
                    .getId());
            f.addProperty("fullRootAuthorized", true);
            f.addProperty(
                "legacyTicks",
                coordinator.getFollowController()
                    .getTicks()
                    + coordinator.getCombatController()
                        .getTicks());
            f.addProperty("walkTarget", actor.peekWalkTarget() != null);
            if (actor.peekCantReachSince() != null) f.addProperty("cantReachSince", actor.peekCantReachSince());
            f.addProperty(
                "navigationDone",
                maid.getNavigator()
                    .noPath());
            var path = maid.getNavigator()
                .getPath();
            f.addProperty("pathPresent", path != null);
            if (path != null) {
                f.addProperty("pathLength", path.getCurrentPathLength());
                f.addProperty("pathIndex", path.getCurrentPathIndex());
                if (path instanceof com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath modern)
                    f.addProperty("pathCanReach", modern.canReach());
            }
            f.addProperty(
                "activeActivities",
                profile == MaidBrainProfile.ENHANCED ? String.valueOf(
                    coordinator.peekEnhancedCore()
                        .selectedKind())
                    : coordinator.peekFaithfulBrain()
                        .getActivities()
                        .active()
                        .name());
            f.add("food", foodSnapshot());
            frames.add(f);
            afterFrame(index);
            previousAge = maid.ticksExisted;
            livingTicks = 0;
            drain();
            if (frames.size() == frameLimit()) {
                complete = true;
                close();
            }
        }

        int frameLimit() {
            return contract.get("framesPerActor")
                .getAsInt();
        }

        void afterFrame(int index) {
            for (var value : scene.getAsJsonArray("events")) {
                var event = value.getAsJsonObject();
                if (event.get("frame")
                    .getAsInt() != index) continue;
                var r = new JsonParser().parse(event.toString())
                    .getAsJsonObject();
                r.addProperty("phase", "END_after_recorded_frame");
                r.add("ownerBefore", ownerSnapshot(true));
                r.addProperty("sittingBefore", maid.isSitting());
                String kind = event.get("kind")
                    .getAsString();
                if (kind.equals("close_obstacle") || kind.equals("open_obstacle")) {
                    boolean close = kind.equals("close_obstacle");
                    for (int b = 1; b <= 3; b++)
                        for (int c = 3; c <= 5; c++) helper.setBlock(8, b, c, close ? Blocks.stone : Blocks.air);
                    r.add("layoutAfter", layout());
                } else if (kind.equals("owner_position")) setPlannedOwner(event.getAsJsonArray("position"));
                else if (kind.equals("release_wait")) maid.setSitting(false);
                else throw new IllegalArgumentException("Unsupported follow event");
                r.add("nextOwnerPosition", vector(ownerX - x, ownerY - y, ownerZ - z));
                r.addProperty("sittingAfter", maid.isSitting());
                events.add(r);
            }
        }

        JsonObject ownerSnapshot(boolean full) {
            var r = new JsonObject();
            r.addProperty(
                "class",
                owner.getClass()
                    .getName());
            r.addProperty(
                "uuid",
                owner.getUniqueID()
                    .toString());
            r.add("position", vector(owner.posX - x, owner.posY - y, owner.posZ - z));
            r.add("eyePosition", vector(owner.posX - x, owner.posY + owner.getEyeHeight() - y, owner.posZ - z));
            r.addProperty("eyeHeight", owner.getEyeHeight());
            r.addProperty("width", owner.width);
            r.addProperty("height", owner.height);
            r.addProperty("alive", owner.isEntityAlive());
            r.addProperty(
                "spectator",
                owner.theItemInWorldManager.getGameType()
                    .getID() == 3);
            r.addProperty("sneaking", owner.isSneaking());
            r.addProperty("invisible", owner.isInvisible());
            r.addProperty(
                "mode",
                owner.theItemInWorldManager.getGameType()
                    .getName());
            r.addProperty("flying", owner.capabilities.isFlying);
            r.addProperty("mayFly", owner.capabilities.allowFlying);
            r.addProperty("disableDamage", owner.capabilities.disableDamage);
            r.addProperty("entityInvulnerable", owner.isEntityInvulnerable());
            r.addProperty(
                "worldRegistered",
                helper.getWorld()
                    .getEntityByID(owner.getEntityId()) == owner);
            r.addProperty("worldPlayerList", helper.getWorld().playerEntities.contains(owner));
            r.addProperty(
                "connectedPlayerRegistry",
                MinecraftServer.getServer()
                    .getConfigurationManager().playerEntityList.contains(owner));
            r.addProperty("nativeHandlerPresent", owner.playerNetServerHandler != null);
            r.addProperty("memoryChannelOpen", channel.isOpen() && manager.isChannelOpen());
            r.addProperty("realNetworkLogin", false);
            r.addProperty("nativeLoginInitializationComplete", nativeLoginComplete);
            r.addProperty(
                "registrationMethod",
                "ServerConfigurationManager.initializeConnectionToPlayer including native FML login events; isolated PLAY channel, no real client login");
            r.addProperty("dimension", owner.dimension);
            if (full) {
                var raw = new NBTTagCompound();
                owner.writeToNBT(raw);
                r.addProperty("rawNBT", raw.toString());
                r.add("binaryNBT", SleepAttributePortSnapshot.binaryNbt(raw));
            }
            return r;
        }

        JsonObject foodSnapshot() {
            var r = new JsonObject();
            r.addProperty("present", food != null && !food.isDead);
            if (food != null) {
                var stack = food.getEntityItem();
                var raw = new NBTTagCompound();
                stack.writeToNBT(raw);
                r.addProperty(
                    "class",
                    food.getClass()
                        .getName());
                r.add("position", vector(food.posX - x, food.posY - y, food.posZ - z));
                r.addProperty("registry", net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()));
                r.addProperty("count", stack.stackSize);
                r.addProperty("itemNBT", raw.toString());
                r.add("itemNbtBinary", SleepAttributePortSnapshot.binaryNbt(raw));
            }
            return r;
        }

        JsonArray layout() {
            var a = new JsonArray();
            for (int bx = 0; bx < 17; bx++) for (int by = 0; by < 8; by++) for (int bz = 0; bz < 9; bz++) {
                var block = helper.getWorld()
                    .getBlock(x + bx, y + by, z + bz);
                if (block == Blocks.air) continue;
                int metadata = helper.getWorld()
                    .getBlockMetadata(x + bx, y + by, z + bz);
                helper.assertTrue(block == Blocks.stone && metadata == 0, "Unknown follow terrain input");
                var r = new JsonObject();
                r.addProperty("x", bx);
                r.addProperty("y", by);
                r.addProperty("z", bz);
                r.addProperty("semantic", "stone");
                r.addProperty("registry", net.minecraft.block.Block.blockRegistry.getNameForObject(block));
                r.addProperty("metadata", metadata);
                a.add(r);
            }
            return a;
        }

        JsonArray vector(double a, double b, double c) {
            var r = new JsonArray();
            r.add(new com.google.gson.JsonPrimitive(a));
            r.add(new com.google.gson.JsonPrimitive(b));
            r.add(new com.google.gson.JsonPrimitive(c));
            return r;
        }

        void drain() {
            if (channel == null) return;
            Object p;
            while ((p = channel.readOutbound()) != null) ReferenceCountUtil.release(p);
            while ((p = channel.readInbound()) != null) ReferenceCountUtil.release(p);
        }

        void close() {
            if (closed) return;
            closed = true;
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            MinecraftForge.EVENT_BUS.unregister(this);
            try {
                if (maid != null) {
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                    helper.getWorld()
                        .removeEntity(maid);
                }
                if (food != null) helper.getWorld()
                    .removeEntity(food);
                if (owner != null) {
                    // 与完整初始化配对，正常FML退出事件也必须归还各模组登录状态
                    var server = MinecraftServer.getServer();
                    if (server.getConfigurationManager().playerEntityList.contains(owner)) {
                        server.getConfigurationManager()
                            .playerLoggedOut(owner);
                        output.addProperty("nativeLogoutComplete", true);
                    }
                    helper.getWorld()
                        .removePlayerEntityDangerously(owner);
                    owner.setDead();
                    helper.assertTrue(
                        !helper.getWorld().playerEntities.contains(owner)
                            && !server.getConfigurationManager().playerEntityList.contains(owner),
                        "Controlled native owner leaked from world or SCM list");
                }
                if (channel != null) {
                    channel.finish();
                    drain();
                }
                for (int a = 0; a < 21; a++)
                    for (int b = 0; b < 11; b++) for (int c = 0; c < 9; c++) helper.setBlock(a, b, c, Blocks.air);
                output.addProperty("ownCleanupComplete", true);
            } finally {
                MinecraftServer.getServer()
                    .setCanSpawnAnimals(oldAnimals);
                var world = helper.getWorld();
                world.setWorldTime(oldTime);
                world.difficultySetting = oldDifficulty;
                world.getGameRules()
                    .setOrCreateGameRule("doDaylightCycle", oldDaylight);
                world.getGameRules()
                    .setOrCreateGameRule("doMobSpawning", oldSpawning);
                world.getWorldInfo()
                    .setRaining(oldRain);
                world.getWorldInfo()
                    .setThundering(oldThunder);
                world.getWorldInfo()
                    .setRainTime(oldRainTime);
                world.getWorldInfo()
                    .setThunderTime(oldThunderTime);
                chunks.releaseAll();
                output.addProperty("observationComplete", complete && failure == null);
                try {
                    Files.write(
                        Paths.get(System.getProperty("horizonqa.reportDir"), outputName),
                        new Gson().toJson(output)
                            .getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("Cannot preserve follow mechanism capture", error);
                }
            }
        }
    }
}
