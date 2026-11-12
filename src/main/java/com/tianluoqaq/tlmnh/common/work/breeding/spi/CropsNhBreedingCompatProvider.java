package com.tianluoqaq.tlmnh.common.work.breeding.spi;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** CropsNH 作物田校验、世界育种候选检索、土壤底矿检查与作物架交互窄接口 */
public interface CropsNhBreedingCompatProvider {

    CropsNhBreedingCompatProvider NOOP = new CropsNhBreedingCompatProvider() {};

    enum PlotIssue {
        NONE,
        UNLOADED_CHUNK,
        INVALID_SOIL,
        ABOVE_BLOCKED
    }

    final class PlotValidationResult {

        public final boolean valid;
        public final int badX;
        public final int badY;
        public final int badZ;
        public final PlotIssue issue;

        public PlotValidationResult(boolean valid, int badX, int badY, int badZ, PlotIssue issue) {
            this.valid = valid;
            this.badX = badX;
            this.badY = badY;
            this.badZ = badZ;
            this.issue = issue;
        }
    }

    final class BreedingCandidateEntry {

        public final String id;
        public final String unlocalizedName;
        public final ItemStack iconStack;

        public BreedingCandidateEntry(String id, String unlocalizedName, ItemStack iconStack) {
            this.id = id;
            this.unlocalizedName = unlocalizedName;
            this.iconStack = iconStack;
        }
    }

    final class SeedInspection {

        public final String cropId;
        public final String unlocalizedName;
        public final int growth;
        public final int gain;
        public final int resistance;
        public final boolean analyzed;
        public final boolean alternateSeed;

        public SeedInspection(String cropId, String unlocalizedName, int growth, int gain, int resistance,
            boolean analyzed, boolean alternateSeed) {
            this.cropId = cropId;
            this.unlocalizedName = unlocalizedName;
            this.growth = growth;
            this.gain = gain;
            this.resistance = resistance;
            this.analyzed = analyzed;
            this.alternateSeed = alternateSeed;
        }
    }

    enum PlanStatus {
        READY_PURIFY,
        READY_DETERMINISTIC_CROSS,
        READY_POOL_CROSS,
        MISSING_TARGET,
        UNSUPPORTED_IN_WORLD,
        MISSING_PARENTS
    }

    final class TargetBreedingPlan {

        public final PlanStatus status;
        public final String targetUnlocalizedName;
        public final List<String> parentCropIds;
        public final List<String> suggestedParentNames;

        public TargetBreedingPlan(PlanStatus status, String targetUnlocalizedName, List<String> parentCropIds,
            List<String> suggestedParentNames) {
            this.status = status;
            this.targetUnlocalizedName = targetUnlocalizedName;
            this.parentCropIds = parentCropIds;
            this.suggestedParentNames = suggestedParentNames;
        }
    }

    final class CellFoundationCheck {

        public final boolean soilOk;
        public final boolean subSoilOk;
        public final boolean requiresFarmlandTilling;
        public final String soilDescriptionKey;
        public final String subSoilDescription;

        public CellFoundationCheck(boolean soilOk, boolean subSoilOk, boolean requiresFarmlandTilling,
            String soilDescriptionKey, String subSoilDescription) {
            this.soilOk = soilOk;
            this.subSoilOk = subSoilOk;
            this.requiresFarmlandTilling = requiresFarmlandTilling;
            this.soilDescriptionKey = soilDescriptionKey;
            this.subSoilDescription = subSoilDescription;
        }
    }

    enum StickKind {
        EMPTY_SPACE,
        SINGLE_EMPTY,
        CROSS_EMPTY,
        WEED,
        PLANTED,
        OBSTRUCTED
    }

    final class CropStickCellState {

        public final StickKind kind;
        public final String cropId;
        public final int growth;
        public final int gain;
        public final int resistance;
        public final boolean mature;
        public final boolean canHarvest;
        public final boolean sick;
        public final boolean growthRequirementsMet;

        public CropStickCellState(StickKind kind, String cropId, int growth, int gain, int resistance, boolean mature,
            boolean canHarvest, boolean sick, boolean growthRequirementsMet) {
            this.kind = kind;
            this.cropId = cropId;
            this.growth = growth;
            this.gain = gain;
            this.resistance = resistance;
            this.mature = mature;
            this.canHarvest = canHarvest;
            this.sick = sick;
            this.growthRequirementsMet = growthRequirementsMet;
        }

        public static CropStickCellState nonPlanted(StickKind kind, boolean mature, boolean growthRequirementsMet) {
            return new CropStickCellState(
                kind,
                "",
                BreedingSpiTuning.NO_STAT,
                BreedingSpiTuning.NO_STAT,
                BreedingSpiTuning.NO_STAT,
                mature,
                false,
                false,
                growthRequirementsMet);
        }
    }

    default boolean isAvailable() {
        return false;
    }

    default ItemStack taskIcon() {
        return null;
    }

    default ItemStack createCropSticksStack(int count) {
        return null;
    }

    default boolean isCropSticksBlock(World world, int x, int y, int z) {
        return false;
    }

    default boolean isCropSticksItem(ItemStack stack) {
        return false;
    }

    default boolean isSpadeItem(ItemStack stack) {
        return false;
    }

    default PlotValidationResult validateBreedingPlot(World world, int centerX, int soilY, int centerZ) {
        return new PlotValidationResult(false, centerX, soilY, centerZ, PlotIssue.INVALID_SOIL);
    }

    default List<BreedingCandidateEntry> listInWorldCandidates(boolean mutationPools) {
        return Collections.emptyList();
    }

    default SeedInspection inspectSeedOrAlternate(ItemStack stack) {
        return null;
    }

    default TargetBreedingPlan resolveTargetPlan(boolean mutationPoolMode, String targetId,
        Collection<String> availableCropIds) {
        return new TargetBreedingPlan(
            PlanStatus.UNSUPPORTED_IN_WORLD,
            "",
            Collections.emptyList(),
            Collections.emptyList());
    }

    default CellFoundationCheck checkCellFoundation(World world, int x, int soilY, int z, String governingCropId,
        List<String> crossParentIds) {
        return new CellFoundationCheck(true, true, false, "", "");
    }

    default boolean matchesRequiredSoilItem(String governingCropId, ItemStack candidate, boolean hasTillTool) {
        return false;
    }

    default boolean matchesRequiredSubSoilItem(String governingCropId, List<String> crossParentIds,
        ItemStack candidate) {
        return false;
    }

    default boolean tillSoilIfApplicable(World world, int x, int soilY, int z) {
        return false;
    }

    default CropStickCellState inspectCropStickCell(World world, int x, int cropY, int z) {
        return CropStickCellState.nonPlanted(StickKind.OBSTRUCTED, false, false);
    }

    default boolean placeOrUpgradeCropStick(World world, int x, int cropY, int z, ItemStack sticksStack,
        boolean crossCrop) {
        return false;
    }

    default boolean plantSeedOnStick(World world, int x, int cropY, int z, ItemStack seedStack) {
        return false;
    }

    default List<ItemStack> spadeHarvestKeepAlive(World world, int x, int cropY, int z, ItemStack spadeStack) {
        return Collections.emptyList();
    }

    default List<ItemStack> spadeClearWeedOrUproot(World world, int x, int cropY, int z, ItemStack spadeStack,
        boolean breakSticksToo) {
        return Collections.emptyList();
    }

    /** 仅限 QA 原生测试夹具加速真实生长周期使用，生产育种循环严禁调用 */
    default void tickGrowthAcceleration(World world, int x, int cropY, int z, int extraGrowthTicks) {}
}
