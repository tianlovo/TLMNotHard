package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/** 收纳只解释本模组收据；实体和物品的嵌套原始NBT不裁剪 */
final class MaidCaptureRecord {

    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            MaidLifecycleNbtKeys.KEY_TOKEN,
            MaidLifecycleNbtKeys.KEY_OWNER,
            MaidLifecycleNbtKeys.KEY_SUBJECT,
            MaidLifecycleNbtKeys.KEY_STAGE,
            MaidLifecycleNbtKeys.KEY_SEQUENCE,
            MaidLifecycleNbtKeys.KEY_SOURCE,
            MaidLifecycleNbtKeys.KEY_ORIGINAL_MAID,
            MaidLifecycleNbtKeys.KEY_RECEIPT,
            MaidLifecycleNbtKeys.KEY_CREDENTIAL,
            MaidLifecycleNbtKeys.KEY_INVENTORY_BEFORE,
            MaidLifecycleNbtKeys.KEY_INVENTORY_RECEIPT,
            MaidLifecycleNbtKeys.KEY_INVENTORY_FILLED,
            MaidLifecycleNbtKeys.KEY_SELECTED,
            MaidLifecycleNbtKeys.KEY_DIMENSION,
            MaidLifecycleNbtKeys.KEY_CURSOR,
            MaidLifecycleNbtKeys.KEY_OBSERVED));

    private MaidCaptureRecord() {}

    static void validate(NBTTagCompound data, UUID token, UUID owner, UUID subject) {
        if (!data.func_150296_c()
            .equals(FIELDS))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_CAPTURE_RECORD_RETAINED);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_TOKEN, MaidLifecycleNbtKeys.KEY_OWNER,
            MaidLifecycleNbtKeys.KEY_SUBJECT, MaidLifecycleNbtKeys.KEY_STAGE })
            if (!data.hasKey(key, Constants.NBT.TAG_STRING))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_CAPTURE_IDENTITY_ENCODING);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_SEQUENCE, MaidLifecycleNbtKeys.KEY_SELECTED,
            MaidLifecycleNbtKeys.KEY_DIMENSION })
            if (!data.hasKey(key, Constants.NBT.TAG_INT))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_CAPTURE_POSITION_ENCODING);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_SOURCE, MaidLifecycleNbtKeys.KEY_ORIGINAL_MAID,
            MaidLifecycleNbtKeys.KEY_RECEIPT, MaidLifecycleNbtKeys.KEY_CREDENTIAL, MaidLifecycleNbtKeys.KEY_CURSOR,
            MaidLifecycleNbtKeys.KEY_OBSERVED })
            if (!data.hasKey(key, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_CAPTURE_PAYLOAD_ENCODING);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_INVENTORY_BEFORE,
            MaidLifecycleNbtKeys.KEY_INVENTORY_RECEIPT, MaidLifecycleNbtKeys.KEY_INVENTORY_FILLED }) {
            if (!data.hasKey(key, Constants.NBT.TAG_LIST)
                || ((NBTTagList) data.getTag(key)).tagCount() != data.getTagList(key, Constants.NBT.TAG_COMPOUND)
                    .tagCount())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_CAPTURE_INVENTORY_ENCODING);
        }
        if (!token.toString()
            .equals(data.getString(MaidLifecycleNbtKeys.KEY_TOKEN))
            || !owner.toString()
                .equals(data.getString(MaidLifecycleNbtKeys.KEY_OWNER))
            || !subject.toString()
                .equals(data.getString(MaidLifecycleNbtKeys.KEY_SUBJECT))
            || data.getInteger(MaidLifecycleNbtKeys.KEY_SEQUENCE) < MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || data.getInteger(MaidLifecycleNbtKeys.KEY_SELECTED) < 0
            || data.getInteger(MaidLifecycleNbtKeys.KEY_SELECTED) > MaidLifecycleTuning.CAPTURE_RECORD_VALIDATE_PARAM
            || !Arrays
                .asList(
                    MaidLifecycleNbtKeys.KEY_PREPARED,
                    MaidLifecycleNbtKeys.KEY_REMOVED,
                    MaidLifecycleNbtKeys.KEY_DELIVERED,
                    MaidLifecycleNbtKeys.KEY_HELD)
                .contains(data.getString(MaidLifecycleNbtKeys.KEY_STAGE)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_CAPTURE_IDENTITY_OR_STATE);
        for (String key : new String[] { MaidLifecycleNbtKeys.KEY_SOURCE, MaidLifecycleNbtKeys.KEY_ORIGINAL_MAID }) {
            var maid = data.getCompoundTag(key);
            if (!maid.hasKey(MaidLifecycleNbtKeys.KEY_UUID_MOST, Constants.NBT.TAG_LONG)
                || !maid.hasKey(MaidLifecycleNbtKeys.KEY_UUID_LEAST, Constants.NBT.TAG_LONG)
                || !maid.hasKey(MaidLifecycleNbtKeys.KEY_OWNER_UUID, Constants.NBT.TAG_STRING)
                || !subject.equals(
                    new UUID(
                        maid.getLong(MaidLifecycleNbtKeys.KEY_UUID_MOST),
                        maid.getLong(MaidLifecycleNbtKeys.KEY_UUID_LEAST)))
                || !owner.toString()
                    .equals(maid.getString(MaidLifecycleNbtKeys.KEY_OWNER_UUID)))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CAPTURE_SOURCE_IDENTITY_MISMATCH);
        }
        if (data.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE)
            .hasKey(MaidLifecycleNbtKeys.KEY_RIDING)
            || !token.toString()
                .equals(
                    data.getCompoundTag(MaidLifecycleNbtKeys.KEY_RECEIPT)
                        .getCompoundTag(MaidLifecycleIds.ID_TAG)
                        .getString(MaidLifecycleNbtKeys.RECEIPT_2))
            || !token.toString()
                .equals(
                    data.getCompoundTag(MaidLifecycleNbtKeys.KEY_CREDENTIAL)
                        .getCompoundTag(MaidLifecycleIds.ID_TAG)
                        .getString(MaidLifecycleNbtKeys.KEY_TLMNH_TOKEN))
            || !owner.toString()
                .equals(
                    data.getCompoundTag(MaidLifecycleNbtKeys.KEY_CREDENTIAL)
                        .getCompoundTag(MaidLifecycleIds.ID_TAG)
                        .getString(MaidLifecycleNbtKeys.KEY_INIT_MAID_OWNER)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CAPTURE_CREDENTIAL_MISMATCH);
        if (!MaidLifecycleNbtKeys.KEY_HELD.equals(data.getString(MaidLifecycleNbtKeys.KEY_STAGE))
            && !data.getCompoundTag(MaidLifecycleNbtKeys.KEY_OBSERVED)
                .hasNoTags())
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNEXPECTED_CAPTURE_SUCCESSOR_RECORD);
    }
}
