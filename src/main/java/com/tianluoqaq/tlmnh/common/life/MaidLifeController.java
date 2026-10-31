package com.tianluoqaq.tlmnh.common.life;

import java.util.function.Supplier;

import net.minecraft.block.BlockBed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidOwnerSensor;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.TerminalOutcome;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

/** 由唯一协调器仲裁的实际生活动作，不额外注册移动任务 */
public final class MaidLifeController {

    public enum Status {
        IDLE,
        BLOCKED,
        NO_OWNER,
        COOLDOWN,
        APPROACH_BED,
        SLEEPING,
        APPROACH_SNACK,
        TARGET_LOST,
        PATH_FAILED,
        ACTION_FAILED,
        CANCELLED
    }

    private enum Action {
        BED,
        STEAL,
        PLACE
    }

    private static final int SCAN_INTERVAL = 20, SCAN_BUDGET = 128, MAX_ACTION_TICKS = 200;
    private static final String REWARD_AT = "TLMNHStealEdibleRewardAt";
    private final EntityMaid maid;
    private final Runnable claimControl;
    private final Supplier<EntityPlayer> ownerSource;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private TileEntity target;
    private Action action;
    private MaidTask selectedTask;
    private PathEntity ownedPath;
    private Vec3 approachPoint;
    private EntityPlayer owner;
    private Status status = Status.IDLE;
    private PathResult lastPathResult;
    private long tick, nextOwnerScan, nextScan, nextSnack, nextPath, selectedAt;
    private int scanCursor, failures, scans, pathRequests, bites, placements, sleepEntries;
    private boolean sleeping, tryPlaceNext;
    private double wakeX, wakeY, wakeZ;

    public MaidLifeController(EntityMaid maid, Runnable claimControl, Supplier<EntityPlayer> ownerSource) {
        if (maid == null || claimControl == null || ownerSource == null)
            throw new IllegalArgumentException("Life action requires its actor, arbiter and owner observation");
        this.maid = maid;
        this.claimControl = claimControl;
        this.ownerSource = ownerSource;
    }

