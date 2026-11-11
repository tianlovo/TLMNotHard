package com.tianluoqaq.tlmnh.common.protection;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import cpw.mods.fml.relauncher.Side;

/** 不覆盖失配基线，恢复原环境或经验证的新合同后才能重新接入 */
public final class CompatibilityLedger extends WorldSavedData {

    public static final String NAME = ProtectionIds.KEY_TLMNH_COMPATIBILITY;
    private NBTTagCompound data = new NBTTagCompound();
    private boolean readComplete;

    public CompatibilityLedger(String name) {
        super(name);
    }

    public static void verify(World world) {
        if (world == null || world.isRemote || world.mapStorage == null) return;
        var ledger = (CompatibilityLedger) world.mapStorage.loadData(CompatibilityLedger.class, NAME);
        var file = world.getSaveHandler()
            .getMapFileFromName(NAME);
        // MapStorage会吞读取异常并返回已构造但未读完的对象，不能将其误判为新世界
        if (file != null && file.exists() && (ledger == null || !ledger.readComplete)) {
            for (var feature : ExternalFeature.values()) if (!feature.clientOnly) IntegrationGuard.trip(
                Side.SERVER,
                feature,
                new IllegalStateException(ProtectionDiagnostics.MSG_EXISTING_COMPATIBILITY_LEDGER_UNREADABLE_NO));
            return;
        }
        if (ledger == null) {
            var existing = world.getSaveHandler()
                .getMapFileFromName(NAME);
            if (existing != null && existing.exists()) {
                for (var feature : ExternalFeature.values()) if (!feature.clientOnly) IntegrationGuard.trip(
                    Side.SERVER,
                    feature,
                    new IllegalStateException(ProtectionDiagnostics.MSG_EXISTING_COMPATIBILITY_LEDGER_COULD_NOT));
                return;
            }
            ledger = new CompatibilityLedger(NAME);
            world.mapStorage.setData(NAME, ledger);
        }
        ledger.check();
        // 新建实例在首次保存后仍是MapStorage中的同一对象，不应误判为一次失败的磁盘读取
        ledger.readComplete = true;
    }

    public void check() {
        check(Side.SERVER);
    }

    public void check(Side side) {
        if ((readComplete || !data.hasNoTags()) && (!data.hasKey(ProtectionIds.KEY_SCHEMA, Constants.NBT.TAG_INT)
            || data.getInteger(ProtectionIds.KEY_SCHEMA) != ProtectionTuning.COMPATIBILITY_LEDGER_CHECK_UNIT_STEP)) {
            for (var feature : ExternalFeature.values())
                if (feature.clientOnly == (side == Side.CLIENT)) IntegrationGuard.trip(
                    side,
                    feature,
                    new IllegalStateException(ProtectionDiagnostics.MSG_NEWER_COMPATIBILITY_SCHEMA_RETAINED_WITHOUT));
            return;
        }
        data.setInteger(ProtectionIds.KEY_SCHEMA, ProtectionTuning.COMPATIBILITY_LEDGER_CHECK_UNIT_STEP);
        for (var feature : ExternalFeature.values()) {
            if (feature.clientOnly != (side == Side.CLIENT)) continue;
            String current = feature.fingerprint();
            if (current.contains(ProtectionIds.ID_UNREADABLE)) {
                IntegrationGuard.trip(
                    side,
                    feature,
                    new IllegalStateException(ProtectionDiagnostics.MSG_DEPENDENCY_SOURCE_FINGERPRINT_UNAVAILABLE));
                continue;
            }
            var saved = data.getCompoundTag(feature.id);
            if (data.hasKey(feature.id) && (!data.hasKey(feature.id, Constants.NBT.TAG_COMPOUND)
                || !saved.hasKey(ProtectionIds.KEY_CONTRACT, Constants.NBT.TAG_INT)
                || !saved.hasKey(ProtectionIds.KEY_FINGERPRINT, Constants.NBT.TAG_STRING))) {
                IntegrationGuard.trip(
                    side,
                    feature,
                    new IllegalStateException(ProtectionDiagnostics.MSG_MALFORMED_COMPATIBILITY_RECORD_RETAINED));
                continue;
            }
            if (!data.hasKey(feature.id, Constants.NBT.TAG_COMPOUND)
                || saved.getInteger(ProtectionIds.KEY_CONTRACT) < feature.contractRevision) {
                saved = new NBTTagCompound();
                saved.setInteger(ProtectionIds.KEY_CONTRACT, feature.contractRevision);
                saved.setString(ProtectionIds.KEY_FINGERPRINT, current);
                data.setTag(feature.id, saved);
                markDirty();
            } else if (saved.getInteger(ProtectionIds.KEY_CONTRACT) != feature.contractRevision
                || !saved.getString(ProtectionIds.KEY_FINGERPRINT)
                    .equals(current)) {
                        IntegrationGuard.trip(
                            side,
                            feature,
                            new IllegalStateException(
                                ProtectionDiagnostics.MSG_DEPENDENCY_ENVIRONMENT_CHANGED_FOR + feature.id
                                    + ProtectionDiagnostics.MSG_STORED
                                    + saved.getString(ProtectionIds.KEY_FINGERPRINT)
                                    + ProtectionDiagnostics.MSG_CURRENT
                                    + current));
                    }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound source) {
        data = (NBTTagCompound) source.copy();
        readComplete = true;
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
