package com.tianluoqaq.tlmnh.common.development.life;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.visual.MaidVisualFixtureState;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 原场景女仆的可恢复卧姿显示；不模拟自然入睡、租约或物品消费 */
public final class MaidSleepVisualFixture {

    private static final String TAG = "TLMNHSleepVisualFixture";

    private MaidSleepVisualFixture() {}

    public static void set(EntityPlayerMP player, String action) {
        var maid = AnimationLab.ownedSceneMaid(player);
        require(DevelopmentFlags.isDevCommandsEnabled(), "Development fixture required");
        require(
            action.equals("clear") || action.equals("reimu-north")
                || action.equals("reimu-east")
                || action.equals("winefox-east"),
            "Unknown sleeping display action");
        var parent = maid.getEntityData();
        if (!parent.hasKey(TAG)) {
            require(
                !action.equals("clear") && !MaidVisualFixtureState.exists(maid),
                "New fixture or previous tracked restore required");
            require(
                !maid.hasPose(MaidPose.SLEEPING) && maid.ridingEntity == null
                    && maid.getSleepingBedDirection() == -1
                    && near(maid, .5, 65, .5),
                "Original resting scene required");
            MaidVisualFixtureState.capture(maid);
            var saved = new NBTTagCompound();
            saved.setInteger("Schema", 1);
            saved.setString(
                "Actor",
                maid.getUniqueID()
                    .toString());
            saved.setString(
                "Owner",
                player.getUniqueID()
                    .toString());
            saved.setDouble("X", maid.posX);
            saved.setDouble("Y", maid.posY);
            saved.setDouble("Z", maid.posZ);
            saved.setFloat("Yaw", maid.rotationYaw);
            saved.setFloat("Pitch", maid.rotationPitch);
            saved.setFloat("BodyYaw", maid.renderYawOffset);
            saved.setFloat("HeadYaw", maid.rotationYawHead);
            parent.setTag(TAG, saved);
        }
        require(parent.hasKey(TAG, Constants.NBT.TAG_COMPOUND), "Opaque sleeping fixture retained");
        var saved = parent.getCompoundTag(TAG);
        require(
            saved.hasKey("Schema", Constants.NBT.TAG_INT) && saved.getInteger("Schema") == 1
                && saved.getString("Actor")
                    .equals(
                        maid.getUniqueID()
                            .toString())
                && saved.getString("Owner")
                    .equals(
                        player.getUniqueID()
                            .toString())
                && MaidVisualFixtureState.exists(maid),
            "Changed sleeping display scope retained");
        require(
            near(maid, .5, 65.5625, .5) || near(maid, saved.getDouble("X"), saved.getDouble("Y"), saved.getDouble("Z")),
            "Scene actor was moved outside fixture; original snapshot retained");
        MaidVisualFixtureState.verifyBackSlot(maid);
        MaidVisualFixtureState.verifyHeadSlot(maid);
        if (action.equals("clear")) {
            // 同一服务端命令内先核并清本次无物品来源床，再恢复原位置/字段，不留中间world tick
            MaidBedVisualFixture.set(player, "clear");
            position(maid, saved.getDouble("X"), saved.getDouble("Y"), saved.getDouble("Z"));
            maid.rotationYaw = maid.prevRotationYaw = saved.getFloat("Yaw");
            maid.rotationPitch = maid.prevRotationPitch = saved.getFloat("Pitch");
            maid.renderYawOffset = maid.prevRenderYawOffset = saved.getFloat("BodyYaw");
            maid.rotationYawHead = maid.prevRotationYawHead = saved.getFloat("HeadYaw");
            MaidVisualFixtureState.restore(maid);
            require(
                near(maid, saved.getDouble("X"), saved.getDouble("Y"), saved.getDouble("Z"))
                    && maid.getSleepingBedDirection() == -1
                    && !maid.hasPose(MaidPose.SLEEPING),
                "Restore incomplete; marker retained");
            parent.removeTag(TAG);
            TLMNotHard.LOG.info("TLMNH_SLEEP_VISUAL_CLEAR complete=true position_restored=true");
            return;
        }
        int direction = action.equals("reimu-north") ? 2 : 3;
        MaidBedVisualFixture.set(player, direction == 2 ? "sleep-north" : "sleep-east");
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setModelId(action.equals("winefox-east") ? "geckolib:winefox_hanfu" : "touhou_little_maid:hakurei_reimu");
        maid.setSitting(false);
        maid.getConfigManager()
            .setShowChatBubble(false);
        maid.setSleepingBedDirection(direction);
        maid.setPose(MaidPose.SLEEPING, true);
        position(maid, .5, 65.5625, .5);
        // 任意身体朝向不应覆盖已同步的床方向
        maid.rotationYaw = maid.prevRotationYaw = maid.renderYawOffset = maid.prevRenderYawOffset = 37;
        maid.rotationYawHead = maid.prevRotationYawHead = 37;
        maid.rotationPitch = maid.prevRotationPitch = 0;
        TLMNotHard.LOG
            .info("TLMNH_SLEEP_VISUAL_READY model={} direction={} sleeping=true", maid.getModelId(), direction);
    }

    private static void position(EntityMaid maid, double x, double y, double z) {
        maid.setPositionAndUpdate(x, y, z);
        maid.prevPosX = maid.lastTickPosX = maid.posX;
        maid.prevPosY = maid.lastTickPosY = maid.posY;
        maid.prevPosZ = maid.lastTickPosZ = maid.posZ;
        maid.motionX = maid.motionY = maid.motionZ = 0;
    }

    private static boolean near(EntityMaid maid, double x, double y, double z) {
        return maid.getDistanceSq(x, y, z) < .0001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
