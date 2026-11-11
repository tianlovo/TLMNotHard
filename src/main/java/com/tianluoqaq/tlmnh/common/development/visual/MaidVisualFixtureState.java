package com.tianluoqaq.tlmnh.common.development.visual;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
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
            DevelopmentFlags.isDevCommandsEnabled() && !maid.worldObj.isRemote
                && AnimationLab.isLab(maid.worldObj)
                && maid.worldObj.getEntityByID(maid.getEntityId()) == maid,
            "Registered server void-lab actor required");
    }

    public static void capture(EntityMaid maid) {
        scope(maid);
        require(!exists(maid), "Previous visual fixture snapshot must be restored first");
        var data = new NBTTagCompound();
        data.setInteger("Schema", 2);
        data.setString(
            "Actor",
            maid.getUniqueID()
                .toString());
        data.setString("Owner", maid.func_152113_b());
        data.setString("Model", maid.getModelId());
        data.setBoolean("Sitting", maid.isSitting());
        data.setBoolean("Sleeping", maid.hasPose(MaidPose.SLEEPING));
        data.setInteger("SleepDirection", maid.getSleepingBedDirection());
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
        writeItem(data, "OriginalHead", maid.getEquipmentInSlot(4));
        writeItem(data, "ExpectedHead", maid.getEquipmentInSlot(4));
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
        return data.hasKey(key + "Present", Constants.NBT.TAG_BYTE)
            && data.getBoolean(key + "Present") == (stack != null)
            && (stack == null || data.hasKey(key, Constants.NBT.TAG_COMPOUND) && data.getCompoundTag(key)
                .equals(ProtectedItemCodec.encode(stack)));
    }

    private static NBTTagCompound saved(EntityMaid maid) {
        scope(maid);
        var parent = maid.getEntityData();
        require(parent.hasKey(TAG, Constants.NBT.TAG_COMPOUND), "Tracked visual fixture snapshot required");
        var data = parent.getCompoundTag(TAG);
        require(
            data.hasKey("Schema", Constants.NBT.TAG_INT)
                && (data.getInteger("Schema") == 1 || data.getInteger("Schema") == 2)
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

    public static void verifyHeadSlot(EntityMaid maid) {
        var data = saved(maid);
        require(
            data.getInteger("Schema") == 2 && matches(data, "ExpectedHead", maid.getEquipmentInSlot(4)),
            "Head slot changed or old snapshot lacks head tracking; item and snapshot retained");
    }

    public static void trackHeadSlot(EntityMaid maid) {
        var data = saved(maid);
        require(data.getInteger("Schema") == 2, "Old snapshot cannot track head changes");
        writeItem(data, "ExpectedHead", maid.getEquipmentInSlot(4));
    }

    /** 仅给本次世界持物成像提供同步输入；不是自然战斗/举盾AI入口 */
    public static void stageHeldShield(EntityMaid maid, ItemStack shield, boolean blocking) {
        scope(maid);
        require(
            maid.getBrainCoordinator()
                .isSuspended()
                && maid.getNavigator()
                    .noPath()
                && maid.ridingEntity == null
                && maid.riddenByEntity == null
                && maid.getItemUseState()
                    .peek() == null,
            "Idle suspended unmounted actor without a live item lease required");
        require(shield != null && shield.stackSize == 1, "One native shield required");
        var encoded = ProtectedItemCodec.encode(shield);
        var admitted = ProtectedItemCodec.decode(encoded);
        require(ItemStack.areItemStacksEqual(shield, admitted), "Native shield protected round trip differs");
        if (!exists(maid)) capture(maid);
        var data = saved(maid);
        require(
            !data.hasKey("HeldFixture") || data.hasKey("HeldFixture", Constants.NBT.TAG_COMPOUND),
            "Unknown held snapshot retained");
        if (!data.hasKey("HeldFixture")) {
            require(poseFlags(maid) == 0, "Do not replace an existing action pose");
            var held = new NBTTagCompound();
            held.setInteger("Schema", 1);
            held.setTag("OriginalInventory", inventory(maid));
            held.setTag("ExpectedInventory", inventory(maid));
            held.setInteger("OriginalPose", poseFlags(maid));
            held.setInteger("ExpectedPose", poseFlags(maid));
            writeItem(held, "OriginalMain", maid.getHeldItem());
            writeItem(held, "OriginalOff", maid.getOffhandItem());
            data.setTag("HeldFixture", held);
        }
        var held = held(maid, data);
        require(
            inventory(maid).equals(held.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND))
                && poseFlags(maid) == held.getInteger("ExpectedPose"),
            "Foreign equipment or pose retained before shield staging");
        var expected = inventory(maid);
        var next = new NBTTagList();
        int off = MaidInventory.OFF_HAND;
        for (int i = 0; i < expected.tagCount(); i++) {
            var entry = expected.getCompoundTagAt(i);
            if ((entry.getByte("Slot") & 255) != off) next.appendTag(entry.copy());
        }
        var item = (NBTTagCompound) encoded.copy();
        item.setByte("Slot", (byte) off);
        next.appendTag(item);
        // Canonical slot order matches inventory(), including the armor slots following offhand.
        var ordered = new NBTTagList();
        for (int slot = 0; slot < maid.getMaidInventory()
            .getSizeInventory(); slot++) for (int i = 0; i < next.tagCount(); i++)
                if ((next.getCompoundTagAt(i)
                    .getByte("Slot") & 255) == slot) ordered.appendTag(
                        next.getCompoundTagAt(i)
                            .copy());
        int flags = blocking ? MaidPose.USING_ITEM.getMask() | MaidPose.USING_OFFHAND.getMask() : 0;
        held.setTag("ExpectedInventory", ordered);
        held.setInteger("ExpectedPose", flags);
        maid.setOffhandItem(admitted);
        maid.setPose(MaidPose.USING_ITEM, blocking);
        maid.setPose(MaidPose.USING_OFFHAND, blocking);
        require(
            inventory(maid).equals(ordered) && poseFlags(maid) == flags,
            "Shield staging callback changed equipment or pose; snapshot retained");
    }

    private static NBTTagList inventory(EntityMaid maid) {
        var result = new NBTTagList();
        for (int slot = 0; slot < maid.getMaidInventory()
            .getSizeInventory(); slot++) {
            var stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (stack == null) continue;
            var tag = ProtectedItemCodec.encode(stack);
            tag.setByte("Slot", (byte) slot);
            result.appendTag(tag);
        }
        return result;
    }

    private static int poseFlags(EntityMaid maid) {
        int flags = 0;
        for (var pose : MaidPose.values()) if (maid.hasPose(pose)) flags |= pose.getMask();
        return flags;
    }

    private static NBTTagCompound held(EntityMaid maid, NBTTagCompound data) {
        require(data.hasKey("HeldFixture", Constants.NBT.TAG_COMPOUND), "Typed held snapshot required");
        var held = data.getCompoundTag("HeldFixture");
        require(
            held.hasKey("Schema", Constants.NBT.TAG_INT) && held.getInteger("Schema") == 1
                && held.hasKey("OriginalInventory", Constants.NBT.TAG_LIST)
                && held.hasKey("ExpectedInventory", Constants.NBT.TAG_LIST)
                && held.hasKey("OriginalPose", Constants.NBT.TAG_INT)
                && held.getInteger("OriginalPose") == 0
                && held.hasKey("ExpectedPose", Constants.NBT.TAG_INT)
                && (held.getInteger("ExpectedPose") == 0 || held.getInteger("ExpectedPose")
                    == (MaidPose.USING_ITEM.getMask() | MaidPose.USING_OFFHAND.getMask()))
                && held.hasKey("OriginalMainPresent", Constants.NBT.TAG_BYTE)
                && held.hasKey("OriginalOffPresent", Constants.NBT.TAG_BYTE)
                && held.hasKey("OriginalMain") == held.getBoolean("OriginalMainPresent")
                && held.hasKey("OriginalOff") == held.getBoolean("OriginalOffPresent")
                && maid.getItemUseState()
                    .peek() == null,
            "Unknown held snapshot or live item lease retained");
        return held;
    }

    private static boolean originalHandMatches(NBTTagCompound held, String key, int slot) {
        var original = held.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND);
        NBTTagCompound entry = null;
        for (int i = 0; i < original.tagCount(); i++) {
            var item = original.getCompoundTagAt(i);
            require(item.hasKey("Slot", Constants.NBT.TAG_BYTE), "Unknown original inventory slot retained");
            if ((item.getByte("Slot") & 255) == slot) {
                require(entry == null, "Ambiguous original hand retained");
                entry = (NBTTagCompound) item.copy();
                entry.removeTag("Slot");
            }
        }
        return entry == null ? !held.getBoolean(key + "Present")
            : held.hasKey(key, Constants.NBT.TAG_COMPOUND) && entry.equals(held.getCompoundTag(key));
    }

    private static boolean heldRestored(EntityMaid maid, NBTTagCompound data) {
        if (!data.hasKey("HeldFixture")) return true;
        var held = held(maid, data);
        return inventory(maid).equals(held.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND))
            && poseFlags(maid) == held.getInteger("OriginalPose");
    }

    private static void restoreHeld(EntityMaid maid, NBTTagCompound data) {
        if (!data.hasKey("HeldFixture")) return;
        var held = held(maid, data);
        require(
            originalHandMatches(held, "OriginalMain", MaidInventory.MAIN_HAND)
                && originalHandMatches(held, "OriginalOff", MaidInventory.OFF_HAND),
            "Original hand and complete inventory backups differ; retained");
        var actual = inventory(maid);
        require(
            (actual.equals(held.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND))
                || actual.equals(held.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND)))
                && (poseFlags(maid) == held.getInteger("ExpectedPose")
                    || poseFlags(maid) == held.getInteger("OriginalPose")),
            "Foreign complete inventory or pose retained during held restore");
        var main = held.getBoolean("OriginalMainPresent") ? ProtectedItemCodec.decodeField(held, "OriginalMain") : null;
        var off = held.getBoolean("OriginalOffPresent") ? ProtectedItemCodec.decodeField(held, "OriginalOff") : null;
        require(
            matches(held, "OriginalMain", main) && matches(held, "OriginalOff", off),
            "Original hands cannot be decoded exactly");
        if (!matches(held, "OriginalMain", maid.getHeldItem())) maid.setCurrentItemOrArmor(0, main);
        if (!matches(held, "OriginalOff", maid.getOffhandItem())) maid.setOffhandItem(off);
        maid.setPose(MaidPose.USING_ITEM, false);
        maid.setPose(MaidPose.USING_OFFHAND, false);
        require(
            inventory(maid).equals(held.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND))
                && poseFlags(maid) == held.getInteger("OriginalPose"),
            "Original complete inventory or pose restore differs; snapshot retained");
    }

    /** 饱食/饥饿只作为真实同步输入，不在渲染中伪造头角 */
    public static void stageHunger(EntityMaid maid, int hunger) {
        scope(maid);
        require(hunger == 0 || hunger == 20, "Only hungry/full visual inputs are supported");
        require(
            maid.getBrainCoordinator()
                .isSuspended(),
            "Suspend the lab brain before staging hunger");
        if (!exists(maid)) capture(maid);
        var data = saved(maid);
        require(data.hasKey("OriginalHunger") == data.hasKey("ExpectedHunger"), "Incomplete hunger snapshot retained");
        if (!data.hasKey("OriginalHunger")) {
            data.setInteger("OriginalHunger", maid.getHunger());
            data.setInteger("ExpectedHunger", maid.getHunger());
        }
        require(
            data.hasKey("OriginalHunger", Constants.NBT.TAG_INT) && data.hasKey("ExpectedHunger", Constants.NBT.TAG_INT)
                && maid.getHunger() == data.getInteger("ExpectedHunger"),
            "Foreign hunger change retained");
        maid.setHunger(hunger);
        data.setInteger("ExpectedHunger", hunger);
        TLMNotHard.LOG.info("TLMNH_VISUAL_HUNGER_STAGED maid={} hunger={}", maid.getUniqueID(), hunger);
    }

    public static void restore(EntityMaid maid) {
        var data = saved(maid);
        boolean tracksHunger = data.hasKey("OriginalHunger");
        require(tracksHunger == data.hasKey("ExpectedHunger"), "Incomplete hunger snapshot retained");
        require(
            !tracksHunger || data.hasKey("OriginalHunger", Constants.NBT.TAG_INT)
                && data.hasKey("ExpectedHunger", Constants.NBT.TAG_INT)
                && maid.getHunger() == data.getInteger("ExpectedHunger"),
            "Foreign hunger change; snapshot retained");
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
            data.hasKey("Model", Constants.NBT.TAG_STRING) && BuiltinModelCatalog.contains(data.getString("Model"))
                && data.hasKey("Sitting", Constants.NBT.TAG_BYTE)
                && data.hasKey("Sleeping", Constants.NBT.TAG_BYTE)
                && data.hasKey("Suspended", Constants.NBT.TAG_BYTE)
                && data.hasKey("ShowBack", Constants.NBT.TAG_BYTE)
                && data.hasKey("ShowBubble", Constants.NBT.TAG_BYTE)
                && data.hasKey("OriginalBackPresent", Constants.NBT.TAG_BYTE),
            "Incomplete visual fixture snapshot retained");
        ItemStack original = data.getBoolean("OriginalBackPresent")
            ? ProtectedItemCodec.decodeField(data, "OriginalBack")
            : null;
        require(!data.getBoolean("OriginalBackPresent") || original != null, "Original back item could not be decoded");
        boolean tracksHead = data.getInteger("Schema") == 2;
        ItemStack originalHead = null;
        if (tracksHead) {
            require(
                data.hasKey("OriginalHeadPresent", Constants.NBT.TAG_BYTE)
                    && (matches(data, "ExpectedHead", maid.getEquipmentInSlot(4))
                        || matches(data, "OriginalHead", maid.getEquipmentInSlot(4))),
                "Head slot changed outside fixture; original snapshot retained");
            originalHead = data.getBoolean("OriginalHeadPresent") ? ProtectedItemCodec.decodeField(data, "OriginalHead")
                : null;
            require(
                !data.getBoolean("OriginalHeadPresent") || originalHead != null,
                "Original head item could not be decoded");
        }
        restoreHeld(maid, data);
        maid.setModelId(data.getString("Model"));
        if (tracksHunger) maid.setHunger(data.getInteger("OriginalHunger"));
        maid.setSitting(data.getBoolean("Sitting"));
        maid.setSleepingBedDirection(
            data.hasKey("SleepDirection", Constants.NBT.TAG_INT) ? data.getInteger("SleepDirection") : -1);
        maid.setPose(MaidPose.SLEEPING, data.getBoolean("Sleeping"));
        maid.getMaidInventory()
            .setInventorySlotContents(5, original);
        if (tracksHead) maid.setCurrentItemOrArmor(4, originalHead);
        maid.getConfigManager()
            .setShowBackItem(data.getBoolean("ShowBack"));
        maid.getConfigManager()
            .setShowChatBubble(data.getBoolean("ShowBubble"));
        maid.getBrainCoordinator()
            .setSuspended(data.getBoolean("Suspended"));
        maid.getChatBubbleManager()
            .clear();
        require(
            heldRestored(maid, data) && (!tracksHunger || maid.getHunger() == data.getInteger("OriginalHunger"))
                && (!tracksHead || matches(data, "OriginalHead", maid.getEquipmentInSlot(4)))
                && matches(
                    data,
                    "OriginalBack",
                    maid.getMaidInventory()
                        .getStackInSlot(5))
                && maid.getModelId()
                    .equals(data.getString("Model"))
                && maid.isSitting() == data.getBoolean("Sitting")
                && maid.hasPose(MaidPose.SLEEPING) == data.getBoolean("Sleeping")
                && (!data.hasKey("SleepDirection", Constants.NBT.TAG_INT)
                    || maid.getSleepingBedDirection() == data.getInteger("SleepDirection"))
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
