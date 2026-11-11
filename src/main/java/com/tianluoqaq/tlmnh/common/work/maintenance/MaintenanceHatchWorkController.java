package com.tianluoqaq.tlmnh.common.work.maintenance;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleInventory;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellItem;
import com.tianluoqaq.tlmnh.common.work.maintenance.spi.MaintenanceHatchCompatProvider;
import com.tianluoqaq.tlmnh.common.work.maintenance.spi.MaintenanceHatchCompatProvider.MaintenanceTarget;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionCode;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionEntry;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionIds;

/**
 * 维护仓工作模式服务端领域控制器：
 * 校验饰品栏维护铃铛与副手格雷科技扳手，扫描已绑定区块内待修普通维护仓（ID == 90），
 * 执行零旋转、零耗材、零扳手耐久与电量损耗的挥手检修；全部正常时慢速巡检各绑定区块
 */
public final class MaintenanceHatchWorkController {

    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner(true);
    private MaintenanceTarget currentTarget;
    private long lastScanWorldTime = MaintenanceWorkTuning.UNSET_TIME;
    private long lastCheckWorldTime = MaintenanceWorkTuning.UNSET_TIME;
    private long lastNavWorldTime = MaintenanceWorkTuning.UNSET_TIME;
    private long repairCooldownUntil = MaintenanceWorkTuning.UNSET_TIME;
    private int patrolIndex;
    private int patrolStayTicks;

    public void reset() {
        currentTarget = null;
        lastScanWorldTime = MaintenanceWorkTuning.UNSET_TIME;
        lastCheckWorldTime = MaintenanceWorkTuning.UNSET_TIME;
        lastNavWorldTime = MaintenanceWorkTuning.UNSET_TIME;
        repairCooldownUntil = MaintenanceWorkTuning.UNSET_TIME;
        patrolStayTicks = MaintenanceWorkTuning.ZERO;
    }

    public boolean hasActiveBellDomain(EntityMaid maid) {
        MaintenanceBellData bell = findEquippedBellData(maid);
        return bell != null && bell.isBound() && maid.worldObj != null
            && bell.dimension() == maid.worldObj.provider.dimensionId;
    }

    public void tick(EntityMaid maid, MaintenanceHatchCompatProvider provider, MaidMissingConditionNotifier notifier) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return;
        MaintenanceHatchCompatProvider activeProvider = provider != null ? provider
            : MaintenanceHatchCompatProvider.NOOP;
        long now = maid.worldObj.getTotalWorldTime();

        List<MissingConditionEntry> missing = collectMissingConditions(maid, activeProvider);
        if (!missing.isEmpty()) {
            currentTarget = null;
            if (now - lastCheckWorldTime >= MaintenanceWorkTuning.CHECK_INTERVAL_TICKS || lastCheckWorldTime < 0L) {
                lastCheckWorldTime = now;
                notifier.reportMissingForMaid(
                    maid,
                    MaidTask.MAINTENANCE_HATCH,
                    MissingConditionIds.MODE_MAINTENANCE,
                    missing);
            }
            return;
        }
        notifier.reportHealthy();

        MaintenanceBellData bell = findEquippedBellData(maid);
        if (bell == null || !bell.isBound()) return;

        if (currentTarget == null || now - lastScanWorldTime >= MaintenanceWorkTuning.SCAN_INTERVAL_TICKS) {
            lastScanWorldTime = now;
            currentTarget = selectBestTarget(
                maid,
                activeProvider.findNeedingMaintenanceHatches(maid.worldObj, bell.chunks()));
        }

