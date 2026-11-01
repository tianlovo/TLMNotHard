package com.tianluoqaq.tlmnh.common.protection;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import cpw.mods.fml.relauncher.Side;

/** 不覆盖失配基线，恢复原环境或经验证的新合同后才能重新接入 */
public final class CompatibilityLedger extends WorldSavedData {

    public static final String NAME = "TLMNH_Compatibility";
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
                new IllegalStateException("Existing compatibility ledger unreadable; no replacement baseline"));
            return;
        }
        if (ledger == null) {
            var existing = world.getSaveHandler()
                .getMapFileFromName(NAME);
            if (existing != null && existing.exists()) {
                for (var feature : ExternalFeature.values()) if (!feature.clientOnly) IntegrationGuard.trip(
                    Side.SERVER,
                    feature,
                    new IllegalStateException(
                        "Existing compatibility ledger could not be read; baseline not replaced"));
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
        if ((readComplete || !data.hasNoTags()) && (!data.hasKey("Schema", 3) || data.getInteger("Schema") != 1)) {
            for (var feature : ExternalFeature.values())
                if (feature.clientOnly == (side == Side.CLIENT)) IntegrationGuard.trip(
                    side,
                    feature,
                    new IllegalStateException("Newer compatibility schema retained without downgrade"));
            return;
        }
        data.setInteger("Schema", 1);
        for (var feature : ExternalFeature.values()) {
            if (feature.clientOnly != (side == Side.CLIENT)) continue;
            String current = feature.fingerprint();
            if (current.contains("=unreadable;")) {
                IntegrationGuard
                    .trip(side, feature, new IllegalStateException("Dependency source fingerprint unavailable"));
                continue;
            }
            var saved = data.getCompoundTag(feature.id);
            if (data.hasKey(feature.id)
                && (!data.hasKey(feature.id, 10) || !saved.hasKey("Contract", 3) || !saved.hasKey("Fingerprint", 8))) {
                IntegrationGuard
                    .trip(side, feature, new IllegalStateException("Malformed compatibility record retained"));
                continue;
            }
            if (!data.hasKey(feature.id, 10) || saved.getInteger("Contract") < feature.contractRevision) {
                saved = new NBTTagCompound();
                saved.setInteger("Contract", feature.contractRevision);
                saved.setString("Fingerprint", current);
                data.setTag(feature.id, saved);
                markDirty();
            } else if (saved.getInteger("Contract") != feature.contractRevision || !saved.getString("Fingerprint")
                .equals(current)) {
                    IntegrationGuard.trip(
                        side,
                        feature,
                        new IllegalStateException(
                            "Dependency environment changed for " + feature.id
                                + "; stored="
                                + saved.getString("Fingerprint")
                                + "; current="
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
