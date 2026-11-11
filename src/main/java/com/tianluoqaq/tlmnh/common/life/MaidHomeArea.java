package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.entity.DataWatcher;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.life.MaidHomeSettings.Point;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 家园设置由服务端修改；读取活动范围不访问区块或加载目标维度 */
public final class MaidHomeArea {

    private final EntityMaid maid;
    private String observed;
    private MaidHomeSettings settings = MaidHomeSettings.EMPTY;
    private Activity observedActivity;
    private Point restrictionCenter = Point.ZERO;
    private int restrictionRadius = MaidHomeConfig.nonHomeRange;
    private NBTTagCompound unreadSave;

    public MaidHomeArea(EntityMaid maid) {
        this.maid = maid;
    }

    public static void register(DataWatcher watcher) {
        watcher.addObject(
            MaidLifeTuning.DATA_HOME,
            encode(MaidHomeSettings.EMPTY, Point.ZERO, MaidHomeConfig.nonHomeRange));
    }

    public MaidHomeSettings settings() {
        var encoded = maid.getDataWatcher()
            .getWatchableObjectString(MaidLifeTuning.DATA_HOME);
        if (!encoded.equals(observed)) {
            observed = encoded;
            try {
                var parts = encoded.split(MaidLifeIds.HOME_AREA_SPLIT_MESSAGE, MaidLifeTuning.UNSET_OR_NOT_FOUND);
                settings = MaidHomeSettings.decode(parts[MaidLifeTuning.DECODE_INITIAL_VALUE]);
                if (parts.length == MaidLifeTuning.TARGET_LENGTH_THRESHOLD) {
                    var xyz = parts[MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP]
                        .split(MaidLifeIds.COMMA_DELIMITER, MaidLifeTuning.UNSET_OR_NOT_FOUND);
                    if (xyz.length != MaidLifeTuning.TARGET_LENGTH_THRESHOLD)
                        throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_RESTRICTION_CENTER);
                    restrictionCenter = new Point(
                        Integer.parseInt(xyz[MaidLifeTuning.DECODE_INITIAL_VALUE]),
                        Integer.parseInt(xyz[MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP]),
                        Integer.parseInt(xyz[MaidLifeTuning.HOME_AREA_PARSE_INT_FIRST_PARAM]));
                    restrictionRadius = Integer.parseInt(parts[MaidLifeTuning.HOME_AREA_PARSE_INT_FIRST_PARAM]);
                    if (restrictionRadius < MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP
                        || restrictionRadius > MaidLifeTuning.MAX_RESTRICTION_RADIUS_THRESHOLD)
                        throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_RESTRICTION_RADIUS);
                } else if (parts.length == MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP) {
                    restrictionCenter = Point.ZERO;
                    restrictionRadius = MaidHomeConfig.nonHomeRange;
                } else throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_RESTRICTION_METADATA);
            } catch (IllegalArgumentException invalid) {
                settings = MaidHomeSettings.EMPTY;
                restrictionCenter = Point.ZERO;
                restrictionRadius = MaidHomeConfig.nonHomeRange;
                ModContext.LOG.warn(MaidLifeDiagnostics.MSG_INVALID_SYNCHRONIZED_HOME_SETTINGS_FOR, maid.getUniqueID());
            }
        }
        return settings;
    }

    public boolean enabled() {
        return settings().enabled;
    }

    public boolean sameDimension() {
        return maid.worldObj != null && settings().dimension == maid.worldObj.provider.dimensionId;
    }

    public Activity activity() {
        return MaidLifeRules.activity(maid.getSchedule(), maid.worldObj.getWorldTime());
    }

    public Point center() {
        return settings().center(activity());
    }

    public int radius() {
        return switch (activity()) {
            case WORK -> MaidHomeConfig.workRange;
            case IDLE -> MaidHomeConfig.idleRange;
            case REST -> MaidHomeConfig.sleepRange;
        };
    }

    public boolean contains(double x, double y, double z) {
        return !enabled() || sameDimension() && Double.isFinite(x)
            && Double.isFinite(y)
            && Double.isFinite(z)
            && center().containsBlock(
                MathHelper.floor_double(x),
                MathHelper.floor_double(y),
                MathHelper.floor_double(z),
                radius());
    }

    public boolean containsMaid() {
        return contains(maid.posX, maid.posY, maid.posZ);
    }

    public Point restrictionCenter() {
        settings();
        return restrictionCenter;
    }

    public int restrictionRadius() {
        settings();
        return restrictionRadius;
    }

    public boolean withinRestriction(int x, int y, int z) {
        return !enabled() || restrictionCenter().containsBlock(x, y, z, restrictionRadius());
    }

    /** 原restrictTo只在home开启时更新；关闭home仍保留最后一次半径和中心 */
    public void restrictToCurrentSchedule() {
        if (maid.worldObj == null || maid.worldObj.isRemote || !enabled()) return;
        restrictionCenter = settings().center(activity());
        restrictionRadius = radius();
        publish(settings);
    }

    private static String encode(MaidHomeSettings settings, Point center, int radius) {
        return settings.encode() + MaidLifeIds.PIPE_DELIMITER + center + MaidLifeIds.PIPE_DELIMITER + radius;
    }

    public boolean setEnabled(boolean enabled) {
        if (!canEdit()) return false;
        var old = settings();
        if (old.enabled == enabled) return true;
        var point = currentPoint();
        change(
            enabled && !old.configured
                ? new MaidHomeSettings(true, false, maid.worldObj.provider.dimensionId, point, point, point)
                : new MaidHomeSettings(enabled, old.configured, old.dimension, old.work, old.idle, old.rest));
        return true;
    }

    public boolean capture(Activity activity) {
        if (!canEdit() || !enabled() || !sameDimension()) return false;
        var old = settings();
        var point = currentPoint();
        change(
            new MaidHomeSettings(
                true,
                true,
                old.dimension,
                activity == Activity.WORK ? point : old.work,
                activity == Activity.IDLE ? point : old.idle,
                activity == Activity.REST ? point : old.rest));
        return true;
    }

    public boolean resetHere() {
        if (!canEdit() || !enabled()) return false;
        var point = currentPoint();
        change(new MaidHomeSettings(true, false, maid.worldObj.provider.dimensionId, point, point, point));
        return true;
    }

    private boolean canEdit() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && maid.isEntityAlive()
            && maid.isTamed()
            && maid.ridingEntity == null
            && maid.riddenByEntity == null
            && unreadSave == null
            && Double.isFinite(maid.posX)
            && Double.isFinite(maid.posY)
            && Double.isFinite(maid.posZ)
            && Math.abs(maid.posX) <= MaidLifeTuning.HOME_AREA_CAN_EDIT_PARAM
            && Math.abs(maid.posZ) <= MaidLifeTuning.HOME_AREA_CAN_EDIT_PARAM
            && maid.posY >= 0
            && maid.posY < MaidLifeTuning.MIN_POS_Y_THRESHOLD;
    }

    private Point currentPoint() {
        return new Point(
            MathHelper.floor_double(maid.posX),
            MathHelper.floor_double(maid.posY),
            MathHelper.floor_double(maid.posZ));
    }

    private void change(MaidHomeSettings next) {
        maid.getBrainCoordinator()
            .onHomeCommandChanged();
        publish(next);
        restrictToCurrentSchedule();
        observedActivity = activity();
    }

    private void publish(MaidHomeSettings next) {
        maid.getDataWatcher()
            .updateObject(MaidLifeTuning.DATA_HOME, encode(next, restrictionCenter, restrictionRadius));
        observed = null;
    }

    /** 日程切换取消旧活动动作，再由协调器决定去新活动点或开始该范围内的生活动作 */
    public boolean refreshActivity() {
        var next = activity();
        boolean changed = enabled() && observedActivity != null && next != observedActivity;
        observedActivity = next;
        return changed;
    }

    public void save(NBTTagCompound tag) {
        if (unreadSave != null) {
            tag.setTag(MaidLifeIds.SAVE_TAG, unreadSave.copy());
            return;
        }
        var home = new NBTTagCompound();
        home.setInteger(MaidLifeIds.KEY_SCHEMA, MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP);
        home.setString(MaidLifeIds.KEY_SETTINGS, settings().encode());
        tag.setTag(MaidLifeIds.SAVE_TAG, home);
    }

    public void load(NBTTagCompound tag) {
        unreadSave = null;
        observedActivity = null;
        publish(MaidHomeSettings.EMPTY);
        if (!tag.hasKey(MaidLifeIds.SAVE_TAG, Constants.NBT.TAG_COMPOUND)) return;
        var home = tag.getCompoundTag(MaidLifeIds.SAVE_TAG);
        try {
            if (home.getInteger(MaidLifeIds.KEY_SCHEMA) != MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP)
                throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_UNKNOWN_HOME_SCHEMA);
            publish(MaidHomeSettings.decode(home.getString(MaidLifeIds.KEY_SETTINGS)));
            restrictToCurrentSchedule();
        } catch (IllegalArgumentException invalid) {
            unreadSave = (NBTTagCompound) home.copy();
            ModContext.LOG.warn(MaidLifeDiagnostics.MSG_PRESERVING_UNREADABLE_HOME_SETTINGS_FOR, maid.getUniqueID());
        }
    }
}
