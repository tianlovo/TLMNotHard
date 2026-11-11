package com.tianluoqaq.tlmnh.qa;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.cabinet.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidFilmRecovery;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidRecoveryTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTileReturns;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryStore;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/** 实际普通转移与原生保存回调，不把同JVM磁盘读回称作任意跨文件崩溃原子性 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ReturnedMaterialTests {

    private ReturnedMaterialTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "returned_tile_sources")
    public static void nativeCabinetAndColoredBedRemovalPersistSourcesBeforeWholeReturnedStacks(GameTestHelper helper)
        throws Exception {
        var f = new LifecycleConservationTests.Fixture(helper);
        var events = new Events(f);
        artifacts();
        var cabinet = cabinet(f, 8);
        var arrows = item(Items.arrow, 7);
        var opaque = new NBTTagCompound();
        opaque.setString("UnknownRegistryItem", "original raw must survive");
        opaque.setIntArray("Opaque", new int[] { 9, 4, 2 });
        cabinet.setInventorySlotContents(0, arrows.copy());
        cabinet.recovery()
            .retain(opaque);
        var parcel = cabinet.recovery()
            .firstForTransfer()
            .copy();
        var source = MaidShrineTransfer.snapshot(cabinet);
        var before = new ArrayList<>(
            f.ledger()
                .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
        events.accept(arrows, parcel);
        helper.getWorld()
            .setBlockToAir(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord);
        var ids = added(f, before);
        helper.assertTrue(
            ids.size() == 2 && events.outputs.isEmpty(),
            "Cabinet removal published before source confirmation");
        for (var id : ids) helper.assertTrue(
            f.ledger()
                .returnedPhase(id) == MaidLifecycleData.ReturnedPhase.PREPARED
                && source.equals(
                    row(f, id).getCompoundTag("Context")
                        .getCompoundTag("Source")
                        .getCompoundTag("Tile")),
            "Cabinet did not retain its full original source before clear");
        MaidFilmRecovery.retryPending(helper.getWorld());
        MaidFilmRecovery.retryPending(helper.getWorld());
        helper.assertTrue(
            events.live(arrows) == 1 && events.live(parcel) == 1 && events.outputs.size() == 2,
            "Cabinet original slot and opaque recovery parcel were not each delivered exactly once");
        for (var id : ids) helper.assertTrue(row(f, id) == null, "Confirmed cabinet return remained redeemable");
        helper.assertTrue(
            diskTile(f, cabinet.xCoord, cabinet.yCoord, cabinet.zCoord) == null,
            "Cabinet source removal was not persisted before output");

        helper.getWorld()
            .setBlock(f.x + 12, f.y + 1, f.z + 4, MaidLifeContent.maidBed, 0, 2);
        helper.getWorld()
            .setBlock(f.x + 12, f.y + 1, f.z + 5, MaidLifeContent.maidBed, 8, 2);
        var bed = (TileEntityMaidBed) helper.getWorld()
            .getTileEntity(f.x + 12, f.y + 1, f.z + 5);
        var colored = bed.coloredItem();
        before = new ArrayList<>(
            f.ledger()
                .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
        events.accept(colored);
        MaidLifeContent.maidBed.harvestBlock(helper.getWorld(), f.owner, bed.xCoord, bed.yCoord, bed.zCoord, 8);
        ids = added(f, before);
        helper.assertTrue(
            ids.size() == 1 && f.ledger()
                .returnedPhase(ids.get(0)) == MaidLifecycleData.ReturnedPhase.PREPARED,
            "Admitted colored-bed harvest did not stage its exact source first");
        MaidFilmRecovery.retryPending(helper.getWorld());
        MaidFilmRecovery.retryPending(helper.getWorld());
        helper.assertTrue(
            events.live(colored) == 1 && row(f, ids.get(0)) == null
                && diskTile(f, bed.xCoord, bed.yCoord, bed.zCoord) == null,
            "Colored bed output or original native head removal was not confirmed exactly once");
        System.out.println("TLMNH_RETURNED_SOURCES_PASS cabinet=slot+opaqueParcel bed=colored sourceNativeAbsent=true");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "returned_publication")
    public static void canceledReturnRetriesAndRewrittenOutputStaysHeldWithNativeRawEscrow(GameTestHelper helper)
        throws Exception {
        var f = new LifecycleConservationTests.Fixture(helper);
        var events = new Events(f);
        var canceled = item(Items.arrow, 5);
        events.accept(canceled);
        var id = MaidFilmRecovery.queueOwnedItem(helper.getWorld(), canceled, f.x + 8.5, f.y + 1, f.z + 4.5);
        events.join = event -> {
            helper.assertTrue(
                "ISSUING".equals(diskRow(f, id).getString("Phase")),
                "External join callback ran before durable issuing record");
            event.setCanceled(true);
        };
        MaidFilmRecovery.retryPending(helper.getWorld());
        helper.assertTrue(
            events.outputs.size() == 1 && events.live(canceled) == 0
                && f.ledger()
                    .returnedPhase(id) == MaidLifecycleData.ReturnedPhase.READY,
            "Safely canceled native publication was not available for retry");
        events.join = null;
        MaidFilmRecovery.retryPending(helper.getWorld());
        MaidFilmRecovery.retryPending(helper.getWorld());
        helper.assertTrue(
            events.outputs.size() == 2 && events.live(canceled) == 1 && row(f, id) == null,
            "Canceled return retry did not deliver exactly one complete stack");

        var original = item(Items.emerald, 9);
        events.accept(original);
        var held = MaidFilmRecovery.queueOwnedItem(helper.getWorld(), original, f.x + 9.5, f.y + 1, f.z + 4.5);
        events.join = event -> ((EntityItem) event.entity).getEntityItem().stackSize = 10;
        int calls = events.outputs.size();
        MaidFilmRecovery.retryPending(helper.getWorld());
        captureFault(f, "returned_material/" + held);
        events.join = null;
        MaidFilmRecovery.retryPending(helper.getWorld());
        MaidFilmRecovery.retryPending(helper.getWorld());
        var stored = diskRow(f, held);
        helper.assertTrue(
            events.outputs.size() == calls + 1 && stored != null
                && "HELD".equals(stored.getString("Phase"))
                && ProtectedItemCodec.encode(original)
                    .equals(stored.getCompoundTag("Stack")),
            "Unknown 9-to-10 output lost its complete original escrow or was published again");
        var successor = events.outputs.get(events.outputs.size() - 1);
        saveChunk(f, successor);
        var nativeSuccessor = diskEntity(f, successor);
        helper.assertTrue(
            nativeSuccessor != null && nativeSuccessor.getCompoundTag("Item")
                .getByte("Count") == 10 && MaidSpawnChecks.present(successor),
            "Unknown external successor was removed or not saved natively");
        var restored = new MaidLifecycleData("returned-native-reload");
        restored.readFromNBT(diskLedger(f));
        helper.assertTrue(
            restored.returnedPhase(held) == MaidLifecycleData.ReturnedPhase.HELD
                && restored.reserveReturnedFilm(held) == null,
            "Native saved held return became redeemable after independent ledger read");
        System.out
            .println("TLMNH_RETURNED_OUTPUT_PASS cancel=retry unknown9to10=held nativeLedger=true nativeMca=true");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "ordinary_source_transfer")
    public static void shrineAndRecoveryNativeSavesKeepUnknownSuccessorsAndSafeCancellationCanRetry(
        GameTestHelper helper) throws Exception {
        var f = new LifecycleConservationTests.Fixture(helper);
        var events = new Events(f);
        helper.setBlock(8, 1, 4, MaidResurrectionContent.SHRINE);
        var shrine = (MaidShrineTile) helper.getWorld()
            .getTileEntity(f.x + 8, f.y + 1, f.z + 4);
        var film = MaidLifecycleItems
            .credential(MaidResurrectionContent.FILM, UUID.randomUUID(), f.owner.getUniqueID());
        film.getTagCompound()
            .setString("Opaque", "whole original film");
        f.owner.inventory.mainInventory[0] = film.copy();
        var successor = item(Items.gold_ingot, 17);
        var initial = new ArrayList<>(
            f.ledger()
                .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
        events.snapshot = () -> f.owner.inventory.mainInventory[0] = successor;
        helper.assertTrue(
            !MaidShrineTransfer.transfer(f.owner, shrine, true) && shrine.getFilm() == null
                && f.owner.inventory.mainInventory[0] == successor,
            "Pre-debit native source snapshot overwrote an unknown held-film successor");
        var early = added(f, initial);
        helper.assertTrue(
            early.size() == 1 && f.ledger()
                .returnedPhase(early.get(0)) == MaidLifecycleData.ReturnedPhase.HELD
                && ProtectedItemCodec.encode(film)
                    .equals(diskRow(f, early.get(0)).getCompoundTag("Stack")),
            "Unknown player source before debit was incorrectly acknowledged as safe cancellation");
        captureFault(
            f,
            "shrine_transfer/" + f.owner
                .getUniqueID() + "/" + shrine.xCoord + "/" + shrine.yCoord + "/" + shrine.zCoord);
        f.resetPlayer();
        film = MaidLifecycleItems.credential(MaidResurrectionContent.FILM, UUID.randomUUID(), f.owner.getUniqueID());
        film.getTagCompound()
            .setString("Opaque", "whole original film after safe wait");
        f.owner.inventory.mainInventory[0] = film.copy();
        events.saved = () -> f.owner.inventory.mainInventory[5] = successor;
        helper.assertTrue(
            !MaidShrineTransfer.transfer(f.owner, shrine, true)
                && ItemStack.areItemStacksEqual(film, f.owner.inventory.mainInventory[0])
                && shrine.getFilm() == null
                && f.owner.inventory.mainInventory[5] == successor
                && events.saves == 1,
            "Safely retractable insertion did not retain callback inventory and original source");
        helper.assertTrue(
            MaidShrineTransfer.transfer(f.owner, shrine, true) && f.owner.inventory.mainInventory[0] == null
                && ItemStack.areItemStacksEqual(film, shrine.getFilm())
                && f.owner.inventory.mainInventory[5] == successor,
            "Same original film could not retry safe native save rejection");
        var before = new ArrayList<>(
            f.ledger()
                .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
        events.saved = () -> f.owner.inventory.mainInventory[0].getTagCompound()
            .setString("ExternalPayload", "keep");
        helper.assertTrue(
            !MaidShrineTransfer.transfer(f.owner, shrine, false) && shrine.getFilm() == null
                && "keep".equals(
                    f.owner.inventory.mainInventory[0].getTagCompound()
                        .getString("ExternalPayload")),
            "Extraction overwrote an in-place changed same-token successor");
        var ids = added(f, before);
        helper.assertTrue(
            ids.size() == 1 && f.ledger()
                .returnedPhase(ids.get(0)) == MaidLifecycleData.ReturnedPhase.HELD
                && ProtectedItemCodec.encode(film)
                    .equals(diskRow(f, ids.get(0)).getCompoundTag("Stack")),
            "Changed extraction destination did not retain the complete original held film");

        f.resetPlayer();
        var cabinet = cabinet(f, 9);
        var original = item(Items.arrow, 9);
        var recovery = new NBTTagList();
        recovery.appendTag(ProtectedItemCodec.encode(original));
        cabinet.recovery()
            .read(recovery);
        before = new ArrayList<>(
            f.ledger()
                .pendingReturnedFilms(helper.getWorld().provider.dimensionId));
        events.saved = () -> f.owner.inventory.mainInventory[0] = successor;
        helper.assertTrue(
            !cabinet.recovery()
                .recoverOne(f.owner)
                && cabinet.recovery()
                    .size() == 0
                && f.owner.inventory.mainInventory[0] == successor
                && !cabinet.recovery()
                    .recoverOne(f.owner),
            "Recovery save failure overwrote its unknown successor or repeated original delivery");
        captureFault(f, "player_inventory/recovery/" + f.owner.getUniqueID());
        ids = added(f, before);
        helper.assertTrue(
            ids.size() == 1 && f.ledger()
                .returnedPhase(ids.get(0)) == MaidLifecycleData.ReturnedPhase.HELD
                && ProtectedItemCodec.encode(original)
                    .equals(diskRow(f, ids.get(0)).getCompoundTag("Stack"))
                && diskTile(f, cabinet.xCoord, cabinet.yCoord, cabinet.zCoord).getTagList(RecoveryStore.TAG, 10)
                    .tagCount() == 0,
            "Unknown player destination did not preserve raw escrow and durably debited cabinet");

        f.resetPlayer();
        var maid = new EntityMaid(helper.getWorld());
        f.owned.add(maid);
        helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Recovery NPC owner binding failed");
        helper.spawnEntity(maid, 6.5, 1, 4.5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setPickup(false);
        maid.getMaidInventory()
            .recovery()
            .read(recovery);
        helper.assertTrue(
            maid.getMaidInventory()
                .recovery()
                .recoverOne(f.owner)
                && maid.getMaidInventory()
                    .recovery()
                    .size() == 0
                && ItemStack.areItemStacksEqual(original, f.owner.inventory.mainInventory[0])
                && diskEntity(f, maid).getTagList(RecoveryStore.TAG, 10)
                    .tagCount() == 0,
            "Normal owner recovery did not persist both native NPC source and complete player destination");
        System.out.println(
            "TLMNH_ORDINARY_TRANSFER_PASS shrine=safeRetry+unknownSameToken recovery=unknownHeld+nativeNpcNormal");
        helper.succeed();
    }

    private static TileEntitySnackCabinet cabinet(LifecycleConservationTests.Fixture f, int x) {
        f.helper.setBlock(x, 1, 4, MaidLifeContent.snackCabinet);
        return (TileEntitySnackCabinet) f.helper.getWorld()
            .getTileEntity(f.x + x, f.y + 1, f.z + 4);
    }

    private static ItemStack item(net.minecraft.item.Item item, int count) {
        var result = new ItemStack(item, count);
        result.setTagCompound(new NBTTagCompound());
        result.getTagCompound()
            .setString(
                "TransferFixture",
                UUID.randomUUID()
                    .toString());
        result.getTagCompound()
            .setIntArray("Opaque", new int[] { 8, 2, 5 });
        return result;
    }

    private static List<UUID> added(LifecycleConservationTests.Fixture f, List<UUID> before) {
        var result = new ArrayList<>(
            f.ledger()
                .pendingReturnedFilms(f.helper.getWorld().provider.dimensionId));
        result.removeAll(before);
        return result;
    }

    private static NBTTagCompound row(LifecycleConservationTests.Fixture f, UUID id) {
        var saved = new NBTTagCompound();
        f.ledger()
            .writeToNBT(saved);
        return row(saved, id);
    }

    private static NBTTagCompound row(NBTTagCompound ledger, UUID id) {
        var rows = ledger.getTagList("ReturnedFilms", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (id.toString()
            .equals(
                rows.getCompoundTagAt(i)
                    .getString("Id")))
            return (NBTTagCompound) rows.getCompoundTagAt(i)
                .copy();
        return null;
    }

    static NBTTagCompound diskLedger(LifecycleConservationTests.Fixture f) throws Exception {
        try (var input = new FileInputStream(
            f.helper.getWorld()
                .getSaveHandler()
                .getMapFileFromName(MaidLifecycleData.NAME))) {
            return CompressedStreamTools.readCompressed(input)
                .getCompoundTag("data");
        }
    }

    private static NBTTagCompound diskRow(LifecycleConservationTests.Fixture f, UUID id) {
        try {
            return row(diskLedger(f), id);
        } catch (Exception failure) {
            throw new IllegalStateException("Native returned-material ledger unavailable", failure);
        }
    }

    private static NBTTagCompound diskChunk(LifecycleConservationTests.Fixture f, int x, int z) throws Exception {
        var loader = (AnvilChunkLoader) f.helper.getWorld().theChunkProviderServer.currentChunkLoader;
        try (var input = RegionFileCache.getChunkInputStream(loader.chunkSaveLocation, x >> 4, z >> 4)) {
            if (input == null) throw new IllegalStateException("No native source/target chunk");
            return CompressedStreamTools.read(input)
                .getCompoundTag("Level");
        }
    }

    private static NBTTagCompound diskTile(LifecycleConservationTests.Fixture f, int x, int y, int z) throws Exception {
        var rows = diskChunk(f, x, z).getTagList("TileEntities", 10);
        NBTTagCompound result = null;
        for (int i = 0; i < rows.tagCount(); i++) {
            var value = rows.getCompoundTagAt(i);
            if (value.getInteger("x") != x || value.getInteger("y") != y || value.getInteger("z") != z) continue;
            if (result != null) throw new IllegalStateException("Nonunique native tile");
            result = (NBTTagCompound) value.copy();
        }
        return result;
    }

    static NBTTagCompound diskEntity(LifecycleConservationTests.Fixture f, Entity entity) throws Exception {
        var rows = diskChunk(f, (int) Math.floor(entity.posX), (int) Math.floor(entity.posZ))
            .getTagList("Entities", 10);
        NBTTagCompound result = null;
        for (int i = 0; i < rows.tagCount(); i++) {
            var value = rows.getCompoundTagAt(i);
            if (!entity.getUniqueID()
                .equals(new UUID(value.getLong("UUIDMost"), value.getLong("UUIDLeast")))) continue;
            if (result != null) throw new IllegalStateException("Nonunique native entity");
            result = (NBTTagCompound) value.copy();
        }
        return result;
    }

    static void saveChunk(LifecycleConservationTests.Fixture f, Entity entity) throws Exception {
        var world = f.helper.getWorld();
        var loader = (AnvilChunkLoader) world.theChunkProviderServer.currentChunkLoader;
        loader.saveChunk(
            world,
            world.getChunkFromBlockCoords((int) Math.floor(entity.posX), (int) Math.floor(entity.posZ)));
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
    }

    private static void captureFault(LifecycleConservationTests.Fixture f, String key) {
        var helper = f.helper;
        var exact = IntegrationGuard.faults(Side.SERVER)
            .get(key);
        helper.assertTrue(
            !f.priorFaults.containsKey(key) && exact != null
                && (key.startsWith("returned_material/") ? exact.owner == null
                    : f.owner.getUniqueID()
                        .equals(exact.owner)),
            "Expected fresh scoped conservation diagnostic missing: " + key);
        helper.afterTest(() -> {
            synchronized (IntegrationGuard.class) {
                try {
                    var field = IntegrationGuard.class.getDeclaredField("FAULTS");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var all = (Map<Side, Map<String, IntegrationGuard.Fault>>) field.get(null);
                    var server = all.get(Side.SERVER);
                    helper.assertTrue(
                        server != null && server.get(key) == exact,
                        "Refusing to clear an unknown diagnostic successor");
                    server.remove(key);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not isolate exact fixture diagnostic", failure);
                }
            }
        });
    }

    private static void artifacts() throws Exception {
        System.out.println(
            "TLMNH_RETURNED_PRODUCTION_ARTIFACT=" + DevelopmentArtifactIdentity.capture(
                MaidLifecycleData.class,
                MaidFilmRecovery.class,
                MaidTileReturns.class,
                MaidShrineTransfer.class,
                MaidRecoveryTransfer.class,
                Class.forName("com.tianluoqaq.tlmnh.common.lifecycle.MaidPlayerTransferSnapshot"),
                Class.forName("com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleDurability")));
        System.out.println(
            "TLMNH_RETURNED_OBSERVER_ARTIFACT="
                + DevelopmentArtifactIdentity.capture(ReturnedMaterialTests.class, LifecycleConservationTests.class));
    }

    public static final class Events implements IExtendedEntityProperties {

        final LifecycleConservationTests.Fixture f;
        final List<EntityItem> outputs = new ArrayList<>();
        final List<ItemStack> accepted = new ArrayList<>();
        Consumer<EntityJoinWorldEvent> join;
        Runnable saved, snapshot;
        int saves;

        Events(LifecycleConservationTests.Fixture f) {
            this.f = f;
            MinecraftForge.EVENT_BUS.register(this);
            f.owner.registerExtendedProperties("tlmnhqa_returned_" + UUID.randomUUID(), this);
            f.helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(this));
        }

        void accept(ItemStack... items) {
            for (var item : items) accepted.add(item.copy());
        }

        @Override
        public void saveNBTData(NBTTagCompound compound) {
            if (snapshot == null) return;
            var action = snapshot;
            snapshot = null;
            action.run();
        }

        @Override
        public void loadNBTData(NBTTagCompound compound) {}

        @Override
        public void init(Entity entity, World world) {}

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (event.world != f.helper.getWorld() || !(event.entity instanceof EntityItem item)) return;
            boolean selected = false;
            for (var stack : accepted) if (ItemStack.areItemStacksEqual(stack, item.getEntityItem())) selected = true;
            if (!selected) return;
            outputs.add(item);
            f.owned.add(item);
            if (join != null) join.accept(event);
        }

        @SubscribeEvent
        public void saved(PlayerEvent.SaveToFile event) {
            if (event.entityPlayer != f.owner || saved == null) return;
            var action = saved;
            saved = null;
            saves++;
            action.run();
        }

        int live(ItemStack expected) {
            int count = 0;
            for (var item : outputs) if (MaidSpawnChecks.present(item, f.helper.getWorld())
                && ItemStack.areItemStacksEqual(expected, item.getEntityItem())) count++;
            return count;
        }
    }
}
