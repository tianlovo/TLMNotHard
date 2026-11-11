package com.tianluoqaq.tlmnh.common.work.breeding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleInventory;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellItem;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingPlotPlanner.CellAssignment;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingPlotPlanner.CellRole;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.CellFoundationCheck;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.CropStickCellState;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.PlanStatus;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.PlotValidationResult;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.SeedInspection;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.StickKind;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.TargetBreedingPlan;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionCode;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionEntry;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionIds;

/**
 * CropsNH 育种工作模式服务端领域控制器：
 * 生产循环 100% 遵循自然作物生长节奏（绝不调用测试专用的生长加速钩子），
 * 执行全量缺件检测播报、物理挖掘等级换土壤/底矿、替用种子识别、保活收种除草与三维属性爬坡提纯
 */
public final class CropsNhBreedingWorkController {

    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner(true);
    private long lastCheckWorldTime = BreedingWorkTuning.UNSET_TIME;
    private long lastStepWorldTime = BreedingWorkTuning.UNSET_TIME;
    private long lastNavWorldTime = BreedingWorkTuning.UNSET_TIME;

    public void reset() {
        lastCheckWorldTime = BreedingWorkTuning.UNSET_TIME;
        lastStepWorldTime = BreedingWorkTuning.UNSET_TIME;
        lastNavWorldTime = BreedingWorkTuning.UNSET_TIME;
    }

    public boolean hasActiveBellDomain(EntityMaid maid) {
        BreedingBellData bell = findEquippedBellData(maid);
        return bell != null && bell.isBound() && maid.worldObj != null
            && bell.dimension() == maid.worldObj.provider.dimensionId;
    }

