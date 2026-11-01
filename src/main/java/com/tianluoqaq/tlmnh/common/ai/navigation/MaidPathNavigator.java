package com.tianluoqaq.tlmnh.common.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext.floor;
import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType.*;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidNavigationManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Navigation;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** One action-facing navigator, with independent modern ground/water navigation states */
public final class MaidPathNavigator extends PathNavigate implements MaidNavigationActor {

    private final EntityMaid maid;
    private final float[] costs = MaidPathType.maidCosts();
    private final State ground = new State(false), water = new State(true);
    private State active;
    private final MaidNavigationManager manager;
    private Point walkTarget;
    private long generation;
    private boolean avoidSun;

    public MaidPathNavigator(EntityMaid maid, World world) {
        super(maid, world);
        this.maid = maid;
        manager = new MaidNavigationManager(this);
        setCanSwim(true);
    }

    public MaidNavigationManager getManager() {
        return manager;
    }

    public long generation() {
        return generation;
    }

    public boolean usesWaterNavigation() {
        return active == water;
    }

    public void tickMode() {
        manager.tick();
    }

    public void resetNavigation() {
        manager.resetNavigation();
    }

    private MaidNativePathRegion region() {
        return new MaidNativePathRegion(maid.worldObj, maid);
    }

    /** Pure query used by the guarded production create/install boundary */
    public MaidModernPath createPath(int x, int y, int z, float range, int accuracy) {
        return query(active, x, y, z, range, accuracy);
    }

    public MaidAmphibiousPath createWaterPath(int x, int y, int z, float range, int accuracy) {
        return (MaidAmphibiousPath) query(water, x, y, z, range, accuracy);
    }

    private MaidModernPath query(State state, int x, int y, int z, float range, int accuracy) {
        var region = region();
        var requested = new Point(x, y, z);
        if (!state.water) {
            if (region.air(x, y, z)) {
                int below = y - 1;
                while (below > region.minY() && region.air(x, below, z)) below--;
                if (below > region.minY()) y = below + 1;
                else {
                    while (below < region.maxY() && region.air(x, below, z)) below++;
                    y = below;
                }
            }
            if (region.solid(x, y, z)) {
                y++;
                while (y < region.maxY() && region.solid(x, y, z)) y++;
            }
        }
        var context = context(region);
        var result = MaidPathSearch
            .find(context, state.water, x, y, z, range, accuracy, floor(getPathSearchRange() * 16));
        return state.water ? new MaidAmphibiousPath(result, requested) : new MaidModernPath(result, requested);
    }

