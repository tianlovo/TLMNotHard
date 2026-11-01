package com.tianluoqaq.tlmnh.common.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext.floor;
import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType.*;

import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 现有动作共用一个导航身份：地面保留旧执行，现代水路线使用三维推进和现代watchdog */
public final class MaidPathNavigator extends PathNavigate {

    private final EntityMaid maid;
    private final float[] costs = MaidPathType.maidCosts();
    private double speed;
    private int tick, lastStuckCheck;
    private Vec3 lastStuckPos = Vec3.createVectorHelper(0, 0, 0);
    private int timeoutX, timeoutY, timeoutZ;
    private long timeoutTimer, lastTimeoutCheck;
    private double timeoutLimit;
    private boolean stuck;

    public MaidPathNavigator(EntityMaid maid, World world) {
        super(maid, world);
        this.maid = maid;
        setCanSwim(true);
    }

    /** 纯创建，不改变现有路线或重置其计时；成功安装才接收新查询的watchdog状态 */
    public MaidAmphibiousPath createWaterPath(int x, int y, int z, float range, int accuracy) {
        var region = new MaidNativePathRegion(maid.worldObj);
        var context = new MaidPathContext(
            region,
            maid.posX,
            maid.boundingBox.minY,
            maid.posZ,
            maid.width,
            maid.height,
            maid.stepHeight,
            maid.getMaxSafePointTries(),
            maid.onGround,
            costs);
        int limit = floor(getPathSearchRange() * 16);
        return new MaidAmphibiousPath(MaidAmphibiousSearch.find(context, x, y, z, range, accuracy, limit));
    }

    @Override
    public boolean setPath(PathEntity path, double speed) {
        boolean installed = super.setPath(path, speed);
        this.speed = speed;
        if (installed && getPath() instanceof MaidAmphibiousPath modern) {
            for (int i = 0; i < modern.getCurrentPathLength(); i++) {
                var node = modern.getPathPointFromIndex(i);
                if (maid.worldObj.getBlock(node.xCoord, node.yCoord, node.zCoord) == Blocks.cauldron) {
                    modern.raise(i, node.yCoord + 1);
                    if (i + 1 < modern.getCurrentPathLength()
                        && node.yCoord >= modern.getPathPointFromIndex(i + 1).yCoord)
                        modern.raise(i + 1, node.yCoord + 1);
                }
            }
            lastStuckCheck = tick;
            lastStuckPos = position();
            resetTimeout();
        }
        return installed;
    }

    @Override
    public void setSpeed(double speed) {
        super.setSpeed(speed);
        this.speed = speed;
    }

    public boolean isModernStuck() {
        return stuck;
    }

    @Override
    public void onUpdateNavigation() {
        tick++;
        if (!(getPath() instanceof MaidAmphibiousPath path)) {
            super.onUpdateNavigation();
            return;
        }
        if (path.isFinished()) return;
        follow(path);
        if (!noPath()) {
            var target = getPath().getPosition(maid);
            maid.getMoveHelper()
                .setMoveTo(target.xCoord, target.yCoord, target.zCoord, speed);
        }
    }

    private Vec3 position() {
        return Vec3.createVectorHelper(maid.posX, maid.posY + maid.height * .5, maid.posZ);
    }

    private void follow(MaidAmphibiousPath path) {
        var position = position();
        var node = path.getPathPointFromIndex(path.getCurrentPathIndex());
        float tolerance = maid.width > .75F ? maid.width / 2F : .75F - maid.width / 2F;
        double offset = (double) (maid.width + 1F) / 2;
        boolean near = Math.abs(maid.posX - (node.xCoord + offset)) <= tolerance
            && Math.abs(maid.posZ - (node.zCoord + offset)) <= tolerance
            && Math.abs(maid.posY - node.yCoord) < 1;
        var type = path.currentType();
        if (near
            || type != DANGER_FIRE && type != DANGER_OTHER && type != WALKABLE_DOOR && nextInDirection(path, position))
            path.incrementPathIndex();
        detectStuck(position);
    }

    private boolean nextInDirection(MaidAmphibiousPath path, Vec3 position) {
        int index = path.getCurrentPathIndex();
        if (index + 1 >= path.getCurrentPathLength()) return false;
        var first = path.getPathPointFromIndex(index);
        var target = Vec3.createVectorHelper(first.xCoord + .5, first.yCoord, first.zCoord + .5);
        if (position.squareDistanceTo(target) >= 4) return false;
        if (maid.isInWater() || maid.handleLavaMovement()) {
            var end = path.getPosition(maid)
                .addVector(0, maid.height * .5, 0);
            if (maid.worldObj.func_147447_a(position, end, false, true, false) == null) return true;
        }
        var second = path.getPathPointFromIndex(index + 1);
        var one = target.subtract(position);
        var two = Vec3.createVectorHelper(second.xCoord + .5, second.yCoord, second.zCoord + .5)
            .subtract(position);
        double a = one.xCoord * one.xCoord + one.yCoord * one.yCoord + one.zCoord * one.zCoord,
            b = two.xCoord * two.xCoord + two.yCoord * two.yCoord + two.zCoord * two.zCoord;
        return (b < a || a < .5) && one.normalize()
            .dotProduct(two.normalize()) < 0;
    }

    private void detectStuck(Vec3 position) {
        if (tick - lastStuckCheck > 100) {
            float speed = maid.getAIMoveSpeed();
            float distance = (speed >= 1 ? speed : speed * speed) * 100F * .25F;
            if (position.squareDistanceTo(lastStuckPos) < distance * distance) {
                stuck = true;
                watchdogStop();
            } else stuck = false;
            lastStuckCheck = tick;
            lastStuckPos = position;
        }
        var path = getPath();
        if (path == null || path.isFinished()) return;
        var node = path.getPathPointFromIndex(path.getCurrentPathIndex());
        long time = maid.worldObj.getTotalWorldTime();
        if (node.xCoord == timeoutX && node.yCoord == timeoutY && node.zCoord == timeoutZ)
            timeoutTimer += time - lastTimeoutCheck;
        else {
            timeoutX = node.xCoord;
            timeoutY = node.yCoord;
            timeoutZ = node.zCoord;
            double distance = position.distanceTo(Vec3.createVectorHelper(timeoutX + .5, timeoutY, timeoutZ + .5));
            timeoutLimit = maid.getAIMoveSpeed() > 0 ? distance / maid.getAIMoveSpeed() * 20 : 0;
        }
        if (timeoutLimit > 0 && timeoutTimer > timeoutLimit * 3) {
            resetTimeout();
            watchdogStop();
        }
        lastTimeoutCheck = time;
    }

    private void resetTimeout() {
        timeoutX = timeoutY = timeoutZ = 0;
        timeoutTimer = 0;
        timeoutLimit = 0;
        stuck = false;
    }

    private void watchdogStop() {
        // 现代停止事实独立保存，不能伪装成RC2_NATIVE_WATCHDOG事件
        clearPathEntity();
    }
}
