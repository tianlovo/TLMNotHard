package com.tianluoqaq.tlmnh.common.development.visual;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

/** 视觉夹具只恢复自身改动的字段；原始快照随测试实体保存，意外换入的装备不覆盖 */
public final class MaidVisualFixtureState {

    private static final String TAG = "TLMNHVisualFixture";

    private MaidVisualFixtureState() {}

    public static boolean exists(EntityMaid maid) {
        return maid.getEntityData()
            .hasKey(TAG);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static void scope(EntityMaid maid) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !maid.worldObj.isRemote
                && AnimationLab.isLab(maid.worldObj)
                && maid.worldObj.getEntityByID(maid.getEntityId()) == maid,
            "Registered server void-lab actor required");
    }

    public static void capture(EntityMaid maid) {
        scope(maid);
        require(!exists(maid), "Previous visual fixture snapshot must be restored first");
        var data = new NBTTagCompound();
        data.setInteger("Schema", 1);
        data.setString(
            "Actor",
            maid.getUniqueID()
                .toString());
        data.setString("Owner", maid.func_152113_b());
        data.setString("Model", maid.getModelId());
        data.setBoolean("Sitting", maid.isSitting());
        data.setBoolean("Sleeping", maid.hasPose(MaidPose.SLEEPING));
        data.setBoolean(
            "Suspended",
            maid.getBrainCoordinator()
                .isSuspended());
        data.setBoolean(
            "ShowBack",
            maid.getConfigManager()
                .isShowBackItem());
        data.setBoolean(
            "ShowBubble",
            maid.getConfigManager()
                .isChatBubbleShow());
        writeItem(
            data,
            "OriginalBack",
            maid.getMaidInventory()
                .getStackInSlot(5));
        writeItem(
            data,
            "ExpectedBack",
            maid.getMaidInventory()
                .getStackInSlot(5));
        var original = new NBTTagCompound();
        maid.writeToNBT(original);
        // 原生writeToNBT会借用ForgeData，先深复制再存入活实体，避免形成自引用NBT
        data.setTag("OriginalEntity", original.copy());
        maid.getEntityData()
            .setTag(TAG, data);
        TLMNotHard.LOG.info(
            "TLMNH_VISUAL_FIXTURE_SAVED uuid={} originalBack={}",
            maid.getUniqueID(),
            data.getBoolean("OriginalBackPresent"));
    }

    private static void writeItem(NBTTagCompound data, String key, ItemStack stack) {
        data.setBoolean(key + "Present", stack != null);
        if (stack == null) data.removeTag(key);
        else data.setTag(key, ProtectedItemCodec.encode(stack));
    }

    private static boolean matches(NBTTagCompound data, String key, ItemStack stack) {
        return data.hasKey(key + "Present", 1) && data.getBoolean(key + "Present") == (stack != null)
            && (stack == null || data.hasKey(key, 10) && data.getCompoundTag(key)
                .equals(ProtectedItemCodec.encode(stack)));
    }

    private static NBTTagCompound saved(EntityMaid maid) {
        scope(maid);
        var parent = maid.getEntityData();
        require(parent.hasKey(TAG, 10), "Tracked visual fixture snapshot required");
        var data = parent.getCompoundTag(TAG);
        require(
            data.hasKey("Schema", 3) && data.getInteger("Schema") == 1
                && maid.getUniqueID()
                    .toString()
                    .equals(data.getString("Actor"))
                && maid.func_152113_b()
                    .equals(data.getString("Owner")),
            "Unknown or mismatched visual fixture snapshot retained");
        return data;
    }

    public static void verifyBackSlot(EntityMaid maid) {
        require(
            matches(
                saved(maid),
                "ExpectedBack",
                maid.getMaidInventory()
                    .getStackInSlot(5)),
            "Back slot changed outside this visual fixture; existing item and original snapshot retained");
    }

    public static void trackBackSlot(EntityMaid maid) {
        var data = saved(maid);
        writeItem(
            data,
            "ExpectedBack",
            maid.getMaidInventory()
                .getStackInSlot(5));
    }

    public static void restore(EntityMaid maid) {
        var data = saved(maid);
        require(
            matches(
                data,
                "ExpectedBack",
                maid.getMaidInventory()
                    .getStackInSlot(5))
                || matches(
                    data,
                    "OriginalBack",
                    maid.getMaidInventory()
                        .getStackInSlot(5)),
            "Back slot changed outside this visual fixture; original snapshot retained");
        require(
            data.hasKey("Model", 8) && BuiltinModelCatalog.contains(data.getString("Model"))
                && data.hasKey("Sitting", 1)
                && data.hasKey("Sleeping", 1)
                && data.hasKey("Suspended", 1)
                && data.hasKey("ShowBack", 1)
                && data.hasKey("ShowBubble", 1)
                && data.hasKey("OriginalBackPresent", 1),
            "Incomplete visual fixture snapshot retained");
        ItemStack original = data.getBoolean("OriginalBackPresent")
            ? ProtectedItemCodec.decodeField(data, "OriginalBack")
            : null;
        require(!data.getBoolean("OriginalBackPresent") || original != null, "Original back item could not be decoded");
        maid.setModelId(data.getString("Model"));
        maid.setSitting(data.getBoolean("Sitting"));
        maid.setPose(MaidPose.SLEEPING, data.getBoolean("Sleeping"));
        maid.getMaidInventory()
            .setInventorySlotContents(5, original);
        maid.getConfigManager()
            .setShowBackItem(data.getBoolean("ShowBack"));
        maid.getConfigManager()
            .setShowChatBubble(data.getBoolean("ShowBubble"));
        maid.getBrainCoordinator()
            .setSuspended(data.getBoolean("Suspended"));
        maid.getChatBubbleManager()
            .clear();
        require(
            matches(
                data,
                "OriginalBack",
                maid.getMaidInventory()
                    .getStackInSlot(5))
                && maid.getModelId()
                    .equals(data.getString("Model"))
                && maid.isSitting() == data.getBoolean("Sitting")
                && maid.hasPose(MaidPose.SLEEPING) == data.getBoolean("Sleeping")
                && maid.getConfigManager()
                    .isShowBackItem() == data.getBoolean("ShowBack")
                && maid.getConfigManager()
                    .isChatBubbleShow() == data.getBoolean("ShowBubble")
                && maid.getBrainCoordinator()
                    .isSuspended() == data.getBoolean("Suspended"),
            "Visual fixture restore incomplete; original snapshot retained");
        maid.getEntityData()
            .removeTag(TAG);
        TLMNotHard.LOG.info(
            "TLMNH_VISUAL_FIXTURE_RESTORED uuid={} model={} originalBack={} owned_item_nbt=equal",
            maid.getUniqueID(),
            maid.getModelId(),
            data.getBoolean("OriginalBackPresent"));
    }
}
