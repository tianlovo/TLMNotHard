package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.function.BooleanSupplier;

import net.minecraft.block.BlockBed;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;

/** POI缺失平台的床头索引与睡眠占用，原型生活选择器不参与现代任务 */
final class MaidModernLife {

    private final EntityMaid maid;
    private final World world;
    private final BooleanSupplier current;
    private TileEntityMaidBed sleepingBed;
    private int sleepEntries;

    MaidModernLife(EntityMaid maid, BooleanSupplier current) {
        this.maid = maid;
        world = maid.worldObj;
        this.current = current;
    }

    Point findBed(Point center, int radius) {
        if (!(world instanceof WorldServer server)) return null;
        Point best = null;
        double bestDistance = Double.MAX_VALUE;
        int mx = MathHelper.floor_double(maid.posX), my = MathHelper.floor_double(maid.posY),
            mz = MathHelper.floor_double(maid.posZ);
        for (int cx = (center.x - radius) >> 4; cx <= (center.x + radius) >> 4; cx++)
            for (int cz = (center.z - radius) >> 4; cz <= (center.z + radius) >> 4; cz++) {
                var raw = server.theChunkProviderServer.loadedChunkHashMap
                    .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(cx, cz));
                if (!(raw instanceof Chunk chunk)) continue;
                for (Object value : chunk.chunkTileEntityMap.values()) {
                    if (!(value instanceof TileEntityMaidBed bed) || bed.getWorldObj() != world
                        || bed.isInvalid()
                        || !bed.complete()
                        || (world.getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 4) != 0
                        || !bed.available(maid)) continue;
                    double dx = bed.xCoord - center.x, dy = bed.yCoord - center.y, dz = bed.zCoord - center.z;
                    if (dx * dx + dy * dy + dz * dz > (double) radius * radius) continue;
                    dx = bed.xCoord - mx;
                    dy = bed.yCoord - my;
                    dz = bed.zCoord - mz;
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Point(bed.xCoord, bed.yCoord, bed.zCoord);
                    }
                }
            }
        return best;
    }

    void sleepAt(Point point) {
        if (sleepingBed != null || !world.blockExists(point.x, point.y, point.z)) return;
        var raw = world.getTileEntity(point.x, point.y, point.z);
        if (!(raw instanceof TileEntityMaidBed bed) || !bed.complete()
            || (world.getBlockMetadata(point.x, point.y, point.z) & 4) != 0 && !bed.isOwnedBy(maid)) return;
        sleepingBed = bed;
        if (!bed.claim(maid) || sleepingBed != bed || !current.getAsBoolean()) {
            if (sleepingBed == bed) wake();
            else bed.release(maid);
            return;
        }
        maid.getMealController()
            .cancel();
        maid.setPose(MaidPose.SLEEPING, true);
        // 原MaidBedTask在LivingEntity.startSleeping之后覆盖为床头+.8
        maid.setPositionAndUpdate(point.x + .5, point.y + .8, point.z + .5);
        maid.motionX = maid.motionY = maid.motionZ = 0;
        maid.setJumping(false);
        maid.onMaidStartSleeping();
        sleepEntries++;
    }

    void maintain() {
        var bed = sleepingBed;
        if (bed == null) return;
        if (maid.worldObj != world || !maid.hasPose(MaidPose.SLEEPING)
            || maid.ridingEntity != null
            || maid.isSitting()
            || !world.blockExists(bed.xCoord, bed.yCoord, bed.zCoord)
            || world.getTileEntity(bed.xCoord, bed.yCoord, bed.zCoord) != bed
            || !bed.complete()
            || maid.getDistanceSq(bed.xCoord + .5, bed.yCoord + .8, bed.zCoord + .5) > 16
            || !bed.claim(maid)) wake();
    }

    void wake() {
        var bed = sleepingBed;
        if (bed == null) return;
        sleepingBed = null;
        maid.setPose(MaidPose.SLEEPING, false);
        bed.release(maid);
        // 低版本床出口使用实际站立碰撞；外部抱走/传送时不能拉回旧床
        if (maid.worldObj != world || maid.ridingEntity != null
            || maid.riddenByEntity != null
            || maid.getDistanceSq(bed.xCoord + .5, bed.yCoord + .8, bed.zCoord + .5) > 16) return;
        for (int[] offset : BlockBed.field_149981_a) {
            double x = bed.xCoord + offset[0] + .5, y = bed.yCoord, z = bed.zCoord + offset[1] + .5;
            int ix = MathHelper.floor_double(x), iz = MathHelper.floor_double(z);
            if (!world.blockExists(ix, bed.yCoord - 1, iz) || !world.blockExists(ix, bed.yCoord + 2, iz)
                || !World.doesBlockHaveSolidTopSurface(world, ix, bed.yCoord - 1, iz)) continue;
            double radius = maid.width / 2;
            var box = AxisAlignedBB.getBoundingBox(x - radius, y, z - radius, x + radius, y + maid.height, z + radius);
            if (world.getCollidingBoundingBoxes(maid, box)
                .isEmpty() && !world.isAnyLiquid(box)) {
                maid.setPositionAndUpdate(x, y, z);
                return;
            }
        }
    }

    int sleepEntries() {
        return sleepEntries;
    }
}
