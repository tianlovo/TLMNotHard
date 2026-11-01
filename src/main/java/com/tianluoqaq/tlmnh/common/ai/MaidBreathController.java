package com.tianluoqaq.tlmnh.common.ai;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.material.Material;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 低氧物品使用与三维找空气，复用 Root 动作所有权，不加载区块或改变空气值 */
public final class MaidBreathController {

    private static final int START_AIR = 100, RECOVERED_AIR = 290, RANGE = 16;
    private static final int SEARCH_PER_TICK = 128, MAX_SEARCHED = 32768, RETRY_TICKS = 20;
    private static final double SPEED = .5;
    private static final int[][] NEIGHBORS = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
        { 0, -1, 0 } };
    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final ArrayDeque<Node> searching = new ArrayDeque<>();
    private final Set<ChunkCoordinates> visited = new HashSet<>();
    private final ArrayDeque<ChunkCoordinates> route = new ArrayDeque<>();
    private MaidActionRun run;
    private ChunkCoordinates origin;
    private boolean active;
    private long retryAt;
    private String status = "idle";

    public MaidBreathController(EntityMaid maid, MaidActionRunner runner) {
        this.maid = maid;
        this.runner = runner;
    }

    public boolean isActive() {
        return active;
    }

    public com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager.Target nextSwimTarget() {
        var point = route.peek();
        return point == null ? null
            : new com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager.Target(
                point.posX,
                point.posY,
                point.posZ);
    }

    public String getStatus() {
        return status;
    }

    public String routeDiagnostic() {
        var next = route.peek();
        return "remaining=" + route.size()
            + ";next="
            + (next == null ? "none" : next.posX + "," + next.posY + "," + next.posZ)
            + ";feet="
            + maid.boundingBox.minY
            + ";size="
            + maid.width
            + ","
            + maid.height
            + ";motion="
            + maid.motionX
            + ","
            + maid.motionY
            + ","
            + maid.motionZ;
    }

    public boolean isSwimming() {
        return active && !route.isEmpty()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && runner.isOwned(run, maid.worldObj, maid.getTask());
    }

    public boolean tick() {
        if (maid.worldObj.isRemote || !maid.isEntityAlive()
            || maid.getBrainCoordinator()
                .isSuspended()
            || maid.isPotionActive(Potion.waterBreathing)
            || maid.getAir() >= (active ? RECOVERED_AIR : START_AIR)) {
            cancel();
            return false;
        }
        active = true;
        if (run == null || !runner.isOwned(run, maid.worldObj, maid.getTask())) {
            var proposed = runner.newRun(maid.worldObj, maid.getTask());
            if (proposed == null || !runner.claim(MaidActionRunner.Owner.BREATH, proposed, this::cancel)) return true;
            run = proposed;
            maid.getNavigator()
                .clearPathEntity();
            maid.getShieldController()
                .cancelUse("low_air");
            maid.getMealController()
                .cancel();
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) return true;
        if (maid.getMealController()
            .isBreathing()
            || maid.getMealController()
                .tryStartBreathing()) {
            route.clear();
            setStatus("using_breath_item");
            return true;
        }
        if (maid.isSitting() || maid.ridingEntity != null || maid.getLeashed() || maid.hasPose(MaidPose.SLEEPING)) {
            clearSearch();
            setStatus("movement_forbidden");
            return true;
        }
        if (!route.isEmpty()) {
            var next = route.peek();
            // 泳姿会改变眼高；起初可呼吸的终点不能继续沿用站姿的空气判断
            if (!passable(next) || !givesAir(route.peekLast())) clearSearch();
            else {
                double dy = next.posY - maid.boundingBox.minY;
                if (maid.getDistanceSq(next.posX + .5, maid.posY, next.posZ + .5) + dy * dy < .09) route.remove();
                setStatus("going_to_air");
                return true;
            }
        }
        long now = maid.worldObj.getTotalWorldTime();
        if (searching.isEmpty()) {
            if (now < retryAt) {
                setStatus("no_air_route");
                return true;
            }
            clearSearch();
            origin = new ChunkCoordinates(
                MathHelper.floor_double(maid.posX),
                MathHelper.floor_double(maid.boundingBox.minY),
                MathHelper.floor_double(maid.posZ));
            visited.add(origin);
            searching.add(new Node(origin, null));
        }
        for (int scanned = 0; scanned < SEARCH_PER_TICK && !searching.isEmpty(); scanned++) {
            var node = searching.remove();
            if (givesAir(node.point)) {
                for (var step = node; step != null; step = step.parent) route.addFirst(step.point);
                searching.clear();
                visited.clear();
                setStatus("going_to_air");
                return true;
            }
            for (int[] delta : NEIGHBORS) {
                var point = new ChunkCoordinates(
                    node.point.posX + delta[0],
                    node.point.posY + delta[1],
                    node.point.posZ + delta[2]);
                int dx = point.posX - origin.posX, dz = point.posZ - origin.posZ;
                if (dx * dx + dz * dz > RANGE * RANGE || Math.abs(point.posY - origin.posY) > RANGE
                    || visited.size() >= MAX_SEARCHED
                    || !visited.add(point)
                    || !passable(point)) continue;
                searching.add(new Node(point, node));
            }
        }
        if (searching.isEmpty()) {
            retryAt = now + RETRY_TICKS;
            setStatus("no_air_route");
        } else setStatus("searching_air");
        return true;
    }

    private boolean loaded(ChunkCoordinates p) {
        if (p.posY < 1 || p.posY + maid.height >= maid.worldObj.getHeight()) return false;
        for (int x = p.posX - 1; x <= p.posX + 1; x++)
            for (int z = p.posZ - 1; z <= p.posZ + 1; z++) if (!maid.worldObj.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)) return false;
        return true;
    }

    private boolean passable(ChunkCoordinates p) {
        if (!loaded(p) || !maid.getHomeArea()
            .contains(p.posX + .5, p.posY, p.posZ + .5)) return false;
        for (int y = p.posY; y <= MathHelper.floor_float(p.posY + maid.height); y++) {
            var material = maid.worldObj.getBlock(p.posX, y, p.posZ)
                .getMaterial();
            if (material == Material.lava || material == Material.fire || material == Material.cactus) return false;
        }
        double half = maid.width / 2;
        var box = AxisAlignedBB.getBoundingBox(
            p.posX + .5 - half,
            p.posY + .001,
            p.posZ + .5 - half,
            p.posX + .5 + half,
            p.posY + maid.height - .001,
            p.posZ + .5 + half);
        return maid.worldObj.getCollidingBoundingBoxes(maid, box)
            .isEmpty();
    }

    private boolean givesAir(ChunkCoordinates p) {
        if (!passable(p)) return false;
        var eye = maid.worldObj.getBlock(p.posX, MathHelper.floor_double(p.posY + maid.getEyeHeight()), p.posZ);
        return !eye.getMaterial()
            .isLiquid() || eye.getClass()
                .getName()
                .equals("ganymedes01.etfuturum.blocks.BlockBubbleColumn");
    }

    /** 采用上游 MaidMoveControl 与 travel 的水中加速度/阻力，在原生碰撞移动阶段执行 */
    public boolean travelWater() {
        if (!isSwimming() || !maid.isInWater()) return false;
        var target = route.peek();
        double x = target.posX + .5 - maid.posX, y = target.posY - maid.boundingBox.minY,
            z = target.posZ + .5 - maid.posZ;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length > 1.0e-5) {
            float yaw = (float) Math.toDegrees(Math.atan2(z, x)) - 90;
            float turn = MathHelper.wrapAngleTo180_float(yaw - maid.rotationYaw);
            maid.rotationYaw += Math.max(-90, Math.min(90, turn));
            maid.renderYawOffset = maid.rotationYaw;
            double speed = SPEED * maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .getAttributeValue();
            maid.motionX += speed * x * .005;
            maid.motionY += speed * y / length * .25;
            maid.motionZ += speed * z * .005;
            maid.moveFlying(0, (float) (speed * 3 * Math.sqrt(x * x + z * z) / length), .01F);
            maid.getLookHelper()
                .setLookPosition(target.posX + .5, target.posY + maid.getEyeHeight(), target.posZ + .5, 30, 30);
        }
        maid.moveEntity(maid.motionX, maid.motionY, maid.motionZ);
        maid.motionX *= .9;
        maid.motionY *= .9;
        maid.motionZ *= .9;
        return true;
    }

    private void clearSearch() {
        searching.clear();
        visited.clear();
        route.clear();
        origin = null;
    }

    public void cancel() {
        var stopped = run;
        boolean owns = runner.isOwned(stopped, maid.worldObj, maid.getTask());
        run = null;
        if (owns) {
            maid.getNavigator()
                .clearPathEntity();
            maid.getMoveHelper()
                .setMoveTo(maid.posX, maid.posY, maid.posZ, 0);
        }
        if (stopped != null) stopped.cancel();
        runner.release(stopped);
        if (maid.getMealController()
            .isBreathing())
            maid.getMealController()
                .cancel();
        active = false;
        retryAt = 0;
        clearSearch();
        setStatus("idle");
    }

    private void setStatus(String value) {
        if (status.equals(value)) return;
        status = value;
        maid.getBrainCoordinator()
            .getTelemetry()
            .state("breath", "", value);
    }

    private static final class Node {

        final ChunkCoordinates point;
        final Node parent;

        Node(ChunkCoordinates point, Node parent) {
            this.point = point;
            this.parent = parent;
        }
    }
}
