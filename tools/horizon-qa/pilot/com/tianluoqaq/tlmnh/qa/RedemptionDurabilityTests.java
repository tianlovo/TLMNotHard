package com.tianluoqaq.tlmnh.qa;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidFilmData;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 实际出生、原生保存回调及独立磁盘读取；独立JVM窗口复用既有生命周期runner */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class RedemptionDurabilityTests {

    private static final String WINDOW = "TLMNH_QA_RedemptionPersistence";
    private static final String PERSISTENCE_SELECTOR = "tlmnhqapilot:RedemptionDurabilityTests.unpaidNativeCandidateSurvivesOwnedHardCrashAndPaysOnExactLogin";
    private static final long START = ManagementFactory.getRuntimeMXBean()
        .getStartTime();

    private RedemptionDurabilityTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 1600, batch = "redemption_native_crash")
    public static void unpaidNativeCandidateSurvivesOwnedHardCrashAndPaysOnExactLogin(GameTestHelper helper)
        throws Exception {
        isolateGiftConfiguration(helper);
        String run = System.getProperty("tlmnh.qaPersistenceRun", "");
        String phase = System.getProperty("tlmnh.qaPersistencePhase", "");
        var world = helper.getWorld();
        var expectedWorld = new File(
            System.getProperty("tlmnh.qaPersistenceRuntime", ""),
            "TLMNH-HorizonQA-persistence-" + run).getCanonicalFile();
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands") && run.matches("[0-9]{10,20}")
                && world.getSaveHandler()
                    .getWorldDirectory()
                    .getCanonicalFile()
                    .equals(expectedWorld)
                && world.provider.dimensionId == 0
                && Integer.getInteger("tlmnh.qaPersistenceSchema", 0) >= 6
                && Integer.getInteger("tlmnh.qaPersistenceSchema", 0) <= 8
                && System.getProperty("tlmnh.qaPersistenceContract", "")
                    .equals("durable"),
            "Explicit owned supported-schema crash contract required");
        String version = cpw.mods.fml.common.Loader.instance()
            .getIndexedModList()
            .get("tlmnh")
            .getVersion();
        helper.assertTrue(
            com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint.mod("tlmnh")
                .equals(version + ":sha256:" + System.getProperty("tlmnh.qaExpectedSha")),
            "Loaded artifact differs");
        if (phase.equals("crash-arm")) {
            helper.assertTrue(
                !world.getSaveHandler()
                    .getMapFileFromName(WINDOW)
                    .exists(),
                "Unique redemption window already exists");
            // 新两阶段实验先建立可正常重启的原生世界头，再创建待付费候选；不保存后面的业务窗口
            helper.assertTrue(
                world.getWorldInfo()
                    .isInitialized(),
                "Native QA world initialization has not completed");
            world.getSaveHandler()
                .saveWorldInfo(world.getWorldInfo());
            var levelFile = new File(
                world.getSaveHandler()
                    .getWorldDirectory(),
                "level.dat");
            try (var input = new FileInputStream(levelFile)) {
                var root = CompressedStreamTools.readCompressed(input);
                var level = root.getCompoundTag("Data");
                helper.assertTrue(
                    root.hasKey("Data", 10) && level.hasKey("initialized", 1)
                        && level.getBoolean("initialized")
                        && level.hasKey("RandomSeed", 4)
                        && level.getLong("RandomSeed") == world.getSeed()
                        && level.hasKey("generatorName", 8)
                        && level.getString("generatorName")
                            .equals(
                                world.getWorldInfo()
                                    .getTerrainType()
                                    .getWorldTypeName())
                        && level.hasKey("SpawnX", 3)
                        && level.getInteger("SpawnX") == world.getWorldInfo()
                            .getSpawnX()
                        && level.hasKey("SpawnY", 3)
                        && level.getInteger("SpawnY") == world.getWorldInfo()
                            .getSpawnY()
                        && level.hasKey("SpawnZ", 3)
                        && level.getInteger("SpawnZ") == world.getWorldInfo()
                            .getSpawnZ(),
                    "Native initialized level metadata did not persist before the business window");
            }
            try (var channel = java.nio.channels.FileChannel
                .open(levelFile.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            for (var serverWorld : MinecraftServer.getServer().worldServers)
                if (serverWorld != null) serverWorld.levelSaving = true;
            armNativeWindow(helper, run);
            throw new IllegalStateException(
                "Owned hard-crash window expired without the coordinating runner killing its exact child");
        }
        helper.assertTrue(phase.equals("crash-check"), "Explicit redemption crash phase required");
        try (var input = new FileInputStream(
            world.getSaveHandler()
                .getMapFileFromName(WINDOW))) {
            var state = CompressedStreamTools.readCompressed(input)
                .getCompoundTag("data");
            helper.assertTrue(
                state.getInteger("Schema") == 1 && run.equals(state.getString("Run"))
                    && state.getLong("ProcessStart") != START,
                "A distinct JVM must load the armed native window");
        }
        verifyNativeWindow(helper, run);
        System.out.println("TLMNH_REDEMPTION_RESTART_PASS process_start=" + START + " run=" + run);
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "redemption_durability")
    public static void actualSlabAndShrinePersistOneMaidAndTheirExactCosts(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, UUID.randomUUID(), false);
        var slab = f.slab();
        var token = MaidLifecycleItems.token(slab);
        helper.assertTrue(f.release(slab) == MaidLifecycleService.Result.SUCCESS, "Actual slab release failed");
        helper.assertTrue(
            f.last != null && f.last.isOwner(f.player)
                && f.live() == 1
                && f.player.getCurrentEquippedItem()
                    .getItem() == MaidResurrectionContent.EMPTY_SLAB
                && f.ledger()
                    .reserve(token, MaidLifecycleData.Kind.INITIAL) == null,
            "Slab release did not create one owned maid and consume its sole right");
        helper.assertTrue(
            f.diskEntry(token)
                .getBoolean("Consumed")
                && f.diskEntry(token)
                    .getCompoundTag("Redemption")
                    .hasNoTags(),
            "Successful slab consumption was not durable");
        f.assertPlayerDisk(false);
        var shrine = f.film();
        var filmToken = MaidLifecycleItems.token(shrine.getFilm());
        var revived = MaidLifecycleService.resurrect(f.player, shrine);
        f.diagnose("shrine", filmToken, revived);
        helper.assertTrue(revived == MaidLifecycleService.Result.SUCCESS, "Actual shrine resurrection failed");
        helper.assertTrue(
            f.live() == 2 && shrine.getFilm() == null
                && f.player.getHealth() == .25F
                && f.diskEntry(filmToken)
                    .getBoolean("Consumed"),
            "Shrine entity, film or life cost differs");
        f.assertPlayerDisk(true);
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "redemption_durability")
    public static void safeCancellationRetriesButUnknownMaidSuccessorKeepsItsPersistentBarrier(GameTestHelper helper)
        throws Exception {
        var canceled = new Fixture(helper, UUID.randomUUID(), false);
        var source = canceled.slab();
        var token = MaidLifecycleItems.token(source);
        canceled.cancel = true;
        helper.assertTrue(
            canceled.release(source) == MaidLifecycleService.Result.SPAWN_REJECTED && canceled.ledger()
                .redemption(token)
                .hasNoTags()
                && canceled.live() == 0
                && ItemStack.areItemStacksEqual(source, canceled.player.getCurrentEquippedItem()),
            "Safe canceled spawn did not durably retract before allowing retry");
        canceled.cancel = false;
        helper.assertTrue(
            canceled.release(source) == MaidLifecycleService.Result.SUCCESS && canceled.live() == 1,
            "Safely canceled credential could not retry once");
        helper.getWorld()
            .removeEntity(canceled.last);
        var changed = new Fixture(helper, UUID.randomUUID(), false);
        var other = changed.slab();
        var held = MaidLifecycleItems.token(other);
        changed.rewriteMaid = true;
        helper.assertTrue(
            changed.release(other) == MaidLifecycleService.Result.DENIED && changed.last != null
                && MaidSpawnChecks.present(changed.last, helper.getWorld())
                && "external-successor".equals(changed.last.getCustomNameTag())
                && !changed.diskEntry(held)
                    .getCompoundTag("Redemption")
                    .hasNoTags(),
            "Unknown successor was removed or its durable barrier was cleared");
        int calls = changed.calls;
        helper.assertTrue(
            changed.release(other) != MaidLifecycleService.Result.SUCCESS && changed.calls == calls,
            "Persistent unknown successor admitted another spawn");
        var recovery = new NBTTagList();
        recovery.appendTag(
            com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec.encode(new ItemStack(Items.arrow, 9)));
        changed.last.getMaidInventory()
            .recovery()
            .read(recovery);
        var pendingSource = new NBTTagCompound();
        changed.last.writeToNBT(pendingSource);
        var playerSource = (NBTTagList) changed.player.inventory.writeToNBT(new NBTTagList())
            .copy();
        new com.tianluoqaq.tlmnh.common.protection.runtime.ProtectionCommand()
            .processCommand(changed.player, new String[] { "recover", Integer.toString(changed.last.getEntityId()) });
        var afterClaim = new NBTTagCompound();
        changed.last.writeToNBT(afterClaim);
        helper.assertTrue(
            pendingSource.equals(afterClaim)
                && playerSource.equals(changed.player.inventory.writeToNBT(new NBTTagList()))
                && changed.last.getMaidInventory()
                    .recovery()
                    .size() == 1,
            "Normal recovery command changed an unsettled candidate or delivered its inventory before costs");
        String candidateKey = "redemption/candidate/" + changed.last.getUniqueID();
        var candidateFault = IntegrationGuard.faults(Side.SERVER)
            .get(candidateKey);
        if (candidateFault != null) {
            helper.assertTrue(
                !changed.priorFaults.containsKey(candidateKey) && changed.player.getUniqueID()
                    .equals(candidateFault.owner),
                "Pending recovery fixture collided with an unknown candidate diagnostic");
            changed.controlledFaults.put(candidateKey, candidateFault);
        }
        changed.assertPending(held);
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "redemption_durability")
    public static void nativeCostSaveCallbackPreservesInventoryAndExactExistingTargetCanFinishWithoutRespawn(
        GameTestHelper helper) throws Exception {
        isolateGiftConfiguration(helper);
        var f = new Fixture(helper, UUID.randomUUID(), false);
        var shrine = f.film();
        var token = MaidLifecycleItems.token(shrine.getFilm());
        f.rewritePlayer = true;
        var savedResult = MaidLifecycleService.resurrect(f.player, shrine);
        f.diagnose("save-callback", token, savedResult);
        helper.assertTrue(
            savedResult == MaidLifecycleService.Result.DENIED && f.savedCallbacks == 1
                && f.last != null
                && MaidSpawnChecks.present(f.last, helper.getWorld())
                && f.player.getHealth() == .25F
                && f.player.inventory.mainInventory[5] == f.successor,
            "Actual SaveToFile callback did not retain its successor and the one pending maid");
        f.assertPending(token);
        var beforeTick = new NBTTagCompound();
        f.last.writeToNBT(beforeTick);
        f.last.onUpdate();
        var afterTick = new NBTTagCompound();
        f.last.writeToNBT(afterTick);
        helper.assertTrue(
            beforeTick.equals(afterTick) && !f.player.interactWith(f.last),
            "Unpaid exact candidate advanced a natural tick or allowed normal interaction");
        var attack = new net.minecraftforge.event.entity.player.AttackEntityEvent(f.player, f.last);
        helper.assertTrue(MinecraftForge.EVENT_BUS.post(attack), "Unpaid candidate allowed ordinary player attack");
        var after = (NBTTagList) f.player.inventory.writeToNBT(new NBTTagList())
            .copy();
        int calls = f.calls;
        new MaidLifecycleEvents().onLogin(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(f.player));
        helper.assertTrue(
            f.calls == calls && after.equals(f.player.inventory.writeToNBT(new NBTTagList()))
                && !f.ledger()
                    .redemption(token)
                    .hasNoTags(),
            "Login overwrote unknown callback inventory or respawned");
        // 本夹具明确取回自己刚置入的同对象，恢复原槽；生产从不回写此快照
        helper.assertTrue(f.player.inventory.mainInventory[5] == f.successor, "Fixture successor changed ownership");
        f.player.inventory.mainInventory[5] = f.original.copy();
        new MaidLifecycleEvents().onLogin(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(f.player));
        helper.assertTrue(
            f.calls == calls && f.live() == 1
                && f.ledger()
                    .redemption(token)
                    .hasNoTags()
                && f.diskEntry(token)
                    .getBoolean("Consumed")
                && f.player.getHealth() == .25F,
            "Exact loaded target and repaired physical source did not finish without second spawn or life debit");
        f.assertPlayerDisk(true);
        helper.succeed();
    }

    private static void isolateGiftConfiguration(GameTestHelper helper) {
        boolean previous = com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig.giveInitialSlab;
        com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig.giveInitialSlab = false;
        helper.afterTest(() -> com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig.giveInitialSlab = previous);
    }

    /** 首个JVM停在候选原生存盘之后、代价之前；第二JVM只能恢复同一候选 */
    static UUID armNativeWindow(GameTestHelper helper, String run) throws Exception {
        var f = new Fixture(
            helper,
            UUID.nameUUIDFromBytes((run + "/redemption-window").getBytes(StandardCharsets.UTF_8)),
            true);
        var shrine = f.film();
        var token = MaidLifecycleItems.token(shrine.getFilm());
        helper.getWorld()
            .getSaveHandler()
            .getSaveHandler()
            .writePlayerData(f.player);
        f.windowToken = token;
        f.hardArm = true;
        var replacement = new WindowInventory(f.player, f);
        replacement.readFromNBT(f.player.inventory.writeToNBT(new NBTTagList()));
        replacement.currentItem = f.player.inventory.currentItem;
        f.player.inventory = replacement;
        MaidLifecycleService.resurrect(f.player, shrine);
        return token;
    }

    static void verifyNativeWindow(GameTestHelper helper, String run) throws Exception {
        var actor = UUID.nameUUIDFromBytes((run + "/redemption-window").getBytes(StandardCharsets.UTF_8));
        var f = new Fixture(helper, actor, true);
        var raw = f.diskLedger();
        UUID token = null;
        NBTTagCompound pending = null;
        var entries = raw.getTagList("Entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            var entry = entries.getCompoundTagAt(i);
            if (actor.toString()
                .equals(
                    entry.getCompoundTag("Redemption")
                        .getString("Actor"))) {
                helper.assertTrue(token == null, "Pending actor acquired multiple redemption windows");
                token = UUID.fromString(entry.getString("Token"));
                pending = entry.getCompoundTag("Redemption");
            }
        }
        helper.assertTrue(
            token != null && pending != null
                && pending.getString("Stage")
                    .equals("PREPARED"),
            "Actual restart lost the durable native redemption intent");
        f.assertPending(token);
        // 先核完整来源与精确既有候选，再沿真实登录恢复代价；不自动补造实体
        var film = com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec
            .decode(pending.getCompoundTag("Source"));
        helper.assertTrue(
            MaidLifecycleItems.isFilm(film) && token.equals(MaidLifecycleItems.token(film)),
            "Native restart lost the complete source film");
        helper.getWorld()
            .getSaveHandler()
            .getSaveHandler()
            .readPlayerData(f.player);
        helper.assertTrue(
            f.player.getHealth() == 11,
            "Armed player.dat already paid life before the selected crash window");
        EntityMaid candidate = null;
        for (Object entity : helper.getWorld().loadedEntityList)
            if (entity instanceof EntityMaid maid && pending.getString("Candidate")
                .equals(
                    maid.getUniqueID()
                        .toString()))
                candidate = maid;
        helper.assertTrue(
            candidate != null && MaidLifecycleService.isPendingRedemption(candidate),
            "Unpaid native candidate did not load behind its pending barrier");
        f.last = candidate;
        var beforeTick = new NBTTagCompound();
        candidate.writeToNBT(beforeTick);
        candidate.onUpdate();
        var afterTick = new NBTTagCompound();
        candidate.writeToNBT(afterTick);
        helper.assertTrue(beforeTick.equals(afterTick), "Pending loaded candidate advanced before its cost recovery");
        new MaidLifecycleEvents().onLogin(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(f.player));
        helper.assertTrue(
            f.calls == 0 && f.player.getHealth() == .25F
                && f.ledger()
                    .redemption(token)
                    .hasNoTags()
                && f.diskEntry(token)
                    .getBoolean("Consumed")
                && !MaidLifecycleService.isPendingRedemption(candidate),
            "Exact native restart did not pay the original life cost and release the same candidate without spawn");
        f.assertPlayerDisk(true);
        f.tracePersistenceState("settled");
    }

    private static final class WindowInventory extends net.minecraft.entity.player.InventoryPlayer {

        private final Fixture fixture;

        WindowInventory(EntityPlayerMP player, Fixture fixture) {
            super(player);
            this.fixture = fixture;
        }

        @Override
        public NBTTagList writeToNBT(NBTTagList output) {
            var result = super.writeToNBT(output);
            if (fixture.hardArm && fixture.last != null) {
                try {
                    var pending = fixture.ledger()
                        .redemption(fixture.windowToken);
                    if (pending.getString("Stage")
                        .equals("PREPARED") && fixture.nativeCandidate(pending)) {
                        fixture.hardArm = false;
                        fixture.announceUnpaidWindow(pending);
                        // 保持实际调用栈窗口，不返回伪造成功；超时正常抛出并允许生产安全撤回
                        long deadline = System.nanoTime() + 60_000_000_000L;
                        while (System.nanoTime() < deadline) Thread.sleep(20);
                        throw new IllegalStateException("Controlled unpaid redemption window was not hard-killed");
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot arm exact native redemption crash window", failure);
                }
            }
            return result;
        }
    }

    public static final class Fixture {

        final GameTestHelper helper;
        final EntityPlayerMP player;
        final List<EntityMaid> owned = new ArrayList<>();
        final int x, y, z;
        final boolean persistent;
        final boolean originalAnimals;
        final ItemStack original = new ItemStack(Items.stick, 3);
        final java.util.Map<String, IntegrationGuard.Fault> priorFaults = IntegrationGuard.faults(Side.SERVER);
        final java.util.Map<String, IntegrationGuard.Fault> controlledFaults = new java.util.HashMap<>();
        EntityMaid last;
        ItemStack successor;
        int calls, savedCallbacks;
        boolean cancel, rewriteMaid, rewritePlayer;
        boolean hardArm;
        UUID windowToken;

        Fixture(GameTestHelper helper, UUID profile, boolean persistent) {
            this.helper = helper;
            this.persistent = persistent;
            if (persistent) helper.assertTrue(
                PERSISTENCE_SELECTOR.equals(System.getProperty("horizonqa.tests", "")),
                "Persistent animal-update override requires its single selector in the owned child JVM");
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = persistent ? 190 : origin.y();
            z = origin.z();
            if (!persistent || !System.getProperty("tlmnh.qaPersistencePhase", "")
                .equals("crash-check")) for (int a = 3; a <= 10; a++) for (int c = 2; c <= 6; c++)
                    helper.getWorld()
                        .setBlock(x + a, y, z + c, Blocks.stone);
            var server = MinecraftServer.getServer();
            originalAnimals = server.getCanSpawnAnimals();
            server.setCanSpawnAnimals(true);
            player = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(profile, "[QA-Redeem]"),
                new ItemInWorldManager(helper.getWorld()));
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, channel);
            new NetHandlerPlayServer(server, manager, player);
            player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            player.setPosition(x + 5.5, y + 1, z + 4.5);
            player.inventory.mainInventory[5] = original.copy();
            player.setHealth(11);
            MinecraftForge.EVENT_BUS.register(this);
            helper.afterTest(() -> {
                MinecraftForge.EVENT_BUS.unregister(this);
                if (persistent) tracePersistenceState("cleanup-before");
                try {
                    if (!persistent) for (var maid : owned) {
                        var actual = maid.worldObj.getEntityByID(maid.getEntityId());
                        helper.assertTrue(
                            actual == null || actual == maid,
                            "QA cleanup refused an unknown entity successor");
                        maid.worldObj.removePlayerEntityDangerously(maid);
                    }
                    player.setDead();
                    channel.finish();
                    Object packet;
                    while ((packet = channel.readOutbound()) != null) ReferenceCountUtil.release(packet);
                    while ((packet = channel.readInbound()) != null) ReferenceCountUtil.release(packet);
                } finally {
                    // 独立子JVM必须保留true直到原生关服保存；CI原值false会在下一tick直接杀死EntityAnimal
                    if (!persistent) server.setCanSpawnAnimals(originalAnimals);
                    if (persistent) tracePersistenceState("cleanup-after");
                    isolateControlledFaults();
                }
            });
        }

        void tracePersistenceState(String stage) {
            var state = new com.google.gson.JsonObject();
            state.addProperty("stage", stage);
            state.addProperty("phase", System.getProperty("tlmnh.qaPersistencePhase", ""));
            state.addProperty("originalCanSpawnAnimals", originalAnimals);
            state.addProperty(
                "currentCanSpawnAnimals",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
            if (last != null) {
                state.addProperty(
                    "candidate",
                    last.getUniqueID()
                        .toString());
                state.addProperty("present", MaidSpawnChecks.present(last, helper.getWorld()));
                state.addProperty("dead", last.isDead);
                state.addProperty("writeToNBTOptional", last.writeToNBTOptional(new NBTTagCompound()));
            }
            System.out.println("TLMNH_REDEMPTION_PERSISTENCE_STATE " + state);
        }

        MaidLifecycleData ledger() {
            return MaidLifecycleData.get(helper.getWorld());
        }

        ItemStack slab() {
            var token = ledger().initialToken(player.getUniqueID());
            ledger().markDelivered(token);
            var slab = MaidLifecycleItems.credential(MaidResurrectionContent.INITIAL_SLAB, token, player.getUniqueID());
            slab.getTagCompound()
                .setString("ForeignPayload", "source-kept");
            player.inventory.mainInventory[0] = slab;
            return slab;
        }

        MaidLifecycleService.Result release(ItemStack source) {
            var result = MaidLifecycleService.releaseInitial(player, source, x + 4, y, z + 4);
            diagnose(cancel ? "cancel" : "slab", MaidLifecycleItems.token(source), result);
            return result;
        }

        void diagnose(String scenario, UUID token, MaidLifecycleService.Result outcome) {
            if (last == null || token == null
                || ledger().redemption(token)
                    .hasNoTags())
                return;
            try {
                var expected = ledger().redemption(token)
                    .getCompoundTag("Maid");
                var actual = new NBTTagCompound();
                actual.setString("id", net.minecraft.entity.EntityList.getEntityString(last));
                last.writeToNBT(actual);
                actual = (NBTTagCompound) actual.copy();
                var repeated = new NBTTagCompound();
                repeated.setString("id", net.minecraft.entity.EntityList.getEntityString(last));
                last.writeToNBT(repeated);
                var keys = new java.util.TreeSet<String>();
                for (Object key : expected.func_150296_c()) keys.add((String) key);
                for (Object key : actual.func_150296_c()) keys.add((String) key);
                var changes = new com.google.gson.JsonArray();
                for (var key : keys) if (!java.util.Objects.equals(expected.getTag(key), actual.getTag(key))) {
                    var row = new com.google.gson.JsonObject();
                    row.addProperty("key", key);
                    row.addProperty(
                        "expectedType",
                        expected.hasKey(key) ? expected.getTag(key)
                            .getId() : -1);
                    row.addProperty(
                        "actualType",
                        actual.hasKey(key) ? actual.getTag(key)
                            .getId() : -1);
                    changes.add(row);
                }
                var report = new com.google.gson.JsonObject();
                report.addProperty("scenario", scenario);
                report.addProperty("result", outcome.name());
                report.addProperty("sameWorld", last.worldObj == helper.getWorld());
                report.addProperty("currentEntityId", last.getEntityId());
                report.addProperty("dead", last.isDead);
                report.addProperty(
                    "registeredSameObject",
                    helper.getWorld()
                        .getEntityByID(last.getEntityId()) == last);
                report.addProperty(
                    "registeredAbsent",
                    helper.getWorld()
                        .getEntityByID(last.getEntityId()) == null);
                report.addProperty("actualStableAcrossTwoWrites", actual.equals(repeated));
                report.add("changedKeys", changes);
                report.add("expected", SleepAttributePortSnapshot.binaryNbt(expected));
                report.add("actual", SleepAttributePortSnapshot.binaryNbt(actual));
                report.add("actualRepeated", SleepAttributePortSnapshot.binaryNbt(repeated));
                var directory = java.nio.file.Paths.get(System.getProperty("horizonqa.reportDir"))
                    .toAbsolutePath()
                    .normalize();
                var file = directory
                    .resolve("redemption-" + scenario + "-" + token + "-" + System.nanoTime() + ".json");
                java.nio.file.Files.write(
                    file,
                    report.toString()
                        .getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
                System.out.println(
                    "TLMNH_REDEMPTION_NBT_DIAGNOSTIC file=" + file.getFileName()
                        + " result="
                        + outcome
                        + " changedKeys="
                        + changes
                        + " actualStable="
                        + actual.equals(repeated));
            } catch (Exception | LinkageError | AssertionError diagnosticFailure) {
                // 诊断失败不能遮蔽随后保留的原业务断言
                System.out.println("TLMNH_REDEMPTION_NBT_DIAGNOSTIC_FAILED " + diagnosticFailure);
            }
        }

        MaidShrineTile film() {
            player.inventory.mainInventory[0] = null;
            helper.getWorld()
                .setBlock(x + 7, y, z + 4, MaidResurrectionContent.SHRINE);
            var shrine = (MaidShrineTile) helper.getWorld()
                .getTileEntity(x + 7, y, z + 4);
            var originalMaid = new EntityMaid(helper.getWorld());
            helper.assertTrue(originalMaid.bindTo(player.getUniqueID()), "Film fixture owner binding failed");
            var saved = new NBTTagCompound();
            originalMaid.writeToNBT(saved);
            var identity = MaidFilmData.identity(saved);
            var token = ledger().newFilm(player.getUniqueID(), originalMaid.getUniqueID(), identity);
            helper.assertTrue(
                shrine.insert(MaidLifecycleItems.credential(MaidResurrectionContent.FILM, token, player.getUniqueID())),
                "Actual shrine insertion failed");
            return shrine;
        }

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (event.world != helper.getWorld() || !(event.entity instanceof EntityMaid maid) || !maid.isOwner(player))
                return;
            calls++;
            last = maid;
            owned.add(maid);
            if (cancel) event.setCanceled(true);
            if (rewriteMaid) maid.setCustomNameTag("external-successor");
        }

        @SubscribeEvent
        public void saved(PlayerEvent.SaveToFile event) {
            if (!rewritePlayer || event.entityPlayer != player) return;
            rewritePlayer = false;
            savedCallbacks++;
            successor = new ItemStack(Items.gold_ingot, 17);
            successor.setStackDisplayName("callback-owned");
            player.inventory.mainInventory[5] = successor;
        }

        int live() {
            int count = 0;
            for (var maid : owned) if (MaidSpawnChecks.present(maid, helper.getWorld())) count++;
            return count;
        }

        NBTTagCompound diskLedger() throws Exception {
            try (var input = new FileInputStream(
                helper.getWorld()
                    .getSaveHandler()
                    .getMapFileFromName(MaidLifecycleData.NAME))) {
                return CompressedStreamTools.readCompressed(input)
                    .getCompoundTag("data");
            }
        }

        NBTTagCompound diskEntry(UUID token) throws Exception {
            var entries = diskLedger().getTagList("Entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) if (token.toString()
                .equals(
                    entries.getCompoundTagAt(i)
                        .getString("Token")))
                return entries.getCompoundTagAt(i);
            throw new IllegalStateException("Missing actual disk redemption entry");
        }

        boolean nativeCandidate(NBTTagCompound pending) throws Exception {
            if (!(helper
                .getWorld().theChunkProviderServer.currentChunkLoader instanceof net.minecraft.world.chunk.storage.AnvilChunkLoader loader))
                throw new IllegalStateException("Actual native chunk loader required");
            try (var input = net.minecraft.world.chunk.storage.RegionFileCache.getChunkInputStream(
                loader.chunkSaveLocation,
                (int) Math.floor(last.posX) >> 4,
                (int) Math.floor(last.posZ) >> 4)) {
                if (input == null) return false;
                var list = CompressedStreamTools.read(input)
                    .getCompoundTag("Level")
                    .getTagList("Entities", 10);
                int count = 0;
                for (int i = 0; i < list.tagCount(); i++) if (list.getCompoundTagAt(i)
                    .equals(pending.getCompoundTag("Maid"))) count++;
                return count == 1;
            }
        }

        void announceUnpaidWindow(NBTTagCompound pending) throws Exception {
            tracePersistenceState("unpaid-window");
            helper.assertTrue(
                player.getHealth() == 11 && pending.getString("Stage")
                    .equals("PREPARED")
                    && diskEntry(windowToken).getCompoundTag("Redemption")
                        .equals(pending),
                "Native candidate was not durable before the unpaid original source");
            String run = System.getProperty("tlmnh.qaPersistenceRun");
            var state = new NBTTagCompound();
            state.setInteger("Schema", 1);
            state.setString("Run", run);
            state.setLong("ProcessStart", START);
            state.setString("Token", windowToken.toString());
            state.setTag("Intent", pending.copy());
            var root = new NBTTagCompound();
            root.setTag("data", state);
            // 只写本夹具元数据，不save-all世界，也不改生产事务或玩家数据
            var buffer = new java.io.ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, buffer);
            try (var output = new FileOutputStream(
                helper.getWorld()
                    .getSaveHandler()
                    .getMapFileFromName(WINDOW))) {
                output.write(buffer.toByteArray());
                output.getFD()
                    .sync();
            }
            var marker = new com.google.gson.JsonObject();
            marker.addProperty("run", run);
            marker.addProperty("process_start", START);
            marker.addProperty(
                "world",
                helper.getWorld()
                    .getSaveHandler()
                    .getWorldDirectory()
                    .getCanonicalPath());
            marker.addProperty("cwd", new File(".").getCanonicalPath());
            marker.addProperty("contract", "durable");
            marker.addProperty("memory_hold", true);
            marker.addProperty("disk_hold", true);
            marker.addProperty("redemption_pending", true);
            marker.addProperty("maid_on_disk", true);
            marker.addProperty("redemption_token", windowToken.toString());
            marker.addProperty("arbitrary_crash_atomicity_verified", false);
            System.out.println("TLMNH_LIFECYCLE_PERSISTENCE_CRASH_ARMED " + marker);
        }

        void assertPending(UUID token) throws Exception {
            var raw = diskLedger();
            helper.assertTrue(
                raw.getInteger("Schema") == Integer.getInteger("tlmnh.qaPersistenceSchema", 8)
                    && !diskEntry(token).getBoolean("Consumed")
                    && !diskEntry(token).getCompoundTag("Redemption")
                        .hasNoTags(),
                "Actual disk lost the pending barrier");
            var copy = new MaidLifecycleData("[QA-Redemption-Read]");
            copy.readFromNBT(raw);
            helper.assertTrue(
                copy.reserve(token, MaidLifecycleData.Kind.FILM) == null
                    && copy.reserve(token, MaidLifecycleData.Kind.INITIAL) == null,
                "Independent parsed ledger admitted a second redemption");
            String key = "redemption/" + token;
            var fault = IntegrationGuard.faults(Side.SERVER)
                .get(key);
            if (fault != null) {
                helper.assertTrue(
                    !priorFaults.containsKey(key) && player.getUniqueID()
                        .equals(fault.owner),
                    "Controlled redemption diagnostic collided with an existing fault");
                controlledFaults.put(key, fault);
            }
        }

        @SuppressWarnings("unchecked")
        void isolateControlledFaults() {
            synchronized (IntegrationGuard.class) {
                try {
                    var field = IntegrationGuard.class.getDeclaredField("FAULTS");
                    field.setAccessible(true);
                    var all = (java.util.Map<Side, java.util.Map<String, IntegrationGuard.Fault>>) field.get(null);
                    var server = all.get(Side.SERVER);
                    for (var item : controlledFaults.entrySet()) {
                        helper.assertTrue(
                            server != null && server.get(item.getKey()) == item.getValue(),
                            "Cleanup refused an unknown diagnostic successor");
                        server.remove(item.getKey());
                    }
                    var actual = IntegrationGuard.faults(Side.SERVER);
                    for (var item : priorFaults.entrySet()) helper.assertTrue(
                        actual.get(item.getKey()) == item.getValue(),
                        "Cleanup changed a pre-existing fault object");
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot isolate the exact controlled redemption fault", failure);
                }
            }
        }

        void assertPlayerDisk(boolean health) throws Exception {
            assertPlayerDisk(health, true);
        }

        void assertPlayerDisk(boolean health, boolean sameInventory) throws Exception {
            var file = new File(
                helper.getWorld()
                    .getSaveHandler()
                    .getWorldDirectory(),
                "playerdata/" + player.getUniqueID() + ".dat");
            try (var input = new FileInputStream(file)) {
                var saved = CompressedStreamTools.readCompressed(input);
                helper.assertTrue(
                    (!sameInventory || saved.getTagList("Inventory", 10)
                        .equals(player.inventory.writeToNBT(new NBTTagList())))
                        && (!health || saved.hasKey("HealF", 5) && saved.getFloat("HealF") == .25F),
                    "Native player.dat differs from the exact inventory or life cost");
            }
        }
    }
}
