package com.tianluoqaq.tlmnh.common.development.visual;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;

import cpw.mods.fml.common.registry.GameRegistry;

/** 真实注册旗帜与完整NBT的背部显示入口；复用已有逐字段恢复，不创建库存来源或改观察者模式 */
public final class MaidBannerVisualFixture {

    private static final String TAG = "TLMNHBannerVisualFixture";

    private MaidBannerVisualFixture() {}

    public static void set(EntityPlayerMP observer, String action) {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development commands required");
        require(
            action.equals("reimu-plain") || action.equals("reimu-patterned")
                || action.equals("winefox-plain")
                || action.equals("winefox-patterned")
                || action.equals("hidden")
                || action.equals("status")
                || action.equals("restore"),
            "Unknown banner display action");
        var maid = AnimationLab.ownedSceneMaid(observer);
        require(observer.openContainer == observer.inventoryContainer, "Close the current menu first");
        require(maid.ridingEntity == null && maid.riddenByEntity == null, "Unmount the scene actor first");
        var parent = maid.getEntityData();
        if (!parent.hasKey(TAG)) {
            require(
                action.endsWith("-plain") || action.endsWith("-patterned"),
                "Create a banner display before controlling it");
            require(
                !MaidVisualFixtureState.exists(maid) && !maid.hasPose(MaidPose.SLEEPING)
                    && maid.getBrainCoordinator()
                        .isSuspended()
                    && maid.getNavigator()
                        .noPath(),
                "Restore previous fixtures and use the idle suspended lab actor");
            // 先核真实提供方，不在缺失接口时留下半份可恢复状态
            banner(false);
            MaidVisualFixtureState.capture(maid);
            var saved = new NBTTagCompound();
            saved.setInteger("Schema", 1);
            saved.setString(
                "Actor",
                maid.getUniqueID()
                    .toString());
            saved.setString(
                "Owner",
                observer.getUniqueID()
                    .toString());
            saved.setString("ExpectedModel", maid.getModelId());
            saved.setBoolean(
                "ExpectedShowBack",
                maid.getConfigManager()
                    .isShowBackItem());
            parent.setTag(TAG, saved);
        }
        require(parent.hasKey(TAG, 10), "Opaque banner display scope retained");
        var saved = parent.getCompoundTag(TAG);
        require(
            saved.hasKey("Schema", 3) && saved.getInteger("Schema") == 1
                && saved.getString("Actor")
                    .equals(
                        maid.getUniqueID()
                            .toString())
                && saved.getString("Owner")
                    .equals(
                        observer.getUniqueID()
                            .toString())
                && MaidVisualFixtureState.exists(maid),
            "Unknown or changed banner display scope retained");
        require(
            saved.hasKey("ExpectedModel", 8) && saved.hasKey("ExpectedShowBack", 1)
                && saved.getString("ExpectedModel")
                    .equals(maid.getModelId())
                && saved.getBoolean("ExpectedShowBack") == maid.getConfigManager()
                    .isShowBackItem(),
            "Model or display setting changed outside fixture; original snapshot retained");
        MaidVisualFixtureState.verifyBackSlot(maid);
        if (action.equals("restore")) {
            MaidVisualFixtureState.restore(maid);
            parent.removeTag(TAG);
            record(maid, action);
            return;
        }
        if (!action.equals("status")) {
            if (action.equals("hidden")) maid.getConfigManager()
                .setShowBackItem(false);
            else {
                var display = banner(action.endsWith("-patterned"));
                maid.setModelId(
                    action.startsWith("reimu-") ? "touhou_little_maid:hakurei_reimu" : "geckolib:winefox_hanfu");
                saved.setString("ExpectedModel", maid.getModelId());
                maid.setSitting(false);
                maid.getMaidInventory()
                    .setInventorySlotContents(5, display);
                // 先登记实际受保护准入结果，若意外封存仍能通过restore恢复原物品
                MaidVisualFixtureState.trackBackSlot(maid);
                require(
                    ItemStack.areItemStacksEqual(display, maid.getBackpackShowItem()),
                    "Banner admission changed the fixture item");
                maid.getConfigManager()
                    .setShowBackItem(true);
            }
            saved.setString("ExpectedModel", maid.getModelId());
            saved.setBoolean(
                "ExpectedShowBack",
                maid.getConfigManager()
                    .isShowBackItem());
        }
        record(maid, action);
    }

    private static ItemStack banner(boolean patterned) {
        var item = GameRegistry.findItem("etfuturum", "banner");
        require(
            item != null && item.getClass()
                .getName()
                .equals("ganymedes01.etfuturum.blocks.itemblocks.ItemBlockBanner"),
            "Actual Et Futurum banner provider required");
        var stack = new ItemStack(item, 1, 14);
        var tag = new NBTTagCompound();
        tag.setString("TLMNHBannerFixture", "preserve-this-unknown-item-field");
        var block = new NBTTagCompound();
        // 元数据14与NBT底色11不同，必须由真实Tile API采用NBT，而非仅显示默认旗帜
        block.setInteger("Base", 11);
        block.setLong("TLMNHUnknownPayload", 0x123456789ABCDEFL);
        if (patterned) {
            var patterns = new NBTTagList();
            pattern(patterns, "bs", 14);
            pattern(patterns, "cs", 0);
            pattern(patterns, "bo", 4);
            pattern(patterns, "tlmnh_unknown_pattern", 123);
            block.setTag("Patterns", patterns);
        }
        tag.setTag("BlockEntityTag", block);
        stack.setTagCompound(tag);
        return stack;
    }

    private static void pattern(NBTTagList patterns, String id, int color) {
        var entry = new NBTTagCompound();
        entry.setString("Pattern", id);
        entry.setInteger("Color", color);
        entry.setString("TLMNHUnknownPatternField", "retained");
        patterns.appendTag(entry);
    }

    private static void record(EntityMaid maid, String action) {
        var stack = maid.getBackpackShowItem();
        var synced = maid.getDataWatcher()
            .getWatchableObjectItemStack(18);
        require(ItemStack.areItemStacksEqual(stack, synced), "Complete banner tracker copy must match the server item");
        TLMNotHard.LOG.info(
            "TLMNH_BANNER_VISUAL action={} uuid={} model={} show_back={} synced_nbt_equal=true back_nbt={}",
            action,
            maid.getUniqueID(),
            maid.getModelId(),
            maid.getConfigManager()
                .isShowBackItem(),
            stack == null ? "empty"
                : ProtectedItemCodec.encode(stack)
                    .toString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
