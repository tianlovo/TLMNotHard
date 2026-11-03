package com.tianluoqaq.tlmnh.qa;

import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
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
import net.minecraftforge.event.entity.player.PlayerEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 实际压缩player.dat与世界意图跨两个JVM；受控保存/槽位中断不冒充真实网络或任意崩溃原子性 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class AcquisitionPersistenceTests {

    public static final String NAME = "TLMNH_QA_AcquisitionPersistence";
    private static final String RECEIPT = "TLMNHAcquisitionReceipt";
    private static final long START = ManagementFactory.getRuntimeMXBean()
        .getStartTime();

    private AcquisitionPersistenceTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "acquisition_persistence")
    public static void exactReceiptAndCredentialRecoverAcrossJvmWithoutGuessingAnUnmarkedCore(GameTestHelper helper)
        throws Exception {
        String run = System.getProperty("tlmnh.qaAcquisitionRun", "");
        String phase = System.getProperty("tlmnh.qaAcquisitionPhase", "");
        helper.assertTrue(
            run.matches("[0-9]{10,20}") && Boolean.getBoolean("tlmnh.devCommands"),
            "Explicit private run required");
        var world = helper.getWorld();
        var expected = new File(
            System.getProperty("tlmnh.qaAcquisitionRuntime", ""),
            "TLMNH-HorizonQA-acquisition-" + run).getCanonicalFile();
        helper.assertTrue(
            world.getSaveHandler()
                .getWorldDirectory()
                .getCanonicalFile()
                .equals(expected),
            "Wrong private world");
        String version = Loader.instance()
            .getIndexedModList()
            .get("tlmnh")
            .getVersion();
        helper.assertTrue(
            DependencyFingerprint.mod("tlmnh")
                .equals(version + ":sha256:" + System.getProperty("tlmnh.qaExpectedSha")),
            "Loaded artifact differs");
        helper.assertTrue(
            IntegrationGuard.available(Side.SERVER, ExternalFeature.PROGRESSION_RECIPES),
            "Actual progression capability unavailable; no core acquisition transaction was tested");
        var state = (LifecyclePersistenceTests.RawData) world.mapStorage
            .loadData(LifecyclePersistenceTests.RawData.class, NAME);
        if (phase.equals("prepare")) {
            helper.assertTrue(
                state == null && !world.getSaveHandler()
                    .getMapFileFromName(NAME)
                    .exists(),
                "Fresh world required");
            state = new LifecyclePersistenceTests.RawData(NAME);
            state.data.setInteger("Schema", 1);
            state.data.setString("Run", run);
            world.mapStorage.setData(NAME, state);
        } else helper.assertTrue(
            phase.equals("verify") && state != null
                && state.loaded
                && state.data.getString("Run")
                    .equals(run)
                && state.data.getLong("LastProcess") != START,
            "A different JVM must read the fixture");
        boolean oldGift = MaidAcquisitionConfig.giveInitialSlab;
        helper.afterTest(() -> MaidAcquisitionConfig.giveInitialSlab = oldGift);
        MaidAcquisitionConfig.giveInitialSlab = false;
        helper.setBlock(5, 0, 4, Blocks.stone);
        helper.setBlock(5, 1, 4, Blocks.stone);
        var ledger = MaidLifecycleData.get(world);
        for (String kind : new String[] { "gift", "receipt", "bound", "prepared" }) {
            var owner = UUID.nameUUIDFromBytes((run + "/" + kind).getBytes(StandardCharsets.UTF_8));
            var player = player(helper, owner);
            var record = state.data.getCompoundTag(kind);
            if (phase.equals("prepare")) {
                record.setString("Owner", owner.toString());
                prepare(helper, player, ledger, record, kind);
                state.data.setTag(kind, record);
            } else {
                helper.assertTrue(
                    record.getString("Owner")
                        .equals(owner.toString()),
                    "Wrong recorded player identity");
                var loaded = world.getSaveHandler()
                    .getSaveHandler()
                    .readPlayerData(player);
                helper.assertTrue(
                    loaded != null && loaded.getTagList("Inventory", 10)
                        .equals(record.getTagList("NativeInventory", 10)),
                    "Native player.dat did not load the exact saved inventory");
                verify(helper, player, ledger, record, kind);
            }
            record.setTag(
                "NativeInventory",
                nativePlayer(player).getTagList("Inventory", 10)
                    .copy());
            record.setInteger("NativeSelected", nativePlayer(player).getInteger("SelectedItemSlot"));
            state.data.setTag(kind, record);
        }
        state.data.setString("Phase", phase);
        state.data.setLong("LastProcess", START);
        state.markDirty();
        System.out.println(
            "TLMNH_ACQUISITION_PERSISTENCE_PASS phase=" + phase
                + " run="
                + run
                + " processStart="
                + START
                + " schema=5 realNetworkVerified=false arbitraryCrashAtomicityVerified=false");
        helper.succeed();
    }

    private static void prepare(GameTestHelper helper, EntityPlayerMP player, MaidLifecycleData ledger,
        NBTTagCompound record, String kind) throws Exception {
        if (kind.equals("gift")) {
            var saving = new SavingProbe(helper, player, kind);
            MinecraftForge.EVENT_BUS.register(saving);
            MaidAcquisitionConfig.giveInitialSlab = true;
            try {
                helper.assertTrue(!MaidLifecycleEvents.giveInitial(player), "Revoked gift unexpectedly completed");
            } finally {
                MaidAcquisitionConfig.giveInitialSlab = false;
                MinecraftForge.EVENT_BUS.unregister(saving);
            }
            helper.assertTrue(
                saving.triggers == 1 && saving.intentObserved
                    && player.inventory.mainInventory[0].getItem() == Items.gold_ingot
                    && player.inventory.mainInventory[0].stackSize == 17,
                "Native gift saving callback did not retain its exact successor");
            record.setString("Token", saving.token.toString());
            // 明确模拟后续原生退出保存：未知后继留在player.dat，不靠重读旧库存补偿
            player.worldObj.getSaveHandler()
                .getSaveHandler()
                .writePlayerData(player);
        } else {
            var core = new ItemStack(MaidResurrectionContent.MAID_CORE);
            var tag = new NBTTagCompound();
            tag.setIntArray("CorePayload", new int[] { 7, Integer.MIN_VALUE, Integer.MAX_VALUE });
            core.setTagCompound(tag);
            player.inventory.mainInventory[0] = core;
            player.worldObj.getSaveHandler()
                .getSaveHandler()
                .writePlayerData(player);
            if (kind.equals("prepared")) {
                var trap = new BeforeReceiptInventory(player);
                player.inventory = trap;
                helper.assertTrue(
                    activate(helper, player) == MaidLifecycleService.Result.DENIED && trap.token != null
                        && player.inventory.mainInventory[0] == core
                        && !core.getTagCompound()
                            .hasKey(RECEIPT),
                    "Pre-assignment interruption changed the original plain core");
                record.setString("Token", trap.token.toString());
            } else {
                var saving = new SavingProbe(helper, player, kind);
                MinecraftForge.EVENT_BUS.register(saving);
                try {
                    helper.assertTrue(
                        activate(helper, player) == MaidLifecycleService.Result.DENIED,
                        "Native save revocation unexpectedly completed core conversion");
                } finally {
                    MinecraftForge.EVENT_BUS.unregister(saving);
                }
                helper.assertTrue(
                    saving.triggers == 1 && saving.intentObserved && player.inventory.currentItem == 1,
                    "Actual native core saving callback was not reached");
                record.setString("Token", saving.token.toString());
            }
        }
        var token = UUID.fromString(record.getString("Token"));
        String expected = kind.equals("receipt") || kind.equals("prepared") ? "PREPARED" : "ISSUING";
        helper.assertTrue(
            ledger.acquisitionPhase(token)
                .name()
                .equals(expected) && !ledger.delivered(token)
                && ledger.reserve(token, ledger.initialKind(token)) == null,
            "Pending acquisition exposed a redeemable right");
    }

    private static void verify(GameTestHelper helper, EntityPlayerMP player, MaidLifecycleData ledger,
        NBTTagCompound record, String kind) {
        var token = UUID.fromString(record.getString("Token"));
        var before = (NBTTagList) player.inventory.writeToNBT(new NBTTagList())
            .copy();
        MaidAcquisitionConfig.giveInitialSlab = kind.equals("gift");
        new MaidLifecycleEvents().onLogin(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(player));
        MaidAcquisitionConfig.giveInitialSlab = false;
        if (kind.equals("gift")) {
            helper.assertTrue(
                !ledger.delivered(token) && ledger.acquisitionPhase(token)
                    .name()
                    .equals("ISSUING")
                    && before.equals(player.inventory.writeToNBT(new NBTTagList()))
                    && ledger.reserve(token, MaidLifecycleData.Kind.INITIAL) == null,
                "Unknown gift destination was guessed, overwritten or regranted after restart");
        } else if (kind.equals("prepared")) {
            helper.assertTrue(
                before.equals(player.inventory.writeToNBT(new NBTTagList())) && ledger.acquisitionPhase(token)
                    .name()
                    .equals("PREPARED") && !ledger.delivered(token),
                "Plain core was guessed to be the old prepared intent");
            helper.assertTrue(
                activate(helper, player) == MaidLifecycleService.Result.BLOCKED,
                "The separately paid unmarked core could not convert normally");
            var next = MaidLifecycleItems.token(player.inventory.mainInventory[0]);
            helper.assertTrue(
                next != null && !next.equals(token)
                    && ledger.delivered(next)
                    && !ledger.delivered(token)
                    && ledger.reserve(token, MaidLifecycleData.Kind.ACQUIRED) == null,
                "Old unmarked preparation acquired a second redemption right");
            record.setString("NewToken", next.toString());
        } else {
            var slab = player.inventory.mainInventory[0];
            helper.assertTrue(
                token.equals(MaidLifecycleItems.token(slab)) && ledger.delivered(token)
                    && ledger.acquisitionPhase(token)
                        .name()
                        .equals("NONE")
                    && slab.getTagCompound()
                        .getIntArray("CorePayload")[1] == Integer.MIN_VALUE,
                "Accurate physical receipt or slab did not recover the original token and complete payload");
            var received = (NBTTagList) player.inventory.writeToNBT(new NBTTagList())
                .copy();
            new MaidLifecycleEvents()
                .onLogin(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(
                received.equals(player.inventory.writeToNBT(new NBTTagList())),
                "Repeated login duplicated delivery");
        }
        if (!kind.equals("gift")) helper.assertTrue(
            !ledger.delivered(ledger.initialToken(player.getUniqueID())),
            "Paid core consumed the independent free gift");
    }

    private static MaidLifecycleService.Result activate(GameTestHelper helper, EntityPlayerMP player) {
        var at = helper.absolute(5, 0, 4);
        return MaidLifecycleService.activateCore(player, player.getCurrentEquippedItem(), at.x(), at.y(), at.z());
    }

    private static NBTTagCompound nativePlayer(EntityPlayerMP player) throws Exception {
        try (var input = new FileInputStream(
            new File(
                player.worldObj.getSaveHandler()
                    .getWorldDirectory(),
                "playerdata/" + player.getUniqueID() + ".dat"))) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static EntityPlayerMP player(GameTestHelper helper, UUID id) {
        var server = MinecraftServer.getServer();
        var player = new EntityPlayerMP(
            server,
            helper.getWorld(),
            new GameProfile(id, "[QA-Acquire]"),
            new ItemInWorldManager(helper.getWorld()));
        var manager = new NetworkManager(false);
        var channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, channel);
        new NetHandlerPlayServer(server, manager, player);
        player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        var at = helper.absolute(3, 1, 4);
        player.setPosition(at.x() + .5, at.y(), at.z() + .5);
        helper.afterTest(() -> {
            player.setDead();
            channel.finish();
            Object packet;
            while ((packet = channel.readOutbound()) != null) ReferenceCountUtil.release(packet);
            while ((packet = channel.readInbound()) != null) ReferenceCountUtil.release(packet);
        });
        return player;
    }

    public static final class SavingProbe {

        final GameTestHelper helper;
        final EntityPlayerMP player;
        final String kind;
        UUID token;
        int triggers;
        boolean intentObserved;

        SavingProbe(GameTestHelper helper, EntityPlayerMP player, String kind) {
            this.helper = helper;
            this.player = player;
            this.kind = kind;
        }

        @SubscribeEvent
        public void saved(PlayerEvent.SaveToFile event) throws Exception {
            if (event.entityPlayer != player) return;
            var stack = player.inventory.mainInventory[0];
            if (stack == null) return;
            boolean receipt = stack.getItem() == MaidResurrectionContent.MAID_CORE && stack.hasTagCompound()
                && stack.getTagCompound()
                    .hasKey(RECEIPT, 10);
            boolean slab = stack.getItem() == MaidResurrectionContent.INITIAL_SLAB;
            if (kind.equals("receipt") ? !receipt : !slab) return;
            token = receipt ? UUID.fromString(
                stack.getTagCompound()
                    .getCompoundTag(RECEIPT)
                    .getString("Token"))
                : MaidLifecycleItems.token(stack);
            try (var input = new FileInputStream(
                player.worldObj.getSaveHandler()
                    .getMapFileFromName(MaidLifecycleData.NAME))) {
                var data = CompressedStreamTools.readCompressed(input)
                    .getCompoundTag("data");
                int schema = Integer.getInteger("tlmnh.qaAcquisitionSchema", 5);
                helper.assertTrue(
                    (schema == 5 || schema == 6 || schema == 7) && data.getInteger("Schema") == schema,
                    "Wrong explicit acquisition disk schema inside native save");
                var entries = data.getTagList("Entries", 10);
                for (int i = 0; i < entries.tagCount(); i++) if (entries.getCompoundTagAt(i)
                    .getString("Token")
                    .equals(token.toString())) {
                        intentObserved = entries.getCompoundTagAt(i)
                            .getString("AcquisitionPhase")
                            .equals(receipt ? "PREPARED" : "ISSUING");
                    }
            }
            helper.assertTrue(intentObserved, "Player save ran before its durable acquisition intent");
            triggers++;
            if (kind.equals("gift")) player.inventory.mainInventory[0] = new ItemStack(Items.gold_ingot, 17);
            else if (kind.equals("payload")) stack.getTagCompound()
                .setString("CallbackPayload", "callback-owned");
            else player.inventory.currentItem = 1;
        }
    }

    /** 仅在实际原生setter的收据赋值前注入中断，其余布局/写盘均使用目标InventoryPlayer */
    public static final class BeforeReceiptInventory extends InventoryPlayer {

        UUID token;

        BeforeReceiptInventory(EntityPlayerMP owner) {
            super(owner);
            mainInventory = owner.inventory.mainInventory;
            armorInventory = owner.inventory.armorInventory;
            currentItem = owner.inventory.currentItem;
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            if (token == null && stack != null
                && stack.getItem() == MaidResurrectionContent.MAID_CORE
                && stack.hasTagCompound()
                && stack.getTagCompound()
                    .hasKey(RECEIPT, 10)) {
                token = UUID.fromString(
                    stack.getTagCompound()
                        .getCompoundTag(RECEIPT)
                        .getString("Token"));
                throw new IllegalStateException("Controlled interruption before physical core receipt assignment");
            }
            super.setInventorySlotContents(slot, stack);
        }
    }
}