    /** 即使处于 SLEEPING，也须由协调器每 tick 调用以处理醒来和失效清理 */
    public boolean tick(boolean permitted) {
        tick++;
        if (!permitted || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || !maid.isTamed()
            || maid.isSitting()
            || maid.ridingEntity != null
            || maid.riddenByEntity != null
            || maid.getLeashed()
            || maid.getAttackTarget() != null
            || maid.hurtTime > 0
            || maid.isInWater()
            || maid.isBurning()
            || maid.hasPose(MaidPose.SLEEPING) && !sleeping) {
            stop(Status.BLOCKED);
            return false;
        }
        if (tick >= nextOwnerScan) {
            nextOwnerScan = tick + SCAN_INTERVAL;
            owner = ownerSource.get();
        }
        if (!ownerValid(owner) || maid.getDistanceSqToEntity(owner) > 144) {
            stop(Status.NO_OWNER);
            return false;
        }
        boolean rest = MaidLifeRules.isRest(maid.getSchedule(), maid.worldObj.getWorldTime());
        if (target != null && (maid.getTask() != selectedTask || rest != (action == Action.BED)))
            stop(Status.CANCELLED);
        if (target == null) {
            if (tick < nextScan || !rest && tick < nextSnack) {
                status = Status.COOLDOWN;
                return false;
            }
            nextScan = tick + SCAN_INTERVAL;
            if (!select(rest)) {
                status = Status.IDLE;
                return false;
            }
        }
        if (!validTarget()) {
            stop(Status.TARGET_LOST);
            return false;
        }
        if (sleeping) {
            var bed = (TileEntityMaidBed) target;
            if (!bed.claim(maid)) {
                stop(Status.TARGET_LOST);
                return false;
            }
            // 不推进动画钟；实体仍有原生重力和碰撞，只固定已占用床上的休息位置
            maid.setPosition(bed.xCoord + 0.5, bed.yCoord + 0.5625, bed.zCoord + 0.5);
            maid.motionX = maid.motionY = maid.motionZ = 0;
            maid.setJumping(false);
            status = Status.SLEEPING;
            return true;
        }
        if (tick - selectedAt > MAX_ACTION_TICKS) {
            stop(Status.PATH_FAILED, TerminalOutcome.TIMED_OUT);
            return false;
        }
        double targetY = target.yCoord + (action == Action.BED ? 0.5 : 1.5);
        double distance = maid.getDistanceSq(target.xCoord + 0.5, targetY, target.zCoord + 0.5);
        if (distance <= 4 && visible(target.xCoord + 0.5, targetY, target.zCoord + 0.5)) return useTarget();
        status = action == Action.BED ? Status.APPROACH_BED : Status.APPROACH_SNACK;
        if (tick >= nextPath) {
            nextPath = tick + 20;
            Vec3 stand = standBeside(target);
            pathRequests++;
            lastPathResult = stand == null ? PathResult.UNREACHABLE
                : planner.request(maid, stand.xCoord, stand.yCoord, stand.zCoord, 0.6D);
            if (lastPathResult == PathResult.STARTED) {
                ownedPath = maid.getNavigator()
                    .getPath();
                approachPoint = stand;
                failures = 0;
            } else if (++failures >= 3) {
                stop(Status.PATH_FAILED);
                return false;
            }
        }
        // 1.7导航在距终点一个实体宽度内就结束；邻格路径结束不一定进入柜上蛋糕的原2格交互球
        // 只补完本动作已走到的安全邻格，继续使用原生移动与碰撞，不扩大交互半径或传送
        if (action != Action.BED && approachPoint != null
            && ownedPath != null
            && maid.getNavigator()
                .getPath() == ownedPath
            && maid.getNavigator()
                .noPath()
            && maid.getDistanceSq(approachPoint.xCoord, approachPoint.yCoord, approachPoint.zCoord)
                <= maid.width * maid.width
            && safeStand(approachPoint.xCoord, approachPoint.yCoord, approachPoint.zCoord))
            maid.getMoveHelper()
                .setMoveTo(approachPoint.xCoord, approachPoint.yCoord, approachPoint.zCoord, 0.6D);
        if (action == Action.BED && !((TileEntityMaidBed) target).claim(maid)) {
            stop(Status.TARGET_LOST);
            return false;
        }
        maid.getLookHelper()
            .setLookPosition(target.xCoord + 0.5, targetY, target.zCoord + 0.5, 20, maid.getVerticalFaceSpeed());
        return true;
    }

    private boolean ownerValid(EntityPlayer player) {
        return MaidOwnerSensor.isValid(maid, player);
    }

    private boolean select(boolean rest) {
        var telemetry = telemetry();
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : 0;
        var tiles = maid.worldObj.loadedTileEntityList;
        int size = tiles.size();
        if (size == 0) {
            telemetry.scan("life_tiles", 0, 0, "loaded_tile_list_global");
            if (recording) telemetry.work("life_tiles", WorkComponent.SENSOR, System.nanoTime() - started);
            return false;
        }
        TileEntity best = null;
        double closest = Double.MAX_VALUE;
        boolean place = !rest && tryPlaceNext && cakeSlot() >= 0;
        scans++;
        for (int count = 0; count < Math.min(size, SCAN_BUDGET); count++) {
            TileEntity candidate = (TileEntity) tiles.get(Math.floorMod(scanCursor++, size));
            if (candidate == null || candidate.isInvalid() || candidate.getWorldObj() != maid.worldObj) continue;
            double dx = candidate.xCoord + 0.5 - maid.posX, dz = candidate.zCoord + 0.5 - maid.posZ;
            if (Math.abs(candidate.yCoord - maid.posY) > 3 || dx * dx + dz * dz > (rest ? 144 : 25)) continue;
            boolean usable = rest ? candidate instanceof TileEntityMaidBed bed && bed.available(maid) && bedClear(bed)
                : candidate instanceof TileEntitySnackCabinet && snackValid(candidate, place);
            if (recording)
                telemetry.candidate("life_tile", tileId(candidate), usable, "spatial_filter_passed;rest=" + rest);
            double distance = maid
                .getDistanceSq(candidate.xCoord + 0.5, candidate.yCoord + 0.5, candidate.zCoord + 0.5);
            if (usable && distance < closest) {
                best = candidate;
                closest = distance;
            }
        }
        telemetry.scan(
            "life_tiles",
            size,
            Math.min(size, SCAN_BUDGET),
            "loaded_tile_list_global;candidate_details_only_after_spatial_filter");
        if (recording) telemetry.work("life_tiles", WorkComponent.SENSOR, System.nanoTime() - started);
        if (!rest) tryPlaceNext = !place;
        if (best == null) return false;
        if (rest && !((TileEntityMaidBed) best).claim(maid)) return false;
        claimControl.run();
        target = best;
        action = rest ? Action.BED : place ? Action.PLACE : Action.STEAL;
        if (recording) telemetry.state("life_selected", tileId(best), action.name());
        selectedTask = maid.getTask();
        selectedAt = tick;
        nextPath = tick;
        failures = 0;
        if (!rest) nextSnack = tick + MaidLifeRules.SNACK_INTERVAL;
        return true;
    }

