package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneTransfer;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/** 原生死亡入口、实际IO拒绝与同JVM原生NBT重建；不冒称独立JVM或任意硬崩溃原子性 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class DeathIngressTests {

    private static final String INGRESS = "TLMNHDeathIngress";

    private DeathIngressTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "death_ingress_normal")
    public static void actualOwnedDeathPersistsWholeSourceBeforeOneGraveAndKeepsXpIndependent(GameTestHelper helper)
        throws Exception {
        var f = fixture(helper);
        var events = new Events(f);
        artifacts();
        var maid = source(f, true, 8);
        for (int slot = 0; slot < maid.getMaidInventory()
            .getSizeInventory(); slot++)
            maid.getMaidInventory()
                .setInventorySlotContents(slot, stack(slot + 1));
        maid.getMaidInventory()
            .recovery()
            .retain(opaque());
        maid.setExperience(53);
        maid.setHealth(0);
        maid.onDeath(DamageSource.generic);
        var token = f.ledger()
            .deathToken(maid.getUniqueID());
        var entry = entry(f, token);
        helper.assertTrue(
            entry != null && "COMMITTED".equals(
                entry.getCompoundTag("DeathIngress")
                    .getString("Stage"))
                && entry.getTagList("TombstoneItems", 10)
                    .tagCount() == 14
                && entry.getInteger("DeathExperience") == 53
                && entry.getCompoundTag("DeathIngress")
                    .getCompoundTag("Source")
                    .getInteger("MaidExperience") == 53,
            "Real death did not durably retain twelve full slots, recovery, film and separate XP");
        helper.assertTrue(events.graves.size() == 1, "Real owned death did not reach exactly one grave callback");
        var grave = events.graves.get(0);
        helper.assertTrue(
            events.graves.size() == 1 && entry.getBoolean("Published")
                && "PUBLISHED".equals(
                    entry.getCompoundTag("TombstonePublication")
                        .getString("Stage"))
                && ReturnedMaterialTests.diskEntity(f, grave) != null
                && MaidDeathService.nativeDeathExperience(maid) == 0,
            "Grave acknowledgement preceded native persistence or original XP remained payable");
        var savedSource = ReturnedMaterialTests.diskEntity(f, maid);
        helper.assertTrue(
            savedSource != null && savedSource.getInteger("MaidExperience") == 0
                && savedSource.getTagList("MaidInventory", 10)
                    .tagCount() == 0
                && savedSource.getTagList("TLMNHRecovery", 10)
                    .tagCount() == 0,
            "Actual native source was not cleared before outgoing rights opened");
        helper.assertTrue(
            MaidDeathService.preserve(maid) && events.graves.size() == 1,
            "Second native preserve callback created another grave");
        helper.assertTrue(
            MaidTombstoneTransfer.collect(grave, f.owner) && f.ledger()
                .tombstoneEmpty(token)
                && f.ledger()
                    .remainingDeathExperience(token) == 53
                && !MaidTombstoneTransfer.collect(grave, f.owner),
            "Normal grave could not transfer exactly once independently of XP");
        checkpoint(f, "normal", "Entries", token, entry(f, token), "Token", "Owner", "Subject", "Kind", "DeathIngress");
        System.out.println(
            "TLMNH_DEATH_INGRESS_NORMAL_PASS slots=12 recovery=opaque film=one xp=53 nativeSource=true nativeGrave=true");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "death_ingress_rebuild")
    public static void nativeLedgerRefusalKeepsSourceAndMarkerRebuildsWhileMalformedMarkerRemainsSaved(
        GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        var events = new Events(f);
        var maid = source(f, true, 8);
        var original = stack(7);
        maid.getMaidInventory()
            .setInventorySlotContents(0, original.copy());
        maid.setExperience(31);
        f.ledger()
            .markDirty();
        helper.getWorld().mapStorage.saveAllData();
        helper.assertTrue(
            ReturnedMaterialTests.diskLedger(f)
                .getInteger("Schema") == 9,
            "Native preflight ledger not saved");
        blockLedgerOnce(f, () -> {
            maid.setHealth(0);
            maid.onDeath(DamageSource.generic);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(
                    original,
                    maid.getMaidInventory()
                        .getStackInSlot(0))
                    && maid.getExperience() == 31
                    && events.graves.isEmpty()
                    && !MaidDeathService.canFinishDeath(maid, true),
                "Failed write-ahead save cleared original materials, published a grave or allowed native expiry");
        });
        captureFault(f, "death_ingress/" + maid.getUniqueID());
        var token = f.ledger()
            .deathToken(maid.getUniqueID());
        ReturnedMaterialTests.saveChunk(f, maid);
        var nativeSource = ReturnedMaterialTests.diskEntity(f, maid);
        var receipt = nativeSource.getCompoundTag("ForgeData")
            .getCompoundTag(INGRESS);
        helper.assertTrue(
            receipt.hasKey("Source", 10) && receipt.getString("Token")
                .equals(token.toString()) && nativeSource.getInteger("MaidExperience") == 31,
            "Native independent source save did not preserve the complete first-attempt marker");
        var oldDisk = ReturnedMaterialTests.diskLedger(f);
        helper.assertTrue(
            find(oldDisk, token) == null,
            "Write-refusal precondition was not the actual missing-ledger window");
        // 只重建本域卸载清理、真实序列化的NPC和账本；不是第二JVM或整个世界重启
        new MaidLifecycleEvents().onWorldUnload(new WorldEvent.Unload(helper.getWorld()));
        helper.getWorld()
            .removePlayerEntityDangerously(maid);
        f.ledger()
            .readFromNBT(oldDisk);
        var restored = (EntityMaid) EntityList.createEntityFromNBT(nativeSource, helper.getWorld());
        f.owned.add(restored);
        var base = nativeMaid(restored);
        base.getCompoundTag("ForgeData")
            .removeTag(INGRESS);
        evidence(f, "source-rebuild", receipt.getCompoundTag("Source"), base);
        helper.assertTrue(
            helper.getWorld()
                .spawnEntityInWorld(restored),
            "Original native source marker rebuild was refused");
        MaidDeathService.retryPending(helper.getWorld());
        helper.assertTrue(
            entry(f, token) != null && "COMMITTED".equals(
                entry(f, token).getCompoundTag("DeathIngress")
                    .getString("Stage"))
                && events.graves.size() == 1
                && restored.getExperience() == 0
                && restored.getMaidInventory()
                    .getStackInSlot(0) == null
                && MaidDeathService.canFinishDeath(restored, false),
            "Exact native marker did not restore the original token and finish one complete transfer");
        checkpoint(
            f,
            "source-rebuild",
            "Entries",
            token,
            entry(f, token),
            "Token",
            "Owner",
            "Subject",
            "Kind",
            "DeathIngress");

        var malformed = new EntityMaid(helper.getWorld());
        helper.assertTrue(malformed.bindTo(f.owner.getUniqueID()), "Malformed source fixture owner binding failed");
        malformed.setPosition(f.x + 10.5, f.y + 1, f.z + 4.5);
        malformed.getMaidInventory()
            .setInventorySlotContents(0, stack(4));
        malformed.setExperience(19);
        var unknown = new NBTTagCompound();
        unknown.setInteger("Version", 999);
        unknown.setString("UnknownRaw", "must remain on actual entity");
        malformed.getEntityData()
            .setTag(INGRESS, unknown.copy());
        malformed.setHealth(0);
        f.owned.add(malformed);
        helper.assertTrue(
            helper.getWorld()
                .spawnEntityInWorld(malformed),
            "Unknown marker source was canceled before raw preservation");
        captureFault(f, "death_ingress/" + malformed.getUniqueID());
        for (int tick = 0; tick < 25; tick++) malformed.onUpdate();
        helper.assertTrue(
            !MaidDeathService.canFinishDeath(malformed, false) && !malformed.isDead
                && MaidSpawnChecks.present(malformed)
                && malformed.getExperience() == 19
                && unknown.equals(
                    malformed.getEntityData()
                        .getCompoundTag(INGRESS)),
            "Unconfirmed unknown source was changed or expired after native reconstruction");
        ReturnedMaterialTests.saveChunk(f, malformed);
        var savedMalformed = ReturnedMaterialTests.diskEntity(f, malformed);
        evidence(
            f,
            "malformed-preserved",
            nativeMaid(malformed),
            savedMalformed == null ? new NBTTagCompound() : savedMalformed);
        helper.assertTrue(
            savedMalformed != null && unknown.equals(
                savedMalformed.getCompoundTag("ForgeData")
                    .getCompoundTag(INGRESS))
                && savedMalformed.getTagList("MaidInventory", 10)
                    .tagCount() == 1,
            "Actual native MCA lost the loaded malformed-marker source");
        System.out.println(
            "TLMNH_DEATH_INGRESS_REBUILD_PASS actualLedgerRefusal=true nativeMarkerRebuild=true malformed=loaded+frozen+saved multiJvm=false");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "wild_native_recovery")
    public static void wildRecoveryUsesNativeCaptureCancelAndOpaqueInteractionXpRemainsHeld(GameTestHelper helper)
        throws Exception {
        var f = fixture(helper);
        var events = new Events(f);
        for (boolean canceled : new boolean[] { false, true }) {
            var wild = source(f, false, canceled ? 10 : 8);
            wild.getMaidInventory()
                .setInventorySlotContents(0, stack(3));
            wild.getMaidInventory()
                .recovery()
                .retain(opaque());
            events.wild = wild;
            events.cancelDrops = canceled;
            int captured = events.wildDrops.size();
            wild.setHealth(0);
            wild.onDeath(DamageSource.generic);
            helper.assertTrue(
                events.wildDrops.size() == captured + 2 && wild.getMaidInventory()
                    .getStackInSlot(0) == null
                    && wild.getMaidInventory()
                        .recovery()
                        .size() == 0
                    && f.ledger()
                        .deathToken(wild.getUniqueID()) == null,
                "Wild original slot and complete recovery parcel did not share the native capture list");
            for (int i = captured; i < events.wildDrops.size(); i++) helper.assertTrue(
                MaidSpawnChecks.present(events.wildDrops.get(i)) != canceled,
                "Native LivingDropsEvent cancellation was bypassed or value published twice");
        }
        events.cancelDrops = false;
        var heldWild = source(f, false, 12);
        heldWild.getMaidInventory()
            .recovery()
            .retain(opaque());
        heldWild.setExperience(31);
        var hold = new NBTTagCompound();
        hold.setString("UnknownPaidBalance", "full record retained");
        heldWild.getEntityData()
            .setTag("TLMNHInteractionTransfer", hold.copy());
        events.wild = heldWild;
        heldWild.setHealth(0);
        heldWild.onDeath(DamageSource.generic);
        captureFault(f, "interaction_transfer/" + heldWild.getUniqueID());
        var rows = ReturnedMaterialTests.diskLedger(f)
            .getTagList("ReturnedFilms", 10);
        boolean retained = false;
        for (int i = 0; i < rows.tagCount(); i++) {
            var row = rows.getCompoundTagAt(i);
            var raw = row.getCompoundTag("Stack");
            if (raw.getLong("UUIDMost") != heldWild.getUniqueID()
                .getMostSignificantBits() || raw.getLong("UUIDLeast")
                    != heldWild.getUniqueID()
                        .getLeastSignificantBits())
                continue;
            retained = "HELD".equals(row.getString("Phase")) && raw.getInteger("MaidExperience") == 31
                && hold.equals(
                    raw.getCompoundTag("ForgeData")
                        .getCompoundTag("TLMNHInteractionTransfer"));
            helper.assertTrue(
                f.ledger()
                    .reserveReturnedFilm(UUID.fromString(row.getString("Id"))) == null,
                "Opaque NPC data became a decodable automatic ItemStack publication");
            checkpoint(f, "wild-held", "ReturnedFilms", UUID.fromString(row.getString("Id")), row);
        }
        helper.assertTrue(
            retained && MaidDeathService.nativeDeathExperience(heldWild) == 0,
            "Unknown interaction record or full XP balance was lost through wild death");
        System.out
            .println("TLMNH_WILD_DEATH_PASS nativeCapture=slot+recovery cancel=obeyed opaqueRaw=held xp31=notReissued");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 140, batch = "death_failed_snapshot_replay")
    public static void failedNativeSnapshotCannotOverwriteLaterCommittedDeathOnFlush(GameTestHelper helper)
        throws Exception {
        var f = fixture(helper);
        var events = new Events(f);
        artifacts();
        var provider = Class.forName("com.mitchej123.hodgepodge.util.WorldDataSaver");
        helper.assertTrue(
            "2.7.221:sha256:9b0bfb7e84a66387319952d76e6f114d6218a988e04d303965c31efb2165e824"
                .equals(com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint.mod("hodgepodge")),
            "Actual failed-snapshot regression requires the verified native provider");
        var saver = provider.getField("INSTANCE")
            .get(null);
        var flush = provider.getMethod("flush");
        f.ledger()
            .markDirty();
        helper.getWorld().mapStorage.saveAllData();
        flush.invoke(saver);
        var baseline = ReturnedMaterialTests.diskLedger(f);
        var failed = new NBTTagCompound[1];
        blockLedgerOnce(f, () -> {
            f.ledger()
                .markDirty();
            helper.getWorld().mapStorage.saveAllData();
            try {
                // 原生等待只排空pending；不能调用flush把本例要观察的failed重排掉
                net.minecraft.world.storage.ThreadedFileIOBase.threadedIOInstance.waitForFinish();
                failed[0] = failedSnapshot(f, saver);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot observe the actual native failed snapshot", failure);
            }
            helper.assertTrue(
                baseline.equals(failed[0]),
                "Controlled native IO refusal did not retain the exact old ledger in failedData");
        });
        evidence(f, "native-failed-snapshot", baseline, failed[0]);
        var maid = source(f, true, 8);
        maid.getMaidInventory()
            .setInventorySlotContents(0, stack(11));
        maid.setHealth(0);
        maid.onDeath(DamageSource.generic);
        var token = f.ledger()
            .deathToken(maid.getUniqueID());
        var committed = entry(f, token);
        helper.assertTrue(
            find(failed[0], token) == null && committed != null
                && "COMMITTED".equals(
                    committed.getCompoundTag("DeathIngress")
                        .getString("Stage"))
                && events.graves.size() == 1
                && failedSnapshot(f, saver) == null,
            "New death was not committed after the real old failed snapshot drained");
        // 再走提供方公开flush，复现旧failed在后续保存/关服才重排的实际路径
        flush.invoke(saver);
        var afterReplay = entry(f, token);
        evidence(f, "failed-snapshot-replay", committed, afterReplay == null ? new NBTTagCompound() : afterReplay);
        helper.assertTrue(
            committed.equals(afterReplay),
            "Late native failed-snapshot replay overwrote the acknowledged new death receipt");
        checkpoint(
            f,
            "failed-snapshot-replay",
            "Entries",
            token,
            committed,
            "Token",
            "Owner",
            "Subject",
            "Kind",
            "DeathIngress");
        System.out.println(
            "TLMNH_DEATH_FAILED_SNAPSHOT_PASS actualNativeFailed=true oldSnapshotMissingToken=true laterFlush=true finalDiskCheck=external-required token="
                + token);
        helper.succeed();
    }

    /** 仅在已核提供方pending锁内读取真实failed快照，不插入/删除/改写它的任何队列 */
    private static NBTTagCompound failedSnapshot(LifecycleConservationTests.Fixture f, Object saver) throws Exception {
        var type = saver.getClass();
        var pending = type.getDeclaredField("pendingData");
        var failed = type.getDeclaredField("failedData");
        pending.setAccessible(true);
        failed.setAccessible(true);
        var target = f.helper.getWorld()
            .getSaveHandler()
            .getMapFileFromName(MaidLifecycleData.NAME)
            .getCanonicalFile();
        synchronized (pending.get(saver)) {
            var rows = (Map<?, ?>) failed.get(saver);
            for (var row : rows.entrySet()) {
                if (!(row.getKey() instanceof java.io.File file) || !target.equals(file.getCanonicalFile())) continue;
                var wrapped = row.getValue();
                var tag = wrapped.getClass()
                    .getDeclaredField("tag");
                tag.setAccessible(true);
                return (NBTTagCompound) ((NBTTagCompound) tag.get(wrapped)).getCompoundTag("data")
                    .copy();
            }
        }
        return null;
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "tombstone_publication")
    public static void safeCanceledGraveRetriesOnceAndUnknownPreparedHandleIsNeverRecreated(GameTestHelper helper)
        throws Exception {
        var f = fixture(helper);
        var events = new Events(f);
        events.cancelGrave = true;
        var maid = source(f, true, 8);
        maid.setHealth(0);
        maid.onDeath(DamageSource.generic);
        var token = f.ledger()
            .deathToken(maid.getUniqueID());
        helper.assertTrue(
            events.graves.size() == 1 && entry(f, token).getCompoundTag("TombstonePublication")
                .hasNoTags()
                && f.ledger()
                    .pendingTombstones(helper.getWorld().provider.dimensionId)
                    .contains(token),
            "Safe native grave rejection was not durably retryable or double-preserve republished immediately");
        events.cancelGrave = false;
        MaidDeathService.retryPending(helper.getWorld());
        MaidDeathService.retryPending(helper.getWorld());
        helper.assertTrue(
            events.graves.size() == 2 && entry(f, token).getBoolean("Published"),
            "Safely canceled grave did not retry to exactly one native confirmed handle");
        var grave = events.graves.get(1);
        var saved = new NBTTagCompound();
        f.ledger()
            .writeToNBT(saved);
        var pending = find(saved, token);
        pending.setBoolean("Published", false);
        pending.getCompoundTag("TombstonePublication")
            .setString("Stage", "PREPARED");
        f.ledger()
            .readFromNBT(saved); // 明确构造恢复格式，不冒称此行是实际硬崩溃
        var changed = new NBTTagCompound();
        grave.writeToNBT(changed);
        changed.setString("MaidName", "unknown prepared successor");
        grave.readFromNBT(changed);
        MaidDeathService.retryPending(helper.getWorld());
        captureFault(f, "tombstone_publication/" + token);
        var held = entry(f, token).getCompoundTag("TombstonePublication");
        helper.assertTrue(
            "HELD".equals(held.getString("Stage"))
                && "unknown prepared successor".equals(
                    held.getCompoundTag("Observed")
                        .getString("MaidName"))
                && MaidSpawnChecks.present(grave)
                && "unknown prepared successor".equals(grave.getMaidName()),
            "Resumed unknown handle was deleted, overwritten or left in repeated confirmation");
        ReturnedMaterialTests.saveChunk(f, grave);
        evidence(
            f,
            "unknown-grave-preserved",
            held.getCompoundTag("Observed"),
            ReturnedMaterialTests.diskEntity(f, grave));
        int calls = events.graves.size();
        MaidDeathService.retryPending(helper.getWorld());
        MaidDeathService.retryPending(helper.getWorld());
        helper.assertTrue(
            events.graves.size() == calls && f.ledger()
                .reserveTombstone(token) == null,
            "Held prepared handle was recreated or admitted original escrow");
        System.out.println("TLMNH_GRAVE_PUBLICATION_PASS safeCancel=oneRetry unknownPrepared=held+intact newSpawn=0");
        helper.succeed();
    }

    private static LifecycleConservationTests.Fixture fixture(GameTestHelper helper) {
        helper.assertTrue(
            IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY),
            "Native death publication requires its external capability to be available");
        return new LifecycleConservationTests.Fixture(helper);
    }

    private static EntityMaid source(LifecycleConservationTests.Fixture f, boolean owned, int x) {
        var maid = new EntityMaid(f.helper.getWorld());
        if (owned) f.helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Death fixture owner binding failed");
        f.owned.add(maid);
        f.helper.spawnEntity(maid, x + .5, 1, 4.5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setPickup(false);
        return maid;
    }

    private static ItemStack stack(int count) {
        var result = new ItemStack(Items.arrow, count);
        result.setTagCompound(new NBTTagCompound());
        result.getTagCompound()
            .setIntArray("Opaque", new int[] { 9, count, 2 });
        return result;
    }

    private static NBTTagCompound opaque() {
        var raw = new NBTTagCompound();
        raw.setString("MissingRegistry", "unrecognized raw recovery");
        raw.setIntArray("Opaque", new int[] { 7, 2, 9 });
        return raw;
    }

    private static NBTTagCompound nativeMaid(EntityMaid maid) {
        var raw = new NBTTagCompound();
        raw.setString("id", EntityList.getEntityString(maid));
        maid.writeToNBT(raw);
        return (NBTTagCompound) raw.copy();
    }

    private static NBTTagCompound find(NBTTagCompound data, UUID token) {
        if (token == null) return null;
        var rows = data.getTagList("Entries", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (token.toString()
            .equals(
                rows.getCompoundTagAt(i)
                    .getString("Token")))
            return rows.getCompoundTagAt(i);
        return null;
    }

    private static NBTTagCompound entry(LifecycleConservationTests.Fixture f, UUID token) throws Exception {
        return find(ReturnedMaterialTests.diskLedger(f), token);
    }

    private static void blockLedgerOnce(LifecycleConservationTests.Fixture f, Runnable action) throws Exception {
        var worldRoot = f.helper.getWorld()
            .getSaveHandler()
            .getWorldDirectory()
            .getCanonicalFile()
            .toPath();
        var target = f.helper.getWorld()
            .getSaveHandler()
            .getMapFileFromName(MaidLifecycleData.NAME)
            .getCanonicalFile()
            .toPath();
        f.helper.assertTrue(
            target.startsWith(worldRoot) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS),
            "Controlled ledger must be a regular file inside this owned QA world");
        var backup = target.resolveSibling(target.getFileName() + ".qa-death-" + UUID.randomUUID());
        var sentinel = target.resolve("tlmnh-qa-death-owner");
        byte[] nonce = UUID.randomUUID()
            .toString()
            .getBytes(StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(target);
        boolean moved = false, blocked = false;
        Throwable primary = null;
        try {
            f.helper.assertTrue(!Files.exists(backup), "Unknown existing death-IO backup");
            Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            Files.createDirectory(target);
            blocked = true;
            Files.write(sentinel, nonce, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            action.run();
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                if (blocked) {
                    f.helper.assertTrue(
                        Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                            && Files.isRegularFile(sentinel, LinkOption.NOFOLLOW_LINKS)
                            && Arrays.equals(nonce, Files.readAllBytes(sentinel)),
                        "Unknown death-IO blocker successor");
                    int count = 0;
                    try (var children = Files.newDirectoryStream(target)) {
                        for (var child : children) {
                            f.helper.assertTrue(child.equals(sentinel), "Unknown child in death-IO blocker");
                            count++;
                        }
                    }
                    f.helper.assertTrue(count == 1, "Exact nonce sentinel directory required for cleanup");
                    Files.delete(sentinel);
                    Files.delete(target);
                }
                if (moved) {
                    f.helper.assertTrue(
                        !Files.exists(target) && Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                            && Arrays.equals(original, Files.readAllBytes(backup)),
                        "Unknown ledger backup successor retained");
                    Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (Exception | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private static void captureFault(LifecycleConservationTests.Fixture f, String key) {
        var exact = IntegrationGuard.faults(Side.SERVER)
            .get(key);
        f.helper.assertTrue(
            !f.priorFaults.containsKey(key) && exact != null,
            "Expected unique death-fixture diagnostic missing: " + key);
        f.helper.afterTest(() -> {
            synchronized (IntegrationGuard.class) {
                try {
                    var field = IntegrationGuard.class.getDeclaredField("FAULTS");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var all = (Map<Side, Map<String, IntegrationGuard.Fault>>) field.get(null);
                    var faults = all.get(Side.SERVER);
                    f.helper.assertTrue(
                        faults != null && faults.get(key) == exact,
                        "Refusing to remove an unknown diagnostic successor");
                    faults.remove(key);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot isolate exact death-fixture diagnostic", failure);
                }
            }
        });
    }

    private static void evidence(LifecycleConservationTests.Fixture f, String name, NBTTagCompound expected,
        NBTTagCompound actual) throws Exception {
        var result = new JsonObject();
        result.add("expected", SleepAttributePortSnapshot.binaryNbt(expected));
        result.add("actual", SleepAttributePortSnapshot.binaryNbt(actual));
        Files.write(
            java.nio.file.Paths.get(
                System.getProperty("horizonqa.reportDir"),
                "death-" + f.owner.getUniqueID() + "-" + name + ".json"),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(result)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 只输出不变提交记录；关服后的真实.dat核对由外层只读检查完成 */
    private static void checkpoint(LifecycleConservationTests.Fixture f, String name, String collection, UUID key,
        NBTTagCompound expected, String... compareFields) throws Exception {
        f.helper.assertTrue(expected != null, "Persistence checkpoint requires an actual committed native row");
        var row = new JsonObject();
        row.addProperty("collection", collection);
        row.addProperty("key", key.toString());
        var fields = new JsonArray();
        for (var field : compareFields) fields.add(new com.google.gson.JsonPrimitive(field));
        row.add("compareFields", fields);
        row.addProperty(
            "expectedBinaryNbt",
            SleepAttributePortSnapshot.binaryNbt(expected)
                .get("data")
                .getAsString());
        var rows = new JsonArray();
        rows.add(row);
        var result = new JsonObject();
        result.addProperty("schema", "death-persistence-checkpoint-v1");
        result.addProperty("case", name);
        result.add("rows", rows);
        Files.write(
            java.nio.file.Paths.get(
                System.getProperty("horizonqa.reportDir"),
                "death-persistence-" + f.owner.getUniqueID() + "-" + name + ".json"),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(result)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void artifacts() throws Exception {
        System.out.println(
            "TLMNH_DEATH_PRODUCTION_ARTIFACT=" + DevelopmentArtifactIdentity.capture(
                MaidDeathService.class,
                MaidLifecycleData.class,
                MaidLifecycleEvents.class,
                MaidLifecycleService.class,
                EntityMaid.class,
                Class.forName("com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathIngress"),
                Class.forName("com.tianluoqaq.tlmnh.compat.hodgepodge.common.storage.HodgepodgeWorldDataBarrier"),
                Class.forName("com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleDurability")));
        System.out.println(
            "TLMNH_DEATH_OBSERVER_ARTIFACT=" + DevelopmentArtifactIdentity
                .capture(DeathIngressTests.class, LifecycleConservationTests.class, ReturnedMaterialTests.class));
    }

    public static final class Events {

        final LifecycleConservationTests.Fixture f;
        final List<MaidTombstoneEntity> graves = new ArrayList<>();
        final List<EntityItem> wildDrops = new ArrayList<>();
        EntityMaid wild;
        boolean cancelGrave, cancelDrops;

        Events(LifecycleConservationTests.Fixture f) {
            this.f = f;
            MinecraftForge.EVENT_BUS.register(this);
            f.helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(this));
        }

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (event.world == f.helper.getWorld() && event.entity instanceof MaidTombstoneEntity grave
                && f.owner.getUniqueID()
                    .equals(grave.getOwnerId())) {
                graves.add(grave);
                f.owned.add(grave);
                if (cancelGrave) event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void drops(LivingDropsEvent event) {
            if (event.entityLiving != wild || event.entityLiving.worldObj != f.helper.getWorld()) return;
            for (var item : event.drops) {
                wildDrops.add(item);
                f.owned.add(item);
            }
            if (cancelDrops) event.setCanceled(true);
        }
    }
}
