package com.tianluoqaq.tlmnh.common.development.persistence;

import java.lang.management.ManagementFactory;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

/** 真实联网旁观玩家的原生player.dat保存/跨进程观察，不改库存或模拟登录 */
public final class MaidPlayerDiskProbe {

    private static final String PREFIX = "TLMNH_PlayerDisk_";

    private MaidPlayerDiskProbe() {}

    public static void run(EntityPlayerMP player, String action, String runId) throws Exception {
        if (!DevelopmentFlags.isDevCommandsEnabled() || !runId.matches("[0-9]{10,20}"))
            throw new IllegalArgumentException("Explicit development disk run ID required");
        AnimationLab.verifyObserver(player);
        var server = MinecraftServer.getServer();
        require(server.isDedicatedServer(), "Actual dedicated server player.dat path required");
        var players = server.getConfigurationManager();
        require(
            players.playerEntityList.size() == 1 && players.playerEntityList.get(0) == player,
            "Exactly one actual connected observer required");
        require(
            player.playerNetServerHandler != null && player.inventory.getItemStack() == null
                && player.openContainer == player.inventoryContainer,
            "Live native player with closed menu and empty cursor required");
        var overworld = server.worldServerForDimension(0);
        var storage = overworld.mapStorage;
        String name = PREFIX + runId;
        var existing = storage.loadData(ProbeData.class, name);
        var expectedFile = overworld.getSaveHandler()
            .getMapFileFromName(name);
        require(
            existing != null || expectedFile == null || !expectedFile.exists(),
            "Unreadable existing disk expectation must not be replaced");
        String digest = DevelopmentArtifactIdentity.capture(MaidPlayerDiskProbe.class)
            .get("artifactSha256")
            .getAsString();
        long processStart = ManagementFactory.getRuntimeMXBean()
            .getStartTime();
        ProbeData data;
        if (action.equals("capture")) {
            require(existing == null, "Existing disk expectation must not be overwritten");
            data = new ProbeData(name);
            data.owner = player.getUniqueID()
                .toString();
            data.artifact = digest;
            data.processStart = processStart;
            data.expected = stable(nativeSnapshot(player));
        } else if (action.equals("verify")) {
            require(existing instanceof ProbeData, "Capture must survive an actual world save first");
            data = (ProbeData) existing;
            require(
                data.schema == 1 && data.owner.equals(
                    player.getUniqueID()
                        .toString())
                    && data.artifact.equals(digest),
                "Disk owner/schema/candidate differs from capture");
            require(
                data.processStart > 0 && data.processStart != processStart,
                "Verification requires a different actual server process");
            require(
                data.expected.equals(stable(nativeSnapshot(player))),
                "Native login changed the complete selected player inventory or persisted data");
        } else throw new IllegalArgumentException("Use capture or verify");

        // 使用实际SCM与SaveHandler，不向文件写自制玩家数据，也不调用读盘方法修改活玩家
        players.saveAllPlayerData();
        var disk = players.getPlayerNBT(player);
        require(
            disk != null && disk.hasKey("UUIDMost", Constants.NBT.TAG_LONG)
                && disk.hasKey("UUIDLeast", Constants.NBT.TAG_LONG)
                && disk.getLong("UUIDMost") == player.getUniqueID()
                    .getMostSignificantBits()
                && disk.getLong("UUIDLeast") == player.getUniqueID()
                    .getLeastSignificantBits(),
            "Actual native disk player identity differs");
        require(
            data.expected.equals(stable(disk)) && data.expected.equals(stable(nativeSnapshot(player))),
            "Native player.dat write changed or lost the selected complete data");
        if (action.equals("capture")) storage.setData(name, data);
        else data.verified = true;
        data.markDirty();
        TLMNotHard.LOG.info(
            "TLMNH_PLAYER_DISK_{}_PASS run={} uuid={} candidate={} inventory_entries={} raw_inventory_and_persisted_match=true different_process={} full_player_nbt_equivalent=false",
            action.toUpperCase(Locale.ROOT),
            runId,
            data.owner,
            digest,
            data.expected.getTagList("Inventory", Constants.NBT.TAG_COMPOUND)
                .tagCount(),
            action.equals("verify"));
    }

    private static NBTTagCompound nativeSnapshot(EntityPlayerMP player) {
        var raw = new NBTTagCompound();
        player.writeToNBT(raw);
        return raw;
    }

    private static NBTTagCompound stable(NBTTagCompound raw) {
        require(
            raw.hasKey("Inventory", Constants.NBT.TAG_LIST) && raw.hasKey("EnderItems", Constants.NBT.TAG_LIST)
                && raw.hasKey("SelectedItemSlot", Constants.NBT.TAG_INT),
            "Native stable inventory fields unavailable");
        var selected = new NBTTagCompound();
        for (String key : new String[] { "Inventory", "EnderItems", "SelectedItemSlot" }) selected.setTag(
            key,
            raw.getTag(key)
                .copy());
        if (raw.hasKey("ForgeData")) {
            require(raw.hasKey("ForgeData", Constants.NBT.TAG_COMPOUND), "Opaque ForgeData must be retained");
            var forge = raw.getCompoundTag("ForgeData");
            if (forge.hasKey("PlayerPersisted")) selected.setTag(
                "PlayerPersisted",
                forge.getTag("PlayerPersisted")
                    .copy());
        }
        return selected;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Player disk observation: " + message);
    }

    public static final class ProbeData extends WorldSavedData {

        private int schema = 1;
        private String owner = "", artifact = "";
        private long processStart;
        private boolean verified;
        private NBTTagCompound expected = new NBTTagCompound();

        public ProbeData(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            schema = tag.getInteger("Schema");
            owner = tag.getString("Owner");
            artifact = tag.getString("ArtifactSha256");
            processStart = tag.getLong("CaptureProcessStart");
            verified = tag.getBoolean("Verified");
            expected = (NBTTagCompound) tag.getCompoundTag("Expected")
                .copy();
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            tag.setInteger("Schema", schema);
            tag.setString("Owner", owner);
            tag.setString("ArtifactSha256", artifact);
            tag.setLong("CaptureProcessStart", processStart);
            tag.setBoolean("Verified", verified);
            tag.setTag("Expected", expected.copy());
        }
    }
}
