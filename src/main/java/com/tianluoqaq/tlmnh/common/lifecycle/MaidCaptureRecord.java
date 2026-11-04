package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** 收纳只解释本模组收据；实体和物品的嵌套原始NBT不裁剪 */
final class MaidCaptureRecord {

    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Token",
            "Owner",
            "Subject",
            "Stage",
            "Sequence",
            "Source",
            "OriginalMaid",
            "Receipt",
            "Credential",
            "InventoryBefore",
            "InventoryReceipt",
            "InventoryFilled",
            "Selected",
            "Dimension",
            "Cursor",
            "Observed"));

    private MaidCaptureRecord() {}

    static void validate(NBTTagCompound data, UUID token, UUID owner, UUID subject) {
        if (!data.func_150296_c()
            .equals(FIELDS)) throw new IllegalStateException("Unknown capture record retained");
        for (String key : new String[] { "Token", "Owner", "Subject", "Stage" })
            if (!data.hasKey(key, 8)) throw new IllegalStateException("Invalid capture identity encoding");
        for (String key : new String[] { "Sequence", "Selected", "Dimension" })
            if (!data.hasKey(key, 3)) throw new IllegalStateException("Invalid capture position encoding");
        for (String key : new String[] { "Source", "OriginalMaid", "Receipt", "Credential", "Cursor", "Observed" })
            if (!data.hasKey(key, 10)) throw new IllegalStateException("Invalid capture payload encoding");
        for (String key : new String[] { "InventoryBefore", "InventoryReceipt", "InventoryFilled" }) {
            if (!data.hasKey(key, 9) || ((NBTTagList) data.getTag(key)).tagCount() != data.getTagList(key, 10)
                .tagCount()) throw new IllegalStateException("Invalid capture inventory encoding");
        }
        if (!token.toString()
            .equals(data.getString("Token"))
            || !owner.toString()
                .equals(data.getString("Owner"))
            || !subject.toString()
                .equals(data.getString("Subject"))
            || data.getInteger("Sequence") < 1
            || data.getInteger("Selected") < 0
            || data.getInteger("Selected") > 8
            || !Arrays.asList("PREPARED", "REMOVED", "DELIVERED", "HELD")
                .contains(data.getString("Stage")))
            throw new IllegalStateException("Invalid capture identity or state");
        for (String key : new String[] { "Source", "OriginalMaid" }) {
            var maid = data.getCompoundTag(key);
            if (!maid.hasKey("UUIDMost", 4) || !maid.hasKey("UUIDLeast", 4)
                || !maid.hasKey("OwnerUUID", 8)
                || !subject.equals(new UUID(maid.getLong("UUIDMost"), maid.getLong("UUIDLeast")))
                || !owner.toString()
                    .equals(maid.getString("OwnerUUID")))
                throw new IllegalStateException("Capture source identity mismatch");
        }
        if (data.getCompoundTag("Source")
            .hasKey("Riding")
            || !token.toString()
                .equals(
                    data.getCompoundTag("Receipt")
                        .getCompoundTag("tag")
                        .getString(MaidCaptureService.RECEIPT))
            || !token.toString()
                .equals(
                    data.getCompoundTag("Credential")
                        .getCompoundTag("tag")
                        .getString("TLMNHToken"))
            || !owner.toString()
                .equals(
                    data.getCompoundTag("Credential")
                        .getCompoundTag("tag")
                        .getString("InitMaidOwner")))
            throw new IllegalStateException("Capture credential mismatch");
        if (!"HELD".equals(data.getString("Stage")) && !data.getCompoundTag("Observed")
            .hasNoTags()) throw new IllegalStateException("Unexpected capture successor record");
    }
}
