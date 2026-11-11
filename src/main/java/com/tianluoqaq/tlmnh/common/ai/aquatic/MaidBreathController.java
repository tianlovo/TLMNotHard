package com.tianluoqaq.tlmnh.common.ai.aquatic;

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
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 低氧物品使用与三维找空气，复用 Root 动作所有权，不加载区块或改变空气值 */
public final class MaidBreathController {

    private static final int[][] NEIGHBORS = {
        { AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
            AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE },
        { AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE },
        { AquaticAiTuning.UNSET_OR_NOT_FOUND, AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE },
        { AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
            AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP },
        { AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE, AquaticAiTuning.UNSET_OR_NOT_FOUND },
        { AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE, AquaticAiTuning.UNSET_OR_NOT_FOUND,
            AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE } };
    private final EntityMaid maid;
    private final MaidActionRunner runner;
    private final ArrayDeque<Node> searching = new ArrayDeque<>();
    private final Set<ChunkCoordinates> visited = new HashSet<>();
    private final ArrayDeque<ChunkCoordinates> route = new ArrayDeque<>();
    private MaidActionRun run;
    private ChunkCoordinates origin;
    private boolean active;
    private long retryAt;
    private String status = AquaticAiIds.ID_IDLE;

    public MaidBreathController(EntityMaid maid, MaidActionRunner runner) {
        this.maid = maid;
        this.runner = runner;
    }

    public boolean isActive() {
        return active;
    }

    public MaidSwimManager.Target nextSwimTarget() {
        var point = route.peek();
        return point == null ? null : new MaidSwimManager.Target(point.posX, point.posY, point.posZ);
    }

    public String getStatus() {
        return status;
    }