        if (currentTarget != null) {
            executeRepairApproach(maid, activeProvider, now);
        } else {
            executeHealthyPatrol(maid, bell.chunks(), now);
        }
    }

    public static List<MissingConditionEntry> collectMissingConditions(EntityMaid maid,
        MaintenanceHatchCompatProvider provider) {
        List<MissingConditionEntry> missing = new ArrayList<>();
        ItemStack bellStack = findEquippedBellStack(maid);
        if (bellStack == null) {
            missing.add(MissingConditionEntry.of(MissingConditionCode.MISSING_MAINTENANCE_BELL));
        } else {
            MaintenanceBellData data = MaintenanceBellData.read(bellStack);
            if (!data.isBound()) {
                missing.add(MissingConditionEntry.of(MissingConditionCode.UNBOUND_MAINTENANCE_BELL));
            } else if (maid.worldObj != null && data.dimension() != maid.worldObj.provider.dimensionId) {
                missing.add(
                    MissingConditionEntry.withArg(
                        MissingConditionCode.WRONG_DIMENSION_MAINTENANCE_BELL,
                        Integer.toString(data.dimension()),
                        false));
            }
        }

        ItemStack offhand = maid.getMaidInventory().getStackInSlot(MaidInventoryLayout.OFF_HAND);
        if (provider == null || !provider.isValidMaintenanceWrench(offhand)) {
            missing.add(MissingConditionEntry.of(MissingConditionCode.MISSING_GT_WRENCH_OFFHAND));
        }
        return missing;
    }

    private void executeRepairApproach(EntityMaid maid, MaintenanceHatchCompatProvider provider, long now) {
        double tx = currentTarget.x + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET;
        double ty = currentTarget.y + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET;
        double tz = currentTarget.z + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET;
        double distSq = maid.getDistanceSq(tx, ty, tz);
        if (distSq <= MaintenanceWorkTuning.REPAIR_REACH_DIST_SQ) {
            maid.getNavigator().clearPathEntity();
            maid.getLookHelper().setLookPosition(
                tx,
                ty,
                tz,
                MaintenanceWorkTuning.LOOK_SPEED,
                maid.getVerticalFaceSpeed());
            if (now >= repairCooldownUntil) {
                maid.swingItem();
                provider.performZeroSideEffectMaintenance(
                    maid.worldObj,
                    currentTarget.x,
                    currentTarget.y,
                    currentTarget.z);
                repairCooldownUntil = now + MaintenanceWorkTuning.REPAIR_COOLDOWN_TICKS;
                currentTarget = null;
                lastScanWorldTime = MaintenanceWorkTuning.UNSET_TIME;
            }
            return;
        }
        requestStepwiseMove(maid, tx, currentTarget.y, tz, now);
    }

    private void executeHealthyPatrol(EntityMaid maid, List<int[]> chunks, long now) {
        if (chunks.isEmpty()) return;
        if (patrolIndex < 0 || patrolIndex >= chunks.size()) {
            patrolIndex = MaintenanceWorkTuning.ZERO;
        }
        int[] pair = chunks.get(patrolIndex);
        int cx = pair[MaintenanceWorkTuning.IDX_X];
        int cz = pair[MaintenanceWorkTuning.IDX_Z];
        IChunkProvider cp = maid.worldObj.getChunkProvider();
        if (cp == null || !cp.chunkExists(cx, cz)) {
            patrolIndex = (patrolIndex + MaintenanceWorkTuning.ONE) % chunks.size();
            return;
        }
        int anchorX = (cx << MaintenanceWorkTuning.BLOCK_TO_CHUNK_SHIFT) + MaintenanceWorkTuning.CHUNK_CENTER_OFFSET;
        int anchorZ = (cz << MaintenanceWorkTuning.BLOCK_TO_CHUNK_SHIFT) + MaintenanceWorkTuning.CHUNK_CENTER_OFFSET;
        int anchorY = findSafeStandingY(maid.worldObj, anchorX, MathHelper.floor_double(maid.posY), anchorZ);
        if (anchorY < 0) {
            patrolIndex = (patrolIndex + MaintenanceWorkTuning.ONE) % chunks.size();
            return;
        }
        double tx = anchorX + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET;
        double tz = anchorZ + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET;
        if (maid.getDistanceSq(tx, anchorY, tz) <= MaintenanceWorkTuning.PATROL_ARRIVE_DIST_SQ) {
            patrolStayTicks++;
            if (patrolStayTicks >= MaintenanceWorkTuning.PATROL_STAY_TICKS) {
                patrolStayTicks = MaintenanceWorkTuning.ZERO;
                patrolIndex = (patrolIndex + MaintenanceWorkTuning.ONE) % chunks.size();
            }
            return;
        }
        requestStepwiseMove(maid, tx, anchorY, tz, now);
    }

    private void requestStepwiseMove(EntityMaid maid, double targetX, double targetY, double targetZ, long now) {
        if (now - lastNavWorldTime < MaintenanceWorkTuning.NAV_REFRESH_TICKS
            && maid.getNavigator().getPath() != null
            && !maid.getNavigator().getPath().isFinished()) {
            return;
        }
        lastNavWorldTime = now;
        double dx = targetX - maid.posX;
        double dz = targetZ - maid.posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double stepX = targetX;
        double stepZ = targetZ;
        double stepY = targetY;
        if (horiz > MaintenanceWorkTuning.STEP_MAX_DISTANCE) {
            double ratio = MaintenanceWorkTuning.STEP_MAX_DISTANCE / horiz;
            stepX = maid.posX + dx * ratio;
            stepZ = maid.posZ + dz * ratio;
            int safeY = findSafeStandingY(
                maid.worldObj,
                MathHelper.floor_double(stepX),
                MathHelper.floor_double(maid.posY),
                MathHelper.floor_double(stepZ));
            if (safeY >= 0) {
                stepY = safeY;
            }
        }
        planner.request(maid, stepX, stepY, stepZ, MaintenanceWorkTuning.WALK_SPEED);
    }

    private static int findSafeStandingY(World world, int x, int refY, int z) {
        int range = MaintenanceWorkTuning.ANCHOR_SEARCH_VERTICAL_RANGE;
        for (int dy = 0; dy <= range; dy++) {
            int upY = refY + dy;
            if (isStandable(world, x, upY, z)) return upY;
            int downY = refY - dy;
            if (dy > 0 && downY > 0 && isStandable(world, x, downY, z)) return downY;
        }
        return MaintenanceWorkTuning.UNSET_Y;
    }

    private static boolean isStandable(World world, int x, int y, int z) {
        if (y <= MaintenanceWorkTuning.ONE) return false;
        Block below = world.getBlock(x, y - MaintenanceWorkTuning.ONE, z);
        if (below == null || !below.getMaterial().isSolid()) return false;
        Block feet = world.getBlock(x, y, z);
        Block head = world.getBlock(x, y + MaintenanceWorkTuning.ONE, z);
        return !feet.getMaterial().blocksMovement() && !head.getMaterial().blocksMovement();
    }

    private static MaintenanceTarget selectBestTarget(EntityMaid maid, List<MaintenanceTarget> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        MaintenanceTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (MaintenanceTarget t : candidates) {
            double d = maid.getDistanceSq(
                t.x + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET,
                t.y + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET,
                t.z + MaintenanceWorkTuning.BLOCK_CENTER_OFFSET);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = t;
            }
        }
        return best;
    }

    public static ItemStack findEquippedBellStack(EntityMaid maid) {
        if (maid == null) return null;
        MaidBaubleInventory baubles = maid.getMaidBaubleInventory();
        int unlocked = MaidBaubleLayout.unlockedSlots(maid.getFavorabilityManager().getLevel());
        int limit = Math.min(unlocked, baubles.getSizeInventory());
        for (int i = 0; i < limit; i++) {
            ItemStack s = baubles.getStackInSlot(i);
            if (s != null && s.stackSize > 0 && s.getItem() instanceof MaintenanceBellItem) {
                return s;
            }
        }
        return null;
    }

    private static MaintenanceBellData findEquippedBellData(EntityMaid maid) {
        ItemStack stack = findEquippedBellStack(maid);
        return stack == null ? null : MaintenanceBellData.read(stack);
    }
}
