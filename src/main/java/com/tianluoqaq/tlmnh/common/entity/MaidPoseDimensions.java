package com.tianluoqaq.tlmnh.common.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.mixin.MixinEntityPose;

/** 现代 refreshDimensions 的增长搜索：候选中心体积减去按新尺寸膨胀的方块碰撞 */
public final class MaidPoseDimensions {

    private MaidPoseDimensions() {}

    public static void refresh(EntityMaid maid, MaidEntityPose pose) {
        maid.refreshPoseEyeHeight(pose);
        float oldWidth = maid.width, oldHeight = maid.height;
        if (oldWidth == pose.width && oldHeight == pose.height) return;
        double feet = maid.boundingBox.minY;
        maid.width = pose.width;
        maid.height = pose.height;
        maid.boundingBox.setBounds(
            maid.posX - pose.width / 2.0,
            feet,
            maid.posZ - pose.width / 2.0,
            maid.posX + pose.width / 2.0,
            feet + pose.height,
            maid.posZ + pose.width / 2.0);
        if (maid.worldObj == null || maid.worldObj.isRemote
            || maid.noClip
            || ((MixinEntityPose) maid).tlmnh$isFirstUpdate()
            || pose.width <= oldWidth && pose.height <= oldHeight) return;
        double dx = Math.max(0F, pose.width - oldWidth) + 1.0e-6;
        double dy = Math.max(0F, pose.height - oldHeight) + 1.0e-6;
        double cy = feet + oldHeight / 2.0;
        var region = AxisAlignedBB.getBoundingBox(
            maid.posX - dx / 2,
            cy - dy / 2,
            maid.posZ - dx / 2,
            maid.posX + dx / 2,
            cy + dy / 2,
            maid.posZ + dx / 2);
        var query = region.copy()
            .expand(pose.width, pose.height, pose.width);
        var blocks = new ArrayList<AxisAlignedBB>();
        for (int x = MathHelper.floor_double(query.minX); x <= MathHelper.floor_double(query.maxX); x++)
            for (int z = MathHelper.floor_double(query.minZ); z <= MathHelper.floor_double(query.maxZ); z++)
                for (int y = MathHelper.floor_double(query.minY); y <= MathHelper.floor_double(query.maxY); y++) {
                    if (!maid.worldObj.blockExists(x, y, z)) continue;
                    maid.worldObj.getBlock(x, y, z)
                        .addCollisionBoxesToList(maid.worldObj, x, y, z, query, blocks, maid);
                }
        var free = closestCenter(
            region,
            Vec3.createVectorHelper(maid.posX, cy, maid.posZ),
            blocks,
            pose.width,
            pose.height);
        if (free != null)
            maid.setPosition(free.xCoord, free.yCoord - pose.height / 2.0 + maid.yOffset - maid.ySize, free.zCoord);
    }

    public static Vec3 closestCenter(AxisAlignedBB region, Vec3 origin, List<AxisAlignedBB> blocks, float width,
        float height) {
        List<AxisAlignedBB> pieces = new ArrayList<>();
        pieces.add(region);
        for (var block : blocks) {
            var inflated = block.copy()
                .expand(width / 2.0, height / 2.0, width / 2.0);
            var next = new ArrayList<AxisAlignedBB>();
            for (var piece : pieces) subtract(piece, inflated, next);
            pieces = next;
        }
        Vec3 best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var piece : pieces) {
            var point = Vec3.createVectorHelper(
                clamp(origin.xCoord, piece.minX, piece.maxX),
                clamp(origin.yCoord, piece.minY, piece.maxY),
                clamp(origin.zCoord, piece.minZ, piece.maxZ));
            double candidate = point.squareDistanceTo(origin);
            if (candidate < distance) {
                distance = candidate;
                best = point;
            }
        }
        return best;
    }

    private static void subtract(AxisAlignedBB box, AxisAlignedBB cut, List<AxisAlignedBB> out) {
        if (!box.intersectsWith(cut)) {
            out.add(box);
            return;
        }
        double x0 = Math.max(box.minX, cut.minX), x1 = Math.min(box.maxX, cut.maxX);
        double y0 = Math.max(box.minY, cut.minY), y1 = Math.min(box.maxY, cut.maxY);
        double z0 = Math.max(box.minZ, cut.minZ), z1 = Math.min(box.maxZ, cut.maxZ);
        add(out, box.minX, box.minY, box.minZ, x0, box.maxY, box.maxZ);
        add(out, x1, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        add(out, x0, box.minY, box.minZ, x1, y0, box.maxZ);
        add(out, x0, y1, box.minZ, x1, box.maxY, box.maxZ);
        add(out, x0, y0, box.minZ, x1, y1, z0);
        add(out, x0, y0, z1, x1, y1, box.maxZ);
    }

    private static void add(List<AxisAlignedBB> out, double x0, double y0, double z0, double x1, double y1, double z1) {
        if (x1 > x0 && y1 > y0 && z1 > z0) out.add(AxisAlignedBB.getBoundingBox(x0, y0, z0, x1, y1, z1));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