    private boolean validTarget() {
        if (target == null || target.isInvalid()
            || target.getWorldObj() != maid.worldObj
            || !maid.worldObj.blockExists(target.xCoord, target.yCoord, target.zCoord)
            || maid.worldObj.getTileEntity(target.xCoord, target.yCoord, target.zCoord) != target) return false;
        return action == Action.BED
            ? ((TileEntityMaidBed) target).complete() && ((TileEntityMaidBed) target).isOwnedBy(maid)
                && bedClear((TileEntityMaidBed) target)
            : snackValid(target, action == Action.PLACE);
    }

    private boolean snackValid(TileEntity cabinet, boolean place) {
        int x = cabinet.xCoord, y = cabinet.yCoord + 1, z = cabinet.zCoord;
        if (!maid.worldObj.blockExists(x, y, z) || !(maid.worldObj.getBlock(x, y - 1, z) instanceof BlockSnackCabinet))
            return false;
        return place
            ? cakeSlot() >= 0 && maid.worldObj.isAirBlock(x, y, z)
                && !(MathHelper.floor_double(maid.posX) == x && MathHelper.floor_double(maid.posY) == y
                    && MathHelper.floor_double(maid.posZ) == z)
                && Blocks.cake.canPlaceBlockAt(maid.worldObj, x, y, z)
            : maid.worldObj.getBlock(x, y, z) == Blocks.cake && maid.worldObj.getBlockMetadata(x, y, z) <= 5;
    }

    private boolean bedClear(TileEntityMaidBed bed) {
        int direction = maid.worldObj.getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 3;
        for (int part = 0; part < 2; part++) {
            int x = bed.xCoord - BlockBed.field_149981_a[direction][0] * part;
            int z = bed.zCoord - BlockBed.field_149981_a[direction][1] * part;
            for (int dy = 1; dy <= 2; dy++) if (!maid.worldObj.blockExists(x, bed.yCoord + dy, z)
                || !maid.worldObj.isAirBlock(x, bed.yCoord + dy, z)) return false;
        }
        return true;
    }

    private boolean useTarget() {
        stopNavigation();
        if (action == Action.BED) {
            var bed = (TileEntityMaidBed) target;
            if (!bed.claim(maid)) {
                stop(Status.TARGET_LOST);
                return false;
            }
            wakeX = maid.posX;
            wakeY = maid.posY;
            wakeZ = maid.posZ;
            sleeping = true;
            maid.setPose(MaidPose.SLEEPING, true);
            maid.setPositionAndUpdate(bed.xCoord + 0.5, bed.yCoord + 0.5625, bed.zCoord + 0.5);
            maid.motionX = maid.motionY = maid.motionZ = 0;
            sleepEntries++;
            telemetry().terminal(
                "sleep_entry",
                tileId(bed),
                "bed_claimed_pose_started;not_whole_sleep_cycle",
                TerminalOutcome.COMPLETED,
                true);
            status = Status.SLEEPING;
            return true;
        }
        int x = target.xCoord, y = target.yCoord + 1, z = target.zCoord;
        boolean success = false;
        if (action == Action.STEAL) {
            int next = MaidLifeRules.nextCakeBites(maid.worldObj.getBlockMetadata(x, y, z));
            success = next < 0 ? maid.worldObj.setBlockToAir(x, y, z)
                : maid.worldObj.setBlockMetadataWithNotify(x, y, z, next, 3);
            if (success) {
                bites++;
                maid.playSound("random.eat", 1.0F, 1.0F);
                long time = maid.worldObj.getTotalWorldTime();
                if (time >= maid.getEntityData()
                    .getLong(REWARD_AT)) {
                    if (maid.getFavorability() < Integer.MAX_VALUE) maid.setFavorability(maid.getFavorability() + 1);
                    maid.getEntityData()
                        .setLong(REWARD_AT, time + MaidLifeRules.REWARD_INTERVAL);
                }
            }
        } else success = placeCake(x, y, z);
        if (success) maid.swingItem();
        stop(success ? Status.COOLDOWN : Status.ACTION_FAILED);
        return success;
    }

