package com.tianluoqaq.tlmnh.common.ai.aquatic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 沿当前动作已安装的路径开关门和爬梯，不另行抢占移动目标或创建路径 */
public final class MaidTraversalController {

    private final EntityMaid maid;
    private final Map<ChunkCoordinates, Block> opened = new HashMap<>();
    private World observedWorld;
    private boolean climbing;

    public MaidTraversalController(EntityMaid maid) {
        this.maid = maid;
    }

    public void configureNavigator() {
        // 1.7.10 的这个开关只允许规划穿过闭合木门；没有安装破门任务
        maid.getNavigator()
            .setBreakDoors(
                maid.getConfigManager()
                    .isOpenDoor());
    }

    /** 既有原型控制器探针仍使用该入口 */
    public void tick() {
        tick(true);
    }

    public void tick(boolean prototypeClimbing) {
        if (maid.worldObj.isRemote) return;
        if (!prototypeClimbing) {
            // 现代分支由原Door OneShot与门记忆管理，旧控制器不再开关门或爬梯
            opened.clear();
            stopClimbing();
            return;
        }
        if (observedWorld != maid.worldObj) {
            opened.clear();
            observedWorld = maid.worldObj;
        }
        var path = maid.getNavigator()
            .getPath();
        boolean active = maid.isEntityAlive() && maid.isTamed()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && path != null
            && !path.isFinished();
        if (active) {
            int index = path.getCurrentPathIndex();
            if (index > 0) open(path.getPathPointFromIndex(index - 1));
            open(path.getPathPointFromIndex(index));
            if (prototypeClimbing) climb(path);
            else stopClimbing();
        } else stopClimbing();
        closePassedDoors(active ? path : null);
    }

    public void clear() {
        // 动作交接后仍在后续 tick 关闭自己打开的门，换世界时才丢弃旧世界坐标
        stopClimbing();
    }

    private void open(PathPoint point) {
        int x = point.xCoord, y = point.yCoord, z = point.zCoord;
        if (!maid.worldObj.blockExists(x, y, z)
            || maid.getDistanceSq(x + AquaticAiTuning.CENTER_OFFSET_D, y, z + AquaticAiTuning.CENTER_OFFSET_D)
                > AquaticAiTuning.CHUNK_SECTION_SHIFT)
            return;
        var block = maid.worldObj.getBlock(x, y, z);
        if (block instanceof BlockDoor
            && (maid.worldObj.getBlockMetadata(x, y, z) & AquaticAiTuning.TRAVERSAL_CONTROLLER_BIT_MASK_8) != 0) y--;
        boolean permitted = block instanceof BlockDoor && block.getMaterial() == Material.wood ? maid.getConfigManager()
            .isOpenDoor()
            : block instanceof BlockFenceGate && maid.getConfigManager()
                .isOpenFenceGate();
        if (permitted && !isOpen(block, x, y, z)) {
            setOpen(block, x, y, z, true);
            opened.put(new ChunkCoordinates(x, y, z), block);
        }
    }

    private boolean isOpen(Block block, int x, int y, int z) {
        return block instanceof BlockDoor door ? CompatServices.doors()
            .isDoorOpenOrOpening(door, maid.worldObj, x, y, z)
            : (maid.worldObj.getBlockMetadata(x, y, z) & AquaticAiTuning.CHUNK_SECTION_SHIFT) != 0;
    }

    private void setOpen(Block block, int x, int y, int z, boolean value) {
        if (block instanceof BlockDoor door) door.func_150014_a(maid.worldObj, x, y, z, value);
        else {
            int meta = maid.worldObj.getBlockMetadata(x, y, z);
            maid.worldObj.setBlockMetadataWithNotify(
                x,
                y,
                z,
                value ? meta | AquaticAiTuning.CHUNK_SECTION_SHIFT : meta & ~AquaticAiTuning.CHUNK_SECTION_SHIFT,
                AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM);
            maid.worldObj.playAuxSFXAtEntity(
                null,
                AquaticAiTuning.TRAVERSAL_CONTROLLER_PLAY_AUX_SFX_AT_ENTITY_SECOND_P,
                x,
                y,
                z,
                AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE);
        }
    }