    public String routeDiagnostic() {
        var next = route.peek();
        return AquaticAiIds.ID_REMAINING + route.size()
            + AquaticAiIds.ID_NEXT
            + (next == null ? AquaticAiIds.ID_NONE
                : next.posX + AquaticAiIds.COMMA_DELIMITER + next.posY + AquaticAiIds.COMMA_DELIMITER + next.posZ)
            + AquaticAiIds.ID_FEET
            + maid.boundingBox.minY
            + AquaticAiIds.ID_SIZE
            + maid.width
            + AquaticAiIds.COMMA_DELIMITER
            + maid.height
            + AquaticAiIds.ID_MOTION
            + maid.motionX
            + AquaticAiIds.COMMA_DELIMITER
            + maid.motionY
            + AquaticAiIds.COMMA_DELIMITER
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
            || maid.getAir() >= (active ? AquaticAiTuning.RECOVERED_AIR : AquaticAiTuning.START_AIR)) {
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
                .cancelUse(AquaticAiIds.ID_LOW_AIR);
            maid.getMealController()
                .cancel();
        }
        if (!runner.isOwned(run, maid.worldObj, maid.getTask())) return true;
        if (maid.getMealController()
            .isBreathing()
            || maid.getMealController()
                .tryStartBreathing()) {
            route.clear();
            setStatus(AquaticAiIds.ID_USING_BREATH_ITEM);
            return true;
        }
        if (maid.isSitting() || maid.ridingEntity != null || maid.getLeashed() || maid.hasPose(MaidPose.SLEEPING)) {
            clearSearch();
            setStatus(AquaticAiIds.ID_MOVEMENT_FORBIDDEN);
            return true;
        }
        if (!route.isEmpty()) {
            var next = route.peek();
            // 泳姿会改变眼高；起初可呼吸的终点不能继续沿用站姿的空气判断
            if (!passable(next) || !givesAir(route.peekLast())) clearSearch();
            else {
                double dy = next.posY - maid.boundingBox.minY;
                if (maid.getDistanceSq(
                    next.posX + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR,
                    maid.posY,
                    next.posZ + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR) + dy * dy
                    < AquaticAiTuning.MIN_DY_THRESHOLD) route.remove();
                setStatus(AquaticAiIds.ID_GOING_TO_AIR);
                return true;
            }
        }
        long now = maid.worldObj.getTotalWorldTime();
        if (searching.isEmpty()) {
            if (now < retryAt) {
                setStatus(AquaticAiIds.ID_NO_AIR_ROUTE);
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
        for (int scanned = 0; scanned < AquaticAiTuning.SEARCH_PER_TICK && !searching.isEmpty(); scanned++) {
            var node = searching.remove();
            if (givesAir(node.point)) {
                for (var step = node; step != null; step = step.parent) route.addFirst(step.point);
                searching.clear();
                visited.clear();
                setStatus(AquaticAiIds.ID_GOING_TO_AIR);
                return true;
            }
            for (int[] delta : NEIGHBORS) {
                var point = new ChunkCoordinates(
                    node.point.posX + delta[AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE],
                    node.point.posY + delta[AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP],
                    node.point.posZ + delta[AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM]);
                int dx = point.posX - origin.posX, dz = point.posZ - origin.posZ;
                if (dx * dx + dz * dz > AquaticAiTuning.RANGE * AquaticAiTuning.RANGE
                    || Math.abs(point.posY - origin.posY) > AquaticAiTuning.RANGE
                    || visited.size() >= AquaticAiTuning.MAX_SEARCHED
                    || !visited.add(point)
                    || !passable(point)) continue;
                searching.add(new Node(point, node));
            }
        }
        if (searching.isEmpty()) {
            retryAt = now + AquaticAiTuning.RETRY_TICKS;
            setStatus(AquaticAiIds.ID_NO_AIR_ROUTE);
        } else setStatus(AquaticAiIds.ID_SEARCHING_AIR);
        return true;
    }

    private boolean loaded(ChunkCoordinates p) {
        if (p.posY < AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP
            || p.posY + maid.height >= maid.worldObj.getHeight()) return false;
        for (int x = p.posX - 1; x <= p.posX + 1; x++)
            for (int z = p.posZ - 1; z <= p.posZ + 1; z++) if (!maid.worldObj.getChunkProvider()
                .chunkExists(x >> AquaticAiTuning.CHUNK_SECTION_SHIFT, z >> AquaticAiTuning.CHUNK_SECTION_SHIFT))
                return false;
        return true;
    }

    private boolean passable(ChunkCoordinates p) {
        if (!loaded(p) || !maid.getHomeArea()
            .contains(
                p.posX + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR,
                p.posY,
                p.posZ + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR))
            return false;
        for (int y = p.posY; y <= MathHelper.floor_float(p.posY + maid.height); y++) {
            var material = maid.worldObj.getBlock(p.posX, y, p.posZ)
                .getMaterial();
            if (material == Material.lava || material == Material.fire || material == Material.cactus) return false;
        }
        double half = maid.width / AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM;
        var box = AxisAlignedBB.getBoundingBox(
            p.posX + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR - half,
            p.posY + AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET,
            p.posZ + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR - half,
            p.posX + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR + half,
            p.posY + maid.height - AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET,
            p.posZ + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR + half);
        return maid.worldObj.getCollidingBoundingBoxes(maid, box)
            .isEmpty();
    }

    private boolean givesAir(ChunkCoordinates p) {
        if (!passable(p)) return false;
        var eye = maid.worldObj.getBlock(p.posX, MathHelper.floor_double(p.posY + maid.getEyeHeight()), p.posZ);
        return !eye.getMaterial()
            .isLiquid() || CompatServices.worldContent()
                .isBubbleColumn(eye);
    }

    /** 采用上游 MaidMoveControl 与 travel 的水中加速度/阻力，在原生碰撞移动阶段执行 */
    public boolean travelWater() {
        if (!isSwimming() || !maid.isInWater()) return false;
        var target = route.peek();
        double x = target.posX + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR - maid.posX,
            y = target.posY - maid.boundingBox.minY,
            z = target.posZ + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR - maid.posZ;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length > AquaticAiTuning.MAX_LENGTH_THRESHOLD) {
            float yaw = (float) Math.toDegrees(Math.atan2(z, x)) - AquaticAiTuning.BREATH_CONTROLLER_YAW;
            float turn = MathHelper.wrapAngleTo180_float(yaw - maid.rotationYaw);
            maid.rotationYaw += Math.max(
                AquaticAiTuning.BREATH_CONTROLLER_MAX_FIRST_PARAM,
                Math.min(AquaticAiTuning.BREATH_CONTROLLER_YAW, turn));
            maid.renderYawOffset = maid.rotationYaw;
            double speed = AquaticAiTuning.SPEED * maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .getAttributeValue();
            maid.motionX += speed * x * AquaticAiTuning.BREATH_CONTROLLER_TRAVEL_WATER_FACTOR;
            maid.motionY += speed * y / length * AquaticAiTuning.BREATH_CONTROLLER_TRAVEL_WATER_FACTOR_2;
            maid.motionZ += speed * z * AquaticAiTuning.BREATH_CONTROLLER_TRAVEL_WATER_FACTOR;
            maid.moveFlying(
                AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE,
                (float) (speed * AquaticAiTuning.BREATH_CONTROLLER_MOVE_FLYING_SECOND_PARAM
                    * Math.sqrt(x * x + z * z)
                    / length),
                AquaticAiTuning.AQUATIC_CONTROLLER_MOVE_FLYING_THIRD_FACTOR);
            maid.getLookHelper()
                .setLookPosition(
                    target.posX + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR,
                    target.posY + maid.getEyeHeight(),
                    target.posZ + AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR,
                    AquaticAiTuning.BREATH_CONTROLLER_SET_LOOK_POSITION_FOURTH_PARAM,
                    AquaticAiTuning.BREATH_CONTROLLER_SET_LOOK_POSITION_FOURTH_PARAM);
        }
        maid.moveEntity(maid.motionX, maid.motionY, maid.motionZ);
        maid.motionX *= AquaticAiTuning.AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR;
        maid.motionY *= AquaticAiTuning.AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR;
        maid.motionZ *= AquaticAiTuning.AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR;
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
                .setMoveTo(
                    maid.posX,
                    maid.posY,
                    maid.posZ,
                    AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE);
        }
        if (stopped != null) stopped.cancel();
        runner.release(stopped);
        if (maid.getMealController()
            .isBreathing())
            maid.getMealController()
                .cancel();
        active = false;
        retryAt = AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE;
        clearSearch();
        setStatus(AquaticAiIds.ID_IDLE);
    }

    private void setStatus(String value) {
        if (status.equals(value)) return;
        status = value;
        maid.getBrainCoordinator()
            .getTelemetry()
            .state(AquaticAiIds.ID_BREATH, "", value);
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
