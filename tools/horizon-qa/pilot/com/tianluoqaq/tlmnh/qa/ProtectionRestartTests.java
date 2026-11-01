package com.tianluoqaq.tlmnh.qa;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.RecoveryParcel;

import cpw.mods.fml.common.Loader;

/** 三个真实进程共用专用世界；第二份同版本测试JAR移除Canary方法，第三次恢复原JAR */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ProtectionRestartTests {

    public static final String DATA_NAME = "TLMNH_QA_ProtectionRestart";

    private ProtectionRestartTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "protection_restart")
    public static void persistedProviderChangeAndExplicitRestoration(GameTestHelper helper) {
        String phase = System.getProperty("tlmnh.qaProtectionPhase", "");
        helper.assertTrue(
            phase.equals("baseline") || phase.equals("changed") || phase.equals("restored"),
            "Run with an explicit baseline/changed/restored process phase and the same dedicated test world");
        var world = helper.getWorld();
        var state = (State) world.mapStorage.loadData(State.class, DATA_NAME);
        String provider = DependencyFingerprint.mod("tlmnhqapilot");
        String version = Loader.instance()
            .getIndexedModList()
            .get("tlmnhqapilot")
            .getVersion();
        helper.assertTrue(
            version.equals("1") && provider.startsWith("1:sha256:"),
            "Test provider identity is not pinned to version 1");
        helper.assertTrue(
            IntegrationGuard.available(ExternalFeature.SHIELDS),
            "Canary probe started with an already-disabled boundary");
        // 直接字节码方法引用，变体真正移除该方法，而不是在同一进程人工抛一个模拟错误
        int api = IntegrationGuard.call(ExternalFeature.SHIELDS, () -> ProtectionCanary.apiRevision(), () -> -1);
        if (phase.equals("baseline")) {
            helper.assertTrue(state == null && api == 1, "Baseline world was not fresh or original API is unavailable");
            var file = world.getSaveHandler()
                .getMapFileFromName(DATA_NAME);
            helper.assertTrue(!file.exists(), "Existing restart data must not be replaced by a new baseline");
            state = new State(DATA_NAME);
            world.mapStorage.setData(DATA_NAME, state);
            var item = new ItemStack(PilotMod.PROTECTION_CANARY, 3, 17);
            var custom = new NBTTagCompound();
            custom.setString(
                "UniquePayload",
                UUID.randomUUID()
                    .toString());
            item.setTagCompound(custom);
            state.data.setInteger("Schema", 1);
            state.data.setString("Provider", provider);
            state.data.setString("Version", version);
            state.data.setTag("Original", ProtectedItemCodec.encode(item));
            state.data.setTag("Current", ProtectedItemCodec.encode(item));
        } else {
            helper.assertTrue(
                state != null && state.loaded && state.data.getInteger("Schema") == 1,
                "Previous process world data did not load from disk");
            helper.assertTrue(
                state.data.getString("Version")
                    .equals(version),
                "Provider version changed instead of only code");
            var original = state.data.getCompoundTag("Original");
            if (phase.equals("changed")) {
                helper.assertTrue(
                    state.data.getString("Stage")
                        .equals("baseline") && api == -1 && !provider.equals(state.data.getString("Provider")),
                    "Changed process did not load different same-version provider code");
                var fault = IntegrationGuard.faults()
                    .get(ExternalFeature.SHIELDS.id);
                helper.assertTrue(
                    fault != null && fault.cause instanceof NoSuchMethodError,
                    "Removed provider method was not caught by the actual adapter boundary");
                var parcel = ProtectedItemCodec.decode(state.data.getCompoundTag("Current"));
                helper.assertTrue(
                    parcel.getItem() == RecoveryParcel.ITEM && original.equals(
                        parcel.getTagCompound()
                            .getCompoundTag("Original"))
                        && ProtectedItemCodec.restore(parcel) == null,
                    "Changed provider consumed, rewrote or force-restored the original item");
                state.data.setTag("Current", ProtectedItemCodec.encode(parcel));
                state.data.setString("ChangedProvider", provider);
            } else {
                helper.assertTrue(
                    state.data.getString("Stage")
                        .equals("changed") && api == 1
                        && provider.equals(state.data.getString("Provider"))
                        && !provider.equals(state.data.getString("ChangedProvider")),
                    "Original provider was not restored byte-for-byte");
                var parcel = ProtectedItemCodec.decode(state.data.getCompoundTag("Current"));
                helper.assertTrue(
                    parcel.getItem() == RecoveryParcel.ITEM,
                    "Restart automatically unsealed a protected item");
                var restored = ProtectedItemCodec.restore(parcel);
                helper.assertTrue(
                    restored != null && restored.getItem() == PilotMod.PROTECTION_CANARY
                        && restored.stackSize == 3
                        && restored.getItemDamage() == 17
                        && original.getCompoundTag("tag")
                            .equals(restored.getTagCompound()),
                    "Explicit compatible restoration lost count/damage/custom NBT");
                state.data.setTag("Current", ProtectedItemCodec.encode(restored));
            }
        }
        state.data.setString("Stage", phase);
        state.markDirty();
        world.mapStorage.saveAllData();
        var expected = (NBTTagCompound) state.data.copy();
        var file = world.getSaveHandler()
            .getMapFileFromName(DATA_NAME);
        // 实装Hodgepodge将MapStorage写入原生IO队列，方法返回时不一定已经落盘
        helper.succeedWhen(() -> {
            if (!file.isFile()) return false;
            try (var input = new java.io.FileInputStream(file)) {
                var saved = net.minecraft.nbt.CompressedStreamTools.readCompressed(input)
                    .getCompoundTag("data");
                if (!saved.equals(expected)) return false;
            } catch (java.io.IOException pendingWrite) {
                return false;
            }
            System.out.println(
                "TLMNH_PROTECTION_RESTART_PASS phase=" + phase
                    + " provider="
                    + provider
                    + " version="
                    + version
                    + " api="
                    + api
                    + " persisted=true");
            return true;
        });
    }

    public static final class State extends WorldSavedData {

        NBTTagCompound data = new NBTTagCompound();
        boolean loaded;

        public State(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound source) {
            data = (NBTTagCompound) source.copy();
            loaded = true;
        }

        @Override
        public void writeToNBT(NBTTagCompound target) {
            for (Object key : data.func_150296_c()) {
                String name = (String) key;
                target.setTag(
                    name,
                    data.getTag(name)
                        .copy());
            }
        }
    }
}