    public void tick(EntityMaid maid, BreedingTaskConfigData config, CropsNhBreedingCompatProvider provider,
        MaidMissingConditionNotifier notifier) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote || config == null) return;
        CropsNhBreedingCompatProvider compat = provider != null ? provider : CropsNhBreedingCompatProvider.NOOP;
        long now = maid.worldObj.getTotalWorldTime();

        EvaluationResult eval = evaluatePlotAndMissing(maid, config, compat);
        if (!eval.missing.isEmpty()) {
            if (now - lastCheckWorldTime >= BreedingWorkTuning.CHECK_INTERVAL_TICKS || lastCheckWorldTime < 0L) {
                lastCheckWorldTime = now;
                notifier.reportMissingForMaid(
                    maid,
                    MaidTask.CROPSNH_BREEDING,
                    MissingConditionIds.MODE_BREEDING,
                    eval.missing);
            }
            return;
        }
        notifier.reportHealthy();

        BreedingBellData bell = eval.bell;
        double cx = bell.centerX() + BreedingWorkTuning.BLOCK_CENTER_OFFSET;
        double cy = bell.cropY();
        double cz = bell.centerZ() + BreedingWorkTuning.BLOCK_CENTER_OFFSET;
        if (maid.getDistanceSq(cx, cy, cz) > BreedingWorkTuning.WORK_REACH_DIST_SQ) {
            if (now - lastNavWorldTime >= BreedingWorkTuning.NAV_REFRESH_TICKS
                || maid.getNavigator().getPath() == null
                || maid.getNavigator().getPath().isFinished()) {
                lastNavWorldTime = now;
                planner.request(
                    maid,
                    cx + BreedingWorkTuning.STAND_OFFSET_BLOCKS,
                    cy,
                    cz,
                    BreedingWorkTuning.WALK_SPEED);
            }
            return;
        }

        if (now - lastStepWorldTime < BreedingWorkTuning.WORK_STEP_INTERVAL_TICKS) {
            return;
        }
        lastStepWorldTime = now;
        executeSingleNaturalStep(maid, bell, config, compat, eval.plan, eval.cellAssignments);
    }

    private void executeSingleNaturalStep(EntityMaid maid, BreedingBellData bell, BreedingTaskConfigData config,
        CropsNhBreedingCompatProvider compat, TargetBreedingPlan targetPlan, List<CellAssignment> assignments) {
        World world = maid.worldObj;
        int cropY = bell.cropY();
        int soilY = bell.soilY();
        int subSoilY = bell.subSoilY();
        ItemStack spadeStack = findFirstSpadeStack(maid, compat);
        if (spadeStack == null) return;

        // 1. 优先清理全田杂草与治愈病株
        for (CellAssignment cell : assignments) {
            int x = bell.centerX() + cell.dx;
            int z = bell.centerZ() + cell.dz;
            CropStickCellState state = compat.inspectCropStickCell(world, x, cropY, z);
            if (state.kind == StickKind.WEED) {
                aimAndSwing(maid, x, cropY, z);
                storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                return;
            }
        }

        // 2. 检查每一格土壤与底矿地基；不满足时执行真实挖掘等级破坏与库存方块替换
        String targetGoverningCrop = config.isMutationPoolMode() ? "" : config.targetId();
        for (CellAssignment cell : assignments) {
            if (cell.role == CellRole.LEAVE_EMPTY) continue;
            int x = bell.centerX() + cell.dx;
            int z = bell.centerZ() + cell.dz;
            String cellGoverning = cell.role == CellRole.PARENT_SINGLE ? cell.desiredParentCropId : targetGoverningCrop;
            List<String> cellParents = cell.role == CellRole.CROSS_DOUBLE ? targetPlan.parentCropIds
                : Collections.emptyList();
            CellFoundationCheck fcheck = compat.checkCellFoundation(world, x, soilY, z, cellGoverning, cellParents);
            if (!fcheck.subSoilOk || !fcheck.soilOk) {
                aimAndSwing(maid, x, soilY, z);
                replaceCellFoundation(maid, world, x, soilY, subSoilY, z, cellGoverning, cellParents, fcheck, compat, spadeStack);
                return;
            }
        }

        // 3. 处理十字架位与空余位：收获已变异出的新植株、淘汰非目标杂交种、铺设双层十字架
        for (CellAssignment cell : assignments) {
            int x = bell.centerX() + cell.dx;
            int z = bell.centerZ() + cell.dz;
            CropStickCellState state = compat.inspectCropStickCell(world, x, cropY, z);
            if (cell.role == CellRole.LEAVE_EMPTY) {
                if (state.kind != StickKind.EMPTY_SPACE) {
                    aimAndSwing(maid, x, cropY, z);
                    storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, true));
                    return;
                }
                continue;
            }
            if (cell.role == CellRole.CROSS_DOUBLE) {
                if (state.kind == StickKind.PLANTED) {
                    boolean matchesTarget = matchesConfiguredTarget(config, targetPlan, state.cropId);
                    if (!matchesTarget) {
                        aimAndSwing(maid, x, cropY, z);
                        storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                        ensureCrossStick(maid, world, x, cropY, z, compat);
                        return;
                    }
                    if (state.canHarvest || state.mature) {
                        aimAndSwing(maid, x, cropY, z);
                        storeDrops(maid, compat.spadeHarvestKeepAlive(world, x, cropY, z, spadeStack));
                        storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                        ensureCrossStick(maid, world, x, cropY, z, compat);
                        return;
                    }
                } else if (state.kind == StickKind.EMPTY_SPACE || state.kind == StickKind.SINGLE_EMPTY) {
                    if (ensureCrossStick(maid, world, x, cropY, z, compat)) {
                        aimAndSwing(maid, x, cropY, z);
                        return;
                    }
                }
            }
        }

        // 4. 处理母本单架位：收获成熟优种、拔除非期望旧种、用更高综合分种子迭代替换最弱母本、种植空母本位
        int weakestParentScore = Integer.MAX_VALUE;
        int weakestX = 0;
        int weakestZ = 0;
        boolean hasWeakestParent = false;

        for (CellAssignment cell : assignments) {
            if (cell.role != CellRole.PARENT_SINGLE) continue;
            int x = bell.centerX() + cell.dx;
            int z = bell.centerZ() + cell.dz;
            CropStickCellState state = compat.inspectCropStickCell(world, x, cropY, z);
            if (state.kind == StickKind.CROSS_EMPTY) {
                aimAndSwing(maid, x, cropY, z);
                storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, true));
                return;
            }
            if (state.kind == StickKind.PLANTED) {
                if (!cell.desiredParentCropId.isEmpty() && !cell.desiredParentCropId.equals(state.cropId)) {
                    aimAndSwing(maid, x, cropY, z);
                    storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                    return;
                }
                if (state.canHarvest) {
                    aimAndSwing(maid, x, cropY, z);
                    storeDrops(maid, compat.spadeHarvestKeepAlive(world, x, cropY, z, spadeStack));
                    return;
                }
                int score = config.scoreSeed(state.growth, state.gain, state.resistance);
                if (score < weakestParentScore) {
                    weakestParentScore = score;
                    weakestX = x;
                    weakestZ = z;
                    hasWeakestParent = true;
                }
            } else if (state.kind == StickKind.EMPTY_SPACE) {
                ItemStack stickSlot = findFirstCropSticksSlot(maid, compat);
                if (stickSlot != null && compat.placeOrUpgradeCropStick(world, x, cropY, z, stickSlot, false)) {
                    cleanupEmptySlots(maid.getMaidInventory());
                    aimAndSwing(maid, x, cropY, z);
                    return;
                }
            } else if (state.kind == StickKind.SINGLE_EMPTY && !cell.desiredParentCropId.isEmpty()) {
                ItemStack bestSeed = findBestSeedStackInRawInventory(maid, config, compat, cell.desiredParentCropId);
                if (bestSeed != null && compat.plantSeedOnStick(world, x, cropY, z, bestSeed)) {
                    cleanupEmptySlots(maid.getMaidInventory());
                    aimAndSwing(maid, x, cropY, z);
                    return;
                }
            }
        }

        // 5. 同种提纯爬坡：若背包里已有比田里最弱母本评分更高的同种种子，拔除最弱母本换上优种
        if (targetPlan.status == PlanStatus.READY_PURIFY && hasWeakestParent && !config.targetId().isEmpty()) {
            ItemStack upgradeSeed = findBestSeedStackInRawInventory(maid, config, compat, config.targetId());
            if (upgradeSeed != null) {
                SeedInspection insp = compat.inspectSeedOrAlternate(upgradeSeed);
                if (insp != null && config.scoreSeed(insp.growth, insp.gain, insp.resistance) > weakestParentScore) {
                    aimAndSwing(maid, weakestX, cropY, weakestZ);
                    storeDrops(maid, compat.spadeClearWeedOrUproot(world, weakestX, cropY, weakestZ, spadeStack, false));
                    if (compat.plantSeedOnStick(world, weakestX, cropY, weakestZ, upgradeSeed)) {
                        cleanupEmptySlots(maid.getMaidInventory());
                    }
                }
            }
        }
    }

    public static EvaluationResult evaluatePlotAndMissing(EntityMaid maid, BreedingTaskConfigData config,
        CropsNhBreedingCompatProvider compat) {
        List<MissingConditionEntry> missing = new ArrayList<>();
        ItemStack bellStack = findEquippedBellStack(maid);
        BreedingBellData bell = null;
        if (bellStack == null) {
            missing.add(MissingConditionEntry.of(MissingConditionCode.MISSING_BREEDING_BELL));
            return new EvaluationResult(null, null, Collections.emptyList(), missing);
        }
        bell = BreedingBellData.read(bellStack);
        if (!bell.isBound()) {
            missing.add(MissingConditionEntry.of(MissingConditionCode.UNBOUND_BREEDING_BELL));
            return new EvaluationResult(bell, null, Collections.emptyList(), missing);
        }
        if (maid.worldObj != null && bell.dimension() != maid.worldObj.provider.dimensionId) {
            missing.add(
                MissingConditionEntry.withArg(
                    MissingConditionCode.WRONG_DIMENSION_BREEDING_BELL,
                    Integer.toString(bell.dimension()),
                    false));
            return new EvaluationResult(bell, null, Collections.emptyList(), missing);
        }

        PlotValidationResult plotCheck = compat
            .validateBreedingPlot(maid.worldObj, bell.centerX(), bell.soilY(), bell.centerZ());
        if (!plotCheck.valid) {
            String coords = plotCheck.badX + BreedingWorkIds.COORD_SEP
                + plotCheck.badY
                + BreedingWorkIds.COORD_SEP
                + plotCheck.badZ;
            missing.add(MissingConditionEntry.withArg(MissingConditionCode.INVALID_PLOT_OBSTRUCTED, coords, false));
            return new EvaluationResult(bell, null, Collections.emptyList(), missing);
        }

        if (!config.hasTarget()) {
            missing.add(MissingConditionEntry.of(MissingConditionCode.MISSING_TARGET_CONFIG));
            return new EvaluationResult(bell, null, Collections.emptyList(), missing);
        }

        Set<String> availableCrops = collectAvailableCropIds(maid, bell, compat);
        TargetBreedingPlan plan = compat
            .resolveTargetPlan(config.isMutationPoolMode(), config.targetId(), availableCrops);
        if (plan.status == PlanStatus.UNSUPPORTED_IN_WORLD || plan.status == PlanStatus.MISSING_TARGET) {
            missing.add(
                MissingConditionEntry.withArg(
                    MissingConditionCode.UNSUPPORTED_IN_WORLD_CROP,
                    plan.targetUnlocalizedName.isEmpty() ? config.targetId() : plan.targetUnlocalizedName,
                    !plan.targetUnlocalizedName.isEmpty()));
            return new EvaluationResult(bell, plan, Collections.emptyList(), missing);
        }

        if (findFirstSpadeStack(maid, compat) == null && !hasSpadeInNestedBackpacks(maid, compat)) {
            missing.add(MissingConditionEntry.of(MissingConditionCode.MISSING_CROPSNH_SPADE));
        }

        if (plan.status == PlanStatus.MISSING_PARENTS) {
            String parentsHint = String.join(BreedingWorkIds.PARENT_JOIN, plan.suggestedParentNames);
            missing.add(
                MissingConditionEntry.withTwoArgs(
                    MissingConditionCode.MISSING_PARENT_SEEDS,
                    plan.targetUnlocalizedName.isEmpty() ? config.targetId() : plan.targetUnlocalizedName,
                    parentsHint,
                    !plan.targetUnlocalizedName.isEmpty()));
            return new EvaluationResult(bell, plan, Collections.emptyList(), missing);
        }

        boolean purify = plan.status == PlanStatus.READY_PURIFY;
        String purifyCropId = purify && !plan.parentCropIds.isEmpty()
            ? plan.parentCropIds.get(BreedingWorkTuning.ZERO)
            : config.targetId();
        List<CellAssignment> cells = BreedingPlotPlanner.planCells(purify, purifyCropId, plan.parentCropIds);

        int existingSticks = countPlacedSticksOnPlot(maid.worldObj, bell, compat);
        int inventorySticks = countCropSticksInAllStorage(maid, compat);
        int neededSticks = BreedingPlotPlanner.requiredTotalSticks(cells);
        if (existingSticks + inventorySticks < neededSticks) {
            missing.add(
                MissingConditionEntry.withArg(
                    MissingConditionCode.MISSING_CROP_STICKS,
                    Integer.toString(neededSticks - existingSticks - inventorySticks),
                    false));
        }

        checkFoundationSuppliesAndTools(maid, bell, config, compat, plan, cells, missing);
        return new EvaluationResult(bell, plan, cells, missing);
    }

    private static void checkFoundationSuppliesAndTools(EntityMaid maid, BreedingBellData bell,
        BreedingTaskConfigData config, CropsNhBreedingCompatProvider compat, TargetBreedingPlan plan,
        List<CellAssignment> cells, List<MissingConditionEntry> missing) {
        World world = maid.worldObj;
        boolean hasTillTool = hasTillToolInRawInventory(maid, compat);
        String targetGoverning = config.isMutationPoolMode() ? "" : config.targetId();
        for (CellAssignment cell : cells) {
            if (cell.role == CellRole.LEAVE_EMPTY) continue;
            int x = bell.centerX() + cell.dx;
            int z = bell.centerZ() + cell.dz;
            String cellGoverning = cell.role == CellRole.PARENT_SINGLE ? cell.desiredParentCropId : targetGoverning;
            List<String> cellParents = cell.role == CellRole.CROSS_DOUBLE ? plan.parentCropIds
                : Collections.emptyList();
            CellFoundationCheck fcheck = compat
                .checkCellFoundation(world, x, bell.soilY(), z, cellGoverning, cellParents);
            if (!fcheck.soilOk) {
                if (!checkBreakableOrHasTool(maid, world, x, bell.soilY(), z, missing)) return;
                if (!hasMatchingSoilBlock(maid, cellGoverning, hasTillTool, compat)) {
                    missing.add(
                        MissingConditionEntry.withArg(
                            MissingConditionCode.MISSING_SOIL_BLOCK,
                            fcheck.soilDescriptionKey,
                            !fcheck.soilDescriptionKey.isEmpty()));
                    return;
                }
            }
            if (!fcheck.subSoilOk) {
                if (!checkBreakableOrHasTool(maid, world, x, bell.soilY(), z, missing)) return;
                if (!checkBreakableOrHasTool(maid, world, x, bell.subSoilY(), z, missing)) return;
                if (!hasMatchingSubSoilBlock(maid, cellGoverning, cellParents, compat)) {
                    missing.add(
                        MissingConditionEntry.withArg(
                            MissingConditionCode.MISSING_SUBSOIL_BLOCK,
                            fcheck.subSoilDescription,
                            false));
                    return;
                }
            }
        }
    }

    private static boolean checkBreakableOrHasTool(EntityMaid maid, World world, int x, int y, int z,
        List<MissingConditionEntry> missing) {
        Block oldBlock = world.getBlock(x, y, z);
        if (oldBlock == null || world.isAirBlock(x, y, z) || oldBlock.getMaterial().isLiquid()) return true;
        float hardness = oldBlock.getBlockHardness(world, x, y, z);
        if (hardness < BreedingWorkTuning.ZERO_HARDNESS) {
            String coords = x + BreedingWorkIds.COORD_SEP + y + BreedingWorkIds.COORD_SEP + z;
            missing.add(MissingConditionEntry.withArg(MissingConditionCode.INVALID_PLOT_OBSTRUCTED, coords, false));
            return false;
        }
        int meta = world.getBlockMetadata(x, y, z);
        if (oldBlock.getMaterial().isToolNotRequired()) return true;
        if (findHarvestToolSlot(maid, oldBlock, meta) >= 0) return true;
        String toolClass = oldBlock.getHarvestTool(meta);
        if (toolClass == null || toolClass.isEmpty()) toolClass = BreedingWorkIds.TOOL_CLASS_FALLBACK;
        int reqLevel = Math.max(BreedingWorkTuning.ZERO, oldBlock.getHarvestLevel(meta));
        missing.add(
            MissingConditionEntry.withTwoArgs(
                MissingConditionCode.MISSING_HARVEST_TOOL,
                oldBlock.getLocalizedName(),
                toolClass + BreedingWorkIds.SPACE_LEVEL_PREFIX + reqLevel,
                false));
        return false;
    }

    private static void replaceCellFoundation(EntityMaid maid, World world, int x, int soilY, int subSoilY, int z,
        String governingCropId, List<String> crossParentIds, CellFoundationCheck fcheck,
        CropsNhBreedingCompatProvider compat, ItemStack spadeStack) {
        int cropY = soilY + BreedingWorkTuning.ONE;
        CropStickCellState stickState = compat.inspectCropStickCell(world, x, cropY, z);
        if (stickState.kind != StickKind.EMPTY_SPACE) {
            storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, true));
        }

        boolean hasTill = hasTillToolInRawInventory(maid, compat);
        if (!fcheck.subSoilOk) {
            // 先合法拆掉上层土壤回收，再合法拆掉底块换新底块，最后重铺合规土壤
            if (!mineBlockWithRealTool(maid, world, x, soilY, z)) return;
            if (!mineBlockWithRealTool(maid, world, x, subSoilY, z)) return;
            int subSlot = findMatchingSubSoilSlot(maid, governingCropId, crossParentIds, compat);
            if (subSlot >= 0) {
                placeInventoryBlockAt(maid, world, subSlot, x, subSoilY, z);
            }
            int soilSlot = findMatchingSoilSlot(maid, governingCropId, hasTill, compat);
            if (soilSlot >= 0) {
                placeInventoryBlockAt(maid, world, soilSlot, x, soilY, z);
                compat.tillSoilIfApplicable(world, x, soilY, z);
            }
            return;
        }

        if (!fcheck.soilOk) {
            if (fcheck.requiresFarmlandTilling && compat.tillSoilIfApplicable(world, x, soilY, z)) {
                return;
            }
            if (!mineBlockWithRealTool(maid, world, x, soilY, z)) return;
            int soilSlot = findMatchingSoilSlot(maid, governingCropId, hasTill, compat);
            if (soilSlot >= 0) {
                placeInventoryBlockAt(maid, world, soilSlot, x, soilY, z);
                compat.tillSoilIfApplicable(world, x, soilY, z);
            }
        }
    }

    private static boolean mineBlockWithRealTool(EntityMaid maid, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || world.isAirBlock(x, y, z) || block.getMaterial().isLiquid()) {
            world.setBlockToAir(x, y, z);
            return true;
        }
        if (block.getBlockHardness(world, x, y, z) < BreedingWorkTuning.ZERO_HARDNESS) return false;
        int meta = world.getBlockMetadata(x, y, z);
        int toolSlot = BreedingWorkTuning.UNSET_SLOT;
        if (!block.getMaterial().isToolNotRequired()) {
            toolSlot = findHarvestToolSlot(maid, block, meta);
            if (toolSlot < 0) return false;
        } else {
            toolSlot = findOptionalSpeedToolSlot(maid, block, meta);
        }

        List<ItemStack> drops = block.getDrops(
            world,
            x,
            y,
            z,
            meta,
            BreedingWorkTuning.DEFAULT_DROP_FORTUNE);
        world.playAuxSFX(
            BreedingWorkTuning.BLOCK_BREAK_AUX_SFX,
            x,
            y,
            z,
            Block.getIdFromBlock(block) + (meta << BreedingWorkTuning.BLOCK_META_SHIFT));
        world.setBlockToAir(x, y, z);
        storeDrops(maid, drops);

        if (toolSlot >= 0) {
            MaidInventory inv = maid.getMaidInventory();
            ItemStack tool = inv.getStackInSlot(toolSlot);
            if (tool != null && tool.isItemStackDamageable()) {
                if (tool.attemptDamageItem(BreedingWorkTuning.ONE, maid.getRNG())) {
                    tool.stackSize--;
                    if (tool.stackSize <= 0) {
                        inv.setInventorySlotContents(toolSlot, null);
                    }
                }
                inv.markDirty();
            }
        }
        return true;
    }

    private static boolean placeInventoryBlockAt(EntityMaid maid, World world, int slot, int x, int y, int z) {
        MaidInventory inv = maid.getMaidInventory();
        ItemStack stack = inv.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock itemBlock)) {
            return false;
        }
        int meta = itemBlock.getMetadata(stack.getItemDamage());
        boolean placed = world.setBlock(x, y, z, itemBlock.field_150939_a, meta, BreedingWorkTuning.BLOCK_UPDATE_FLAGS);
        if (placed) {
            if (world.getBlock(x, y, z) == itemBlock.field_150939_a) {
                itemBlock.field_150939_a.onBlockPlacedBy(world, x, y, z, maid, stack);
                itemBlock.field_150939_a.onPostBlockPlaced(world, x, y, z, meta);
            }
            inv.decrStackSize(slot, BreedingWorkTuning.ONE);
        }
        return placed;
    }

    private static boolean ensureCrossStick(EntityMaid maid, World world, int x, int cropY, int z,
        CropsNhBreedingCompatProvider compat) {
        ItemStack sticks = findFirstCropSticksSlot(maid, compat);
        if (sticks == null) return false;
        boolean changed = compat.placeOrUpgradeCropStick(world, x, cropY, z, sticks, true);
        if (changed) {
            cleanupEmptySlots(maid.getMaidInventory());
        }
        return changed;
    }

    private static boolean matchesConfiguredTarget(BreedingTaskConfigData config, TargetBreedingPlan plan,
        String cropId) {
        if (cropId == null || cropId.isEmpty()) return false;
        if (!config.isMutationPoolMode()) {
            return config.targetId().equals(cropId);
        }
        return plan.parentCropIds.contains(cropId);
    }

    private static void aimAndSwing(EntityMaid maid, int x, int y, int z) {
        maid.getLookHelper().setLookPosition(
            x + BreedingWorkTuning.BLOCK_CENTER_OFFSET,
            y + BreedingWorkTuning.BLOCK_CENTER_OFFSET,
            z + BreedingWorkTuning.BLOCK_CENTER_OFFSET,
            BreedingWorkTuning.LOOK_SPEED,
            maid.getVerticalFaceSpeed());
        maid.swingItem();
    }

    private static void storeDrops(EntityMaid maid, List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) return;
        MaidInventory inv = maid.getMaidInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.stackSize <= 0) continue;
            ItemStack rem = inv.insertAvailable(drop, false);
            if (rem != null && rem.stackSize > 0) {
                EntityItem item = maid.entityDropItem(rem, BreedingWorkTuning.DROP_OFFSET_Y);
                if (item != null) {
                    item.delayBeforeCanPickup = BreedingWorkTuning.ZERO;
                }
            }
        }
    }

    private static void cleanupEmptySlots(MaidInventory inv) {
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize <= 0) {
                inv.setInventorySlotContents(i, null);
            }
        }
        inv.markDirty();
    }

    private static int findHarvestToolSlot(EntityMaid maid, Block block, int meta) {
        MaidInventory inv = maid.getMaidInventory();
        String reqTool = block.getHarvestTool(meta);
        int reqLevel = block.getHarvestLevel(meta);
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0 || s.getItem() == null) continue;
            if (ForgeHooks.canToolHarvestBlock(block, meta, s)) return i;
            if (reqTool != null && s.getItem().getHarvestLevel(s, reqTool) >= reqLevel) return i;
        }
        return BreedingWorkTuning.UNSET_SLOT;
    }

    private static int findOptionalSpeedToolSlot(EntityMaid maid, Block block, int meta) {
        String reqTool = block.getHarvestTool(meta);
        if (reqTool == null) return BreedingWorkTuning.UNSET_SLOT;
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > 0 && s.getItem() != null
                && s.getItem().getHarvestLevel(s, reqTool) >= BreedingWorkTuning.ZERO) {
                return i;
            }
        }
        return BreedingWorkTuning.UNSET_SLOT;
    }

    private static Set<String> collectAvailableCropIds(EntityMaid maid, BreedingBellData bell,
        CropsNhBreedingCompatProvider compat) {
        Set<String> ids = new LinkedHashSet<>();
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            SeedInspection insp = compat.inspectSeedOrAlternate(s);
            if (insp != null && !insp.cropId.isEmpty()) {
                ids.add(insp.cropId);
            }
            if (CompatServices.backpacks().isSupportedBackpack(s)) {
                CompatServices.backpacks().forEachStoredStack(s, nested -> {
                    SeedInspection ni = compat.inspectSeedOrAlternate(nested);
                    if (ni != null && !ni.cropId.isEmpty()) ids.add(ni.cropId);
                });
            }
        }
        int cropY = bell.cropY();
        for (int dx = -BreedingWorkTuning.PLOT_RADIUS; dx <= BreedingWorkTuning.PLOT_RADIUS; dx++) {
            for (int dz = -BreedingWorkTuning.PLOT_RADIUS; dz <= BreedingWorkTuning.PLOT_RADIUS; dz++) {
                CropStickCellState st = compat
                    .inspectCropStickCell(maid.worldObj, bell.centerX() + dx, cropY, bell.centerZ() + dz);
                if (st.kind == StickKind.PLANTED && !st.cropId.isEmpty()) {
                    ids.add(st.cropId);
                }
            }
        }
        return ids;
    }

    private static ItemStack findBestSeedStackInRawInventory(EntityMaid maid, BreedingTaskConfigData config,
        CropsNhBreedingCompatProvider compat, String wantedCropId) {
        MaidInventory inv = maid.getMaidInventory();
        ItemStack best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            SeedInspection insp = compat.inspectSeedOrAlternate(s);
            if (insp == null || !wantedCropId.equals(insp.cropId)) continue;
            int score = config.scoreSeed(insp.growth, insp.gain, insp.resistance);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static ItemStack findFirstSpadeStack(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (compat.isSpadeItem(s)) return s;
        }
        return null;
    }

    private static boolean hasSpadeInNestedBackpacks(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        boolean[] found = new boolean[BreedingWorkTuning.ONE];
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (CompatServices.backpacks().isSupportedBackpack(s)) {
                CompatServices.backpacks().forEachStoredStack(s, nested -> {
                    if (compat.isSpadeItem(nested)) found[BreedingWorkTuning.ZERO] = true;
                });
            }
            if (found[BreedingWorkTuning.ZERO]) return true;
        }
        return false;
    }

    private static boolean hasTillToolInRawInventory(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > 0 && (compat.isSpadeItem(s) || s.getItem() instanceof ItemHoe)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack findFirstCropSticksSlot(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (compat.isCropSticksItem(s)) return s;
        }
        return null;
    }

    private static int countCropSticksInAllStorage(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        int[] total = new int[BreedingWorkTuning.ONE];
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (compat.isCropSticksItem(s)) {
                total[BreedingWorkTuning.ZERO] += s.stackSize;
            } else if (CompatServices.backpacks().isSupportedBackpack(s)) {
                CompatServices.backpacks().forEachStoredStack(s, nested -> {
                    if (compat.isCropSticksItem(nested)) total[BreedingWorkTuning.ZERO] += nested.stackSize;
                });
            }
        }
        return total[BreedingWorkTuning.ZERO];
    }

    private static int countPlacedSticksOnPlot(World world, BreedingBellData bell,
        CropsNhBreedingCompatProvider compat) {
        int count = BreedingWorkTuning.ZERO;
        int cropY = bell.cropY();
        for (int dx = -BreedingWorkTuning.PLOT_RADIUS; dx <= BreedingWorkTuning.PLOT_RADIUS; dx++) {
            for (int dz = -BreedingWorkTuning.PLOT_RADIUS; dz <= BreedingWorkTuning.PLOT_RADIUS; dz++) {
                CropStickCellState st = compat
                    .inspectCropStickCell(world, bell.centerX() + dx, cropY, bell.centerZ() + dz);
                if (st.kind == StickKind.CROSS_EMPTY) {
                    count += BreedingWorkTuning.TWO;
                } else if (st.kind == StickKind.SINGLE_EMPTY || st.kind == StickKind.PLANTED
                    || st.kind == StickKind.WEED) {
                    count += BreedingWorkTuning.ONE;
                }
            }
        }
        return count;
    }

    private static boolean hasMatchingSoilBlock(EntityMaid maid, String governingCropId, boolean hasTillTool,
        CropsNhBreedingCompatProvider compat) {
        return findMatchingSoilSlot(maid, governingCropId, hasTillTool, compat) >= 0
            || hasSoilInBackpacks(maid, governingCropId, hasTillTool, compat);
    }

    private static int findMatchingSoilSlot(EntityMaid maid, String governingCropId, boolean hasTillTool,
        CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            if (governingCropId.isEmpty() && s.getItem() instanceof ItemBlock ib
                && (ib.field_150939_a == Blocks.dirt || ib.field_150939_a == Blocks.grass
                    || ib.field_150939_a == Blocks.farmland)) {
                return i;
            }
            if (compat.matchesRequiredSoilItem(governingCropId, s, hasTillTool)) return i;
        }
        return BreedingWorkTuning.UNSET_SLOT;
    }

    private static boolean hasSoilInBackpacks(EntityMaid maid, String governingCropId, boolean hasTillTool,
        CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        boolean[] found = new boolean[BreedingWorkTuning.ONE];
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (CompatServices.backpacks().isSupportedBackpack(s)) {
                CompatServices.backpacks().forEachStoredStack(s, nested -> {
                    if (compat.matchesRequiredSoilItem(governingCropId, nested, hasTillTool)) {
                        found[BreedingWorkTuning.ZERO] = true;
                    }
                });
            }
            if (found[BreedingWorkTuning.ZERO]) return true;
        }
        return false;
    }

    private static boolean hasMatchingSubSoilBlock(EntityMaid maid, String governingCropId, List<String> crossParentIds,
        CropsNhBreedingCompatProvider compat) {
        return findMatchingSubSoilSlot(maid, governingCropId, crossParentIds, compat) >= 0;
    }

    private static int findMatchingSubSoilSlot(EntityMaid maid, String governingCropId, List<String> crossParentIds,
        CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (compat.matchesRequiredSubSoilItem(governingCropId, crossParentIds, s)) return i;
        }
        return BreedingWorkTuning.UNSET_SLOT;
    }

    public static ItemStack findEquippedBellStack(EntityMaid maid) {
        if (maid == null) return null;
        MaidBaubleInventory baubles = maid.getMaidBaubleInventory();
        int unlocked = MaidBaubleLayout.unlockedSlots(maid.getFavorabilityManager().getLevel());
        int limit = Math.min(unlocked, baubles.getSizeInventory());
        for (int i = 0; i < limit; i++) {
            ItemStack s = baubles.getStackInSlot(i);
            if (s != null && s.stackSize > 0 && s.getItem() instanceof BreedingBellItem) {
                return s;
            }
        }
        return null;
    }

    private static BreedingBellData findEquippedBellData(EntityMaid maid) {
        ItemStack s = findEquippedBellStack(maid);
        return s == null ? null : BreedingBellData.read(s);
    }

    public static final class EvaluationResult {

        public final BreedingBellData bell;
        public final TargetBreedingPlan plan;
        public final List<CellAssignment> cellAssignments;
        public final List<MissingConditionEntry> missing;

        public EvaluationResult(BreedingBellData bell, TargetBreedingPlan plan, List<CellAssignment> cellAssignments,
            List<MissingConditionEntry> missing) {
            this.bell = bell;
            this.plan = plan;
            this.cellAssignments = cellAssignments;
            this.missing = missing;
        }
    }
}