    private boolean placeCake(int x, int y, int z) {
        int slot = cakeSlot();
        if (slot < 0 || !(maid.worldObj instanceof WorldServer world)) return false;
        var original = maid.getMaidInventory()
            .getStackInSlot(slot);
        var expectedInventory = original.copy();
        ItemStack one = original.copy();
        one.stackSize = 1;
        var fake = FakePlayerFactory.get(world, new GameProfile(maid.getUniqueID(), "[TLMNH Maid]"));
        int selected = fake.inventory.currentItem;
        var previous = fake.inventory.getStackInSlot(selected);
        double px = fake.posX, py = fake.posY, pz = fake.posZ;
        boolean creative = fake.capabilities.isCreativeMode;
        try {
            fake.setPosition(maid.posX, maid.posY, maid.posZ);
            fake.capabilities.isCreativeMode = false;
            fake.inventory.setInventorySlotContents(selected, one);
            // Forge 原生放置入口包含可取消的 PlaceEvent；成功后才扣真正背包中的一个
            boolean placed = one.tryPlaceItemIntoWorld(fake, world, x, y - 1, z, 1, 0.5F, 1, 0.5F);
            if (!placed || one.stackSize != 0 || world.getBlock(x, y, z) != Blocks.cake) return false;
            if (maid.getMaidInventory()
                .getStackInSlot(slot) != original || !ItemStack.areItemStacksEqual(original, expectedInventory)) {
                // 同步 Forge 回调可能改背包；不能扣别的物品或留下未付出的蛋糕
                world.setBlockToAir(x, y, z);
                return false;
            }
            maid.getMaidInventory()
                .decrStackSize(slot, 1);
            placements++;
            return true;
        } finally {
            fake.inventory.currentItem = selected;
            fake.inventory.setInventorySlotContents(selected, previous);
            fake.capabilities.isCreativeMode = creative;
            fake.setPosition(px, py, pz);
        }
    }

