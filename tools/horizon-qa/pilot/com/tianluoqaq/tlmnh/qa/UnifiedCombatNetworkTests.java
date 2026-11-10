package com.tianluoqaq.tlmnh.qa;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.gtnewhorizons.horizonqa.world.GameTestWorldType;
import com.gtnewhorizons.horizonqa.world.VoidChunkProvider;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidVisibleStateSnapshot;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/** 真实两连接和被动客户端观察；不创建内存主人，不手工攻击或推进演员 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "etfuturum" })
public final class UnifiedCombatNetworkTests {

    private UnifiedCombatNetworkTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 18000, batch = "combat_network_two_native_clients")
    public static void realClientsObserveAutonomousBowSwordJumpAndQuietEquipment(GameTestHelper h) throws Exception {
        Scene scene = new Scene(h);
        h.afterTest(scene::close);
        try {
            scene.start();
        } catch (Exception failure) {
            scene.failure = failure.toString();
            try {
                scene.writeState();
            } catch (RuntimeException retention) {
                failure.addSuppressed(retention);
            }
            throw failure;
        }
        h.onEachTick("real TCP owners, autonomous weapons and passive two-client observation", () -> {
            try {
                scene.tick();
            } catch (Exception failure) {
                scene.failure = failure.toString();
                scene.writeState();
                throw new IllegalStateException("Real network scene failed", failure);
            }
        });
        h.succeedWhen(() -> scene.finished);
    }

    public static final class Scene {

        final GameTestHelper h;
        final MinecraftServer server = MinecraftServer.getServer();
        private static final int X = 512, Y = 100, Z = 512;
        EntityPlayerMP owner, observer;
        final Path output;
        final String batch;
        final JsonObject productionArtifact, qaArtifact, hqaArtifact;
        JsonObject surfaceProviderAdmission;
        NativeSpectatorContract spectatorContract;
        JsonObject protectionObservation;
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final List<EntityArrow> arrows = new ArrayList<>();
        final List<Entity> owned = new ArrayList<>();
        final int oldWork = MaidHomeConfig.workRange, oldNonHome = MaidHomeConfig.nonHomeRange;
        final boolean oldAnimals = server.getCanSpawnAnimals();
        WorldServer world;
        EntityMaid maid;
        EntityPig target;
        ItemStack bow, sword;
        String phase = "setup", failure;
        long phaseAt, clientsJoinedAt = -1;
        long observerStartTicks;
        int observerLastStartEntityTick = -1;
        int ticks, shots, hits, descendingHits;
        boolean bowSelected, swordSelected, bowPose, jumpPlan, ascent, descent, swing, pendingDescent;
        boolean finished, closed, registered, built, contaminated;
        float healthBefore;
        int jumpDiagnosticTicks, jumpDiagnosticRows;
        String lastJumpDiagnosticState, firstUnknownCaptureFailure;
        boolean firstUnknownCaptured;

        Scene(GameTestHelper h) throws Exception {
            this.h = h;
            require(
                Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaCombatNetwork")
                    && "interactive".equals(System.getProperty("horizonqa.mode"))
                    && "void".equals(System.getProperty("horizonqa.world"))
                    && "false".equals(System.getProperty("horizonqa.autoRun"))
                    && "false".equals(System.getProperty("horizonqa.stopServer"))
                    && "1".equals(System.getProperty("horizonqa.turbo")),
                "Explicit native interactive network batch required");
            batch = System.getProperty("tlmnh.qaCombatNetworkBatch", "");
            require(batch.matches("network-[0-9]+"), "Unique network batch required");
            require(
                h.getWorld().provider.dimensionId == 0 && h.getWorld()
                    .getWorldInfo()
                    .getWorldName()
                    .equals("TLMNH-" + batch),
                "Fresh dedicated QA world name mismatch");
            Path actualSave = h.getWorld()
                .getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .toRealPath();
            require(
                actualSave.getParent()
                    .equals(
                        Paths.get(".")
                            .toRealPath())
                    && actualSave.getFileName()
                        .toString()
                        .equals("TLMNH-" + batch),
                "Actual fresh save root is outside the dedicated child");
            output = Paths.get(System.getProperty("horizonqa.reportDir"));
            require(
                server.getConfigurationManager().playerEntityList.isEmpty(),
                "Start the native QA scene before connecting clients");
            productionArtifact = DevelopmentArtifactIdentity.capture(EntityMaid.class);
            qaArtifact = DevelopmentArtifactIdentity.capture(UnifiedCombatNetworkTests.class);
            hqaArtifact = DevelopmentArtifactIdentity.capture(GameTestWorldType.class, VoidChunkProvider.class);
        }

        void start() throws Exception {
            world = h.getWorld();
            JsonObject launch = new JsonParser()
                .parse(new String(Files.readAllBytes(output.resolve("launch.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
            require(
                batch.equals(
                    launch.get("batch")
                        .getAsString())
                    && Paths.get(
                        launch.get("runtime")
                            .getAsString())
                        .toRealPath()
                        .equals(
                            Paths.get(".")
                                .toRealPath()),
                "Network launch manifest does not belong to this actual batch/cwd");
            surfaceProviderAdmission = KnownSurfaceProviderIdentity
                .capture(world.provider, Paths.get("."), launch.getAsJsonObject("runtimeInputs"));
            spectatorContract = NativeSpectatorContract.open(Paths.get("."), launch.getAsJsonObject("runtimeInputs"));
            require(
                world.provider.dimensionId == 0 && world.getWorldInfo()
                    .getTerrainType() == GameTestWorldType.INSTANCE
                    && world.theChunkProviderServer.currentChunkProvider.getClass() == VoidChunkProvider.class,
                "Actual native dim0 gtnhvvoid surface and HQA air provider required");
            require(
                GameType.getByID(3)
                    .getID() == 3,
                "Actual Et Futurum spectator unavailable");
            chunks.forceChunksStrict(world, X - 64, 0, Z - 64, X + 64, 255, Z + 64);
            for (Object raw : world.loadedEntityList.toArray())
                require(!insideArena((Entity) raw), "Unknown existing arena object; preserved");
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++)
                for (int y = 0; y <= Y + 6; y++) require(world.isAirBlock(X + x, y, Z + z), "Arena is not actual air");
            built = true;
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                world.setBlock(X + x, Y, Z + z, Blocks.wool, 7, 3);
                if (Math.abs(x) == 10 || Math.abs(z) == 10)
                    for (int y = Y + 1; y <= Y + 4; y++) world.setBlock(X + x, y, Z + z, Blocks.glass);
            }
            server.setCanSpawnAnimals(true);
            MaidHomeConfig.workRange = MaidHomeConfig.nonHomeRange = 24;
            MinecraftForge.EVENT_BUS.register(this);
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            registered = true;
            stage("waiting-clients");
        }

        @SubscribeEvent
        public void login(PlayerEvent.PlayerLoggedInEvent event) {
            try {
                protectLogin(event);
            } catch (RuntimeException problem) {
                failure = "Native login protection failed: " + problem;
                writeState();
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void observerTick(TickEvent.PlayerTickEvent event) {
            if (!closed && observer != null
                && event.phase == TickEvent.Phase.START
                && event.side == Side.SERVER
                && event.player == observer) {
                observerStartTicks++;
                observerLastStartEntityTick = observer.ticksExisted;
            }
        }

        private void protectLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP actual)) return;
            String name = actual.getCommandSenderName();
            if (!name.equals("TLMNHTest") && !name.equals("TLMNHObserver")) {
                failure = "Unexpected real player; preserved";
                return;
            }
            connection(actual);
            require(actual.worldObj == world && actual.dimension == 0, "New real client joined another world");
            if (closed) {
                // A pending real connection may finish after an early test failure; keep it safe until this JVM exits
                protect(actual, name.equals("TLMNHTest") ? GameType.CREATIVE : GameType.getByID(3));
                positionPlayer(actual, X, Y + 3, Z - 8.5);
                return;
            }
            if (name.equals("TLMNHTest")) {
                require(owner == null, "Owner reconnected during this batch");
                owner = actual;
                protect(actual, GameType.CREATIVE);
                positionPlayer(actual, X - 2.5, Y + 3, Z - 8.5);
            } else {
                require(observer == null, "Observer reconnected during this batch");
                observer = actual;
                protect(actual, GameType.getByID(3));
                positionPlayer(actual, X + 2.5, Y + 3, Z - 8.5);
            }
            writeState();
        }

        void createActors() {
            require(
                server.getConfigurationManager().playerEntityList.size() == 2
                    && server.getConfigurationManager().playerEntityList.contains(owner)
                    && server.getConfigurationManager().playerEntityList.contains(observer)
                    && world.getEntityByID(owner.getEntityId()) == owner
                    && world.getEntityByID(observer.getEntityId()) == observer,
                "Exactly two registered real clients required");
            require(
                owner != observer && !owner.getUniqueID()
                    .equals(observer.getUniqueID()),
                "Distinct actual identities required");
            connection(owner);
            connection(observer);
            maid = new EntityMaid(world);
            owned.add(maid);
            require(maid.bindTo(owner.getUniqueID()), "Real owner binding failed");
            maid.setSchedule(MaidSchedule.ALL);
            maid.setTask(MaidTask.ATTACK);
            maid.setPickup(false);
            maid.setModelId("touhou_little_maid:hakurei_reimu");
            maid.setCustomNameTag("QA-" + batch);
            maid.setCurrentItemOrArmor(0, tagged(new ItemStack(Items.stick, 1), "initial-unknown"));
            sword = tagged(new ItemStack(Items.iron_sword), "native-sword");
            bow = tagged(new ItemStack(Items.bow), "native-bow");
            maid.getMaidInventory()
                .setInventorySlotContents(0, sword);
            maid.getMaidInventory()
                .setInventorySlotContents(1, bow);
            maid.setOffhandItem(tagged(new ItemStack(Items.arrow, 32), "native-ammunition"));
            maid.setCurrentItemOrArmor(4, tagged(new ItemStack(Items.leather_helmet), "native-head"));
            maid.getEntityAttribute(MaidCombatAttributes.ATTACK_SPEED)
                .setBaseValue(1);
            maid.getEntityAttribute(MaidCombatAttributes.SHOOT_COOLDOWN)
                .setBaseValue(40);
            maid.setPosition(X - 7.5, Y + 1, Z + .5);
            maid.getBrainCoordinator()
                .setSuspended(true);
            require(world.spawnEntityInWorld(maid), "Network maid spawn rejected");
            require(
                maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                    == MaidPolicyRuntime.SelectionResult.SELECTED,
                "Full production brain unavailable");
            maid.getBrainCoordinator()
                .setSuspended(true);
            target(X + 7.5, Z + .5);
            stage("input-quiet");
        }

        void tick() throws Exception {
            if (finished) return;
            require(++ticks < 17800 && failure == null, "Network scene failed/expired: " + failure);
            if (phase.equals("waiting-clients")) {
                if (owner != null) connection(owner);
                if (observer != null) connection(observer);
                if (owner == null || observer == null) return;
                if (clientsJoinedAt < 0) clientsJoinedAt = world.getTotalWorldTime();
                long protectionNow = world.getTotalWorldTime();
                long protectionAge = protectionNow - clientsJoinedAt;
                boolean protectedNow = protectedPlayers();
                require(
                    protectionAge < 80,
                    "Native joined-client protection did not settle"
                        + (protectionAge >= 80
                            ? "; start=" + clientsJoinedAt
                                + "; now="
                                + protectionNow
                                + "; age="
                                + protectionAge
                                + "; protectedNow="
                                + protectedNow
                                + "; owner="
                                + protectionSnapshot(owner)
                                + "; observer="
                                + protectionSnapshot(observer)
                                + "; nativeContract="
                                + protectionObservation
                            : ""));
                if (!protectedNow) return;
                createActors();
            }
            connection(owner);
            connection(observer);
            require(
                owner.worldObj == world && observer.worldObj == world && maid.getOwner() == owner,
                "Real connection/world/owner changed");
            require(protectedPlayers(), "Actual native client protection lost: " + protectionObservation);
            require(
                maid.isEntityAlive() && maid.getHealth() == 20 && maid.getTask() == MaidTask.ATTACK,
                "Combat maid damaged or unified task changed");
            for (Object raw : world.loadedEntityList.toArray()) {
                if (insideArena((Entity) raw) && raw != owner && raw != observer && !owned.contains(raw)) {
                    contaminated = true;
                    throw new IllegalStateException("Unknown arena entity; preserved without cleanup authority");
                }
            }
            if (phase.endsWith("active")) {
                require(
                    !maid.getBrainCoordinator()
                        .isSuspended(),
                    "Production root unexpectedly suspended");
                var core = maid.getBrainCoordinator()
                    .peekEnhancedCore();
                require(
                    core != null && maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .isReady()
                        && maid.getBrainCoordinator()
                            .peekPolicyRuntime()
                            .getProfile() == MaidBrainProfile.ENHANCED
                        && maid.getBrainCoordinator()
                            .peekFaithfulBrain() == null
                        && maid.getBrainCoordinator()
                            .getCombatController()
                            .getTicks() == 0,
                    "Production root faulted or changed");
                bowSelected |= maid.getHeldItem() == bow;
                swordSelected |= phase.equals("jump-active") && maid.getHeldItem() == sword;
                bowPose |= maid.hasPose(MaidPose.AIMING_BOW);
                var plan = core.combat()
                    .lastPlan();
                jumpPlan |= phase.equals("jump-active") && plan != null
                    && plan.macro == Macro.JUMP_STRIKE
                    && plan.simulated;
                ascent |= phase.equals("jump-active") && !maid.onGround && maid.motionY > .001;
                descent |= ascent && !maid.onGround && maid.motionY < 0 && maid.fallDistance > 0;
                swing |= phase.equals("jump-active") && maid.isSwingInProgress;
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("schema", "combat-network-native-frame-v2");
            frame.addProperty("phase", phase);
            frame.addProperty("worldTime", world.getTotalWorldTime());
            frame.addProperty("y", maid.posY);
            frame.addProperty("motionY", maid.motionY);
            frame.addProperty("onGround", maid.onGround);
            frame.addProperty("isSwingInProgress", maid.isSwingInProgress);
            frame.addProperty("swingProgressInt", maid.swingProgressInt);
            frame.addProperty("aimingBow", maid.hasPose(MaidPose.AIMING_BOW));
            Files.write(
                output.resolve("network-native-frames.jsonl"),
                (frame + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            captureCachedJump();
            if (ticks % 5 == 0) writeState();
            Path request = output.resolve("advance-" + phase + ".json");
            if (!Files.isRegularFile(request)) return;
            JsonObject command = new JsonParser().parse(new String(Files.readAllBytes(request), StandardCharsets.UTF_8))
                .getAsJsonObject();
            require(
                batch.equals(
                    command.get("batch")
                        .getAsString())
                    && phase.equals(
                        command.get("phase")
                            .getAsString()),
                "Wrong phase acknowledgement");
            switch (phase) {
                case "input-quiet":
                    maid.getBrainCoordinator()
                        .setSuspended(false);
                    stage("bow-active");
                    break;
                case "bow-active":
                    require(
                        bowSelected && bowPose && shots >= 2 && hits > 0 && target.getHealth() < 1000,
                        "Native bow coverage incomplete");
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                    stage("bow-quiet");
                    break;
                case "bow-quiet":
                    require(
                        world.getTotalWorldTime() - phaseAt >= 20 && maid.onGround
                            && !maid.hasPose(MaidPose.AIMING_BOW),
                        "Bow has not settled");
                    removeTargetAndArrows();
                    target(maid.posX > X + 7 ? maid.posX - 1.7 : maid.posX + 1.7, maid.posZ);
                    stage("jump-quiet");
                    break;
                case "jump-quiet":
                    maid.getBrainCoordinator()
                        .setSuspended(false);
                    stage("jump-active");
                    break;
                case "jump-active":
                    require(
                        swordSelected && jumpPlan && ascent && descent && swing && descendingHits > 0,
                        "Native sword exchange/jump/descent/hit incomplete");
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                    stage("final-quiet");
                    break;
                case "final-quiet":
                    require(
                        world.getTotalWorldTime() - phaseAt >= 20 && maid.onGround
                            && !maid.isSwingInProgress
                            && maid.swingProgressInt == 0
                            && !maid.hasPose(MaidPose.AIMING_BOW),
                        "Final pose has not settled");
                    finished = true;
                    stage("completed");
                    break;
                default:
                    throw new IllegalStateException("Unexpected phase");
            }
        }

        void target(double x, double z) {
            target = new EntityPig(world);
            target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(1000);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            target.setHealth(1000);
            target.setPosition(x, Y + 1, z);
            owned.add(target);
            require(world.spawnEntityInWorld(target), "Own native target spawn rejected");
            maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void joined(EntityJoinWorldEvent event) {
            if (event.world != world) return;
            if (maid != null && !event.isCanceled()
                && event.entity instanceof EntityArrow arrow
                && arrow.shootingEntity == maid) {
                if (arrows.size() >= 128) {
                    failure = "Own arrow record overflow";
                    return;
                }
                arrows.add(arrow);
                owned.add(arrow);
                shots++;
            } else if (event.entity instanceof EntityPlayerMP player && (player.getCommandSenderName()
                .equals("TLMNHTest")
                || player.getCommandSenderName()
                    .equals("TLMNHObserver"))) {
                        // The native LoggedIn event certifies these connections before any actor is created
                    } else
                if (insideArena(event.entity) && !owned.contains(event.entity)) {
                    captureFirstUnknown(event);
                    contaminated = true;
                    failure = "Unknown arena join; object preserved";
                }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving == target && event.source.getEntity() == maid
                && event.source.getSourceOfDamage() instanceof EntityArrow arrow
                && arrows.contains(arrow)) hits++;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void pre(MaidHurtTarget.Pre event) {
            if (event.getMaid() != maid || event.getTarget() != target) return;
            pendingDescent = phase.equals("jump-active") && !maid.onGround && maid.motionY < 0 && maid.fallDistance > 0;
            healthBefore = target.getHealth();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void post(MaidHurtTarget.Post event) {
            if (event.getMaid() == maid && event.getTarget() == target
                && event.isHurt()
                && pendingDescent
                && target.getHealth() < healthBefore) descendingHits++;
            pendingDescent = false;
        }

        /** 只读已有缓存，采样键不包含每tick时间戳或位置 */
        private void captureCachedJump() throws Exception {
            if (!phase.equals("jump-active")) return;
            int sampleTick = ++jumpDiagnosticTicks;
            if (jumpDiagnosticRows >= 128) return;
            var core = maid.getBrainCoordinator()
                .peekEnhancedCore();
            var combat = core == null ? null : core.combat();
            var plan = combat == null ? null : combat.lastPlan();
            var snapshot = combat == null ? null : combat.lastSnapshot();
            var result = combat == null ? null : combat.lastResult();
            String diagnostic = core == null ? null
                : core.actor()
                    .unifiedJumpDiagnostic();
            String state = (plan == null ? "null" : plan.macro.name()) + "/"
                + result
                + "/"
                + jumpDiagnosticState(diagnostic);
            boolean changed = !state.equals(lastJumpDiagnosticState);
            lastJumpDiagnosticState = state;
            if (sampleTick > 4 && !changed && sampleTick % 40 != 0) return;
            JsonObject row = new JsonObject();
            row.addProperty("schema", "combat-network-cached-jump-v1");
            row.addProperty("batch", batch);
            row.addProperty("phase", phase);
            row.addProperty("worldTime", world.getTotalWorldTime());
            row.addProperty("entityTick", maid.ticksExisted);
            row.addProperty("phaseSampleTick", sampleTick);
            row.addProperty("sampleReason", sampleTick <= 4 ? "first_four" : changed ? "state_change" : "periodic_40");
            row.addProperty("selectedKind", core == null ? null : String.valueOf(core.selectedKind()));
            row.addProperty("lastResult", result == null ? null : result.name());
            row.addProperty("jumpDiagnostic", diagnostic);
            row.addProperty("historicalJumpPlan", jumpPlan);
            row.addProperty("cacheOnly", true);
            row.add("actualMaid", entityWitness(maid));
            row.add("actualTarget", entityWitness(target));
            ItemStack held = maid.getHeldItem();
            JsonObject item = new JsonObject();
            item.addProperty("nativeId", held == null ? null : net.minecraft.item.Item.getIdFromItem(held.getItem()));
            item.addProperty("metadata", held == null ? null : held.getItemDamage());
            item.addProperty("count", held == null ? null : held.stackSize);
            row.add("held", item);
            if (plan != null) {
                JsonObject value = new JsonObject();
                value.addProperty("macro", plan.macro.name());
                value.addProperty("strategy", plan.strategy.name());
                value.addProperty("reason", plan.reason);
                value.addProperty("simulated", plan.simulated);
                value.addProperty("observedAt", plan.observedAt);
                value.addProperty("expiresAt", plan.expiresAt);
                value.addProperty("targetUuid", plan.targetId == null ? null : plan.targetId.toString());
                value.addProperty("targetEntityId", plan.targetEntityId);
                value.addProperty("weaponSlot", plan.weaponSlot);
                finite(value, "expectedDamage", plan.expectedDamage);
                boolean observedOnly = plan.strategy.name()
                    .equals("REOBSERVE");
                value.addProperty("riskScope", observedOnly ? "OBSERVED_ONLY_TOTAL_UNKNOWN" : "COMPLETE_COMBAT");
                value.addProperty(
                    "expectedRisk",
                    observedOnly || !Double.isFinite(plan.expectedRisk) ? null : plan.expectedRisk);
                value.addProperty("utility", observedOnly || !Double.isFinite(plan.utility) ? null : plan.utility);
                value.addProperty("traceAvailable", plan.trace != null);
                row.add("plan", value);
            } else row.add("plan", null);
            if (snapshot != null) {
                JsonObject value = new JsonObject();
                value.addProperty("tick", snapshot.tick);
                value.addProperty("complete", snapshot.complete);
                value
                    .addProperty("geometryObservedAt", snapshot.geometry == null ? null : snapshot.geometry.observedAt);
                value.addProperty(
                    "geometryFreshAtCapture",
                    snapshot.geometry != null && snapshot.geometry.fresh(snapshot.tick));
                value.addProperty(
                    "geometryFreshNow",
                    snapshot.geometry != null && snapshot.geometry.fresh(world.getTotalWorldTime()));
                value.addProperty("grounded", snapshot.self.grounded);
                value.addProperty("jumpAllowed", snapshot.self.jumpAllowed);
                value.addProperty("stationary", snapshot.self.stationary);
                value.addProperty("cadenceReady", snapshot.self.cadenceReady);
                value.addProperty("cadenceRemainingTicks", snapshot.self.cadenceRemainingTicks);
                value.add("velocity", vector(snapshot.self.velocity));
                var observedTarget = snapshot.target(snapshot.selectedTarget);
                if (observedTarget != null) {
                    value.addProperty("targetUuid", observedTarget.id == null ? null : observedTarget.id.toString());
                    value.addProperty("targetVisible", observedTarget.visible);
                    value.addProperty("targetObservedAt", observedTarget.observedAt);
                    finite(value, "targetEstimatedHealth", observedTarget.health);
                    value.addProperty("targetLastOwnHitAt", observedTarget.lastOwnHitAt);
                }
                row.add("snapshot", value);
            } else row.add("snapshot", null);
            Files.write(
                output.resolve("network-cached-jump.jsonl"),
                (row + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            jumpDiagnosticRows++;
        }

        private static String jumpDiagnosticState(String diagnostic) {
            return diagnostic == null ? null : diagnostic.replaceAll("@-?[0-9]+:maid=", "@<tick>:maid=");
        }

        private void captureFirstUnknown(EntityJoinWorldEvent event) {
            if (firstUnknownCaptured) return;
            firstUnknownCaptured = true;
            try {
                JsonObject row = new JsonObject();
                row.addProperty("schema", "combat-network-first-unknown-v1");
                row.addProperty("batch", batch);
                row.addProperty("phase", phase);
                row.addProperty("worldTime", world.getTotalWorldTime());
                row.addProperty("canceledObserved", event.isCanceled());
                row.addProperty("eventDimension", event.world.provider.dimensionId);
                row.addProperty("entityWorldSameEvent", event.entity.worldObj == event.world);
                row.addProperty("inducedDropOwnership", "UNPROVEN");
                row.addProperty("diagnosticOnly", true);
                row.add("unknown", entityWitness(event.entity));
                row.add("knownMaid", entityWitness(maid));
                row.add("knownTarget", entityWitness(target));
                StackTraceElement[] stack = Thread.currentThread()
                    .getStackTrace();
                JsonArray frames = new JsonArray();
                for (int i = 0; i < Math.min(24, stack.length); i++) {
                    JsonObject frame = new JsonObject();
                    frame.addProperty("class", stack[i].getClassName());
                    frame.addProperty("method", stack[i].getMethodName());
                    frame.addProperty("file", stack[i].getFileName());
                    frame.addProperty("line", stack[i].getLineNumber());
                    frames.add(frame);
                }
                row.add("spawnStack", frames);
                row.addProperty("spawnStackTruncated", stack.length > 24);
                Files
                    .write(output.resolve("network-first-unknown.json"), (row + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception problem) {
                // 诊断留存失败不改变原unknown拒绝或授予清理权限
                firstUnknownCaptureFailure = problem.toString();
            }
        }

        private static JsonObject entityWitness(Entity entity) {
            if (entity == null) return null;
            JsonObject row = new JsonObject();
            row.addProperty(
                "class",
                entity.getClass()
                    .getName());
            row.addProperty("registry", EntityList.getEntityString(entity));
            row.addProperty(
                "uuid",
                entity.getUniqueID()
                    .toString());
            row.addProperty("entityId", entity.getEntityId());
            row.addProperty("dimension", entity.dimension);
            finite(row, "x", entity.posX);
            finite(row, "y", entity.posY);
            finite(row, "z", entity.posZ);
            row.addProperty("isDead", entity.isDead);
            if (entity instanceof EntityLivingBase living) finite(row, "health", living.getHealth());
            return row;
        }

        private static JsonObject vector(com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec value) {
            if (value == null) return null;
            JsonObject row = new JsonObject();
            finite(row, "x", value.x);
            finite(row, "y", value.y);
            finite(row, "z", value.z);
            return row;
        }

        private static void finite(JsonObject row, String name, double value) {
            row.addProperty(name, Double.isFinite(value) ? value : null);
        }

        void stage(String next) {
            phase = next;
            phaseAt = world.getTotalWorldTime();
            writeState();
        }

        void writeState() {
            JsonObject state = new JsonObject();
            state.addProperty("schema", "combat-network-server-v2");
            state.addProperty("batch", batch);
            state.addProperty("phase", phase);
            state.addProperty("phaseAt", phaseAt);
            state.addProperty("worldTime", world == null ? -1 : world.getTotalWorldTime());
            state.addProperty("failure", failure);
            state.addProperty("dimension", world == null ? -1 : world.provider.dimensionId);
            state.addProperty("arenaFloorY", Y);
            state.addProperty(
                "terrainType",
                world == null ? null
                    : world.getWorldInfo()
                        .getTerrainType()
                        .getWorldTypeName());
            state.addProperty(
                "terrainProviderClass",
                world == null ? null
                    : world.theChunkProviderServer.currentChunkProvider.getClass()
                        .getName());
            state.addProperty("worldSeed", world == null ? null : Long.toString(world.getSeed()));
            state.addProperty(
                "providerClass",
                world == null ? null
                    : world.provider.getClass()
                        .getName());
            state.addProperty(
                "ownerUuid",
                owner == null ? null
                    : owner.getUniqueID()
                        .toString());
            state.addProperty(
                "observerUuid",
                observer == null ? null
                    : observer.getUniqueID()
                        .toString());
            state.addProperty(
                "ownerSocket",
                owner == null ? null
                    : owner.playerNetServerHandler.netManager.getSocketAddress()
                        .toString());
            state.addProperty(
                "observerSocket",
                observer == null ? null
                    : observer.playerNetServerHandler.netManager.getSocketAddress()
                        .toString());
            state.addProperty("completed", finished);
            state.addProperty("unknownObjectObserved", contaminated);
            state.addProperty("shots", shots);
            state.addProperty("arrowHits", hits);
            state.addProperty("descendingHits", descendingHits);
            state.addProperty("targetHealth", target == null ? -1 : target.getHealth());
            state.addProperty("bowSelected", bowSelected);
            state.addProperty("swordSelected", swordSelected);
            state.addProperty("bowPose", bowPose);
            state.addProperty("jumpPlan", jumpPlan);
            state.addProperty("jumpPlanMeaning", "Historical OR only; not the current native jump request");
            state.addProperty("jumpDiagnosticRows", jumpDiagnosticRows);
            state.addProperty("firstUnknownCaptured", firstUnknownCaptured);
            state.addProperty("firstUnknownCaptureFailure", firstUnknownCaptureFailure);
            state.addProperty("ascent", ascent);
            state.addProperty("descent", descent);
            state.addProperty("swing", swing);
            state.addProperty("taskId", MaidTask.ATTACK.getId());
            state.add("productionArtifact", productionArtifact);
            state.add("qaArtifact", qaArtifact);
            state.add("hqaArtifact", hqaArtifact);
            state.add("surfaceProviderAdmission", surfaceProviderAdmission);
            state.add("nativeSpectatorSource", spectatorContract == null ? null : spectatorContract.source);
            state.add("serverProtection", protectionObservation);
            state.addProperty("observerPlayerStartTicks", observerStartTicks);
            state.addProperty("observerLastStartEntityTick", observerLastStartEntityTick);
            if (maid != null) {
                state.addProperty(
                    "maidUuid",
                    maid.getUniqueID()
                        .toString());
                state.addProperty("y", maid.posY);
                state.add("visible", MaidVisibleStateSnapshot.capture(maid));
            }
            try {
                Path pending = output.resolve("network-state.pending");
                Files.write(pending, (state + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(pending, output.resolve("network-state.json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception problem) {
                throw new IllegalStateException("Cannot preserve network state", problem);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            if (registered) {
                MinecraftForge.EVENT_BUS.unregister(this);
                // The narrow native-login protection remains for pending clients until the owned JVM exits
            }
            if (maid != null) maid.getBrainCoordinator()
                .setSuspended(true);
            for (Entity entity : owned) if (entity.worldObj == world) {
                entity.setDead();
                world.removePlayerEntityDangerously(entity);
            }
            if (built && !contaminated) for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                if (world.getBlock(X + x, Y, Z + z) == Blocks.wool && world.getBlockMetadata(X + x, Y, Z + z) == 7)
                    world.setBlockToAir(X + x, Y, Z + z);
                if (Math.abs(x) == 10 || Math.abs(z) == 10) for (int y = Y + 1; y <= Y + 4; y++)
                    if (world.getBlock(X + x, y, Z + z) == Blocks.glass) world.setBlockToAir(X + x, y, Z + z);
            }
            chunks.releaseAll();
            server.setCanSpawnAnimals(oldAnimals);
            MaidHomeConfig.workRange = oldWork;
            MaidHomeConfig.nonHomeRange = oldNonHome;
            if (owner != null) protect(owner, GameType.CREATIVE);
            if (observer != null) protect(observer, GameType.getByID(3));
            writeState();
            System.out.println("TLMNH_COMBAT_NETWORK_SERVER_CLOSED completed=" + finished + " failure=" + failure);
        }

        void removeTargetAndArrows() {
            target.setDead();
            world.removePlayerEntityDangerously(target);
            for (EntityArrow arrow : arrows) {
                arrow.setDead();
                world.removePlayerEntityDangerously(arrow);
            }
        }

        void connection(EntityPlayerMP player) {
            require(
                player.playerNetServerHandler != null && player.playerNetServerHandler.netManager.channel()
                    .isActive()
                    && player.playerNetServerHandler.netManager.getSocketAddress() instanceof InetSocketAddress,
                "A real native TCP player connection is required");
        }

        void protect(EntityPlayerMP player, GameType mode) {
            player.setGameType(mode);
            player.capabilities.disableDamage = true;
            player.capabilities.allowFlying = player.capabilities.isFlying = true;
            player.fallDistance = 0;
            player.sendPlayerAbilities();
        }

        JsonObject protectionSnapshot(EntityPlayerMP player) {
            JsonObject state = new JsonObject();
            state.addProperty(
                "uuid",
                player.getUniqueID()
                    .toString());
            state.addProperty(
                "class",
                player.getClass()
                    .getName());
            state.addProperty("entityId", player.getEntityId());
            state.addProperty(
                "gameType",
                player.theItemInWorldManager.getGameType()
                    .getID());
            state.addProperty("health", player.getHealth());
            state.addProperty("disableDamage", player.capabilities.disableDamage);
            state.addProperty("allowFlying", player.capabilities.allowFlying);
            state.addProperty("isFlying", player.capabilities.isFlying);
            state.addProperty("isCreativeMode", player.capabilities.isCreativeMode);
            state.addProperty("noClip", player.noClip);
            state.addProperty("onGround", player.onGround);
            state.addProperty("x", player.posX);
            state.addProperty("y", player.posY);
            state.addProperty("z", player.posZ);
            state.addProperty("fallDistance", player.fallDistance);
            return state;
        }

        boolean protectedPlayers() throws Exception {
            NativeSpectatorContract.Observation nativeObserver = spectatorContract.observe(observer);
            JsonObject ownerAir = airSnapshot(owner), observerAir = airSnapshot(observer);
            boolean ownerProtected = owner.theItemInWorldManager.getGameType() == GameType.CREATIVE && !owner.isDead
                && owner.getHealth() > 0
                && owner.capabilities.disableDamage
                && owner.capabilities.allowFlying
                && owner.capabilities.isFlying;
            boolean observerProtected = observer.theItemInWorldManager.getGameType()
                .getID() == 3 && !observer.isDead
                && observer.getHealth() > 0
                && observer.capabilities.disableDamage
                && observer.capabilities.allowFlying
                && observer.capabilities.isFlying
                && !observer.capabilities.isCreativeMode;
            boolean safeAir = ownerAir.get("insidePreflightedAir")
                .getAsBoolean()
                && observerAir.get("insidePreflightedAir")
                    .getAsBoolean();
            boolean accepted = ownerProtected && observerProtected && nativeObserver.accepted && safeAir;
            JsonObject observed = new JsonObject();
            observed.addProperty("schema", "fixed-air-native-player-protection-v1");
            observed.addProperty("sceneTick", ticks);
            observed.addProperty("ownerAbilitiesAccepted", ownerProtected);
            observed.addProperty("observerAbilitiesAccepted", observerProtected);
            observed.addProperty("observerPlayerStartTicks", observerStartTicks);
            observed.addProperty("observerLastStartEntityTick", observerLastStartEntityTick);
            observed.add("owner", protectionSnapshot(owner));
            observed.add("observer", protectionSnapshot(observer));
            observed.add("nativeObserver", nativeObserver.evidence);
            observed.add("ownerAir", ownerAir);
            observed.add("observerAir", observerAir);
            observed.addProperty("accepted", accepted);
            protectionObservation = observed;
            return accepted;
        }

        JsonObject airSnapshot(EntityPlayerMP player) {
            JsonObject result = new JsonObject();
            AxisAlignedBB box = player.boundingBox;
            result.addProperty("boxPresent", box != null);
            result.addProperty("footX", player.posX);
            result.addProperty("footY", player.posY);
            result.addProperty("footZ", player.posZ);
            result.addProperty("interiorMinX", X - 9);
            result.addProperty("interiorMaxX", X + 10);
            result.addProperty("interiorMinZ", Z - 9);
            result.addProperty("interiorMaxZ", Z + 10);
            result.addProperty("airMinY", Y + 1);
            result.addProperty("airMaxY", Y + 7);
            boolean safe = false;
            if (box != null) {
                result.addProperty("minX", box.minX);
                result.addProperty("minY", box.minY);
                result.addProperty("minZ", box.minZ);
                result.addProperty("maxX", box.maxX);
                result.addProperty("maxY", box.maxY);
                result.addProperty("maxZ", box.maxZ);
                safe = insidePreflightedAir(
                    player.posX,
                    player.posY,
                    player.posZ,
                    box.minX,
                    box.minY,
                    box.minZ,
                    box.maxX,
                    box.maxY,
                    box.maxZ);
            }
            result.addProperty("insidePreflightedAir", safe);
            return result;
        }

        static boolean insidePreflightedAir(double x, double y, double z, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
            return Double.isFinite(x) && Double.isFinite(y)
                && Double.isFinite(z)
                && Double.isFinite(minX)
                && Double.isFinite(minY)
                && Double.isFinite(minZ)
                && Double.isFinite(maxX)
                && Double.isFinite(maxY)
                && Double.isFinite(maxZ)
                && minX < maxX
                && minY < maxY
                && minZ < maxZ
                && minX >= X - 9
                && maxX <= X + 10
                && minZ >= Z - 9
                && maxZ <= Z + 10
                && minY >= Y + 1
                && maxY <= Y + 7
                && x >= minX
                && x <= maxX
                && y >= minY
                && y < maxY
                && z >= minZ
                && z <= maxZ;
        }

        boolean insideArena(Entity entity) {
            return entity.worldObj == world && Math.abs(entity.posX - X) <= 24 && Math.abs(entity.posZ - Z) <= 24;
        }

        void positionPlayer(EntityPlayerMP player, double x, double y, double z) {
            player.fallDistance = 0;
            player.playerNetServerHandler.setPlayerLocation(x, y, z, 0, 20);
        }

    }

    private static ItemStack tagged(ItemStack item, String label) {
        NBTTagCompound opaque = new NBTTagCompound();
        opaque.setString("Identity", label);
        opaque.setIntArray("Payload", new int[] { 209, 34, 36 });
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("QACombatNetwork", opaque);
        item.setTagCompound(tag);
        return item;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
