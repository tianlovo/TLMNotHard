package com.tianluoqaq.tlmnh.common.work.breeding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
import com.tianluoqaq.tlmnh.common.inventory.spi.BackpackContainerCompatProvider;
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
 * 生产循环完全遵循自然作物生长节奏（不调用测试专用的生长加速钩子），
 * 统一裸槽与可取用嵌套背包的齐件检查与物资取用，执行物理挖掘换土换底矿、替用种子识别、保活收种除草与三维属性爬坡提纯
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
        return bell != null && bell.isBound()
            && maid.worldObj != null
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
            if (now - lastNavWorldTime >= BreedingWorkTuning.NAV_REFRESH_TICKS || maid.getNavigator()
                .getPath() == null
                || maid.getNavigator()
                    .getPath()
                    .isFinished()) {
                lastNavWorldTime = now;
                planner
                    .request(maid, cx + BreedingWorkTuning.STAND_OFFSET_BLOCKS, cy, cz, BreedingWorkTuning.WALK_SPEED);
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
        CheckedOutTool spade = checkoutTool(maid, compat::isSpadeItem);
        if (spade == null) return;

        try {
            ItemStack spadeStack = spade.stack;

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

            // 2. 检查每一格土壤与底矿地基。不满足时执行真实挖掘等级破坏与库存方块替换
            String targetGoverningCrop = config.isMutationPoolMode() ? "" : config.targetId();
            for (CellAssignment cell : assignments) {
                if (cell.role == CellRole.LEAVE_EMPTY) continue;
                int x = bell.centerX() + cell.dx;
                int z = bell.centerZ() + cell.dz;
                String cellGoverning = cell.role == CellRole.PARENT_SINGLE ? cell.desiredParentCropId
                    : targetGoverningCrop;
                List<String> cellParents = cell.role == CellRole.CROSS_DOUBLE ? targetPlan.parentCropIds
                    : Collections.emptyList();
                CellFoundationCheck fcheck = compat.checkCellFoundation(world, x, soilY, z, cellGoverning, cellParents);
                if (!fcheck.subSoilOk || !fcheck.soilOk) {
                    aimAndSwing(maid, x, soilY, z);
                    replaceCellFoundation(
                        maid,
                        world,
                        x,
                        soilY,
                        subSoilY,
                        z,
                        cellGoverning,
                        cellParents,
                        fcheck,
                        compat,
                        spadeStack);
                    return;
                }
            }

            boolean parentRosterComplete = allParentCellsPlanted(world, bell, compat, assignments);

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
                    // 先补齐正确母本，避免高频非目标子代长期抢占补植；纯化仍可收取首颗目标种子
                    boolean needsTargetSeed = !parentRosterComplete && state.kind == StickKind.PLANTED
                        && matchesConfiguredTarget(config, targetPlan, state.cropId)
                        && peekBestSeedInspection(maid, config, compat, state.cropId) == null;
                    if (!parentRosterComplete && !needsTargetSeed) continue;
                    if (state.kind == StickKind.PLANTED) {
                        boolean matchesTarget = matchesConfiguredTarget(config, targetPlan, state.cropId);
                        if (!matchesTarget) {
                            aimAndSwing(maid, x, cropY, z);
                            storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                            placeOrUpgradeStickFromStorage(maid, world, x, cropY, z, compat, true);
                            return;
                        }
                        if (state.canHarvest || state.mature) {
                            aimAndSwing(maid, x, cropY, z);
                            storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                            placeOrUpgradeStickFromStorage(maid, world, x, cropY, z, compat, true);
                            return;
                        }
                    } else if (state.kind == StickKind.EMPTY_SPACE || state.kind == StickKind.SINGLE_EMPTY) {
                        if (placeOrUpgradeStickFromStorage(maid, world, x, cropY, z, compat, true)) {
                            aimAndSwing(maid, x, cropY, z);
                            return;
                        }
                    }
                }
            }

            // 4. 处理母本单架位：未满员缺种时保活收种扩繁，杂交与爬坡未达标期间保留母本成熟度以供相邻十字架杂交
            boolean hasWaitingCrossCell = !parentRosterComplete
                || hasUnplantedCrossCell(world, bell, compat, assignments);
            boolean allParentsMeetTarget = allParentCellsMeetTarget(
                world,
                bell,
                config,
                compat,
                targetPlan,
                assignments);
            int weakestParentScore = Integer.MAX_VALUE;
            int weakestX = BreedingWorkTuning.ZERO;
            int weakestZ = BreedingWorkTuning.ZERO;
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
                        // 先备好替换种子，再拔旧母本，保留首颗目标子代成熟期间的亲本
                        if (peekBestSeedInspection(maid, config, compat, cell.desiredParentCropId) == null) continue;
                        aimAndSwing(maid, x, cropY, z);
                        storeDrops(maid, compat.spadeClearWeedOrUproot(world, x, cropY, z, spadeStack, false));
                        return;
                    }
                    if (state.canHarvest && shouldHarvestMatureParent(
                        maid,
                        world,
                        bell,
                        config,
                        compat,
                        assignments,
                        state.cropId,
                        hasWaitingCrossCell,
                        allParentsMeetTarget)) {
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
                    if (placeOrUpgradeStickFromStorage(maid, world, x, cropY, z, compat, false)) {
                        aimAndSwing(maid, x, cropY, z);
                        return;
                    }
                } else if (state.kind == StickKind.SINGLE_EMPTY && !cell.desiredParentCropId.isEmpty()) {
                    if (plantBestSeedOnStick(maid, world, x, cropY, z, config, compat, cell.desiredParentCropId)) {
                        aimAndSwing(maid, x, cropY, z);
                        return;
                    }
                }
            }

            // 5. 同种提纯爬坡：若裸槽或可取用背包里已有比田里最弱母本评分更高的同种种子，拔除最弱母本换上优种
            if (targetPlan.status == PlanStatus.READY_PURIFY && hasWeakestParent
                && !config.targetId()
                    .isEmpty()) {
                SeedInspection bestUpgrade = peekBestSeedInspection(maid, config, compat, config.targetId());
                if (bestUpgrade != null
                    && config.scoreSeed(bestUpgrade.growth, bestUpgrade.gain, bestUpgrade.resistance)
                        > weakestParentScore) {
                    aimAndSwing(maid, weakestX, cropY, weakestZ);
                    storeDrops(
                        maid,
                        compat.spadeClearWeedOrUproot(world, weakestX, cropY, weakestZ, spadeStack, false));
                    plantBestSeedOnStick(maid, world, weakestX, cropY, weakestZ, config, compat, config.targetId());
                }
            }
        } finally {
            spade.finish(maid);
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

        if (!hasAccessibleStack(maid, compat::isSpadeItem)) {
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
        String purifyCropId = purify && !plan.parentCropIds.isEmpty() ? plan.parentCropIds.get(BreedingWorkTuning.ZERO)
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
        boolean hasTillTool = hasTillToolInAllStorage(maid, compat);
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
                if (!fcheck.requiresFarmlandTilling || !hasTillTool) {
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
            }
            if (!fcheck.subSoilOk) {
                if (!checkBreakableOrHasTool(maid, world, x, bell.soilY(), z, missing)) return;
                if (!checkBreakableOrHasTool(maid, world, x, bell.subSoilY(), z, missing)) return;
                if (!hasMatchingSubSoilBlock(maid, cellGoverning, cellParents, compat)) {
                    missing.add(
                        MissingConditionEntry
                            .withArg(MissingConditionCode.MISSING_SUBSOIL_BLOCK, fcheck.subSoilDescription, false));
                    return;
                }
            }
        }
    }

    private static boolean checkBreakableOrHasTool(EntityMaid maid, World world, int x, int y, int z,
        List<MissingConditionEntry> missing) {
        Block oldBlock = world.getBlock(x, y, z);
        if (oldBlock == null || world.isAirBlock(x, y, z)
            || oldBlock.getMaterial()
                .isLiquid())
            return true;
        float hardness = oldBlock.getBlockHardness(world, x, y, z);
        if (hardness < BreedingWorkTuning.ZERO_HARDNESS) {
            String coords = x + BreedingWorkIds.COORD_SEP + y + BreedingWorkIds.COORD_SEP + z;
            missing.add(MissingConditionEntry.withArg(MissingConditionCode.INVALID_PLOT_OBSTRUCTED, coords, false));
            return false;
        }
        int meta = world.getBlockMetadata(x, y, z);
        if (oldBlock.getMaterial()
            .isToolNotRequired()) return true;
        String rawToolClass = oldBlock.getHarvestTool(meta);
        int reqLevel = oldBlock.getHarvestLevel(meta);
        if (hasAccessibleStack(maid, s -> isQualifiedHarvestTool(s, oldBlock, meta, rawToolClass, reqLevel))) {
            return true;
        }
        String toolClass = rawToolClass == null || rawToolClass.isEmpty() ? BreedingWorkIds.TOOL_CLASS_FALLBACK
            : rawToolClass;
        int displayLevel = Math.max(BreedingWorkTuning.ZERO, reqLevel);
        missing.add(
            MissingConditionEntry.withTwoArgs(
                MissingConditionCode.MISSING_HARVEST_TOOL,
                oldBlock.getLocalizedName(),
                toolClass + BreedingWorkIds.SPACE_LEVEL_PREFIX + displayLevel,
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

        // 嵌套铲子已借出，库存扫描之外还须计入当前持有的工具
        boolean hasTill = (spadeStack != null && spadeStack.stackSize > BreedingWorkTuning.ZERO
            && compat.isSpadeItem(spadeStack)) || hasTillToolInAllStorage(maid, compat);
        if (!fcheck.subSoilOk) {
            // 先合法拆掉上层土壤回收，再合法拆掉底块换新底块，最后重铺合规土壤
            if (!mineBlockWithRealTool(maid, world, x, soilY, z)) return;
            if (!mineBlockWithRealTool(maid, world, x, subSoilY, z)) return;
            placeMatchingBlockFromStorage(
                maid,
                world,
                x,
                subSoilY,
                z,
                s -> isMatchingSubSoilStack(s, governingCropId, crossParentIds, compat));
            if (placeMatchingBlockFromStorage(
                maid,
                world,
                x,
                soilY,
                z,
                s -> isMatchingSoilStack(s, governingCropId, hasTill, compat)) && hasTill) {
                compat.tillSoilIfApplicable(world, x, soilY, z);
            }
            return;
        }

        if (!fcheck.soilOk) {
            if (fcheck.requiresFarmlandTilling && hasTill && compat.tillSoilIfApplicable(world, x, soilY, z)) {
                return;
            }
            if (!mineBlockWithRealTool(maid, world, x, soilY, z)) return;
            if (placeMatchingBlockFromStorage(
                maid,
                world,
                x,
                soilY,
                z,
                s -> isMatchingSoilStack(s, governingCropId, hasTill, compat)) && hasTill) {
                compat.tillSoilIfApplicable(world, x, soilY, z);
            }
        }
    }

    private static boolean mineBlockWithRealTool(EntityMaid maid, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || world.isAirBlock(x, y, z)
            || block.getMaterial()
                .isLiquid()) {
            world.setBlockToAir(x, y, z);
            return true;
        }
        if (block.getBlockHardness(world, x, y, z) < BreedingWorkTuning.ZERO_HARDNESS) return false;
        int meta = world.getBlockMetadata(x, y, z);
        String reqTool = block.getHarvestTool(meta);
        int reqLevel = block.getHarvestLevel(meta);

        CheckedOutTool toolRef;
        if (!block.getMaterial()
            .isToolNotRequired()) {
            toolRef = checkoutTool(maid, s -> isQualifiedHarvestTool(s, block, meta, reqTool, reqLevel));
            if (toolRef == null) return false;
        } else {
            toolRef = reqTool == null ? null : checkoutTool(maid, s -> isOptionalSpeedTool(s, block, reqTool));
        }

        try {
            List<ItemStack> drops = block.getDrops(world, x, y, z, meta, BreedingWorkTuning.DEFAULT_DROP_FORTUNE);
            float hardness = block.getBlockHardness(world, x, y, z);
            if (toolRef != null && toolRef.stack != null && toolRef.stack.getItem() != null) {
                ItemStack tool = toolRef.stack;
                boolean handledByItem = tool.getItem()
                    .onBlockDestroyed(tool, world, block, x, y, z, maid);
                if (!handledByItem && tool.isItemStackDamageable() && hardness > BreedingWorkTuning.ZERO_HARDNESS) {
                    tool.damageItem(BreedingWorkTuning.ONE, maid);
                }
            }
            world.playAuxSFX(
                BreedingWorkTuning.BLOCK_BREAK_AUX_SFX,
                x,
                y,
                z,
                Block.getIdFromBlock(block) + (meta << BreedingWorkTuning.BLOCK_META_SHIFT));
            world.setBlockToAir(x, y, z);
            storeDrops(maid, drops);
            return true;
        } finally {
            if (toolRef != null) {
                toolRef.finish(maid);
            }
        }
    }

    private static boolean placeMatchingBlockFromStorage(EntityMaid maid, World world, int x, int y, int z,
        Predicate<ItemStack> filter) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > BreedingWorkTuning.ZERO && filter.test(s)) {
                return placeInventoryBlockAt(maid, world, i, x, y, z);
            }
        }
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack bag = inv.getStackInSlot(i);
            if (bag == null || !backpacks.isSupportedBackpack(bag)) continue;
            ItemStack extracted = backpacks.extractFromBackpack(
                bag,
                st -> st != null && st.stackSize > BreedingWorkTuning.ZERO && filter.test(st),
                null,
                BreedingWorkTuning.ONE,
                false);
            if (extracted == null) continue;
            boolean placed = placeExtractedBlockStackAt(maid, world, extracted, x, y, z);
            if (!placed && extracted.stackSize > BreedingWorkTuning.ZERO) {
                returnItemToBackpackOrMaid(maid, i, extracted);
            }
            inv.markDirty();
            return placed;
        }
        return false;
    }

    private static boolean placeInventoryBlockAt(EntityMaid maid, World world, int slot, int x, int y, int z) {
        MaidInventory inv = maid.getMaidInventory();
        ItemStack stack = inv.getStackInSlot(slot);
        if (!placeExtractedBlockStackAt(maid, world, stack, x, y, z)) {
            return false;
        }
        inv.decrStackSize(slot, BreedingWorkTuning.ONE);
        return true;
    }

    private static boolean placeExtractedBlockStackAt(EntityMaid maid, World world, ItemStack stack, int x, int y,
        int z) {
        if (stack == null || stack.stackSize <= BreedingWorkTuning.ZERO
            || !(stack.getItem() instanceof ItemBlock itemBlock)) {
            return false;
        }
        int meta = itemBlock.getMetadata(stack.getItemDamage());
        boolean placed = world.setBlock(x, y, z, itemBlock.field_150939_a, meta, BreedingWorkTuning.BLOCK_UPDATE_FLAGS);
        if (placed && world.getBlock(x, y, z) == itemBlock.field_150939_a) {
            itemBlock.field_150939_a.onBlockPlacedBy(world, x, y, z, maid, stack);
            itemBlock.field_150939_a.onPostBlockPlaced(world, x, y, z, meta);
        }
        return placed;
    }

    private static boolean placeOrUpgradeStickFromStorage(EntityMaid maid, World world, int x, int cropY, int z,
        CropsNhBreedingCompatProvider compat, boolean crossStick) {
        int maxNeeded = crossStick ? BreedingWorkTuning.TWO : BreedingWorkTuning.ONE;
        MaidInventory inv = maid.getMaidInventory();

        // 若单格裸槽已满足所需数量，直接原地扣除避免多余搬运
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (compat.isCropSticksItem(s) && s.stackSize >= maxNeeded) {
                boolean changed = compat.placeOrUpgradeCropStick(world, x, cropY, z, s, crossStick);
                if (changed) {
                    cleanupEmptySlots(inv);
                }
                return changed;
            }
        }

        // 跨多个裸槽或嵌套背包汇总最多 maxNeeded 根作物架执行铺设，未消耗余量原路归还
        ItemStack gathered = null;
        int preferredBagSlot = BreedingWorkTuning.UNSET_SLOT;
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            if (gathered != null && gathered.stackSize >= maxNeeded) break;
            ItemStack s = inv.getStackInSlot(i);
            if (!compat.isCropSticksItem(s)) continue;
            int need = maxNeeded - (gathered == null ? BreedingWorkTuning.ZERO : gathered.stackSize);
            int take = Math.min(need, s.stackSize);
            if (gathered == null) {
                gathered = s.copy();
                gathered.stackSize = take;
            } else {
                gathered.stackSize += take;
            }
            s.stackSize -= take;
            if (s.stackSize <= BreedingWorkTuning.ZERO) {
                inv.setInventorySlotContents(i, null);
            }
        }

        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            if (gathered != null && gathered.stackSize >= maxNeeded) break;
            ItemStack bag = inv.getStackInSlot(i);
            if (bag == null || !backpacks.isSupportedBackpack(bag)) continue;
            int need = maxNeeded - (gathered == null ? BreedingWorkTuning.ZERO : gathered.stackSize);
            ItemStack pulled = backpacks.extractFromBackpack(bag, compat::isCropSticksItem, null, need, false);
            if (pulled == null || pulled.stackSize <= BreedingWorkTuning.ZERO) continue;
            if (preferredBagSlot < BreedingWorkTuning.ZERO) {
                preferredBagSlot = i;
            }
            if (gathered == null) {
                gathered = pulled;
            } else {
                gathered.stackSize += pulled.stackSize;
            }
        }

        if (gathered == null || gathered.stackSize <= BreedingWorkTuning.ZERO) {
            return false;
        }
        boolean changed = compat.placeOrUpgradeCropStick(world, x, cropY, z, gathered, crossStick);
        if (gathered.stackSize > BreedingWorkTuning.ZERO) {
            returnItemToBackpackOrMaid(maid, preferredBagSlot, gathered);
        }
        cleanupEmptySlots(inv);
        return changed;
    }

    private static boolean matchesConfiguredTarget(BreedingTaskConfigData config, TargetBreedingPlan plan,
        String cropId) {
        if (cropId == null || cropId.isEmpty()) return false;
        if (!config.isMutationPoolMode()) {
            return config.targetId()
                .equals(cropId);
        }
        return plan.parentCropIds.contains(cropId);
    }

    private static boolean allParentCellsPlanted(World world, BreedingBellData bell,
        CropsNhBreedingCompatProvider compat, List<CellAssignment> assignments) {
        for (CellAssignment cell : assignments) {
            if (cell.role != CellRole.PARENT_SINGLE) continue;
            CropStickCellState state = compat
                .inspectCropStickCell(world, bell.centerX() + cell.dx, bell.cropY(), bell.centerZ() + cell.dz);
            if (state.kind != StickKind.PLANTED || !cell.desiredParentCropId.equals(state.cropId)) return false;
        }
        return true;
    }

    private static boolean hasUnplantedCrossCell(World world, BreedingBellData bell,
        CropsNhBreedingCompatProvider compat, List<CellAssignment> assignments) {
        int cropY = bell.cropY();
        for (CellAssignment cell : assignments) {
            if (cell.role != CellRole.CROSS_DOUBLE) continue;
            CropStickCellState st = compat
                .inspectCropStickCell(world, bell.centerX() + cell.dx, cropY, bell.centerZ() + cell.dz);
            if (st.kind != StickKind.PLANTED) return true;
        }
        return false;
    }

    private static boolean allParentCellsMeetTarget(World world, BreedingBellData bell, BreedingTaskConfigData config,
        CropsNhBreedingCompatProvider compat, TargetBreedingPlan plan, List<CellAssignment> assignments) {
        if (plan == null || plan.status != PlanStatus.READY_PURIFY) return false;
        int cropY = bell.cropY();
        boolean sawParent = false;
        for (CellAssignment cell : assignments) {
            if (cell.role != CellRole.PARENT_SINGLE) continue;
            sawParent = true;
            CropStickCellState st = compat
                .inspectCropStickCell(world, bell.centerX() + cell.dx, cropY, bell.centerZ() + cell.dz);
            if (st.kind != StickKind.PLANTED || !matchesConfiguredTarget(config, plan, st.cropId)
                || !config.meetsTargetStats(st.growth, st.gain, st.resistance)) {
                return false;
            }
        }
        return sawParent;
    }

    private static boolean shouldHarvestMatureParent(EntityMaid maid, World world, BreedingBellData bell,
        BreedingTaskConfigData config, CropsNhBreedingCompatProvider compat, List<CellAssignment> assignments,
        String cropId, boolean hasWaitingCrossCell, boolean allParentsMeetTarget) {
        if (cropId != null && !cropId.isEmpty() && peekBestSeedInspection(maid, config, compat, cropId) == null) {
            int cropY = bell.cropY();
            for (CellAssignment cell : assignments) {
                if (cell.role != CellRole.PARENT_SINGLE || !cropId.equals(cell.desiredParentCropId)) continue;
                CropStickCellState st = compat
                    .inspectCropStickCell(world, bell.centerX() + cell.dx, cropY, bell.centerZ() + cell.dz);
                if (st.kind != StickKind.PLANTED || !cropId.equals(st.cropId)) {
                    return true;
                }
            }
        }
        return !hasWaitingCrossCell || allParentsMeetTarget;
    }

    private static void aimAndSwing(EntityMaid maid, int x, int y, int z) {
        maid.getLookHelper()
            .setLookPosition(
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
            if (drop == null || drop.stackSize <= BreedingWorkTuning.ZERO) continue;
            ItemStack rem = inv.insertAvailable(drop, false);
            if (rem != null && rem.stackSize > BreedingWorkTuning.ZERO) {
                EntityItem item = maid.entityDropItem(rem, BreedingWorkTuning.DROP_OFFSET_Y);
                if (item != null) {
                    item.delayBeforeCanPickup = BreedingWorkTuning.ZERO;
                }
            }
        }
    }

    private static void returnItemToBackpackOrMaid(EntityMaid maid, int preferredBagSlot, ItemStack stack) {
        if (stack == null || stack.stackSize <= BreedingWorkTuning.ZERO) return;
        ItemStack remaining = stack;
        if (preferredBagSlot >= BreedingWorkTuning.ZERO) {
            MaidInventory inv = maid.getMaidInventory();
            ItemStack bag = inv.getStackInSlot(preferredBagSlot);
            BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
            if (bag != null && backpacks.isSupportedBackpack(bag)) {
                remaining = backpacks.insertIntoBackpack(bag, stack, false);
            }
        }
        if (remaining != null && remaining.stackSize > BreedingWorkTuning.ZERO) {
            storeDrops(maid, Collections.singletonList(remaining));
        }
    }

    private static void cleanupEmptySlots(MaidInventory inv) {
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize <= BreedingWorkTuning.ZERO) {
                inv.setInventorySlotContents(i, null);
            }
        }
        inv.markDirty();
    }

    private static boolean hasAccessibleStack(EntityMaid maid, Predicate<ItemStack> filter) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > BreedingWorkTuning.ZERO && s.getItem() != null && filter.test(s)) {
                return true;
            }
        }
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack bag = inv.getStackInSlot(i);
            if (bag == null || !backpacks.isSupportedBackpack(bag)) continue;
            ItemStack preview = backpacks.extractFromBackpack(
                bag,
                st -> st != null && st.stackSize > BreedingWorkTuning.ZERO && st.getItem() != null && filter.test(st),
                null,
                BreedingWorkTuning.ONE,
                true);
            if (preview != null) {
                return true;
            }
        }
        return false;
    }

    private static CheckedOutTool checkoutTool(EntityMaid maid, Predicate<ItemStack> filter) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > BreedingWorkTuning.ZERO && s.getItem() != null && filter.test(s)) {
                return new CheckedOutTool(i, BreedingWorkTuning.UNSET_SLOT, s);
            }
        }
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack bag = inv.getStackInSlot(i);
            if (bag == null || !backpacks.isSupportedBackpack(bag)) continue;
            ItemStack extracted = backpacks.extractFromBackpack(
                bag,
                st -> st != null && st.stackSize > BreedingWorkTuning.ZERO && st.getItem() != null && filter.test(st),
                null,
                BreedingWorkTuning.ONE,
                false);
            if (extracted != null) {
                inv.markDirty();
                return new CheckedOutTool(BreedingWorkTuning.UNSET_SLOT, i, extracted);
            }
        }
        return null;
    }

    private static boolean isQualifiedHarvestTool(ItemStack s, Block block, int meta, String reqTool, int reqLevel) {
        if (s == null || s.stackSize <= BreedingWorkTuning.ZERO || s.getItem() == null) return false;
        if (s.getItem()
            .canHarvestBlock(block, s)
            && (reqLevel <= BreedingWorkTuning.ZERO || ForgeHooks.canToolHarvestBlock(block, meta, s))) {
            return true;
        }
        Set<String> classes = s.getItem()
            .getToolClasses(s);
        return reqTool != null && classes != null
            && classes.contains(reqTool)
            && s.getItem()
                .getHarvestLevel(s, reqTool) >= reqLevel;
    }

    private static boolean isOptionalSpeedTool(ItemStack s, Block block, String reqTool) {
        if (s == null || s.stackSize <= BreedingWorkTuning.ZERO || s.getItem() == null || reqTool == null) {
            return false;
        }
        if (s.getItem()
            .canHarvestBlock(block, s)) {
            return true;
        }
        Set<String> classes = s.getItem()
            .getToolClasses(s);
        return classes != null && classes.contains(reqTool)
            && s.getItem()
                .getHarvestLevel(s, reqTool) >= BreedingWorkTuning.ZERO;
    }

    private static Set<String> collectAvailableCropIds(EntityMaid maid, BreedingBellData bell,
        CropsNhBreedingCompatProvider compat) {
        Set<String> ids = new LinkedHashSet<>();
        MaidInventory inv = maid.getMaidInventory();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= BreedingWorkTuning.ZERO) continue;
            SeedInspection insp = compat.inspectSeedOrAlternate(s);
            if (insp != null && !insp.cropId.isEmpty()) {
                ids.add(insp.cropId);
            }
            if (backpacks.isSupportedBackpack(s)) {
                backpacks.forEachExtractableStack(s, nested -> {
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

    private static boolean isMatchingCropSeed(CropsNhBreedingCompatProvider compat, ItemStack stack,
        String wantedCropId) {
        if (stack == null || stack.stackSize <= BreedingWorkTuning.ZERO
            || wantedCropId == null
            || wantedCropId.isEmpty()) {
            return false;
        }
        SeedInspection insp = compat.inspectSeedOrAlternate(stack);
        return insp != null && wantedCropId.equals(insp.cropId);
    }

    private static int scoreSeedStack(BreedingTaskConfigData config, CropsNhBreedingCompatProvider compat,
        ItemStack stack) {
        SeedInspection insp = compat.inspectSeedOrAlternate(stack);
        if (insp == null) return Integer.MIN_VALUE;
        return config.scoreSeed(insp.growth, insp.gain, insp.resistance);
    }

    private static SeedInspection peekBestSeedInspection(EntityMaid maid, BreedingTaskConfigData config,
        CropsNhBreedingCompatProvider compat, String wantedCropId) {
        MaidInventory inv = maid.getMaidInventory();
        SeedInspection bestInsp = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!isMatchingCropSeed(compat, s, wantedCropId)) continue;
            SeedInspection insp = compat.inspectSeedOrAlternate(s);
            int score = config.scoreSeed(insp.growth, insp.gain, insp.resistance);
            if (bestInsp == null || score > bestScore) {
                bestScore = score;
                bestInsp = insp;
            }
        }
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack bag = inv.getStackInSlot(i);
            if (bag == null || !backpacks.isSupportedBackpack(bag)) continue;
            ItemStack preview = backpacks.extractFromBackpack(
                bag,
                st -> isMatchingCropSeed(compat, st, wantedCropId),
                st -> scoreSeedStack(config, compat, st),
                BreedingWorkTuning.ONE,
                true);
            if (preview == null) continue;
            SeedInspection insp = compat.inspectSeedOrAlternate(preview);
            if (insp == null) continue;
            int score = config.scoreSeed(insp.growth, insp.gain, insp.resistance);
            if (bestInsp == null || score > bestScore) {
                bestScore = score;
                bestInsp = insp;
            }
        }
        return bestInsp;
    }

    private static boolean plantBestSeedOnStick(EntityMaid maid, World world, int x, int cropY, int z,
        BreedingTaskConfigData config, CropsNhBreedingCompatProvider compat, String wantedCropId) {
        MaidInventory inv = maid.getMaidInventory();
        int bestRawSlot = BreedingWorkTuning.UNSET_SLOT;
        int bestRawScore = Integer.MIN_VALUE;
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!isMatchingCropSeed(compat, s, wantedCropId)) continue;
            int score = scoreSeedStack(config, compat, s);
            if (bestRawSlot < BreedingWorkTuning.ZERO || score > bestRawScore) {
                bestRawScore = score;
                bestRawSlot = i;
            }
        }

        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        int bestBagSlot = BreedingWorkTuning.UNSET_SLOT;
        int bestBagScore = Integer.MIN_VALUE;
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack bag = inv.getStackInSlot(i);
            if (bag == null || !backpacks.isSupportedBackpack(bag)) continue;
            ItemStack preview = backpacks.extractFromBackpack(
                bag,
                st -> isMatchingCropSeed(compat, st, wantedCropId),
                st -> scoreSeedStack(config, compat, st),
                BreedingWorkTuning.ONE,
                true);
            if (preview == null) continue;
            int score = scoreSeedStack(config, compat, preview);
            if (bestBagSlot < BreedingWorkTuning.ZERO || score > bestBagScore) {
                bestBagScore = score;
                bestBagSlot = i;
            }
        }

        if (bestRawSlot < BreedingWorkTuning.ZERO && bestBagSlot < BreedingWorkTuning.ZERO) {
            return false;
        }
        if (bestBagSlot >= BreedingWorkTuning.ZERO
            && (bestRawSlot < BreedingWorkTuning.ZERO || bestBagScore > bestRawScore)) {
            ItemStack bag = inv.getStackInSlot(bestBagSlot);
            ItemStack extracted = backpacks.extractFromBackpack(
                bag,
                st -> isMatchingCropSeed(compat, st, wantedCropId),
                st -> scoreSeedStack(config, compat, st),
                BreedingWorkTuning.ONE,
                false);
            if (extracted == null) return false;
            boolean planted = compat.plantSeedOnStick(world, x, cropY, z, extracted);
            if (extracted.stackSize > BreedingWorkTuning.ZERO) {
                returnItemToBackpackOrMaid(maid, bestBagSlot, extracted);
            }
            inv.markDirty();
            return planted;
        }

        ItemStack rawStack = inv.getStackInSlot(bestRawSlot);
        boolean planted = compat.plantSeedOnStick(world, x, cropY, z, rawStack);
        if (planted) {
            cleanupEmptySlots(inv);
        }
        return planted;
    }

    private static boolean hasTillToolInAllStorage(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        return hasAccessibleStack(maid, s -> compat.isSpadeItem(s) || s.getItem() instanceof ItemHoe);
    }

    private static int countCropSticksInAllStorage(EntityMaid maid, CropsNhBreedingCompatProvider compat) {
        MaidInventory inv = maid.getMaidInventory();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        int[] total = new int[BreedingWorkTuning.ONE];
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (compat.isCropSticksItem(s)) {
                total[BreedingWorkTuning.ZERO] += s.stackSize;
            } else if (backpacks.isSupportedBackpack(s)) {
                backpacks.forEachExtractableStack(s, nested -> {
                    if (compat.isCropSticksItem(nested)) {
                        total[BreedingWorkTuning.ZERO] += nested.stackSize;
                    }
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
        return hasAccessibleStack(maid, s -> isMatchingSoilStack(s, governingCropId, hasTillTool, compat));
    }

    private static boolean isMatchingSoilStack(ItemStack s, String governingCropId, boolean hasTillTool,
        CropsNhBreedingCompatProvider compat) {
        if (s == null || s.stackSize <= BreedingWorkTuning.ZERO || !(s.getItem() instanceof ItemBlock ib)) {
            return false;
        }
        if (governingCropId.isEmpty() && (ib.field_150939_a == Blocks.dirt || ib.field_150939_a == Blocks.grass
            || ib.field_150939_a == Blocks.farmland)) {
            return true;
        }
        return compat.matchesRequiredSoilItem(governingCropId, s, hasTillTool);
    }

    private static boolean hasMatchingSubSoilBlock(EntityMaid maid, String governingCropId, List<String> crossParentIds,
        CropsNhBreedingCompatProvider compat) {
        return hasAccessibleStack(maid, s -> isMatchingSubSoilStack(s, governingCropId, crossParentIds, compat));
    }

    private static boolean isMatchingSubSoilStack(ItemStack s, String governingCropId, List<String> crossParentIds,
        CropsNhBreedingCompatProvider compat) {
        if (s == null || s.stackSize <= BreedingWorkTuning.ZERO || !(s.getItem() instanceof ItemBlock)) {
            return false;
        }
        return compat.matchesRequiredSubSoilItem(governingCropId, crossParentIds, s);
    }

    public static ItemStack findEquippedBellStack(EntityMaid maid) {
        if (maid == null) return null;
        MaidBaubleInventory baubles = maid.getMaidBaubleInventory();
        int unlocked = MaidBaubleLayout.unlockedSlots(
            maid.getFavorabilityManager()
                .getLevel());
        int limit = Math.min(unlocked, baubles.getSizeInventory());
        for (int i = 0; i < limit; i++) {
            ItemStack s = baubles.getStackInSlot(i);
            if (s != null && s.stackSize > BreedingWorkTuning.ZERO && s.getItem() instanceof BreedingBellItem) {
                return s;
            }
        }
        return null;
    }

    private static BreedingBellData findEquippedBellData(EntityMaid maid) {
        ItemStack s = findEquippedBellStack(maid);
        return s == null ? null : BreedingBellData.read(s);
    }

    private static final class CheckedOutTool {

        final int rawSlot;
        final int backpackSlot;
        final ItemStack stack;

        CheckedOutTool(int rawSlot, int backpackSlot, ItemStack stack) {
            this.rawSlot = rawSlot;
            this.backpackSlot = backpackSlot;
            this.stack = stack;
        }

        void finish(EntityMaid maid) {
            MaidInventory inv = maid.getMaidInventory();
            if (rawSlot >= BreedingWorkTuning.ZERO) {
                if (stack != null && stack.stackSize <= BreedingWorkTuning.ZERO) {
                    inv.setInventorySlotContents(rawSlot, null);
                }
                inv.markDirty();
                return;
            }
            if (backpackSlot >= BreedingWorkTuning.ZERO) {
                if (stack != null && stack.stackSize > BreedingWorkTuning.ZERO) {
                    returnItemToBackpackOrMaid(maid, backpackSlot, stack);
                }
                inv.markDirty();
            }
        }
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
