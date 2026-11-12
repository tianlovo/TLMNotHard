package com.tianluoqaq.tlmnh.compat.cropsnh.common.breeding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.compat.cropsnh.CropsNhCompat;
import com.tianluoqaq.tlmnh.compat.cropsnh.CropsNhIds;

import cpw.mods.fml.relauncher.Side;

/** CropsNH 2.0.140 世界育种全链路方法句柄适配实现 */
public final class CropsNhBreedingCompat implements CropsNhBreedingCompatProvider {

    public static final CropsNhBreedingCompat INSTANCE = new CropsNhBreedingCompat();

    private CropsNhBreedingCompat() {}

    @Override
    public boolean isAvailable() {
        return OptionalApi.isModLoaded(CropsNhCompat.MOD_ID)
            && IntegrationGuard.available(Side.SERVER, CropsNhIds.GUARD);
    }

    @Override
    public ItemStack taskIcon() {
        if (!OptionalApi.isModLoaded(CropsNhCompat.MOD_ID)) return null;
        try {
            CropsNhApi api = CropsNhApi.get();
            return (ItemStack) api.itemContainerGet
                .invoke(api.cropSticksEnum, CropsNhTuning.ICON_AMOUNT, new Object[CropsNhTuning.ZERO_META]);
        } catch (Throwable failure) {
            IntegrationGuard.report(CropsNhIds.GUARD, failure);
            return null;
        }
    }

    @Override
    public ItemStack createCropSticksStack(int count) {
        if (!isAvailable() || count <= CropsNhTuning.ZERO_META) return null;
        try {
            CropsNhApi api = CropsNhApi.get();
            return (ItemStack) api.itemContainerGet
                .invoke(api.cropSticksEnum, (long) count, new Object[CropsNhTuning.ZERO_META]);
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return null;
        }
    }

