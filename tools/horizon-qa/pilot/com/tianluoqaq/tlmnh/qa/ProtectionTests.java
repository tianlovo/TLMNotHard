package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidGravityFreeArrow;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.protection.CompatibilityLedger;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.ProtectionEvents;
import com.tianluoqaq.tlmnh.common.protection.RecoveryParcel;
import com.tianluoqaq.tlmnh.common.protection.RecoveryStore;

import cpw.mods.fml.common.registry.EntityRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ProtectionTests {

    private ProtectionTests() {}

    /** 同批顺序执行，避免隔离故障夹具相互修改进程熔断状态 */
    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "protection_contract")
    public static void losslessStorageAndUnavailableApis(GameTestHelper helper) throws Exception {
        IntegrationGuard.resetForDevelopment();
        helper.afterTest(IntegrationGuard::resetForDevelopment);
        var world = helper.getWorld();
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.randomUUID(), "QA-Protection"),
            new ItemInWorldManager(world));
        var manager = new NetworkManager(false);
        var channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        new NetHandlerPlayServer(MinecraftServer.getServer(), manager, owner);
        helper.afterTest(() -> {
            owner.setDead();
            channel.finish();
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
        });
        var maid = new EntityMaid(world);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.bindTo(owner.getUniqueID());
        var pos = helper.absolute(5, 1, 4);
        maid.setPosition(pos.x(), pos.y(), pos.z());
        helper.afterTest(maid::setDead);
        var apple = new ItemStack(Items.golden_apple, 3, 1);
        apple.setStackDisplayName("original protected apple");
        apple.getTagCompound()
            .setString(
                "UnrecognizedCustomData",
                UUID.randomUUID()
                    .toString());
        maid.setOffhandItem(apple.copy());

        var calls = new AtomicInteger();
        boolean fallback = IntegrationGuard.call(ExternalFeature.PLAYER_OFFHAND, () -> {
            calls.incrementAndGet();
            throw new NoSuchMethodError("QA removed offhand API");
        }, () -> false);
        IntegrationGuard.call(ExternalFeature.PLAYER_OFFHAND, () -> {
            calls.incrementAndGet();
            return true;
        }, () -> false);
        helper.assertTrue(
            !fallback && calls.get() == 1 && IntegrationGuard.available(ExternalFeature.NUTRITION),
            "Failed boundary was retried or unrelated capability disabled");
        for (int cycle = 0; cycle < 3; cycle++) {
            var saved = new NBTTagCompound();
            maid.writeToNBT(saved);
            maid.readFromNBT(saved);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(apple, maid.getOffhandItem()),
                "Offhand API failure lost apple count/damage/custom NBT on cycle " + cycle);
        }
        ProtectionEvents.warn(owner);
        ProtectionEvents.warn(owner);
        channel.runPendingTasks();
        int warnings = 0;
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof S02PacketChat chat) {
                var bytes = new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
                try {
                    chat.writePacketData(bytes);
                    if (bytes.readStringFromBuffer(32767)
                        .contains("TLMNH-P")) warnings++;
                } finally {
                    bytes.release();
                }
            }
            ReferenceCountUtil.release(packet);
        }
        helper.assertTrue(warnings == 1, "Expected one real S02PacketChat warning on isolated server player channel");
        System.out.println("TLMNH_PROTECTION118_PASS mechanism=removed-api appleCycles=3 chatPackets=" + warnings);

        var missing = ProtectedItemCodec.encode(apple);
        missing.getCompoundTag("TLMNHProtection")
            .setString("Registry", "removed:precious_item");
        missing.setString("FutureOpaqueData", "must survive unchanged");
        var sealed = ProtectedItemCodec.decode(missing);
        for (int cycle = 0; cycle < 3; cycle++) sealed = ProtectedItemCodec.decode(ProtectedItemCodec.encode(sealed));
        helper.assertTrue(
            sealed.getItem() == RecoveryParcel.ITEM && missing.equals(
                sealed.getTagCompound()
                    .getCompoundTag("Original"))
                && ProtectedItemCodec.restore(sealed) == null,
            "Missing registry payload lost or force-restored");
        helper.assertTrue(
            ItemStack.areItemStacksEqual(
                apple,
                ProtectedItemCodec.restore(ProtectedItemCodec.seal(ProtectedItemCodec.encode(apple), "qa"))),
            "Explicit compatible parcel restoration changed item");
        var legacy = apple.writeToNBT(new NBTTagCompound());
        helper.assertTrue(
            ProtectedItemCodec.restore(ProtectedItemCodec.seal(legacy, "legacy_no_registry")) == null,
            "Legacy parcel restored by numeric ID without persistent identity");
        var future = ProtectedItemCodec.encode(apple);
        future.getCompoundTag("TLMNHProtection")
            .setInteger("Schema", 99);
        helper.assertTrue(
            ProtectedItemCodec.decode(future)
                .getItem() == RecoveryParcel.ITEM,
            "Future item schema downgraded");
        var changed = ProtectedItemCodec.encode(apple);
        changed.getCompoundTag("TLMNHProtection")
            .setString("Dependency", "same-version:sha256:changed-code");
        helper.assertTrue(
            ProtectedItemCodec.decode(changed)
                .getItem() == RecoveryParcel.ITEM,
            "Changed owner fingerprint admitted");
        System.out.println(
            "TLMNH_PROTECTION118_PASS mechanism=unresolved-item exactRawCycles=3 futureSchema=sealed ownerChange=sealed");

        var cabinet = new TileEntitySnackCabinet();
        var storage = new NBTTagCompound();
        var entries = new NBTTagList();
        var first = ProtectedItemCodec.encode(apple);
        first.setByte("Slot", (byte) 0);
        entries.appendTag(first);
        entries.appendTag(first.copy());
        var outside = (NBTTagCompound) first.copy();
        outside.setByte("Slot", (byte) 120);
        entries.appendTag(outside);
        storage.setTag("Items", entries);
        cabinet.readFromNBT(storage);
        var roundTrip = new NBTTagCompound();
        cabinet.writeToNBT(roundTrip);
        cabinet.readFromNBT(roundTrip);
        helper.assertTrue(
            ItemStack.areItemStacksEqual(apple, cabinet.getStackInSlot(0)) && cabinet.recovery()
                .size() == 2,
            "Duplicate or unsupported slot overwrote/dropped items");
        Arrays.fill(owner.inventory.mainInventory, new ItemStack(net.minecraft.init.Blocks.cobblestone, 64));
        helper.assertTrue(
            !cabinet.recovery()
                .recoverOne(owner)
                && cabinet.recovery()
                    .size() == 2,
            "Full inventory lost recovery entry");
        owner.inventory.mainInventory[3] = null;
        helper.assertTrue(
            cabinet.recovery()
                .recoverOne(owner)
                && cabinet.recovery()
                    .size() == 1
                && owner.inventory.mainInventory[3].getItem() == RecoveryParcel.ITEM,
            "Recovery transfer was not single-owned");
        System.out.println(
            "TLMNH_PROTECTION118_PASS mechanism=malformed-inventory duplicate=retained outOfRange=retained fullInventory=retained");

        IntegrationGuard.resetForDevelopment();
        var sampleSource = java.nio.file.Files.createTempFile("tlmnh-dependency-", ".jar");
        try {
            java.nio.file.Files.write(sampleSource, new byte[] { 1, 2, 3 });
            String firstHash = com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint
                .source(sampleSource.toFile(), "fixed-version");
            java.nio.file.Files.write(sampleSource, new byte[] { 1, 2, 4 });
            String changedHash = com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint
                .source(sampleSource.toFile(), "fixed-version");
            helper.assertTrue(
                !firstHash.equals(changedHash) && firstHash.startsWith("fixed-version:sha256:")
                    && changedHash.startsWith("fixed-version:sha256:"),
                "Same-version source code changes share a fingerprint");
        } finally {
            java.nio.file.Files.deleteIfExists(sampleSource);
        }
        var emptyLedger = new CompatibilityLedger("empty-read");
        emptyLedger.readFromNBT(new NBTTagCompound());
        emptyLedger.check();
        var emptyAfter = new NBTTagCompound();
        emptyLedger.writeToNBT(emptyAfter);
        helper.assertTrue(
            emptyAfter.hasNoTags() && !IntegrationGuard.available(ExternalFeature.SHIELDS),
            "Empty loaded ledger treated as new world");
        IntegrationGuard.resetForDevelopment();
        var baseline = new CompatibilityLedger("qa");
        baseline.check();
        var original = new NBTTagCompound();
        baseline.writeToNBT(original);
        original.getCompoundTag(ExternalFeature.SHIELDS.id)
            .setString("Fingerprint", "same-version:sha256:different-code");
        baseline.readFromNBT(original);
        baseline.check();
        var retained = new NBTTagCompound();
        baseline.writeToNBT(retained);
        helper.assertTrue(
            !IntegrationGuard.available(ExternalFeature.SHIELDS) && retained.equals(original),
            "Changed baseline overwritten");
        var newer = (NBTTagCompound) original.copy();
        newer.setInteger("Schema", 99);
        newer.setString("UnknownFutureValue", "keep");
        baseline.readFromNBT(newer);
        baseline.check();
        retained = new NBTTagCompound();
        baseline.writeToNBT(retained);
        helper.assertTrue(retained.equals(newer), "Newer ledger schema overwritten");
        System.out.println(
            "TLMNH_PROTECTION118_PASS mechanism=environment-baseline changedFingerprint=disabled baseline=retained futureLedger=retained");

        maid.getMaidInventory()
            .recovery()
            .retain(missing);
        helper.assertTrue(MaidDeathService.preserve(maid), "Death escrow refused protected inventory");
        var ledger = MaidLifecycleData.get(world);
        var token = ledger.deathToken(maid.getUniqueID());
        ledger.markPublished(token);
        var contents = ledger.reserveTombstone(token);
        helper.assertTrue(
            contents != null && contents.tagCount() == 3
                && maid.getOffhandItem() == null
                && maid.getMaidInventory()
                    .recovery()
                    .size() == 0,
            "Death did not move apple, recovery parcel and film exactly once");
        ledger.rollback(token);
        MaidDeathService.preserve(maid);
        helper.assertTrue(
            ledger.reserveTombstone(token)
                .equals(contents),
            "Repeated death duplicated or replaced escrow");
        ledger.rollback(token);
        helper.afterTest(() -> {
            for (Object entity : new java.util.ArrayList<>(world.loadedEntityList))
                if (entity instanceof MaidTombstoneEntity tombstone && token.equals(tombstone.getToken()))
                    tombstone.setDead();
        });
        var filmIdentity = ledger.reserve(token, MaidLifecycleData.Kind.FILM);
        helper.assertTrue(
            !filmIdentity.hasKey(RecoveryStore.TAG) && !filmIdentity.hasKey("TLMNHOffhand"),
            "Film duplicates death inventory");
        ledger.rollback(token);
        helper.assertTrue(
            EntityRegistry.instance()
                .lookupModSpawn(MaidTombstoneEntity.class, true)
                .getModEntityId() == 1
                && EntityRegistry.instance()
                    .lookupModSpawn(MaidGravityFreeArrow.class, true)
                    .getModEntityId() == 2,
            "Entity spawn IDs collide");
        System.out.println(
            "TLMNH_PROTECTION118_PASS mechanism=death-escrow itemEntries=3 repeatedDeath=singleOwner filmInventory=excluded entityIds=distinct");

        IntegrationGuard.resetForDevelopment();
        var fatal = new OutOfMemoryError("QA sentinel, no allocation attempted");
        try {
            IntegrationGuard.call(
                ExternalFeature.NUTRITION,
                () -> { throw new IllegalStateException(new java.lang.reflect.InvocationTargetException(fatal)); },
                () -> null);
            throw new AssertionError("Wrapped VM-fatal error was swallowed");
        } catch (OutOfMemoryError propagated) {
            helper.assertTrue(propagated == fatal, "Different fatal error propagated");
        }
        helper.assertTrue(
            IntegrationGuard.available(ExternalFeature.NUTRITION),
            "Fatal error incorrectly converted into recoverable fault");
        ClassLoader isolated = new WithoutOptionalApis(ProtectionTests.class.getClassLoader());
        var shields = isolated.loadClass("com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems");
        shields.getMethod("inspect", ItemStack.class)
            .invoke(null, apple);
        helper.assertTrue(!IntegrationGuard.available(ExternalFeature.SHIELDS), "Missing shield API escaped fallback");
        var food = isolated.loadClass("com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile");
        Object profile = food.getMethod("inspect", ItemStack.class)
            .invoke(null, apple);
        helper.assertTrue(
            profile == null && !IntegrationGuard.available(ExternalFeature.NUTRITION),
            "Missing nutrition API escaped fallback");
        var potion = new ItemStack(Items.potionitem);
        var potionData = new NBTTagCompound();
        var effects = new NBTTagList();
        var effect = new NBTTagCompound();
        new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.waterBreathing.id, 800, 0)
            .writeCustomPotionEffectToNBT(effect);
        effects.appendTag(effect);
        potionData.setTag("CustomPotionEffects", effects);
        potion.setTagCompound(potionData);
        var independent = com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile.inspectBreathing(potion);
        helper.assertTrue(
            independent != null && independent.available(),
            "Nutrition fallback disabled independent native breathing potion");
        System.out.println(
            "TLMNH_PROTECTION118_PASS mechanism=optional-class-absence shieldApi=denied nutritionApi=denied scope=isolated-adapter-loading");
        helper.succeedWhen(() -> true);
    }

    private static final class WithoutOptionalApis extends ClassLoader {

        WithoutOptionalApis(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("mods.battlegear2.api.") || name.startsWith("squeek.applecore.api."))
                throw new ClassNotFoundException("QA intentionally removed " + name);
            if (!name.startsWith("com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems")
                && !name.startsWith("com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile"))
                return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    var output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                    byte[] bytes = output.toByteArray();
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException error) {
                    throw new ClassNotFoundException(name, error);
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }
}
