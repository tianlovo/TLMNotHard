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
            MaidLifecyclePart2NbtKeys.KEY_VERSION,
            MaidLifecyclePart2NbtKeys.KEY_STAGE,
            MaidLifecyclePart2NbtKeys.KEY_ACTOR,
            MaidLifecyclePart2NbtKeys.KEY_CANDIDATE,
            MaidLifecyclePart2NbtKeys.KEY_DIMENSION,
            MaidLifecyclePart2NbtKeys.KEY_X,
            MaidLifecyclePart2NbtKeys.KEY_Y,
            MaidLifecyclePart2NbtKeys.KEY_Z,
            MaidLifecyclePart2NbtKeys.KEY_SHRINE,
            MaidLifecyclePart2NbtKeys.KEY_METADATA,
            MaidLifecyclePart2NbtKeys.KEY_SOURCE,
            MaidLifecyclePart2NbtKeys.KEY_MAID,
            MaidLifecyclePart2NbtKeys.KEY_INVENTORY,
            MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY,
            MaidLifecyclePart2NbtKeys.KEY_CURSOR,
            MaidLifecyclePart2NbtKeys.KEY_SELECTED,
            MaidLifecyclePart2NbtKeys.KEY_HEALTH,
            MaidLifecyclePart2NbtKeys.KEY_MAXIMUM,
            MaidLifecyclePart2NbtKeys.KEY_CREATIVE,
            MaidLifecyclePart2NbtKeys.KEY_TILE,
            MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE));

    private MaidRedemptionRecord() {}

    static void validate(NBTTagCompound data) {
        for (Object key : data.func_150296_c()) if (!FIELDS.contains(key)) throw new IllegalStateException(
            MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_MAID_REDEMPTION_FIELD_PRESERVED + key);
        if (data.func_150296_c()
            .size() != FIELDS.size() || !data.hasKey(MaidLifecyclePart2NbtKeys.KEY_VERSION, Constants.NBT.TAG_INT)
            || data.getInteger(MaidLifecyclePart2NbtKeys.KEY_VERSION) != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || !data.hasKey(MaidLifecyclePart2NbtKeys.KEY_STAGE, Constants.NBT.TAG_STRING)
            || !(data.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE)
                .equals(MaidLifecyclePart2NbtKeys.KEY_PREPARED)
                || data.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE)
                    .equals(MaidLifecyclePart2NbtKeys.KEY_COSTS)))
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_MAID_REDEMPTION_INTENT);
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_ACTOR,
            MaidLifecyclePart2NbtKeys.KEY_CANDIDATE }) {
            if (!data.hasKey(key, Constants.NBT.TAG_STRING))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_IDENTITY);
            UUID.fromString(data.getString(key));
        }
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_DIMENSION,
            MaidLifecyclePart2NbtKeys.KEY_METADATA, MaidLifecyclePart2NbtKeys.KEY_SELECTED })
            if (!data.hasKey(key, Constants.NBT.TAG_INT))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_INTEGER);
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_X, MaidLifecyclePart2NbtKeys.KEY_Y,
            MaidLifecyclePart2NbtKeys.KEY_Z })
            if (!data.hasKey(key, Constants.NBT.TAG_DOUBLE) || !Double.isFinite(data.getDouble(key)))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_POSITION);
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_SHRINE, MaidLifecyclePart2NbtKeys.KEY_CREATIVE })
            if (!data.hasKey(key, Constants.NBT.TAG_BYTE) || data.getByte(key) < 0
                || data.getByte(key) > MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_FLAG);
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_HEALTH, MaidLifecyclePart2NbtKeys.KEY_MAXIMUM })
            if (!data.hasKey(key, Constants.NBT.TAG_FLOAT) || !Float.isFinite(data.getFloat(key))
                || data.getFloat(key) <= 0)
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_HEALTH);
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_SOURCE, MaidLifecyclePart2NbtKeys.KEY_MAID,
            MaidLifecyclePart2NbtKeys.KEY_CURSOR, MaidLifecyclePart2NbtKeys.KEY_TILE,
            MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE })
            if (!data.hasKey(key, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_SNAPSHOT);
        for (String key : new String[] { MaidLifecyclePart2NbtKeys.KEY_INVENTORY,
            MaidLifecyclePart2NbtKeys.KEY_RESULT_INVENTORY }) {
            if (!data.hasKey(key, Constants.NBT.TAG_LIST))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_INVENTORY);
            var typed = data.getTagList(key, Constants.NBT.TAG_COMPOUND);
            if (((NBTTagList) data.getTag(key)).tagCount() != typed.tagCount())
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_REDEMPTION_INVENTORY_ENTRIES);
        }
        var maid = data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_MAID);
        if (data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE)
            .hasNoTags() || !maid.hasKey(MaidLifecyclePart2NbtKeys.KEY_UUID_MOST, Constants.NBT.TAG_LONG)
            || !maid.hasKey(MaidLifecyclePart2NbtKeys.KEY_UUID_LEAST, Constants.NBT.TAG_LONG)
            || !data.getString(MaidLifecyclePart2NbtKeys.KEY_CANDIDATE)
                .equals(
                    new UUID(
                        maid.getLong(MaidLifecyclePart2NbtKeys.KEY_UUID_MOST),
                        maid.getLong(MaidLifecyclePart2NbtKeys.KEY_UUID_LEAST)).toString())
            || data.getBoolean(MaidLifecyclePart2NbtKeys.KEY_SHRINE)
                && data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_TILE)
                    .hasNoTags()
            || !data.getBoolean(MaidLifecyclePart2NbtKeys.KEY_SHRINE)
                && (!data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_TILE)
                    .hasNoTags()
                    || !data.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_RESULT_TILE)
                        .hasNoTags()))
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_MAID_REDEMPTION_SNAPSHOTS_DISAGREE);
    }
}
