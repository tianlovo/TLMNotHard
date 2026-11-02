package com.tianluoqaq.tlmnh.qa;

import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathExperience;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneTransfer;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 四个独立JVM读取真实世界文件；旧schema是明确构造的磁盘夹具，不代表运行旧二进制 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class LifecyclePersistenceTests {

    public static final String NAME = "TLMNH_QA_LifecyclePersistence";
    private static final long START = ManagementFactory.getRuntimeMXBean()
        .getStartTime();

    private LifecyclePersistenceTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 1200, batch = "lifecycle_persistence")
    public static void nativeHeldEscrowAndLegacyFilesSurviveIndependentProcesses(GameTestHelper helper)
        throws Exception {
        String run = System.getProperty("tlmnh.qaPersistenceRun", "");
        String phase = System.getProperty("tlmnh.qaPersistencePhase", "");
        var world = helper.getWorld();
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands") && run.matches("[0-9]{10,20}"),
            "Explicit private persistence run required");
        var expectedWorld = new File(
            System.getProperty("tlmnh.qaPersistenceRuntime", ""),
            "TLMNH-HorizonQA-persistence-" + run).getCanonicalFile();
        helper.assertTrue(
            world.provider.dimensionId == 0 && world.getSaveHandler()
                .getWorldDirectory()
                .getCanonicalFile()
                .equals(expectedWorld),
            "Unexpected world or owned runtime");
        String version = Loader.instance()
            .getIndexedModList()
            .get("tlmnh")
            .getVersion();
        helper.assertTrue(
            DependencyFingerprint.mod("tlmnh")
                .equals(version + ":sha256:" + System.getProperty("tlmnh.qaExpectedSha")),
            "Loaded artifact differs");
        MinecraftServer.getServer()
            .setCanSpawnAnimals(true);
        var state = (RawData) world.mapStorage.loadData(RawData.class, NAME);
        if (phase.equals("prepare")) {
            helper.assertTrue(
                state == null && !world.getSaveHandler()
                    .getMapFileFromName(NAME)
                    .exists(),
                "Preparation requires a new unique world");
            state = new RawData(NAME);
            state.data.setInteger("Schema", 1);
            state.data.setString("Run", run);
            state.data.setString(
                "Owner",
                UUID.nameUUIDFromBytes(run.getBytes(StandardCharsets.UTF_8))
                    .toString());
            state.data.setInteger("X", world.getSpawnPoint().posX + 96);
            state.data.setInteger("Z", world.getSpawnPoint().posZ + 96);
            world.mapStorage.setData(NAME, state);
        } else helper.assertTrue(
            state != null && state.loaded
                && state.data.getString("Run")
                    .equals(run)
                && state.data.getLong("LastProcess") != START,
            "A new JVM must load the same fixture from disk");
        var f = new Fixture(helper, state);
        switch (phase) {
            case "prepare" -> prepare(f);
            case "verify" -> verify(f);
            case "crash-arm" -> {
                armCrash(f, expectedWorld);
                helper.succeedWhen(() -> false);
                return;
            }
            case "crash-check" -> checkCrash(f);
            default -> throw new IllegalArgumentException("Explicit persistence phase required");
        }
        checkpoint(state, phase);
        System.out.println(
            "TLMNH_LIFECYCLE_PERSISTENCE_PASS phase=" + phase
                + " run="
                + run
                + " processStart="
                + START
                + " arbitraryCrashAtomicityVerified=false playerDatVerified=false");
        helper.succeed();
    }

    private static void prepare(Fixture f) {
        for (int dx = -3; dx < 32; dx++) for (int dz = -3; dz < 4; dz++) {
            f.helper.assertTrue(f.world.isAirBlock(f.x + dx, 190, f.z + dz), "Fixture overlaps existing blocks");
            f.world.setBlock(f.x + dx, 190, f.z + dz, Blocks.stone);
        }
        var identity = new NBTTagCompound();
        identity.setString("IdentityMarker", f.run);
        identity.setTag("NestedIdentity", payload("identity"));
        f.state.data.setTag("Identity", identity);
        var held = stack(f.run, "held");
        var item = f.deposit("ItemToken", held, identity, 0, 1);
        var grave = f.grave(item, 1);
        f.state.data.setString(
            "Grave",
            grave.getUniqueID()
                .toString());
        f.full(grave);
        f.itemMode = "rewrite";
        f.itemMarker = f.run + "/held";
        f.helper.assertTrue(
            !MaidTombstoneTransfer.collect(grave, f.owner) && f.itemCalls == 1
                && f.itemDrop != null
                && f.ledger.isTombstoneHeld(item),
            "Actual rewritten overflow did not hold");
        f.state.data.setString(
            "ExternalItem",
            f.itemDrop.getUniqueID()
                .toString());
        f.state.data.setTag(
            "ChangedItem",
            f.itemDrop.getEntityItem()
                .writeToNBT(new NBTTagCompound()));
        f.state.data.setTag("OriginalItem", held.writeToNBT(new NBTTagCompound()));
        f.state.data.setTag("HeldItems", entries(held));
        f.itemMode = "";
        f.xpX = f.x + 9.5;
        var xp = f.deposit("XpToken", null, identity, 53, 9);
        MaidDeathExperience.retryPending(f.world);
        f.helper.assertTrue(
            f.xpCalls == 1 && f.xpOrb != null
                && f.xpOrb.xpValue == EntityXPOrb.getXPSplit(53) + 1
                && f.ledger.isDeathExperienceHeld(xp)
                && f.ledger.remainingDeathExperience(xp) == 53,
            "Actual rewritten native XP did not retain its complete held balance");
        f.state.data.setString(
            "ExternalXp",
            f.xpOrb.getUniqueID()
                .toString());
        var retryStack = stack(f.run, "retry");
        var retry = f.deposit("RetryToken", retryStack, identity, 0, 17);
        var retryGrave = f.grave(retry, 17);
        f.state.data.setString(
            "RetryGrave",
            retryGrave.getUniqueID()
                .toString());
        f.state.data.setTag("RetryItem", retryStack.writeToNBT(new NBTTagCompound()));
        f.full(retryGrave);
        f.itemMode = "cancel";
        f.itemMarker = f.run + "/retry";
        int before = f.itemCalls;
        f.helper.assertTrue(
            !MaidTombstoneTransfer.collect(retryGrave, f.owner) && f.itemCalls == before + 1
                && !f.ledger.isTombstoneHeld(retry),
            "Safely canceled overflow became permanently held");
        f.itemMode = "";
        for (int schema = 1; schema <= 3; schema++) legacyFile(f, schema, identity, held);
        heldState(f, false);
    }

    private static void verify(Fixture f) {
        f.helper.assertTrue(
            f.state.data.getString("Phase")
                .equals("prepare"),
            "Expected prepared disk fixture");
        heldState(f, false);
        var item = f.token("ItemToken");
        var xp = f.token("XpToken");
        f.helper.assertTrue(
            IntegrationGuard.available(Side.SERVER, "player_inventory/tombstone/" + item)
                && IntegrationGuard.available(Side.SERVER, "death_experience/" + xp),
            "Prior JVM fault leaked into restart");
        var external = (EntityItem) f.find("ExternalItem", EntityItem.class);
        f.helper.assertTrue(
            f.state.data.getCompoundTag("ChangedItem")
                .equals(
                    external.getEntityItem()
                        .writeToNBT(new NBTTagCompound())),
            "Changed external item NBT did not persist");
        var orb = (EntityXPOrb) f.find("ExternalXp", EntityXPOrb.class);
        f.helper.assertTrue(orb.xpValue == EntityXPOrb.getXPSplit(53) + 1, "External native XP did not persist");
        var grave = (MaidTombstoneEntity) f.find("Grave", MaidTombstoneEntity.class);
        f.full(grave);
        f.itemMarker = f.run + "/held";
        f.xpX = f.x + 9.5;
        f.helper.assertTrue(!MaidTombstoneTransfer.collect(grave, f.owner), "Persistent held claim delivered again");
        MaidDeathExperience.retryPending(f.world);
        f.helper.assertTrue(f.itemCalls == 0 && f.xpCalls == 0, "Persistent hold reached a second spawn callback");
        f.helper.assertTrue(
            f.ledger.reserve(item, MaidLifecycleData.Kind.FILM)
                .equals(f.state.data.getCompoundTag("Identity")),
            "Item hold blocked or changed independent film identity");
        f.ledger.commit(item);
        f.helper.assertTrue(
            f.ledger.reserve(item, MaidLifecycleData.Kind.FILM) == null,
            "Independent film credential committed twice");
        var retryGrave = (MaidTombstoneEntity) f.find("RetryGrave", MaidTombstoneEntity.class);
        f.empty(retryGrave);
        f.helper
            .assertTrue(MaidTombstoneTransfer.collect(retryGrave, f.owner), "Safe prior rejection was not retryable");
        f.helper.assertTrue(
            f.state.data.getCompoundTag("RetryItem")
                .equals(f.owner.inventory.mainInventory[0].writeToNBT(new NBTTagCompound()))
                && f.ledger.tombstoneEmpty(f.token("RetryToken"))
                && !MaidTombstoneTransfer.collect(retryGrave, f.owner),
            "Restart retry changed full item NBT or duplicated delivery");
        for (int schema = 1; schema <= 3; schema++) migrate(f, schema);
        heldState(f, true);
    }

    private static void armCrash(Fixture f, File expectedWorld) throws Exception {
        f.helper.assertTrue(
            f.state.data.getString("Phase")
                .equals("verify"),
            "Expected normal restart checkpoint");
        heldState(f, true);
        migratedFiles(f);
        var window = stack(f.run, "window");
        var token = f.deposit("WindowToken", window, f.state.data.getCompoundTag("Identity"), 0, 25);
        var grave = f.grave(token, 25);
        f.state.data.setString(
            "WindowGrave",
            grave.getUniqueID()
                .toString());
        f.state.data.setTag("WindowItem", window.writeToNBT(new NBTTagCompound()));
        checkpoint(f.state, "crash-baseline");
        f.world.saveAllChunks(true, null);
        f.world.saveChunkData();
        f.world.getSaveHandler()
            .flush();
        f.helper.assertTrue(
            !diskEntry(f.world, MaidLifecycleData.NAME, token).getBoolean("TombstoneHeld"),
            "Unheld baseline was not genuinely saved");
        // 禁止本专用JVM自动保存世界账本；以下独立chunk保存走与原生unload相同的safeSaveChunk路径
        for (var world : MinecraftServer.getServer().worldServers) if (world != null) world.levelSaving = true;
        f.full(grave);
        f.itemMode = "rewrite";
        f.itemMarker = f.run + "/window";
        f.helper.assertTrue(
            !MaidTombstoneTransfer.collect(grave, f.owner) && f.itemCalls == 1
                && f.ledger.isTombstoneHeld(token)
                && f.itemDrop != null,
            "Unsaved real callback fault was not reached");
        f.state.data.setString("UnsavedMarker", f.run);
        checkpoint(f.state, "crash-armed");
        f.world.getChunkFromBlockCoords((int) Math.floor(f.itemDrop.posX), (int) Math.floor(f.itemDrop.posZ))
            .setChunkModified();
        f.helper.assertTrue(
            f.world.getChunkProvider()
                .saveChunks(true, null),
            "Independent native chunk save failed");
        f.world.getSaveHandler()
            .flush();
        var persistedDrop = diskEntity(f.world, f.itemDrop);
        f.helper.assertTrue(
            persistedDrop.getCompoundTag("Item")
                .equals(
                    f.itemDrop.getEntityItem()
                        .writeToNBT(new NBTTagCompound())),
            "Raw MCA does not contain the exact rewritten external drop");
        f.helper.assertTrue(
            !diskEntry(f.world, MaidLifecycleData.NAME, token).getBoolean("TombstoneHeld")
                && !diskData(f.world, NAME).hasKey("UnsavedMarker"),
            "Unsaved window was accidentally flushed");
        var marker = new JsonObject();
        marker.addProperty("run", f.run);
        marker.addProperty("process_start", START);
        marker.addProperty("world", expectedWorld.getCanonicalPath());
        marker.addProperty("cwd", new File(".").getCanonicalPath());
        marker.addProperty("window_token", token.toString());
        marker.addProperty(
            "window_entity",
            f.itemDrop.getUniqueID()
                .toString());
        marker.addProperty("disk_hold", false);
        marker.addProperty("memory_hold", true);
        marker.addProperty("external_drop_on_disk", true);
        marker.addProperty("storage_path", "ChunkProviderServer.saveChunks(true)+AnvilSaveHandler.flush+rawMCA");
        marker.addProperty("arbitrary_crash_atomicity_verified", false);
        System.out.println("TLMNH_LIFECYCLE_PERSISTENCE_CRASH_ARMED " + marker);
    }

    private static void checkCrash(Fixture f) {
        f.helper.assertTrue(
            f.state.data.getString("Phase")
                .equals("crash-baseline") && !f.state.data.hasKey("UnsavedMarker"),
            "Crash ran a graceful save or reused in-memory state");
        heldState(f, true);
        migratedFiles(f);
        var token = f.token("WindowToken");
        var items = f.ledger.reserveTombstone(token);
        boolean redeemable = items != null;
        var expectedItems = entries(ItemStack.loadItemStackFromNBT(f.state.data.getCompoundTag("WindowItem")));
        if (redeemable)
            f.helper.assertTrue(items.equals(expectedItems), "Last durable escrow was not restored exactly");
        f.ledger.rollback(token);
        var externalId = UUID.fromString(System.getProperty("tlmnh.qaPersistenceExternal", ""));
        var external = (EntityItem) f.find(externalId, EntityItem.class);
        var expected = ItemStack.loadItemStackFromNBT(f.state.data.getCompoundTag("WindowItem"));
        expected.stackSize++;
        f.helper.assertTrue(
            ItemStack.areItemStacksEqual(expected, external.getEntityItem()),
            "Persisted external item lost its full rewritten NBT");
        var observed = new JsonObject();
        observed.addProperty("run", f.run);
        observed.addProperty("process_start", START);
        observed.addProperty("window_token", token.toString());
        observed.addProperty("external_entity", externalId.toString());
        observed.addProperty("external_drop_on_disk", true);
        observed.addProperty("escrow_redeemable", redeemable);
        observed.addProperty("duplicate_delivery_risk", redeemable);
        observed.addProperty("claim_executed", false);
        System.out.println("TLMNH_LIFECYCLE_PERSISTENCE_WINDOW_OBSERVED " + observed);
        f.helper.assertTrue(
            !redeemable,
            "Material conservation failure: persisted external count10 coexists with redeemable original count9 escrow");
    }

    private static void heldState(Fixture f, boolean filmConsumed) {
        var item = f.token("ItemToken");
        var xp = f.token("XpToken");
        var raw = new NBTTagCompound();
        f.ledger.writeToNBT(raw);
        var itemEntry = entry(raw, item);
        f.helper.assertTrue(
            raw.getInteger("Schema") == 4 && f.ledger.isTombstoneHeld(item)
                && !f.ledger.isDeathExperienceHeld(item)
                && f.ledger.reserveTombstone(item) == null
                && itemEntry.getTagList("TombstoneItems", 10)
                    .equals(f.state.data.getTagList("HeldItems", 10))
                && itemEntry.getCompoundTag("Identity")
                    .equals(f.state.data.getCompoundTag("Identity")),
            "Persistent item hold lost original full items, identity or domain isolation");
        f.helper.assertTrue(
            f.ledger.isDeathExperienceHeld(xp) && !f.ledger.isTombstoneHeld(xp)
                && f.ledger.remainingDeathExperience(xp) == 53
                && f.ledger.reserveDeathExperience(xp) == 0
                && !f.ledger.pendingDeathExperience(f.world.provider.dimensionId, Long.MAX_VALUE)
                    .contains(xp),
            "Persistent XP hold lost its balance or publication exclusion");
        f.helper.assertTrue(
            (f.ledger.reserve(item, MaidLifecycleData.Kind.FILM) == null) == filmConsumed,
            "Independent item film consumption differs");
        f.ledger.rollback(item);
        f.helper.assertTrue(
            f.ledger.reserve(xp, MaidLifecycleData.Kind.FILM)
                .equals(f.state.data.getCompoundTag("Identity")),
            "XP hold blocked independent film identity");
        f.ledger.rollback(xp);
    }

    private static void legacyFile(Fixture f, int schema, NBTTagCompound identity, ItemStack item) {
        var ledger = new MaidLifecycleData(legacyName(schema));
        var initial = ledger.initialToken(f.owner.getUniqueID());
        ledger.markDelivered(initial);
        ledger.reserve(initial, MaidLifecycleData.Kind.INITIAL);
        ledger.commit(initial);
        var film = ledger.depositDeath(
            f.owner.getUniqueID(),
            UUID.randomUUID(),
            identity,
            entries(item),
            f.world.provider.dimensionId,
            f.x + 1.5,
            191,
            f.z + .5,
            "[QA-Legacy]",
            schema == 3 ? 37 : 0,
            0);
        ledger.markPublished(film);
        if (schema >= 2) f.state.data.setString(
            "LegacyAcquired" + schema,
            ledger.acquiredToken(f.owner.getUniqueID())
                .toString());
        var writer = new RawData(legacyName(schema));
        ledger.writeToNBT(writer.data);
        writer.data.setInteger("Schema", schema);
        var entries = writer.data.getTagList("Entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            var entry = entries.getCompoundTagAt(i);
            entry.removeTag("TombstoneHeld");
            entry.removeTag("ExperienceHeld");
            if (schema < 3) {
                entry.removeTag("DeathExperience");
                entry.removeTag("ExperienceReadyAt");
            }
        }
        f.state.data.setString("LegacyInitial" + schema, initial.toString());
        f.state.data.setString("LegacyFilm" + schema, film.toString());
        f.state.data.setTag("LegacyRaw" + schema, writer.data.copy());
        f.world.mapStorage.setData(legacyName(schema), writer);
        writer.markDirty();
    }

    private static void migrate(Fixture f, int schema) {
        var ledger = (MaidLifecycleData) f.world.mapStorage.loadData(MaidLifecycleData.class, legacyName(schema));
        f.helper.assertTrue(
            ledger != null && ledger.reserve(f.token("LegacyInitial" + schema), MaidLifecycleData.Kind.INITIAL) == null,
            "Old consumed initial credential was reset");
        var film = f.token("LegacyFilm" + schema);
        f.helper.assertTrue(
            ledger.reserve(film, MaidLifecycleData.Kind.FILM)
                .equals(f.state.data.getCompoundTag("Identity")),
            "Old film identity changed on disk migration");
        ledger.commit(film);
        if (schema >= 2) {
            var acquired = f.token("LegacyAcquired" + schema);
            f.helper.assertTrue(
                ledger.reserve(acquired, MaidLifecycleData.Kind.ACQUIRED) != null,
                "Old acquired credential was lost");
            ledger.commit(acquired);
        }
        var raw = new NBTTagCompound();
        ledger.writeToNBT(raw);
        f.helper.assertTrue(
            raw.getInteger("Schema") == 4 && !ledger.isTombstoneHeld(film)
                && !ledger.isDeathExperienceHeld(film)
                && ledger.remainingDeathExperience(film) == (schema == 3 ? 37 : 0)
                && entry(raw, film).getTagList("TombstoneItems", 10)
                    .equals(f.state.data.getTagList("HeldItems", 10)),
            "Old schema migration lost complete items, default holds or XP balance");
        f.state.data.setTag("Migrated" + schema, raw);
    }

    private static void migratedFiles(Fixture f) {
        for (int schema = 1; schema <= 3; schema++) {
            var ledger = (MaidLifecycleData) f.world.mapStorage.loadData(MaidLifecycleData.class, legacyName(schema));
            var actual = new NBTTagCompound();
            ledger.writeToNBT(actual);
            var expected = f.state.data.getCompoundTag("Migrated" + schema);
            var entries = expected.getTagList("Entries", 10);
            f.helper.assertTrue(
                actual.getInteger("Schema") == 4 && actual.getTagList("Entries", 10)
                    .tagCount() == entries.tagCount(),
                "Migrated file schema or entry count did not persist");
            for (int i = 0; i < entries.tagCount(); i++) {
                var old = entries.getCompoundTagAt(i);
                f.helper.assertTrue(
                    old.equals(entry(actual, UUID.fromString(old.getString("Token")))),
                    "Migrated full entry changed across another JVM");
            }
            f.helper.assertTrue(
                ledger.reserve(f.token("LegacyFilm" + schema), MaidLifecycleData.Kind.FILM) == null,
                "Migrated consumed film redeemed again after restart");
        }
    }

    private static NBTTagCompound diskData(WorldServer world, String name) throws Exception {
        try (var input = new FileInputStream(
            world.getSaveHandler()
                .getMapFileFromName(name))) {
            return CompressedStreamTools.readCompressed(input)
                .getCompoundTag("data");
        }
    }

    private static NBTTagCompound diskEntry(WorldServer world, String name, UUID token) throws Exception {
        return entry(diskData(world, name), token);
    }

    private static NBTTagCompound diskEntity(WorldServer world, Entity entity) throws Exception {
        int x = (int) Math.floor(entity.posX) >> 4, z = (int) Math.floor(entity.posZ) >> 4;
        try (var input = RegionFileCache.getChunkInputStream(
            world.getSaveHandler()
                .getWorldDirectory(),
            x,
            z)) {
            if (input == null) throw new IllegalStateException("No independently saved native MCA chunk");
            var entities = CompressedStreamTools.read(input)
                .getCompoundTag("Level")
                .getTagList("Entities", 10);
            for (int i = 0; i < entities.tagCount(); i++) {
                var stored = entities.getCompoundTagAt(i);
                var id = new UUID(stored.getLong("UUIDMost"), stored.getLong("UUIDLeast"));
                if (id.equals(entity.getUniqueID())) return stored;
            }
        }
        throw new IllegalStateException("Raw MCA did not retain the owned callback entity UUID");
    }

    private static String legacyName(int schema) {
        return NAME + "_Legacy" + schema;
    }

    private static void checkpoint(RawData state, String phase) {
        state.data.setString("Phase", phase);
        state.data.setLong("LastProcess", START);
        state.markDirty();
    }

    private static NBTTagCompound entry(NBTTagCompound ledger, UUID token) {
        var list = ledger.getTagList("Entries", 10);
        for (int i = 0; i < list.tagCount(); i++) if (list.getCompoundTagAt(i)
            .getString("Token")
            .equals(token.toString())) return list.getCompoundTagAt(i);
        throw new IllegalStateException("Missing persistence fixture token: " + token);
    }

    private static NBTTagList entries(ItemStack stack) {
        var list = new NBTTagList();
        if (stack != null) list.appendTag(ProtectedItemCodec.encode(stack));
        return list;
    }

    private static ItemStack stack(String run, String kind) {
        var item = new ItemStack(Items.diamond, 9, 3);
        item.setTagCompound(payload(kind));
        item.setStackDisplayName("Persistence " + kind);
        item.getTagCompound()
            .setString("PersistenceMarker", run + "/" + kind);
        return item;
    }

    private static NBTTagCompound payload(String marker) {
        var tag = new NBTTagCompound();
        tag.setString("Text", marker);
        tag.setByteArray("Bytes", new byte[] { 0, 1, -1, 127 });
        tag.setIntArray("Ints", new int[] { Integer.MIN_VALUE, 19, Integer.MAX_VALUE });
        var list = new NBTTagList();
        list.appendTag(new NBTTagString("完整原始NBT/" + marker));
        tag.setTag("List", list);
        return tag;
    }

    public static final class RawData extends WorldSavedData {

        NBTTagCompound data = new NBTTagCompound();
        boolean loaded;

        public RawData(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            data = (NBTTagCompound) tag.copy();
            loaded = true;
        }

        @Override
        public void writeToNBT(NBTTagCompound target) {
            for (Object raw : data.func_150296_c()) {
                String key = (String) raw;
                target.setTag(
                    key,
                    data.getTag(key)
                        .copy());
            }
        }
    }

    public static final class Fixture {

        final GameTestHelper helper;
        final WorldServer world;
        final RawData state;
        final MaidLifecycleData ledger;
        final EntityPlayerMP owner;
        final String run;
        final int x, z;
        String itemMode = "", itemMarker = "";
        double xpX = Double.NaN;
        int itemCalls, xpCalls;
        EntityItem itemDrop;
        EntityXPOrb xpOrb;

        Fixture(GameTestHelper helper, RawData state) {
            this.helper = helper;
            this.state = state;
            world = helper.getWorld();
            run = state.data.getString("Run");
            x = state.data.getInteger("X");
            z = state.data.getInteger("Z");
            var chunks = new GameTestChunkLoader();
            helper.afterTest(chunks::releaseAll);
            try {
                chunks.forceChunksStrict(world, x - 4, 0, z - 4, x + 32, 240, z + 4);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot prepare native persistence chunks", failure);
            }
            ledger = MaidLifecycleData.get(world);
            var server = MinecraftServer.getServer();
            owner = new EntityPlayerMP(
                server,
                world,
                new GameProfile(UUID.fromString(state.data.getString("Owner")), "[QA-Persist]"),
                new ItemInWorldManager(world));
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, channel);
            new NetHandlerPlayServer(server, manager, owner);
            owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            MinecraftForge.EVENT_BUS.register(this);
            helper.afterTest(() -> {
                MinecraftForge.EVENT_BUS.unregister(this);
                owner.setDead();
                channel.finish();
                Object packet;
                while ((packet = channel.readOutbound()) != null) ReferenceCountUtil.release(packet);
                while ((packet = channel.readInbound()) != null) ReferenceCountUtil.release(packet);
            });
        }

        UUID token(String key) {
            return UUID.fromString(state.data.getString(key));
        }

        UUID deposit(String key, ItemStack item, NBTTagCompound identity, int xp, int offset) {
            var token = ledger.depositDeath(
                owner.getUniqueID(),
                UUID.randomUUID(),
                identity,
                entries(item),
                world.provider.dimensionId,
                x + offset + .5,
                191,
                z + .5,
                "[QA-Persist]",
                xp,
                0);
            ledger.markPublished(token);
            state.data.setString(key, token.toString());
            return token;
        }

        MaidTombstoneEntity grave(UUID token, int offset) {
            var grave = new MaidTombstoneEntity(world);
            grave.initialize(token, owner.getUniqueID(), "[QA-Persist]");
            grave.setLocationAndAngles(x + offset + .5, 191, z + .5, 0, 0);
            helper.assertTrue(
                world.spawnEntityInWorld(grave) && MaidSpawnChecks.present(grave, world),
                "Actual persistent tombstone did not spawn");
            return grave;
        }

        Entity find(String key, Class<?> type) {
            return find(token(key), type);
        }

        Entity find(UUID id, Class<?> type) {
            Entity found = null;
            for (var entity : world.loadedEntityList) if (id.equals(entity.getUniqueID())) {
                helper.assertTrue(
                    found == null && type.isInstance(entity) && MaidSpawnChecks.present(entity, world),
                    "Duplicate or wrong persisted entity identity");
                found = entity;
            }
            helper.assertTrue(found != null, "Persistent entity was not loaded from chunk NBT: " + id);
            return found;
        }

        void empty(Entity grave) {
            for (int slot = 0; slot < owner.inventory.mainInventory.length; slot++)
                owner.inventory.mainInventory[slot] = null;
            owner.inventory.setItemStack(null);
            owner.inventory.currentItem = 0;
            owner.setSneaking(false);
            owner.setPosition(grave.posX, grave.posY, grave.posZ + 1);
        }

        void full(Entity grave) {
            empty(grave);
            for (int slot = 0; slot < owner.inventory.mainInventory.length; slot++)
                owner.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
            owner.setSneaking(true);
        }

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (event.world != world) return;
            if (event.entity instanceof EntityItem drop && drop.getEntityItem()
                .hasTagCompound()
                && drop.getEntityItem()
                    .getTagCompound()
                    .getString("PersistenceMarker")
                    .equals(itemMarker)) {
                itemCalls++;
                itemDrop = drop;
                if (itemMode.equals("rewrite")) {
                    var changed = drop.getEntityItem()
                        .copy();
                    changed.stackSize++;
                    drop.setEntityItemStack(changed);
                } else if (itemMode.equals("cancel")) event.setCanceled(true);
            } else if (event.entity instanceof EntityXPOrb orb && orb.posX == xpX
                && orb.posY == 191
                && orb.posZ == z + .5) {
                    xpCalls++;
                    xpOrb = orb;
                    orb.xpValue++;
                }
        }
    }
}
