package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/** 单次出生事务的自有格式；物品、女仆及原生库存内部NBT保持不透明 */
final class MaidRedemptionRecord {

    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Version",
            "Stage",
            "Actor",
            "Candidate",
            "Dimension",
            "X",
            "Y",
            "Z",
            "Shrine",
            "Metadata",
            "Source",
            "Maid",
            "Inventory",
            "ResultInventory",
            "Cursor",
            "Selected",
            "Health",
            "Maximum",
            "Creative",
            "Tile",
            "ResultTile"));

    private MaidRedemptionRecord() {}

    static void validate(NBTTagCompound data) {
        for (Object key : data.func_150296_c()) if (!FIELDS.contains(key))
            throw new IllegalStateException("Unknown maid redemption field preserved: " + key);
        if (data.func_150296_c()
            .size() != FIELDS.size() || !data.hasKey("Version", Constants.NBT.TAG_INT)
            || data.getInteger("Version") != 1
            || !data.hasKey("Stage", Constants.NBT.TAG_STRING)
            || !(data.getString("Stage")
                .equals("PREPARED")
                || data.getString("Stage")
                    .equals("COSTS")))
            throw new IllegalStateException("Unknown maid redemption intent");
        for (String key : new String[] { "Actor", "Candidate" }) {
            if (!data.hasKey(key, Constants.NBT.TAG_STRING))
                throw new IllegalStateException("Invalid redemption identity");
            UUID.fromString(data.getString(key));
        }
        for (String key : new String[] { "Dimension", "Metadata", "Selected" })
            if (!data.hasKey(key, Constants.NBT.TAG_INT)) throw new IllegalStateException("Invalid redemption integer");
        for (String key : new String[] { "X", "Y", "Z" })
            if (!data.hasKey(key, Constants.NBT.TAG_DOUBLE) || !Double.isFinite(data.getDouble(key)))
                throw new IllegalStateException("Invalid redemption position");
        for (String key : new String[] { "Shrine", "Creative" })
            if (!data.hasKey(key, Constants.NBT.TAG_BYTE) || data.getByte(key) < 0 || data.getByte(key) > 1)
                throw new IllegalStateException("Invalid redemption flag");
        for (String key : new String[] { "Health", "Maximum" })
            if (!data.hasKey(key, Constants.NBT.TAG_FLOAT) || !Float.isFinite(data.getFloat(key))
                || data.getFloat(key) <= 0) throw new IllegalStateException("Invalid redemption health");
        for (String key : new String[] { "Source", "Maid", "Cursor", "Tile", "ResultTile" })
            if (!data.hasKey(key, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException("Invalid redemption snapshot");
        for (String key : new String[] { "Inventory", "ResultInventory" }) {
            if (!data.hasKey(key, Constants.NBT.TAG_LIST))
                throw new IllegalStateException("Invalid redemption inventory");
            var typed = data.getTagList(key, Constants.NBT.TAG_COMPOUND);
            if (((NBTTagList) data.getTag(key)).tagCount() != typed.tagCount())
                throw new IllegalStateException("Invalid redemption inventory entries");
        }
        var maid = data.getCompoundTag("Maid");
        if (data.getCompoundTag("Source")
            .hasNoTags() || !maid.hasKey("UUIDMost", Constants.NBT.TAG_LONG)
            || !maid.hasKey("UUIDLeast", Constants.NBT.TAG_LONG)
            || !data.getString("Candidate")
                .equals(new UUID(maid.getLong("UUIDMost"), maid.getLong("UUIDLeast")).toString())
            || data.getBoolean("Shrine") && data.getCompoundTag("Tile")
                .hasNoTags()
            || !data.getBoolean("Shrine") && (!data.getCompoundTag("Tile")
                .hasNoTags()
                || !data.getCompoundTag("ResultTile")
                    .hasNoTags()))
            throw new IllegalStateException("Maid redemption snapshots disagree");
    }
}