    private void closePassedDoors(PathEntity path) {
        Iterator<Map.Entry<ChunkCoordinates, Block>> entries = opened.entrySet()
            .iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var p = entry.getKey();
            if (!maid.worldObj.blockExists(p.posX, p.posY, p.posZ)
                || maid.worldObj.getBlock(p.posX, p.posY, p.posZ) != entry.getValue()
                || maid.getDistanceSq(
                    p.posX + AquaticAiTuning.CENTER_OFFSET_D,
                    p.posY,
                    p.posZ + AquaticAiTuning.CENTER_OFFSET_D)
                    > AquaticAiTuning.TRAVERSAL_CONTROLLER_CLOSE_PASSED_DOORS_PARAM) {
                entries.remove();
                continue;
            }
            if (!isOpen(entry.getValue(), p.posX, p.posY, p.posZ)) {
                entries.remove();
                continue;
            }
            if (usesDoor(path, p) || maid.boundingBox.intersectsWith(
                AxisAlignedBB.getBoundingBox(
                    p.posX - AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FIRST_FACTOR,
                    p.posY,
                    p.posZ - AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FIRST_FACTOR,
                    p.posX + AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FOURTH_FACTOR,
                    p.posY + AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM,
                    p.posZ + AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FOURTH_FACTOR)))
                continue;
            var nearby = maid.worldObj.getEntitiesWithinAABB(
                EntityLivingBase.class,
                AxisAlignedBB.getBoundingBox(
                    p.posX - AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM,
                    p.posY - 1,
                    p.posZ - AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM,
                    p.posX + AquaticAiTuning.BREATH_CONTROLLER_MOVE_FLYING_SECOND_PARAM,
                    p.posY + AquaticAiTuning.BREATH_CONTROLLER_MOVE_FLYING_SECOND_PARAM,
                    p.posZ + AquaticAiTuning.BREATH_CONTROLLER_MOVE_FLYING_SECOND_PARAM));
            boolean occupied = false;
            for (Object value : nearby) {
                if (value == maid || !(value instanceof EntityLivingBase other) || !other.isEntityAlive()) continue;
                if (other.boundingBox.intersectsWith(
                    AxisAlignedBB.getBoundingBox(
                        p.posX - AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FIRST_FACTOR,
                        p.posY,
                        p.posZ - AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FIRST_FACTOR,
                        p.posX + AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FOURTH_FACTOR,
                        p.posY + AquaticAiTuning.BREATH_CONTROLLER_TICK_PARAM,
                        p.posZ + AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FOURTH_FACTOR))
                    || other instanceof EntityMaid following && usesDoor(
                        following.getNavigator()
                            .getPath(),
                        p)) {
                    occupied = true;
                    break;
                }
            }
            if (occupied) continue;
            if (maid.worldObj.isBlockIndirectlyGettingPowered(p.posX, p.posY, p.posZ)
                || maid.worldObj.isBlockIndirectlyGettingPowered(p.posX, p.posY + 1, p.posZ)) entries.remove();
            else {
                setOpen(entry.getValue(), p.posX, p.posY, p.posZ, false);
                // Malisis 开门动画尚未结束时会忽略关门请求，保留记录到下一次 tick
                if (!isOpen(entry.getValue(), p.posX, p.posY, p.posZ)) entries.remove();
            }
        }
    }

    private static boolean usesDoor(PathEntity path, ChunkCoordinates p) {
        if (path == null || path.isFinished()) return false;
        for (int i = Math.max(0, path.getCurrentPathIndex() - 1); i <= path.getCurrentPathIndex(); i++) {
            var point = path.getPathPointFromIndex(i);
            if (point.xCoord == p.posX && point.zCoord == p.posZ
                && Math.abs(point.yCoord - p.posY) <= AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP) return true;
        }
        return false;
    }

    private void climb(PathEntity path) {
        if (!maid.getConfigManager()
            .isActiveClimbing() || !maid.isOnLadder()) {
            stopClimbing();
            return;
        }
        int index = path.getCurrentPathIndex();
        var next = path.getPathPointFromIndex(index);
        int y = MathHelper.floor_double(maid.boundingBox.minY);
        int previousY = index > 0 ? path.getPathPointFromIndex(index - 1).yCoord : y;
        boolean up = next.yCoord > y || next.yCoord > previousY;
        if (next.yCoord == y && index + 1 < path.getCurrentPathLength()) {
            var following = path.getPathPointFromIndex(index + 1);
            if (following.yCoord != y) {
                path.incrementPathIndex();
                next = following;
                up = following.yCoord > y;
            }
        }
        if (Math.abs(next.xCoord + AquaticAiTuning.CENTER_OFFSET_D - maid.posX)
            > AquaticAiTuning.TRAVERSAL_CONTROLLER_CLIMB_FACTOR
            || Math.abs(next.zCoord + AquaticAiTuning.CENTER_OFFSET_D - maid.posZ)
                > AquaticAiTuning.TRAVERSAL_CONTROLLER_CLIMB_FACTOR) {
            stopClimbing();
            return;
        }
        if (next.yCoord == y && previousY == next.yCoord) {
            stopClimbing();
            return;
        }
        climbing = true;
        maid.setSneaking(!up);
        maid.motionX = Math.max(
            AquaticAiTuning.TRAVERSAL_CONTROLLER_MAX_FIRST_FACTOR,
            Math.min(
                AquaticAiTuning.TRAVERSAL_CONTROLLER_MIN_FIRST_FACTOR,
                next.xCoord + AquaticAiTuning.CENTER_OFFSET_D - maid.posX));
        maid.motionZ = Math.max(
            AquaticAiTuning.TRAVERSAL_CONTROLLER_MAX_FIRST_FACTOR,
            Math.min(
                AquaticAiTuning.TRAVERSAL_CONTROLLER_MIN_FIRST_FACTOR,
                next.zCoord + AquaticAiTuning.CENTER_OFFSET_D - maid.posZ));
        maid.motionY = up ? AquaticAiTuning.TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FIRST_FACTOR
            : AquaticAiTuning.TRAVERSAL_CONTROLLER_CLIMB_FACTOR_2;
        maid.fallDistance = AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE;
    }

    private void stopClimbing() {
        if (climbing) maid.setSneaking(false);
        climbing = false;
    }
}
