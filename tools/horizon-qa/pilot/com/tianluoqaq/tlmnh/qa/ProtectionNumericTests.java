package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidFilmRecovery;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.RecoveryParcel;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ProtectionNumericTests {

    private ProtectionNumericTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "protection_numeric")
    public static void numericTypesAndWideLiveStacksRemainLossless(GameTestHelper helper) {
        IntegrationGuard.resetForDevelopment();
        helper.afterTest(IntegrationGuard::resetForDevelopment);
        helper.afterTest(() -> ProtectionCanary.rejectDamageQueries = false);
        var ordinary = new ItemStack(Items.golden_apple, 3, 1);
        ordinary.setStackDisplayName("before snapshot");
        var snapshot = ProtectedItemCodec.encode(ordinary);
        ordinary.setStackDisplayName("after snapshot");
        helper.assertTrue(
            snapshot.getCompoundTag("tag")
                .getCompoundTag("display")
                .getString("Name")
                .equals("before snapshot"),
            "Persisted snapshot aliases the live item NBT");
        NBTTagCompound[] malformed = new NBTTagCompound[4];
        for (int i = 0; i < malformed.length; i++) malformed[i] = (NBTTagCompound) snapshot.copy();
        malformed[0].setInteger("Count", 259);
        malformed[1].setFloat("Damage", 1.5F);
        malformed[2].getCompoundTag("TLMNHProtection")
            .setInteger("ExactCount", 4);
        malformed[3].setShort("Damage", (short) -7);
        malformed[3].removeTag("TLMNHProtection");
        int nativeDamage = ItemStack.loadItemStackFromNBT(malformed[3])
            .getItemDamage();
        for (var raw : malformed) {
            var parcel = ProtectedItemCodec.decode(raw);
            for (int cycle = 0; cycle < 3; cycle++)
                parcel = ProtectedItemCodec.decode(ProtectedItemCodec.encode(parcel));
            helper.assertTrue(
                parcel.getItem() == RecoveryParcel.ITEM && raw.equals(
                    parcel.getTagCompound()
                        .getCompoundTag("Original"))
                    && ProtectedItemCodec.restore(parcel) == null,
                "Numeric normalization silently changed the original payload");
        }

        var maid = new EntityMaid(helper.getWorld());
        maid.getBrainCoordinator()
            .setSuspended(true);
        helper.afterTest(maid::setDead);
        var large = new ItemStack(Items.golden_apple, 1000, 40000);
        large.setStackDisplayName("wide live values");
        maid.setOffhandItem(large);
        var expected = (NBTTagCompound) maid.getOffhandItem()
            .getTagCompound()
            .getCompoundTag("Original")
            .copy();
        helper.assertTrue(
            expected.getCompoundTag("TLMNHProtection")
                .getInteger("ExactCount") == 1000
                && expected.getCompoundTag("TLMNHProtection")
                    .getInteger("ExactDamage") == 40000,
            "Wide count/damage truncated before offhand admission");
        for (int cycle = 0; cycle < 3; cycle++) {
            var saved = new NBTTagCompound();
            maid.writeToNBT(saved);
            maid.readFromNBT(saved);
            helper.assertTrue(
                maid.getOffhandItem()
                    .getItem() == RecoveryParcel.ITEM && expected.equals(
                        maid.getOffhandItem()
                            .getTagCompound()
                            .getCompoundTag("Original")),
                "Wide snapshot changed over entity persistence");
        }
        maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.golden_apple, 130, 17));
        maid.setCurrentItemOrArmor(0, new ItemStack(Items.golden_apple, 256, 18));
        var cabinet = new TileEntitySnackCabinet();
        cabinet.setInventorySlotContents(0, new ItemStack(Items.golden_apple, 1000, 19));
        helper.assertTrue(
            maid.getMaidInventory()
                .getStackInSlot(0)
                .getItem() == RecoveryParcel.ITEM
                && maid.getHeldItem()
                    .getItem() == RecoveryParcel.ITEM
                && cabinet.getStackInSlot(0)
                    .getItem() == RecoveryParcel.ITEM,
            "An inventory entrance still clips excess items");

        var canary = new ItemStack(PilotMod.PROTECTION_CANARY, 3, 17);
        ProtectionCanary.rejectDamageQueries = true;
        var encoded = ProtectedItemCodec.encode(canary);
        helper.assertTrue(
            encoded.getCompoundTag("TLMNHProtection")
                .getBoolean("Complete")
                && encoded.getCompoundTag("TLMNHProtection")
                    .getInteger("ExactDamage") == 17,
            "Persistence invoked an external damage callback");
        ProtectionCanary.rejectDamageQueries = false;
        var restored = ProtectedItemCodec.decode(encoded);
        helper.assertTrue(
            restored.getItem() == PilotMod.PROTECTION_CANARY && restored.stackSize == 3
                && restored.getItemDamage() == 17,
            "Ordinary new snapshot failed to restore");
        var legacy = (NBTTagCompound) snapshot.copy();
        legacy.getCompoundTag("TLMNHProtection")
            .setInteger("Schema", 1);
        helper.assertTrue(
            ProtectedItemCodec.decode(legacy)
                .getItem() == Items.golden_apple,
            "Prior schema 1 was rejected");
        System.out.println(
            "TLMNH_PROTECTION_NUMERIC_PASS malformed=4 cycles=3 nativeNegativeDamage=" + nativeDamage
                + " wideCount=1000 wideDamage=40000 entrances=4 externalDamageCalls=none");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "protection_delivery")
    public static void externalPublicationFailureKeepsEscrowAndStopsRepeatedCallbacks(GameTestHelper helper) {
        helper.assertTrue(
            IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY),
            "Actual item API fault test requires this capability to be initially available");
        var count = new AtomicInteger();
        var hook = new PublicationFailureHook(helper, count);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var item : hook.outputs) if (item.worldObj == helper.getWorld() && (helper.getWorld()
                .getEntityByID(item.getEntityId()) == null
                || helper.getWorld()
                    .getEntityByID(item.getEntityId()) == item))
                helper.getWorld()
                    .removeEntity(item);
        });
        var pos = helper.absolute(5, 2, 4);
        var stack = new ItemStack(PilotMod.PROTECTION_CANARY, 7, 17);
        var token = MaidFilmRecovery.returnOwnedItem(helper.getWorld(), stack, pos.x(), pos.y(), pos.z());
        var ledger = MaidLifecycleData.get(helper.getWorld());
        MaidFilmRecovery.retryPending(helper.getWorld());
        var fault = IntegrationGuard.faults(Side.SERVER)
            .get(ExternalFeature.ITEM_DELIVERY.id);
        helper.assertTrue(
            count.get() == 1 && fault != null
                && fault.cause instanceof NoSuchMethodError
                && !IntegrationGuard.available(Side.SERVER, ExternalFeature.ITEM_DELIVERY),
            "Failed external publication API was retried after its capability circuit break");
        var saved = new NBTTagCompound();
        ledger.writeToNBT(saved);
        var rows = saved.getTagList("ReturnedFilms", 10);
        NBTTagCompound raw = null;
        for (int i = 0; i < rows.tagCount(); i++) if (token.toString()
            .equals(
                rows.getCompoundTagAt(i)
                    .getString("Id")))
            raw = rows.getCompoundTagAt(i)
                .getCompoundTag("Stack");
        helper.assertTrue(
            raw != null && raw.equals(ProtectedItemCodec.encode(stack))
                && ledger.returnedPhase(token) == MaidLifecycleData.ReturnedPhase.READY,
            "Safely retracted API failure lost its complete original escrow");
        hook.fail = false;
        synchronized (IntegrationGuard.class) {
            try {
                var field = IntegrationGuard.class.getDeclaredField("FAULTS");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var all = (Map<Side, Map<String, IntegrationGuard.Fault>>) field.get(null);
                var server = all.get(Side.SERVER);
                helper.assertTrue(
                    server != null && server.get(ExternalFeature.ITEM_DELIVERY.id) == fault,
                    "Fixture refused to clear an unknown API fault successor");
                server.remove(ExternalFeature.ITEM_DELIVERY.id);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not restore the exact injected API fault", failure);
            }
        }
        MaidFilmRecovery.retryPending(helper.getWorld());
        MaidFilmRecovery.retryPending(helper.getWorld());
        helper.assertTrue(
            count.get() == 2 && ledger.returnedPhase(token) == null,
            "Restored fixture API did not complete exactly one actual native publication");
        System.out.println(
            "TLMNH_PROTECTION_DELIVERY_PASS faultCallbacks=1 escrow=retained capability=stopped restoredApi=oneNativeDelivery");
        helper.succeedWhen(() -> true);
    }

    public static final class PublicationFailureHook {

        private final GameTestHelper helper;
        private final AtomicInteger count;
        final java.util.List<EntityItem> outputs = new ArrayList<>();
        boolean fail = true;

        public PublicationFailureHook(GameTestHelper helper, AtomicInteger count) {
            this.helper = helper;
            this.count = count;
        }

        @SubscribeEvent
        public void join(EntityJoinWorldEvent event) {
            if (event.world == helper.getWorld() && event.entity instanceof EntityItem item
                && item.getEntityItem()
                    .getItem() == PilotMod.PROTECTION_CANARY) {
                count.incrementAndGet();
                outputs.add(item);
                if (fail) throw new NoSuchMethodError("QA external item publication API removed");
            }
        }
    }
}