    @Override
    public boolean isCropSticksBlock(World world, int x, int y, int z) {
        if (!isAvailable() || world == null) return false;
        try {
            CropsNhApi api = CropsNhApi.get();
            if (!chunkLoaded(world, x, z)) return false;
            return world.getBlock(x, y, z) == api.blockCropSticks
                || api.cropStickTileClass.isInstance(world.getTileEntity(x, y, z));
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean isCropSticksItem(ItemStack stack) {
        if (!isAvailable() || stack == null || stack.getItem() == null) return false;
        try {
            return CropsNhApi.get().itemCropSticksClass.isInstance(stack.getItem());
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean isSpadeItem(ItemStack stack) {
        if (!isAvailable() || stack == null || stack.getItem() == null) return false;
        try {
            return CropsNhApi.get().itemSpadeNhClass.isInstance(stack.getItem());
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public PlotValidationResult validateBreedingPlot(World world, int centerX, int soilY, int centerZ) {
        if (!isAvailable() || world == null) {
            return new PlotValidationResult(false, centerX, soilY, centerZ, PlotIssue.INVALID_SOIL);
        }
        try {
            CropsNhApi api = CropsNhApi.get();
            int r = CropsNhTuning.PLOT_RADIUS;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (!chunkLoaded(world, x, z)) {
                        return new PlotValidationResult(false, x, soilY, z, PlotIssue.UNLOADED_CHUNK);
                    }
                    Block soil = world.getBlock(x, soilY, z);
                    int meta = world.getBlockMetadata(x, soilY, z);
                    boolean registered = (Boolean) api.soilRegIsRegistered.invoke(api.soilRegistry, soil, meta);
                    boolean tillable = api.tillableBlocks.containsKey(soil) || soil == Blocks.dirt
                        || soil == Blocks.grass;
                    if (!registered && !tillable) {
                        return new PlotValidationResult(false, x, soilY, z, PlotIssue.INVALID_SOIL);
                    }
                    int cropY = soilY + CropsNhTuning.ABOVE_CROP_OFFSET_Y;
                    Block above = world.getBlock(x, cropY, z);
                    boolean aboveClear = world.isAirBlock(x, cropY, z) || above == api.blockCropSticks
                        || api.cropStickTileClass.isInstance(world.getTileEntity(x, cropY, z))
                        || above.isReplaceable(world, x, cropY, z);
                    if (!aboveClear) {
                        return new PlotValidationResult(false, x, cropY, z, PlotIssue.ABOVE_BLOCKED);
                    }
                }
            }
            return new PlotValidationResult(true, centerX, soilY, centerZ, PlotIssue.NONE);
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return new PlotValidationResult(false, centerX, soilY, centerZ, PlotIssue.INVALID_SOIL);
        }
    }

    @Override
    public List<BreedingCandidateEntry> listInWorldCandidates(boolean mutationPools) {
        if (!OptionalApi.isModLoaded(CropsNhCompat.MOD_ID)) return Collections.emptyList();
        try {
            CropsNhApi api = CropsNhApi.get();
            List<BreedingCandidateEntry> result = new ArrayList<>();
            if (!mutationPools) {
                Collection<?> all = (Collection<?>) api.cropRegGetAll.invoke(api.cropRegistry);
                if (all == null) return Collections.emptyList();
                for (Object crop : all) {
                    if (!isWorldGrowableAndCrossable(api, crop)) continue;
                    String id = (String) api.cropGetId.invoke(crop);
                    String unloc = (String) api.cropGetUnlocalizedName.invoke(crop);
                    ItemStack icon = (ItemStack) api.cropGetSeedItem.invoke(crop, api.defaultAnalyzedStats);
                    result.add(new BreedingCandidateEntry(id, unloc, icon));
                }
            } else {
                Collection<?> pools = (Collection<?>) api.mutRegPools.invoke(api.mutationRegistry);
                if (pools == null) return Collections.emptyList();
                for (Object pool : pools) {
                    Collection<?> members = (Collection<?>) api.poolGetMembers.invoke(pool);
                    if (members == null) continue;
                    int eligibleCount = CropsNhTuning.ZERO_META;
                    ItemStack firstIcon = null;
                    for (Object member : members) {
                        if (!isWorldGrowableAndCrossable(api, member)) continue;
                        float breedThreshold = (Float) api.cropGetBreedingThreshold.invoke(member);
                        if (breedThreshold < CropsNhTuning.MIN_ALLOWED_THRESHOLD) continue;
                        eligibleCount++;
                        if (firstIcon == null) {
                            firstIcon = (ItemStack) api.cropGetSeedItem.invoke(member, api.defaultAnalyzedStats);
                        }
                    }
                    if (eligibleCount >= CropsNhTuning.MIN_POOL_PARENTS) {
                        String poolKey = (String) api.poolGetUnlocalisedName.invoke(pool);
                        result.add(new BreedingCandidateEntry(poolKey, poolKey, firstIcon));
                    }
                }
            }
            return result;
        } catch (Throwable failure) {
            IntegrationGuard.report(CropsNhIds.GUARD, failure);
            return Collections.emptyList();
        }
    }

    @Override
    public SeedInspection inspectSeedOrAlternate(ItemStack stack) {
        if (!isAvailable() || stack == null || stack.getItem() == null) return null;
        try {
            CropsNhApi api = CropsNhApi.get();
            Object seedData = api.utilsGetSeedData.invoke(stack, true, false);
            if (seedData == null) return null;
            Object crop = api.seedDataGetCrop.invoke(seedData);
            Object stats = api.seedDataGetStats.invoke(seedData);
            if (crop == null || stats == null) return null;
            String id = (String) api.cropGetId.invoke(crop);
            String unloc = (String) api.cropGetUnlocalizedName.invoke(crop);
            int growth = (Byte) api.statsGetGrowth.invoke(stats);
            int gain = (Byte) api.statsGetGain.invoke(stats);
            int resistance = (Byte) api.statsGetResistance.invoke(stats);
            boolean analyzed = (Boolean) api.statsIsAnalyzed.invoke(stats);
            boolean alt = (Boolean) api.cropRegIsAltSeed.invoke(api.cropRegistry, stack);
            return new SeedInspection(id, unloc, growth, gain, resistance, analyzed, alt);
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return null;
        }
    }

    @Override
    public TargetBreedingPlan resolveTargetPlan(boolean mutationPoolMode, String targetId,
        Collection<String> availableCropIds) {
        if (targetId == null || targetId.isEmpty()) {
            return new TargetBreedingPlan(
                PlanStatus.MISSING_TARGET,
                "",
                Collections.emptyList(),
                Collections.emptyList());
        }
        if (!isAvailable()) {
            return new TargetBreedingPlan(
                PlanStatus.UNSUPPORTED_IN_WORLD,
                targetId,
                Collections.emptyList(),
                Collections.emptyList());
        }
        try {
            CropsNhApi api = CropsNhApi.get();
            Set<String> available = availableCropIds == null ? Collections.emptySet() : new HashSet<>(availableCropIds);
            if (mutationPoolMode) {
                return resolvePoolPlan(api, targetId, available);
            }
            Object targetCrop = api.cropRegGetById.invoke(api.cropRegistry, targetId);
            if (targetCrop == null) {
                return new TargetBreedingPlan(
                    PlanStatus.UNSUPPORTED_IN_WORLD,
                    targetId,
                    Collections.emptyList(),
                    Collections.emptyList());
            }
            String targetUnloc = (String) api.cropGetUnlocalizedName.invoke(targetCrop);
            if (!isWorldGrowableAndCrossable(api, targetCrop)) {
                return new TargetBreedingPlan(
                    PlanStatus.UNSUPPORTED_IN_WORLD,
                    targetUnloc,
                    Collections.emptyList(),
                    Collections.emptyList());
            }
            if (available.contains(targetId)) {
                return new TargetBreedingPlan(
                    PlanStatus.READY_PURIFY,
                    targetUnloc,
                    Collections.singletonList(targetId),
                    Collections.emptyList());
            }
            List<List<Object>> worldMutations = collectWorldDeterministicMutations(api, targetCrop);
            List<String> firstSuggested = new ArrayList<>();
            for (List<Object> parents : worldMutations) {
                List<String> ids = new ArrayList<>();
                List<String> unlocs = new ArrayList<>();
                boolean allPresent = true;
                for (Object p : parents) {
                    String pid = (String) api.cropGetId.invoke(p);
                    ids.add(pid);
                    unlocs.add((String) api.cropGetUnlocalizedName.invoke(p));
                    if (!available.contains(pid)) allPresent = false;
                }
                if (firstSuggested.isEmpty()) firstSuggested.addAll(unlocs);
                if (allPresent) {
                    return new TargetBreedingPlan(
                        PlanStatus.READY_DETERMINISTIC_CROSS,
                        targetUnloc,
                        ids,
                        Collections.emptyList());
                }
            }
            List<Object> matchingPools = collectWorldPoolsContaining(api, targetCrop);
            for (Object pool : matchingPools) {
                List<String> chosen = pickAvailablePoolParents(api, pool, available);
                if (chosen.size() >= CropsNhTuning.MIN_POOL_PARENTS) {
                    return new TargetBreedingPlan(
                        PlanStatus.READY_POOL_CROSS,
                        targetUnloc,
                        chosen,
                        Collections.emptyList());
                }
                if (firstSuggested.isEmpty()) {
                    firstSuggested.add((String) api.poolGetUnlocalisedName.invoke(pool));
                }
            }
            if (worldMutations.isEmpty() && matchingPools.isEmpty()) {
                return new TargetBreedingPlan(
                    PlanStatus.UNSUPPORTED_IN_WORLD,
                    targetUnloc,
                    Collections.emptyList(),
                    Collections.emptyList());
            }
            return new TargetBreedingPlan(
                PlanStatus.MISSING_PARENTS,
                targetUnloc,
                Collections.emptyList(),
                firstSuggested);
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return new TargetBreedingPlan(
                PlanStatus.UNSUPPORTED_IN_WORLD,
                targetId,
                Collections.emptyList(),
                Collections.emptyList());
        }
    }

    @Override
    public CellFoundationCheck checkCellFoundation(World world, int x, int soilY, int z, String governingCropId,
        List<String> crossParentIds) {
        if (!isAvailable() || world == null || governingCropId == null || governingCropId.isEmpty()) {
            return new CellFoundationCheck(true, true, false, "", "");
        }
        try {
            CropsNhApi api = CropsNhApi.get();
            Object crop = resolveCropOrPoolRepresentative(api, governingCropId, crossParentIds);
            if (crop == null) return new CellFoundationCheck(true, true, false, "", "");
            Object soilList = api.cropGetSoilTypes.invoke(crop);
            Block soilBlock = world.getBlock(x, soilY, z);
            int soilMeta = world.getBlockMetadata(x, soilY, z);
            boolean soilOk = soilList == null
                || (Boolean) api.soilListIsRegistered.invoke(soilList, soilBlock, soilMeta);
            boolean needsTilling = false;
            String soilTooltip = soilList == null ? "" : (String) api.soilListTooltip.invoke(soilList);
            if (!soilOk && soilList != null) {
                boolean acceptsFarmland = (Boolean) api.soilListIsRegistered
                    .invoke(soilList, Blocks.farmland, CropsNhTuning.ZERO_META);
                boolean tillableHere = api.tillableBlocks.containsKey(soilBlock) || soilBlock == Blocks.dirt
                    || soilBlock == Blocks.grass;
                if (acceptsFarmland && tillableHere) {
                    needsTilling = true;
                }
            }

            int subY = soilY - CropsNhTuning.SUBSOIL_OFFSET_Y;
            Block subBlock = world.getBlock(x, subY, z);
            int subMeta = world.getBlockMetadata(x, subY, z);
            TileEntity subTe = world.getTileEntity(x, subY, z);
            List<Object> subReqs = collectSubSoilRequirements(api, crop, crossParentIds);
            boolean subOk = true;
            String subDesc = "";
            for (Object req : subReqs) {
                boolean ok = (Boolean) api.subSoilCanGrowBlock.invoke(req, subBlock, subMeta, subTe);
                if (!ok) {
                    subOk = false;
                    subDesc = (String) api.reqGetDesc.invoke(req);
                    break;
                }
            }
            return new CellFoundationCheck(soilOk, subOk, needsTilling, soilTooltip, subDesc);
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return new CellFoundationCheck(true, true, false, "", "");
        }
    }

    @Override
    public boolean matchesRequiredSoilItem(String governingCropId, ItemStack candidate, boolean hasTillTool) {
        if (!isAvailable() || candidate == null || !(candidate.getItem() instanceof ItemBlock itemBlock)) {
            return false;
        }
        try {
            CropsNhApi api = CropsNhApi.get();
            Object crop = resolveCropOrPoolRepresentative(api, governingCropId, Collections.emptyList());
            if (crop == null) return false;
            Object soilList = api.cropGetSoilTypes.invoke(crop);
            if (soilList == null) return false;
            Block block = itemBlock.field_150939_a;
            int meta = itemBlock.getMetadata(candidate.getItemDamage());
            if ((Boolean) api.soilListIsRegistered.invoke(soilList, block, meta)) return true;
            if (hasTillTool
                && (Boolean) api.soilListIsRegistered.invoke(soilList, Blocks.farmland, CropsNhTuning.ZERO_META)) {
                return api.tillableBlocks.containsKey(block) || block == Blocks.dirt || block == Blocks.grass;
            }
            return false;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean matchesRequiredSubSoilItem(String governingCropId, List<String> crossParentIds,
        ItemStack candidate) {
        if (!isAvailable() || candidate == null || candidate.getItem() == null) return false;
        try {
            CropsNhApi api = CropsNhApi.get();
            Object crop = resolveCropOrPoolRepresentative(api, governingCropId, crossParentIds);
            if (crop == null) return false;
            List<Object> subReqs = collectSubSoilRequirements(api, crop, crossParentIds);
            if (subReqs.isEmpty()) return false;
            for (Object req : subReqs) {
                if (!(Boolean) api.subSoilIsValidStack.invoke(req, candidate)) return false;
            }
            return true;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean tillSoilIfApplicable(World world, int x, int soilY, int z) {
        if (!isAvailable() || world == null || world.isRemote) return false;
        try {
            CropsNhApi api = CropsNhApi.get();
            int cropY = soilY + CropsNhTuning.ABOVE_CROP_OFFSET_Y;
            if (!world.isAirBlock(x, cropY, z)) return false;
            Block current = world.getBlock(x, soilY, z);
            Block target = (Block) api.tillableBlocks.get(current);
            if (target == null && (current == Blocks.dirt || current == Blocks.grass)) {
                target = Blocks.farmland;
            }
            if (target == null) return false;
            world.setBlock(x, soilY, z, target, CropsNhTuning.ZERO_META, CropsNhTuning.BLOCK_UPDATE_FLAGS);
            world.playSoundEffect(
                x + CropsNhTuning.SOUND_HALF_OFFSET,
                soilY + CropsNhTuning.SOUND_HALF_OFFSET,
                z + CropsNhTuning.SOUND_HALF_OFFSET,
                Blocks.farmland.stepSound.getStepResourcePath(),
                (Blocks.farmland.stepSound.getVolume() + CropsNhTuning.SOUND_VOL_ADD) / CropsNhTuning.SOUND_VOL_DIV,
                Blocks.farmland.stepSound.getPitch() * CropsNhTuning.SOUND_PITCH_SCALE);
            return true;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public CropStickCellState inspectCropStickCell(World world, int x, int cropY, int z) {
        if (!isAvailable() || world == null || !chunkLoaded(world, x, z)) {
            return CropStickCellState.nonPlanted(StickKind.OBSTRUCTED, false, false);
        }
        try {
            CropsNhApi api = CropsNhApi.get();
            TileEntity te = world.getTileEntity(x, cropY, z);
            if (!api.cropStickTileClass.isInstance(te)) {
                Block block = world.getBlock(x, cropY, z);
                boolean replaceable = world.isAirBlock(x, cropY, z) || block.isReplaceable(world, x, cropY, z);
                StickKind kind = replaceable ? StickKind.EMPTY_SPACE : StickKind.OBSTRUCTED;
                return CropStickCellState.nonPlanted(kind, false, false);
            }
            if ((Boolean) api.tileHasWeed.invoke(te)) {
                boolean matureWeed = (Boolean) api.tileIsMature.invoke(te);
                return CropStickCellState.nonPlanted(StickKind.WEED, matureWeed, true);
            }
            if ((Boolean) api.tileHasCrop.invoke(te)) {
                Object seed = api.tileGetSeed.invoke(te);
                Object crop = api.seedDataGetCrop.invoke(seed);
                Object stats = api.seedDataGetStats.invoke(seed);
                String id = (String) api.cropGetId.invoke(crop);
                int gr = (Byte) api.statsGetGrowth.invoke(stats);
                int ga = (Byte) api.statsGetGain.invoke(stats);
                int re = (Byte) api.statsGetResistance.invoke(stats);
                boolean mature = (Boolean) api.tileIsMature.invoke(te);
                boolean canHarv = (Boolean) api.tileCanHarvest.invoke(te);
                boolean sick = (Boolean) api.tileIsSick.invoke(te);
                boolean reqsMet = (Boolean) api.tileAreReqsMet.invoke(te);
                return new CropStickCellState(StickKind.PLANTED, id, gr, ga, re, mature, canHarv, sick, reqsMet);
            }
            boolean cross = (Boolean) api.tileIsCrossCrop.invoke(te);
            StickKind emptyKind = cross ? StickKind.CROSS_EMPTY : StickKind.SINGLE_EMPTY;
            return CropStickCellState.nonPlanted(emptyKind, false, true);
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return CropStickCellState.nonPlanted(StickKind.OBSTRUCTED, false, false);
        }
    }

    @Override
    public boolean placeOrUpgradeCropStick(World world, int x, int cropY, int z, ItemStack sticksStack,
        boolean crossCrop) {
        if (!isAvailable() || world == null || world.isRemote || !isCropSticksItem(sticksStack)) return false;
        if (sticksStack.stackSize <= CropsNhTuning.ZERO_META) return false;
        try {
            CropsNhApi api = CropsNhApi.get();
            TileEntity existing = world.getTileEntity(x, cropY, z);
            if (!api.cropStickTileClass.isInstance(existing)) {
                if (!api.blockCropSticks.canPlaceBlockAt(world, x, cropY, z)) return false;
                if (!world.setBlock(
                    x,
                    cropY,
                    z,
                    api.blockCropSticks,
                    CropsNhTuning.ZERO_META,
                    CropsNhTuning.BLOCK_UPDATE_FLAGS)) {
                    return false;
                }
                sticksStack.stackSize--;
                existing = world.getTileEntity(x, cropY, z);
                if (crossCrop && sticksStack.stackSize > CropsNhTuning.ZERO_META
                    && api.cropStickTileClass.isInstance(existing)
                    && (Boolean) api.tileCanUpgrade.invoke(existing)) {
                    api.tileSetCrossCrop.invoke(existing, true);
                    api.tilePlayCrossSound.invoke(existing);
                    sticksStack.stackSize--;
                }
                world.markBlockForUpdate(x, cropY, z);
                return true;
            }
            if (crossCrop && (Boolean) api.tileCanUpgrade.invoke(existing)) {
                api.tileSetCrossCrop.invoke(existing, true);
                api.tilePlayCrossSound.invoke(existing);
                sticksStack.stackSize--;
                world.markBlockForUpdate(x, cropY, z);
                return true;
            }
            return false;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean plantSeedOnStick(World world, int x, int cropY, int z, ItemStack seedStack) {
        if (!isAvailable() || world == null || world.isRemote || seedStack == null) return false;
        if (seedStack.stackSize <= CropsNhTuning.ZERO_META) return false;
        try {
            CropsNhApi api = CropsNhApi.get();
            TileEntity te = world.getTileEntity(x, cropY, z);
            if (!api.cropStickTileClass.isInstance(te)) return false;
            Object pair = api.tileTryPlantSeed.invoke(te, seedStack);
            if (pair == null) return false;
            Object status = api.pairGetLeft.invoke(pair);
            if (status != api.plantingSuccessEnum) return false;
            seedStack.stackSize--;
            api.tilePlayPlantSound.invoke(te);
            api.tileAreReqsMet.invoke(te);
            api.tileUpdateState.invoke(te);
            return true;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public List<ItemStack> spadeHarvestKeepAlive(World world, int x, int cropY, int z, ItemStack spadeStack) {
        if (!isAvailable() || world == null || world.isRemote || !isSpadeItem(spadeStack)) {
            return Collections.emptyList();
        }
        try {
            CropsNhApi api = CropsNhApi.get();
            TileEntity te = world.getTileEntity(x, cropY, z);
            if (!api.cropStickTileClass.isInstance(te) || !(Boolean) api.tileHasCrop.invoke(te)) {
                return Collections.emptyList();
            }
            if ((Boolean) api.tileHasWeed.invoke(te) || !(Boolean) api.tileIsMature.invoke(te)) {
                return Collections.emptyList();
            }
            Object seed = api.tileGetSeed.invoke(te);
            Object crop = api.seedDataGetCrop.invoke(seed);
            if (api.cropMigratorClass.isInstance(crop)) return Collections.emptyList();
            List<ItemStack> drops = new ArrayList<>();
            if ((Boolean) api.tileCanHarvest.invoke(te)) {
                Collection<?> raw = (Collection<?>) api.tileHarvest.invoke(te, CropsNhTuning.FULL_HARVEST_MULTIPLIER);
                if (raw != null) {
                    for (Object obj : raw) {
                        if (obj instanceof ItemStack stack && stack.stackSize > CropsNhTuning.ZERO_META) {
                            drops.add(stack);
                        }
                    }
                }
                api.harvestDropModify.invoke(spadeStack.getItem(), null, spadeStack, drops, te);
                api.tilePlayHarvestSound.invoke(te);
                api.tileUpdateState.invoke(te);
                return drops;
            }
            if ((Boolean) api.tileAreReqsMet.invoke(te)) {
                api.harvestDropModify.invoke(spadeStack.getItem(), null, spadeStack, drops, te);
                api.tileSetGrowthProgress.invoke(te, CropsNhTuning.ZERO_PROGRESS);
                api.tilePlayHarvestSound.invoke(te);
                api.tileUpdateState.invoke(te);
            }
            return drops;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return Collections.emptyList();
        }
    }

    @Override
    public List<ItemStack> spadeClearWeedOrUproot(World world, int x, int cropY, int z, ItemStack spadeStack,
        boolean breakSticksToo) {
        if (!isAvailable() || world == null || world.isRemote) return Collections.emptyList();
        try {
            CropsNhApi api = CropsNhApi.get();
            TileEntity te = world.getTileEntity(x, cropY, z);
            if (!api.cropStickTileClass.isInstance(te)) return Collections.emptyList();
            List<ItemStack> collected = new ArrayList<>();
            if ((Boolean) api.tileHasWeed.invoke(te)) {
                api.tileClear.invoke(te);
                api.tilePlayRemovalSound.invoke(te);
            } else if ((Boolean) api.tileHasCrop.invoke(te)) {
                if (isSpadeItem(spadeStack) && (Boolean) api.tileCanHarvest.invoke(te)) {
                    Collection<?> raw = (Collection<?>) api.tileHarvest
                        .invoke(te, CropsNhTuning.FULL_HARVEST_MULTIPLIER);
                    if (raw != null) {
                        for (Object obj : raw) {
                            if (obj instanceof ItemStack s && s.stackSize > CropsNhTuning.ZERO_META) {
                                collected.add(s);
                            }
                        }
                    }
                    api.harvestDropModify.invoke(spadeStack.getItem(), null, spadeStack, collected, te);
                } else {
                    ItemStack seedDrop = (ItemStack) api.tileGetSeedDrop.invoke(te);
                    if (seedDrop != null && seedDrop.stackSize > CropsNhTuning.ZERO_META) {
                        collected.add(seedDrop);
                    }
                }
                api.tileClear.invoke(te);
                api.tilePlayRemovalSound.invoke(te);
            }
            if (breakSticksToo) {
                Collection<?> stickDrops = (Collection<?>) api.tileGetBreakItems.invoke(te);
                if (stickDrops != null) {
                    for (Object obj : stickDrops) {
                        if (obj instanceof ItemStack s && s.stackSize > CropsNhTuning.ZERO_META) {
                            collected.add(s);
                        }
                    }
                }
                world.setBlockToAir(x, cropY, z);
                world.removeTileEntity(x, cropY, z);
            } else {
                api.tileUpdateState.invoke(te);
            }
            return collected;
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
            return Collections.emptyList();
        }
    }

    @Override
    public void tickGrowthAcceleration(World world, int x, int cropY, int z, int extraGrowthTicks) {
        if (!isAvailable() || world == null || world.isRemote || extraGrowthTicks <= CropsNhTuning.ZERO_META) return;
        try {
            CropsNhApi api = CropsNhApi.get();
            TileEntity te = world.getTileEntity(x, cropY, z);
            if (!api.cropStickTileClass.isInstance(te)) return;
            for (int i = 0; i < extraGrowthTicks; i++) {
                if ((Boolean) api.tileHasWeed.invoke(te)) break;
                if ((Boolean) api.tileIsSick.invoke(te)) {
                    api.tileCureDisease.invoke(te);
                }
                api.tileOnGrowthTick.invoke(te);
            }
        } catch (Throwable failure) {
            IntegrationGuard.report(Side.SERVER, CropsNhIds.GUARD, failure);
        }
    }

    private static boolean chunkLoaded(World world, int blockX, int blockZ) {
        IChunkProvider provider = world.getChunkProvider();
        int cx = blockX >> CropsNhTuning.BLOCK_TO_CHUNK_SHIFT;
        int cz = blockZ >> CropsNhTuning.BLOCK_TO_CHUNK_SHIFT;
        return provider != null && provider.chunkExists(cx, cz);
    }

    private static boolean isWorldGrowableAndCrossable(CropsNhApi api, Object crop) throws Throwable {
        if (crop == null || api.cropMigratorClass.isInstance(crop)) return false;
        if ((Boolean) api.cropHideFromNei.invoke(crop)) return false;
        if (api.cropGetSoilTypes.invoke(crop) == null) return false;
        float crossThreshold = (Float) api.cropGetCrossingThreshold.invoke(crop);
        if (crossThreshold < CropsNhTuning.MIN_ALLOWED_THRESHOLD) return false;
        Collection<?> reqs = (Collection<?>) api.cropGetGrowthReqs.invoke(crop);
        if (reqs != null) {
            for (Object req : reqs) {
                if (api.migratedGrowthReqClass.isInstance(req)) return false;
                if (api.machineGrowthReqClass.isInstance(req)
                    && !(Boolean) api.worldGrowthOnlyPreventsHarvest.invoke(req)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<List<Object>> collectWorldDeterministicMutations(CropsNhApi api, Object targetCrop)
        throws Throwable {
        Collection<?> mutations = (Collection<?>) api.mutRegDetForCrop.invoke(api.mutationRegistry, targetCrop);
        if (mutations == null || mutations.isEmpty()) return Collections.emptyList();
        List<List<Object>> valid = new ArrayList<>();
        for (Object mut : mutations) {
            Collection<?> reqs = (Collection<?>) api.mutationGetReqs.invoke(mut);
            boolean machineOnly = false;
            if (reqs != null) {
                for (Object req : reqs) {
                    if (api.machineBreedReqClass.isInstance(req)) {
                        machineOnly = true;
                        break;
                    }
                }
            }
            if (machineOnly) continue;
            Collection<?> parents = (Collection<?>) api.mutationGetParents.invoke(mut);
            if (parents == null || parents.isEmpty()) continue;
            List<Object> list = new ArrayList<>();
            boolean ok = true;
            for (Object p : parents) {
                if ((Float) api.cropGetBreedingThreshold.invoke(p) < CropsNhTuning.MIN_ALLOWED_THRESHOLD) {
                    ok = false;
                    break;
                }
                list.add(p);
            }
            if (ok) valid.add(list);
        }
        return valid;
    }

    private static List<Object> collectWorldPoolsContaining(CropsNhApi api, Object targetCrop) throws Throwable {
        Collection<?> pools = (Collection<?>) api.mutRegPools.invoke(api.mutationRegistry);
        if (pools == null) return Collections.emptyList();
        List<Object> result = new ArrayList<>();
        for (Object pool : pools) {
            Collection<?> members = (Collection<?>) api.poolGetMembers.invoke(pool);
            if (members != null && members.contains(targetCrop)) {
                result.add(pool);
            }
        }
        return result;
    }

    private static TargetBreedingPlan resolvePoolPlan(CropsNhApi api, String poolUnlocId, Set<String> available)
        throws Throwable {
        Collection<?> pools = (Collection<?>) api.mutRegPools.invoke(api.mutationRegistry);
        if (pools == null) {
            return new TargetBreedingPlan(
                PlanStatus.UNSUPPORTED_IN_WORLD,
                poolUnlocId,
                Collections.emptyList(),
                Collections.emptyList());
        }
        for (Object pool : pools) {
            String unloc = (String) api.poolGetUnlocalisedName.invoke(pool);
            if (!poolUnlocId.equals(unloc)) continue;
            List<String> chosen = pickAvailablePoolParents(api, pool, available);
            if (chosen.size() >= CropsNhTuning.MIN_POOL_PARENTS) {
                return new TargetBreedingPlan(PlanStatus.READY_POOL_CROSS, unloc, chosen, Collections.emptyList());
            }
            List<String> suggestions = new ArrayList<>();
            Collection<?> members = (Collection<?>) api.poolGetMembers.invoke(pool);
            if (members != null) {
                for (Object m : members) {
                    if (isWorldGrowableAndCrossable(api, m)
                        && (Float) api.cropGetBreedingThreshold.invoke(m) >= CropsNhTuning.MIN_ALLOWED_THRESHOLD) {
                        suggestions.add((String) api.cropGetUnlocalizedName.invoke(m));
                        if (suggestions.size() >= CropsNhTuning.MIN_POOL_PARENTS) break;
                    }
                }
            }
            if (suggestions.size() < CropsNhTuning.MIN_POOL_PARENTS) {
                return new TargetBreedingPlan(
                    PlanStatus.UNSUPPORTED_IN_WORLD,
                    unloc,
                    Collections.emptyList(),
                    Collections.emptyList());
            }
            return new TargetBreedingPlan(PlanStatus.MISSING_PARENTS, unloc, Collections.emptyList(), suggestions);
        }
        return new TargetBreedingPlan(
            PlanStatus.UNSUPPORTED_IN_WORLD,
            poolUnlocId,
            Collections.emptyList(),
            Collections.emptyList());
    }

    private static List<String> pickAvailablePoolParents(CropsNhApi api, Object pool, Set<String> available)
        throws Throwable {
        List<String> chosen = new ArrayList<>();
        Collection<?> members = (Collection<?>) api.poolGetMembers.invoke(pool);
        if (members == null) return chosen;
        for (Object m : members) {
            if (!isWorldGrowableAndCrossable(api, m)) continue;
            if ((Float) api.cropGetBreedingThreshold.invoke(m) < CropsNhTuning.MIN_ALLOWED_THRESHOLD) continue;
            String id = (String) api.cropGetId.invoke(m);
            if (available.contains(id)) {
                chosen.add(id);
                if (chosen.size() >= CropsNhTuning.MIN_POOL_PARENTS) break;
            }
        }
        return chosen;
    }

    private static Object resolveCropOrPoolRepresentative(CropsNhApi api, String idOrPool, List<String> crossParentIds)
        throws Throwable {
        Object direct = api.cropRegGetById.invoke(api.cropRegistry, idOrPool);
        if (direct != null) return direct;
        if (crossParentIds != null && !crossParentIds.isEmpty()) {
            Object fromParent = api.cropRegGetById
                .invoke(api.cropRegistry, crossParentIds.get(CropsNhTuning.ZERO_META));
            if (fromParent != null) return fromParent;
        }
        Collection<?> pools = (Collection<?>) api.mutRegPools.invoke(api.mutationRegistry);
        if (pools != null) {
            for (Object pool : pools) {
                if (idOrPool.equals(api.poolGetUnlocalisedName.invoke(pool))) {
                    Collection<?> members = (Collection<?>) api.poolGetMembers.invoke(pool);
                    if (members != null) {
                        for (Object m : members) {
                            if (isWorldGrowableAndCrossable(api, m)) return m;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<Object> collectSubSoilRequirements(CropsNhApi api, Object crop, List<String> crossParentIds)
        throws Throwable {
        List<Object> reqs = new ArrayList<>();
        Collection<?> growthReqs = (Collection<?>) api.cropGetGrowthReqs.invoke(crop);
        if (growthReqs != null) {
            for (Object r : growthReqs) {
                if (api.subSoilReqClass.isInstance(r)) reqs.add(r);
            }
        }
        if (crossParentIds != null && crossParentIds.size() >= CropsNhTuning.MIN_POOL_PARENTS) {
            Collection<?> dets = (Collection<?>) api.mutRegDetForCrop.invoke(api.mutationRegistry, crop);
            if (dets != null) {
                Set<String> wanted = new HashSet<>(crossParentIds);
                for (Object mut : dets) {
                    Collection<?> parents = (Collection<?>) api.mutationGetParents.invoke(mut);
                    if (parents == null || parents.size() != wanted.size()) continue;
                    boolean match = true;
                    for (Object p : parents) {
                        if (!wanted.contains((String) api.cropGetId.invoke(p))) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) continue;
                    Collection<?> breedReqs = (Collection<?>) api.mutationGetReqs.invoke(mut);
                    if (breedReqs != null) {
                        for (Object br : breedReqs) {
                            if (api.subSoilReqClass.isInstance(br)) reqs.add(br);
                        }
                    }
                }
            }
        }
        return reqs;
    }
}
