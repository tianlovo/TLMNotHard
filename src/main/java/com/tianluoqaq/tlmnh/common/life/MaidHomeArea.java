package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.entity.DataWatcher;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.life.MaidHomeSettings.Point;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 家园设置由服务端修改；读取活动范围不访问区块或加载目标维度 */
public final class MaidHomeArea {

    private static final int DATA_HOME = 29;
    private static final String SAVE_TAG = "TLMNHHome";
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
        watcher.addObject(DATA_HOME, encode(MaidHomeSettings.EMPTY, Point.ZERO, MaidHomeConfig.nonHomeRange));
    }

    public MaidHomeSettings settings() {
        var encoded = maid.getDataWatcher()
            .getWatchableObjectString(DATA_HOME);
        if (!encoded.equals(observed)) {
            observed = encoded;
            try {
                var parts = encoded.split("\\|", -1);
                settings = MaidHomeSettings.decode(parts[0]);
                if (parts.length == 3) {
                    var xyz = parts[1].split(",", -1);
                    if (xyz.length != 3) throw new IllegalArgumentException("Invalid restriction center");
                    restrictionCenter = new Point(
                        Integer.parseInt(xyz[0]),
                        Integer.parseInt(xyz[1]),
                        Integer.parseInt(xyz[2]));
                    restrictionRadius = Integer.parseInt(parts[2]);
                    if (restrictionRadius < 1 || restrictionRadius > 64)
                        throw new IllegalArgumentException("Invalid restriction radius");
                } else if (parts.length == 1) {
                    restrictionCenter = Point.ZERO;
                    restrictionRadius = MaidHomeConfig.nonHomeRange;
                } else throw new IllegalArgumentException("Invalid restriction metadata");
            } catch (IllegalArgumentException invalid) {
                settings = MaidHomeSettings.EMPTY;
                restrictionCenter = Point.ZERO;
                restrictionRadius = MaidHomeConfig.nonHomeRange;
                TLMNotHard.LOG.warn("Invalid synchronized home settings for maid {}", maid.getUniqueID());
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
        return settings.encode() + "|" + center + "|" + radius;
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
            && Math.abs(maid.posX) <= 29999984
            && Math.abs(maid.posZ) <= 29999984
            && maid.posY >= 0
            && maid.posY < 256;
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
            .updateObject(DATA_HOME, encode(next, restrictionCenter, restrictionRadius));
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
            tag.setTag(SAVE_TAG, unreadSave.copy());
            return;
        }
        var home = new NBTTagCompound();
        home.setInteger("Schema", 1);
        home.setString("Settings", settings().encode());
        tag.setTag(SAVE_TAG, home);
    }

    public void load(NBTTagCompound tag) {
        unreadSave = null;
        observedActivity = null;
        publish(MaidHomeSettings.EMPTY);
        if (!tag.hasKey(SAVE_TAG, 10)) return;
        var home = tag.getCompoundTag(SAVE_TAG);
        try {
            if (home.getInteger("Schema") != 1) throw new IllegalArgumentException("Unknown home schema");
            publish(MaidHomeSettings.decode(home.getString("Settings")));
            restrictToCurrentSchedule();
        } catch (IllegalArgumentException invalid) {
            unreadSave = (NBTTagCompound) home.copy();
            TLMNotHard.LOG.warn("Preserving unreadable home settings for maid {}; home disabled", maid.getUniqueID());
        }
    }
}