    public MaidPathContext context(MaidPathRegion region) {
        return new MaidPathContext(
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
    }

    public MaidWalkEvaluator evaluator() {
        return active.water
            ? new com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidUnderWaterNodeEvaluator(
                context(region()))
            : new com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidNodeEvaluator(context(region()));
    }

    public boolean canUpdatePath() {
        return active.water || maid.onGround
            || maid.isInWater()
            || maid.handleLavaMovement()
            || maid.ridingEntity != null;
    }

    @Override
    public PathEntity getPath() {
        return active == null ? null : active.path;
    }

    @Override
    public boolean noPath() {
        return getPath() == null || getPath().isFinished();
    }

    @Override
    public void clearPathEntity() {
        if (active != null) active.stop();
        walkTarget = null;
        super.clearPathEntity();
    }

    @Override
    public boolean setPath(PathEntity path, double speed) {
        boolean installed = active.install(path, speed);
        if (installed && path instanceof MaidModernPath modern) walkTarget = modern.requestedTarget;
        return installed;
    }

    @Override
    public void setSpeed(double speed) {
        super.setSpeed(speed);
        if (active != null) active.speed = speed;
    }

    @Override
    public void setAvoidSun(boolean value) {
        super.setAvoidSun(value);
        avoidSun = value;
    }

    @Override
    public PathEntity getPathToXYZ(double x, double y, double z) {
        if (!canUpdatePath()) return null;
        var path = query(active, floor(x), (int) y, floor(z), getPathSearchRange(), 1);
        active.resetTimeout();
        return path;
    }

    @Override
    public PathEntity getPathToEntityLiving(Entity entity) {
        return getPathToXYZ(entity.posX, entity.posY, entity.posZ);
    }

    public boolean isModernStuck() {
        return active.stuck;
    }

    public double speedModifier() {
        return active.speed;
    }

    @Override
    public void onUpdateNavigation() {
        active.tick++;
        if (active.path == null || active.path.isFinished()) return;
        if (!(active.path instanceof MaidModernPath modern)) {
            super.onUpdateNavigation();
            return;
        }
        if (canUpdatePath()) active.follow(modern);
        else {
            var position = active.position();
            var next = modern.getPosition(maid);
            if (position.yCoord > next.yCoord && !maid.onGround
                && floor(position.xCoord) == floor(next.xCoord)
                && floor(position.zCoord) == floor(next.zCoord)) modern.incrementPathIndex();
        }
        if (!noPath()) {
            var target = getPath().getPosition(maid);
            double y = active.water
                || region().air(floor(target.xCoord), floor(target.yCoord) - 1, floor(target.zCoord)) ? target.yCoord
                    : region().floor(floor(target.xCoord), floor(target.yCoord), floor(target.zCoord));
            maid.getMoveHelper()
                .setMoveTo(target.xCoord, y, target.zCoord, active.speed);
        }
    }

    @Override
    public boolean effectiveServerAi() {
        return !maid.worldObj.isRemote && maid.isEntityAlive()
            && !maid.getBrainCoordinator()
                .isSuspended();
    }

    @Override
    public boolean inWater() {
        return maid.isInWater();
    }

    @Override
    public boolean underWater() {
        return maid.isInsideOfMaterial(net.minecraft.block.material.Material.water);
    }

    @Override
    public boolean water(int x, int y, int z) {
        return region().water(x, y, z);
    }

    @Override
    public boolean air(int x, int y, int z) {
        return region().air(x, y, z);
    }

    @Override
    public boolean landPassable(int x, int y, int z) {
        return region().landPassable(x, y, z);
    }

    @Override
    public Point position() {
        return new Point(floor(maid.posX), floor(maid.posY), floor(maid.posZ));
    }

    @Override
    public Point walkTarget() {
        return walkTarget;
    }

    @Override
    public MaidSwimManager swim() {
        return maid.getAquaticController()
            .getManager();
    }

    @Override
    public Navigation ground() {
        return ground;
    }

    @Override
    public Navigation waterNavigation() {
        return water;
    }

    @Override
    public Navigation current() {
        return active;
    }

    @Override
    public void activate(Navigation navigation) {
        if (navigation != ground && navigation != water) throw new IllegalArgumentException("Foreign navigation state");
        if (active != navigation) {
            active = (State) navigation;
            generation++;
        }
    }

    private final class State implements Navigation {

        final boolean water;
        PathEntity path;
        LegacyPath legacy;
        double speed;
        int tick, lastStuckCheck, timeoutX, timeoutY, timeoutZ;
        long timeoutTimer, lastTimeoutCheck;
        double timeoutLimit;
        boolean stuck;
        Vec3 lastStuckPos = Vec3.createVectorHelper(0, 0, 0);

        State(boolean water) {
            this.water = water;
        }

        @Override
        public MaidNavigationActor.Path path() {
            return path instanceof MaidModernPath modern ? modern : legacy;
        }

        @Override
        public boolean done() {
            return path == null || path.isFinished();
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public void stop() {
            if (path != null && !(path instanceof MaidModernPath)) MaidPathNavigator.super.clearPathEntity();
            path = null;
            legacy = null;
        }

        @Override
        public MaidNavigationActor.Path create(Point target) {
            if (path instanceof MaidModernPath modern && !path.isFinished()
                && modern.result.targetX == target.x
                && modern.result.targetY == target.y
                && modern.result.targetZ == target.z) return modern;
            if (!MaidWorldNavigationBudget.acquire(maid)) return null;
            var created = query(this, target.x, target.y, target.z, Math.min(16, getPathSearchRange()), 0);
            resetTimeout();
            return created;
        }

        @Override
        public boolean moveTo(MaidNavigationActor.Path candidate, double speed) {
            if (!(candidate instanceof MaidModernPath modern)) return false;
            if (active != this && active.path != null) modern.continueCancellation(active.path);
            return install(modern, speed);
        }

        boolean install(PathEntity candidate, double speed) {
            if (candidate == null) {
                stop();
                return false;
            }
            if (candidate instanceof MaidModernPath) {
                if (!candidate.isSamePath(path)) path = candidate;
                if (path.isFinished()) return false;
                MaidPathNavigator.super.clearPathEntity();
                legacy = null;
                var modern = (MaidModernPath) path;
                for (int i = 0; i < modern.getCurrentPathLength(); i++) {
                    var node = modern.getPathPointFromIndex(i);
                    if (maid.worldObj.getBlock(node.xCoord, node.yCoord, node.zCoord) == Blocks.cauldron) {
                        modern.raise(i, node.yCoord + 1);
                        if (i + 1 < modern.getCurrentPathLength()
                            && node.yCoord >= modern.getPathPointFromIndex(i + 1).yCoord)
                            modern.raise(i + 1, node.yCoord + 1);
                    }
                }
                if (!water && avoidSun
                    && !maid.worldObj.canBlockSeeTheSky(floor(maid.posX), floor(maid.posY + .5), floor(maid.posZ))) {
                    for (int i = 0; i < modern.getCurrentPathLength(); i++) {
                        var n = modern.getPathPointFromIndex(i);
                        if (maid.worldObj.canBlockSeeTheSky(n.xCoord, n.yCoord, n.zCoord)) {
                            modern.setCurrentPathLength(i);
                            break;
                        }
                    }
                }
            } else {
                if (!MaidPathNavigator.super.setPath(candidate, speed)) return false;
                path = MaidPathNavigator.super.getPath();
                legacy = new LegacyPath(path);
            }
            if (path.getCurrentPathLength() <= 0) return false;
            this.speed = speed;
            lastStuckCheck = tick;
            lastStuckPos = position();
            resetTimeout();
            return true;
        }

        Vec3 position() {
            double y = maid.posY + maid.height * .5;
            if (!water) {
                int surface = floor(maid.posY);
                if (maid.isInWater()) {
                    int steps = 0;
                    while (region().water(floor(maid.posX), surface, floor(maid.posZ))) {
                        surface++;
                        if (++steps > 16) {
                            surface = floor(maid.posY);
                            break;
                        }
                    }
                } else surface = floor(maid.posY + .5);
                y = surface;
            }
            return Vec3.createVectorHelper(maid.posX, y, maid.posZ);
        }

        void follow(MaidModernPath route) {
            var position = position();
            var node = route.getPathPointFromIndex(route.getCurrentPathIndex());
            float tolerance = maid.width > .75F ? maid.width / 2F : .75F - maid.width / 2F;
            double offset = (double) (maid.width + 1F) / 2;
            boolean near = Math.abs(maid.posX - (node.xCoord + offset)) <= tolerance
                && Math.abs(maid.posZ - (node.zCoord + offset)) <= tolerance
                && Math.abs(maid.posY - node.yCoord) < 1;
            var type = route.currentType();
            if (near || type != DANGER_FIRE && type != DANGER_OTHER
                && type != WALKABLE_DOOR
                && nextInDirection(route, position)) route.incrementPathIndex();
            detectStuck(position);
        }

        boolean nextInDirection(MaidModernPath route, Vec3 position) {
            int index = route.getCurrentPathIndex();
            if (index + 1 >= route.getCurrentPathLength()) return false;
            var first = route.getPathPointFromIndex(index);
            var target = Vec3.createVectorHelper(first.xCoord + .5, first.yCoord, first.zCoord + .5);
            if (position.squareDistanceTo(target) >= 4) return false;
            if (water && (maid.isInWater() || maid.handleLavaMovement())) {
                var end = route.getPosition(maid)
                    .addVector(0, maid.height * .5, 0);
                if (maid.worldObj.func_147447_a(position, end, false, true, false) == null) return true;
            }
            var second = route.getPathPointFromIndex(index + 1);
            var one = target.subtract(position);
            var two = Vec3.createVectorHelper(second.xCoord + .5, second.yCoord, second.zCoord + .5)
                .subtract(position);
            double a = one.xCoord * one.xCoord + one.yCoord * one.yCoord + one.zCoord * one.zCoord,
                b = two.xCoord * two.xCoord + two.yCoord * two.yCoord + two.zCoord * two.zCoord;
            return (b < a || a < .5) && one.normalize()
                .dotProduct(two.normalize()) < 0;
        }

        void detectStuck(Vec3 position) {
            if (tick - lastStuckCheck > 100) {
                float speed = maid.getAIMoveSpeed();
                float distance = (speed >= 1 ? speed : speed * speed) * 100F * .25F;
                if (position.squareDistanceTo(lastStuckPos) < distance * distance) {
                    stuck = true;
                    stop();
                } else stuck = false;
                lastStuckCheck = tick;
                lastStuckPos = position;
            }
            if (done()) return;
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
                stop();
            }
            lastTimeoutCheck = time;
        }

        void resetTimeout() {
            timeoutX = timeoutY = timeoutZ = 0;
            timeoutTimer = 0;
            timeoutLimit = 0;
            stuck = false;
        }
    }

    private static final class LegacyPath implements MaidNavigationActor.Path {

        final PathEntity path;

        LegacyPath(PathEntity path) {
            this.path = path;
        }

        @Override
        public boolean canReach() {
            return false;
        }

        @Override
        public boolean done() {
            return path.isFinished();
        }

        @Override
        public int index() {
            return path.getCurrentPathIndex();
        }

        @Override
        public int size() {
            return path.getCurrentPathLength();
        }

        @Override
        public Point node(int index) {
            var p = path.getPathPointFromIndex(index);
            return new Point(p.xCoord, p.yCoord, p.zCoord);
        }

        @Override
        public Point end() {
            return size() == 0 ? null : node(size() - 1);
        }

        @Override
        public void advance() {
            path.incrementPathIndex();
        }
    }
}