    private int cakeSlot() {
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) {
            var stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.cake) return slot;
        }
        return -1;
    }

    private boolean visible(double x, double y, double z) {
        // 原生 ray trace 会访问区块，先检查整条短线覆盖的区块矩形
        var chunks = maid.worldObj.getChunkProvider();
        int minX = MathHelper.floor_double(Math.min(x, maid.posX)) >> 4,
            maxX = MathHelper.floor_double(Math.max(x, maid.posX)) >> 4;
        int minZ = MathHelper.floor_double(Math.min(z, maid.posZ)) >> 4,
            maxZ = MathHelper.floor_double(Math.max(z, maid.posZ)) >> 4;
        for (int cx = minX; cx <= maxX; cx++)
            for (int cz = minZ; cz <= maxZ; cz++) if (!chunks.chunkExists(cx, cz)) return false;
        var hit = maid.worldObj.rayTraceBlocks(
            Vec3.createVectorHelper(maid.posX, maid.posY + maid.getEyeHeight(), maid.posZ),
            Vec3.createVectorHelper(x, y, z));
        return hit == null || hit.blockX == target.xCoord && hit.blockZ == target.zCoord
            && hit.blockY == target.yCoord + (action == Action.BED ? 0 : 1);
    }

    private Vec3 standBeside(TileEntity tile) {
        Vec3 best = null;
        double distance = Double.MAX_VALUE;
        for (int[] offset : BlockBed.field_149981_a) {
            double x = tile.xCoord + offset[0] + 0.5, z = tile.zCoord + offset[1] + 0.5;
            if (!safeStand(x, tile.yCoord, z)) continue;
            double candidate = maid.getDistanceSq(x, tile.yCoord, z);
            if (candidate < distance) {
                distance = candidate;
                best = Vec3.createVectorHelper(x, tile.yCoord, z);
            }
        }
        return best;
    }

    private boolean safeStand(double x, double y, double z) {
        int ix = MathHelper.floor_double(x), iy = MathHelper.floor_double(y), iz = MathHelper.floor_double(z);
        if (!maid.worldObj.blockExists(ix, iy - 1, iz) || !maid.worldObj.blockExists(ix, iy + 2, iz)
            || !net.minecraft.world.World.doesBlockHaveSolidTopSurface(maid.worldObj, ix, iy - 1, iz)) return false;
        double radius = maid.width / 2;
        return maid.worldObj
            .getCollidingBoundingBoxes(
                maid,
                AxisAlignedBB.getBoundingBox(x - radius, y, z - radius, x + radius, y + maid.height, z + radius))
            .isEmpty()
            && !maid.worldObj.getBlock(ix, iy, iz)
                .getMaterial()
                .isLiquid();
    }

    public void cancel() {
        owner = null;
        nextOwnerScan = 0;
        stop(Status.CANCELLED);
    }

    private void stop(Status reason) {
        stop(reason, null);
    }

    private void stop(Status reason, TerminalOutcome explicitOutcome) {
        if (target != null && action != null && telemetry().isEnabled()) {
            boolean confirmed = reason == Status.COOLDOWN && action != Action.BED;
            var outcome = explicitOutcome != null ? explicitOutcome
                : confirmed ? TerminalOutcome.COMPLETED
                    : reason == Status.PATH_FAILED || reason == Status.ACTION_FAILED || reason == Status.TARGET_LOST
                        ? TerminalOutcome.FAILED
                        : TerminalOutcome.CANCELLED;
            telemetry().terminal(
                "life/" + action.name()
                    .toLowerCase(java.util.Locale.ROOT),
                tileId(target),
                "stop_reason=" + reason,
                outcome,
                confirmed);
        }
        stopNavigation();
        if (sleeping) {
            maid.setPose(MaidPose.SLEEPING, false);
            // 被抱走、跨世界或外部传送后只清本动作，不能把实体拉回旧床坐标
            if (target != null && target.getWorldObj() == maid.worldObj
                && maid.ridingEntity == null
                && maid.riddenByEntity == null
                && maid.getDistanceSq(target.xCoord + 0.5, target.yCoord + 0.5, target.zCoord + 0.5) <= 16) {
                Vec3 safe = standBeside(target);
                if (safe != null) maid.setPositionAndUpdate(safe.xCoord, safe.yCoord, safe.zCoord);
                else if (safeStand(wakeX, wakeY, wakeZ)) maid.setPositionAndUpdate(wakeX, wakeY, wakeZ);
            }
            sleeping = false;
        }
        if (target instanceof TileEntityMaidBed bed) bed.release(maid);
        if (target != null) nextScan = Math.max(nextScan, tick + SCAN_INTERVAL);
        target = null;
        action = null;
        selectedTask = null;
        failures = 0;
        status = reason;
    }

    private MaidTelemetryChannel telemetry() {
        return maid.getBrainCoordinator()
            .getTelemetry();
    }

    private static String tileId(TileEntity tile) {
        return (tile.getWorldObj() == null ? "unknown" : Integer.toString(tile.getWorldObj().provider.dimensionId))
            + ":"
            + tile.xCoord
            + ","
            + tile.yCoord
            + ","
            + tile.zCoord;
    }

    private void stopNavigation() {
        if (ownedPath != null && maid.getNavigator()
            .getPath() == ownedPath) maid.getNavigator()
                .clearPathEntity();
        ownedPath = null;
        approachPoint = null;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public int getScans() {
        return scans;
    }

    public int getPathRequests() {
        return pathRequests;
    }

    public PathResult getLastPathResult() {
        return lastPathResult;
    }

    public int getBites() {
        return bites;
    }

    public int getPlacements() {
        return placements;
    }

    public int getSleepEntries() {
        return sleepEntries;
    }
}
