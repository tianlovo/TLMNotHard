package com.tianluoqaq.tlmnh.compat.cropsnh.common.breeding;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

/**
 * CropsNH 2.0.140 惰性方法句柄缓存：
 * 全程采用 MethodHandles.publicLookup 按精确描述符解析成员，规避 Class.getMethod 全表扫描在专服加载客户端类型
 */
final class CropsNhApi {

    private static volatile CropsNhApi cached;

    final Object cropSticksEnum;
    final MethodHandle itemContainerGet;
    final Block blockCropSticks;
    final Class<?> itemCropSticksClass;
    final Class<?> itemSpadeNhClass;
    final Map<?, ?> tillableBlocks;
    final Object cropRegistry;
    final MethodHandle cropRegGetById;
    final MethodHandle cropRegGetByStack;
    final MethodHandle cropRegGetAll;
    final MethodHandle cropRegIsAltSeed;
    final Object mutationRegistry;
    final MethodHandle mutRegDetForCrop;
    final MethodHandle mutRegPools;
    final Object soilRegistry;
    final MethodHandle soilRegIsRegistered;
    final MethodHandle soilListIsRegistered;
    final MethodHandle soilListTooltip;
    final MethodHandle utilsGetSeedData;
    final Object defaultAnalyzedStats;
    final Class<?> cropStickTileClass;
    final Class<?> subSoilReqClass;
    final Class<?> machineGrowthReqClass;
    final Class<?> migratedGrowthReqClass;
    final Class<?> machineBreedReqClass;
    final Class<?> cropMigratorClass;
    final Object plantingSuccessEnum;

    final MethodHandle seedDataGetCrop;
    final MethodHandle seedDataGetStats;
    final MethodHandle statsGetGrowth;
    final MethodHandle statsGetGain;
    final MethodHandle statsGetResistance;
    final MethodHandle statsIsAnalyzed;

    final MethodHandle cropGetId;
    final MethodHandle cropGetUnlocalizedName;
    final MethodHandle cropHideFromNei;
    final MethodHandle cropGetSoilTypes;
    final MethodHandle cropGetGrowthReqs;
    final MethodHandle cropGetCrossingThreshold;
    final MethodHandle cropGetBreedingThreshold;
    final MethodHandle cropGetSeedItem;

    final MethodHandle worldGrowthOnlyPreventsHarvest;
    final MethodHandle subSoilCanGrowBlock;
    final MethodHandle subSoilIsValidStack;
    final MethodHandle reqGetDesc;

    final MethodHandle mutationGetParents;
    final MethodHandle mutationGetReqs;
    final MethodHandle poolGetUnlocalisedName;
    final MethodHandle poolGetMembers;

    final MethodHandle tileHasCrop;
    final MethodHandle tileHasWeed;
    final MethodHandle tileIsSick;
    final MethodHandle tileIsCrossCrop;
    final MethodHandle tileSetCrossCrop;
    final MethodHandle tileCanUpgrade;
    final MethodHandle tileIsMature;
    final MethodHandle tileCanHarvest;
    final MethodHandle tileAreReqsMet;
    final MethodHandle tileGetSeed;
    final MethodHandle tileTryPlantSeed;
    final MethodHandle tilePlayPlantSound;
    final MethodHandle tilePlayCrossSound;
    final MethodHandle tilePlayHarvestSound;
    final MethodHandle tilePlayRemovalSound;
    final MethodHandle tileUpdateState;
    final MethodHandle tileHarvest;
    final MethodHandle tileSetGrowthProgress;
    final MethodHandle tileGetSeedDrop;
    final MethodHandle tileClear;
    final MethodHandle tileGetBreakItems;
    final MethodHandle tileOnGrowthTick;
    final MethodHandle tileCureDisease;

    final MethodHandle harvestDropModify;
    final MethodHandle spadeOnLeftClick;
    final MethodHandle pairGetLeft;

    private CropsNhApi() throws Throwable {
        ClassLoader cl = CropsNhApi.class.getClassLoader();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        Class<?> itemListCls = Class.forName(CropsNhReflectionSpec.CLS_ITEM_LIST, true, cl);
        Class<?> itemContainerIfc = Class.forName(CropsNhReflectionSpec.CLS_ITEM_CONTAINER, false, cl);
        this.cropSticksEnum = lookup
            .findStaticGetter(itemListCls, CropsNhReflectionSpec.FLD_CROP_STICKS_ENUM, itemListCls)
            .invoke();
        this.itemContainerGet = lookup
            .findVirtual(
                itemContainerIfc,
                CropsNhReflectionSpec.MTD_ITEM_GET,
                MethodType.methodType(ItemStack.class, long.class, Object[].class))
            .asFixedArity();

        Class<?> blocksCls = Class.forName(CropsNhReflectionSpec.CLS_CROPS_BLOCKS, true, cl);
        this.blockCropSticks = (Block) lookup
            .findStaticGetter(blocksCls, CropsNhReflectionSpec.FLD_BLOCK_CROP_STICKS, Block.class)
            .invoke();
        this.itemCropSticksClass = Class.forName(CropsNhReflectionSpec.CLS_ITEM_CROP_STICKS, false, cl);
        this.itemSpadeNhClass = Class.forName(CropsNhReflectionSpec.CLS_ITEM_SPADE_NH, true, cl);
        this.tillableBlocks = (Map<?, ?>) lookup
            .findStaticGetter(itemSpadeNhClass, CropsNhReflectionSpec.FLD_TILLABLE_BLOCKS, IdentityHashMap.class)
            .invoke();

        Class<?> cropCardCls = Class.forName(CropsNhReflectionSpec.CLS_CROP_CARD, false, cl);
        Class<?> cropRegIfc = Class.forName(CropsNhReflectionSpec.CLS_CROP_REGISTRY_IFC, false, cl);
        Class<?> cropRegCls = Class.forName(CropsNhReflectionSpec.CLS_CROP_REGISTRY, true, cl);
        this.cropRegistry = lookup.findStaticGetter(cropRegCls, CropsNhReflectionSpec.FLD_INSTANCE, cropRegCls)
            .invoke();
        this.cropRegGetById = lookup.findVirtual(
            cropRegIfc,
            CropsNhReflectionSpec.MTD_ITEM_GET,
            MethodType.methodType(cropCardCls, String.class));
        this.cropRegGetByStack = lookup.findVirtual(
            cropRegIfc,
            CropsNhReflectionSpec.MTD_ITEM_GET,
            MethodType.methodType(cropCardCls, ItemStack.class));
        this.cropRegGetAll = lookup
            .findVirtual(cropRegCls, CropsNhReflectionSpec.MTD_GET_ALL_CROPS, MethodType.methodType(LinkedList.class));
        this.cropRegIsAltSeed = lookup.findVirtual(
            cropRegIfc,
            CropsNhReflectionSpec.MTD_IS_ALT_SEED,
            MethodType.methodType(boolean.class, ItemStack.class));

        Class<?> mutRegIfc = Class.forName(CropsNhReflectionSpec.CLS_MUTATION_REGISTRY_IFC, false, cl);
        Class<?> mutRegCls = Class.forName(CropsNhReflectionSpec.CLS_MUTATION_REGISTRY, true, cl);
        this.mutationRegistry = lookup.findStaticGetter(mutRegCls, CropsNhReflectionSpec.FLD_INSTANCE, mutRegCls)
            .invoke();
        this.mutRegDetForCrop = lookup.findVirtual(
            mutRegIfc,
            CropsNhReflectionSpec.MTD_GET_DET_MUTATIONS_FOR_CROP,
            MethodType.methodType(Collection.class, cropCardCls));
        this.mutRegPools = lookup.findVirtual(
            mutRegIfc,
            CropsNhReflectionSpec.MTD_GET_MUTATION_POOLS,
            MethodType.methodType(Collection.class));

        Class<?> soilRegIfc = Class.forName(CropsNhReflectionSpec.CLS_SOIL_REGISTRY_IFC, false, cl);
        Class<?> soilRegCls = Class.forName(CropsNhReflectionSpec.CLS_SOIL_REGISTRY, true, cl);
        this.soilRegistry = lookup.findStaticGetter(soilRegCls, CropsNhReflectionSpec.FLD_INSTANCE, soilRegCls)
            .invoke();
        this.soilRegIsRegistered = lookup.findVirtual(
            soilRegIfc,
            CropsNhReflectionSpec.MTD_IS_REGISTERED,
            MethodType.methodType(boolean.class, Block.class, int.class));

        Class<?> soilListCls = Class.forName(CropsNhReflectionSpec.CLS_SOIL_LIST, false, cl);
        this.soilListIsRegistered = lookup.findVirtual(
            soilListCls,
            CropsNhReflectionSpec.MTD_IS_REGISTERED,
            MethodType.methodType(boolean.class, Block.class, int.class));
        this.soilListTooltip = lookup
            .findVirtual(soilListCls, CropsNhReflectionSpec.MTD_SOIL_TOOLTIP, MethodType.methodType(String.class));

        Class<?> seedDataCls = Class.forName(CropsNhReflectionSpec.CLS_SEED_DATA, false, cl);
        Class<?> utilsCls = Class.forName(CropsNhReflectionSpec.CLS_CROPS_UTILS, true, cl);
        this.utilsGetSeedData = lookup.findStatic(
            utilsCls,
            CropsNhReflectionSpec.MTD_GET_SEED_DATA,
            MethodType.methodType(seedDataCls, ItemStack.class, boolean.class, boolean.class));

        Class<?> seedStatsImplCls = Class.forName(CropsNhReflectionSpec.CLS_SEED_STATS_IMPL, true, cl);
        this.defaultAnalyzedStats = lookup
            .findStaticGetter(seedStatsImplCls, CropsNhReflectionSpec.FLD_DEFAULT_ANALYZED, seedStatsImplCls)
            .invoke();

        this.cropStickTileClass = Class.forName(CropsNhReflectionSpec.CLS_CROP_STICK_TILE, false, cl);
        this.subSoilReqClass = Class.forName(CropsNhReflectionSpec.CLS_SUB_SOIL_REQ, false, cl);
        this.machineGrowthReqClass = Class.forName(CropsNhReflectionSpec.CLS_MACHINE_GROWTH_REQ, false, cl);
        this.migratedGrowthReqClass = Class.forName(CropsNhReflectionSpec.CLS_MIGRATED_GROWTH_REQ, false, cl);
        this.machineBreedReqClass = Class.forName(CropsNhReflectionSpec.CLS_MACHINE_BREED_REQ, false, cl);
        this.cropMigratorClass = Class.forName(CropsNhReflectionSpec.CLS_CROP_MIGRATOR, false, cl);

        Class<?> plantResCls = Class.forName(CropsNhReflectionSpec.CLS_PLANTING_RESULT, true, cl);
        this.plantingSuccessEnum = lookup.findStaticGetter(plantResCls, CropsNhReflectionSpec.ENUM_SUCCESS, plantResCls)
            .invoke();

        Class<?> seedStatsCls = Class.forName(CropsNhReflectionSpec.CLS_SEED_STATS, false, cl);
        this.seedDataGetCrop = lookup
            .findVirtual(seedDataCls, CropsNhReflectionSpec.MTD_GET_CROP, MethodType.methodType(cropCardCls));
        this.seedDataGetStats = lookup
            .findVirtual(seedDataCls, CropsNhReflectionSpec.MTD_GET_STATS, MethodType.methodType(seedStatsCls));
        this.statsGetGrowth = lookup
            .findVirtual(seedStatsCls, CropsNhReflectionSpec.MTD_GET_GROWTH, MethodType.methodType(byte.class));
        this.statsGetGain = lookup
            .findVirtual(seedStatsCls, CropsNhReflectionSpec.MTD_GET_GAIN, MethodType.methodType(byte.class));
        this.statsGetResistance = lookup
            .findVirtual(seedStatsCls, CropsNhReflectionSpec.MTD_GET_RESISTANCE, MethodType.methodType(byte.class));
        this.statsIsAnalyzed = lookup
            .findVirtual(seedStatsCls, CropsNhReflectionSpec.MTD_IS_ANALYZED, MethodType.methodType(boolean.class));

        this.cropGetId = lookup
            .findVirtual(cropCardCls, CropsNhReflectionSpec.MTD_GET_ID, MethodType.methodType(String.class));
        this.cropGetUnlocalizedName = lookup.findVirtual(
            cropCardCls,
            CropsNhReflectionSpec.MTD_GET_UNLOCALIZED_NAME,
            MethodType.methodType(String.class));
        this.cropHideFromNei = lookup
            .findVirtual(cropCardCls, CropsNhReflectionSpec.MTD_HIDE_FROM_NEI, MethodType.methodType(boolean.class));
        this.cropGetSoilTypes = lookup
            .findVirtual(cropCardCls, CropsNhReflectionSpec.MTD_GET_SOIL_TYPES, MethodType.methodType(soilListCls));
        this.cropGetGrowthReqs = lookup.findVirtual(
            cropCardCls,
            CropsNhReflectionSpec.MTD_GET_GROWTH_REQS,
            MethodType.methodType(Collection.class));
        this.cropGetCrossingThreshold = lookup.findVirtual(
            cropCardCls,
            CropsNhReflectionSpec.MTD_GET_CROSSING_THRESHOLD,
            MethodType.methodType(float.class));
        this.cropGetBreedingThreshold = lookup.findVirtual(
            cropCardCls,
            CropsNhReflectionSpec.MTD_GET_BREEDING_THRESHOLD,
            MethodType.methodType(float.class));
        this.cropGetSeedItem = lookup.findVirtual(
            cropCardCls,
            CropsNhReflectionSpec.MTD_GET_SEED_ITEM,
            MethodType.methodType(ItemStack.class, seedStatsCls));

        Class<?> worldGrowthCls = Class.forName(CropsNhReflectionSpec.CLS_WORLD_GROWTH_REQ, false, cl);
        this.worldGrowthOnlyPreventsHarvest = lookup.findVirtual(
            worldGrowthCls,
            CropsNhReflectionSpec.MTD_ONLY_PREVENTS_HARVEST,
            MethodType.methodType(boolean.class));
        this.subSoilCanGrowBlock = lookup.findVirtual(
            subSoilReqClass,
            CropsNhReflectionSpec.MTD_CAN_GROW_SUBSOIL,
            MethodType.methodType(boolean.class, Block.class, int.class, TileEntity.class));
        this.subSoilIsValidStack = lookup.findVirtual(
            subSoilReqClass,
            CropsNhReflectionSpec.MTD_IS_VALID_SUBSOIL,
            MethodType.methodType(boolean.class, ItemStack.class));
        this.reqGetDesc = lookup
            .findVirtual(subSoilReqClass, CropsNhReflectionSpec.MTD_GET_DESC, MethodType.methodType(String.class));

        Class<?> cropMutCls = Class.forName(CropsNhReflectionSpec.CLS_CROP_MUTATION, false, cl);
        this.mutationGetParents = lookup
            .findVirtual(cropMutCls, CropsNhReflectionSpec.MTD_GET_PARENTS, MethodType.methodType(Collection.class));
        this.mutationGetReqs = lookup
            .findVirtual(cropMutCls, CropsNhReflectionSpec.MTD_GET_REQUIREMENTS, MethodType.methodType(List.class));

        Class<?> mutPoolCls = Class.forName(CropsNhReflectionSpec.CLS_MUTATION_POOL, false, cl);
        this.poolGetUnlocalisedName = lookup.findVirtual(
            mutPoolCls,
            CropsNhReflectionSpec.MTD_GET_UNLOCALISED_NAME,
            MethodType.methodType(String.class));
        this.poolGetMembers = lookup
            .findVirtual(mutPoolCls, CropsNhReflectionSpec.MTD_GET_MEMBERS, MethodType.methodType(Collection.class));

        Class<?> pairCls = Class.forName(CropsNhReflectionSpec.CLS_APACHE_PAIR, false, cl);
        this.pairGetLeft = lookup
            .findVirtual(pairCls, CropsNhReflectionSpec.MTD_PAIR_LEFT, MethodType.methodType(Object.class));

        this.tileHasCrop = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_HAS_CROP, MethodType.methodType(boolean.class));
        this.tileHasWeed = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_HAS_WEED, MethodType.methodType(boolean.class));
        this.tileIsSick = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_IS_SICK, MethodType.methodType(boolean.class));
        this.tileIsCrossCrop = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_IS_CROSS_CROP,
            MethodType.methodType(boolean.class));
        this.tileSetCrossCrop = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_SET_CROSS_CROP,
            MethodType.methodType(void.class, boolean.class));
        this.tileCanUpgrade = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_CAN_UPGRADE,
            MethodType.methodType(boolean.class));
        this.tileIsMature = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_IS_MATURE, MethodType.methodType(boolean.class));
        this.tileCanHarvest = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_CAN_HARVEST,
            MethodType.methodType(boolean.class));
        this.tileAreReqsMet = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_ARE_REQS_MET,
            MethodType.methodType(boolean.class));
        this.tileGetSeed = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_GET_SEED, MethodType.methodType(seedDataCls));
        this.tileTryPlantSeed = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_TRY_PLANT_SEED,
            MethodType.methodType(pairCls, ItemStack.class));
        this.tilePlayPlantSound = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_PLAY_PLANT_SOUND,
            MethodType.methodType(void.class));
        this.tilePlayCrossSound = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_PLAY_CROSS_SOUND,
            MethodType.methodType(void.class));
        this.tilePlayHarvestSound = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_PLAY_HARVEST_SOUND,
            MethodType.methodType(void.class));
        this.tilePlayRemovalSound = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_PLAY_REMOVAL_SOUND,
            MethodType.methodType(void.class));
        this.tileUpdateState = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_UPDATE_STATE, MethodType.methodType(void.class));
        this.tileHarvest = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_HARVEST,
            MethodType.methodType(ArrayList.class, double.class));
        this.tileSetGrowthProgress = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_SET_GROWTH_PROGRESS,
            MethodType.methodType(void.class, int.class));
        this.tileGetSeedDrop = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_GET_SEED_DROP,
            MethodType.methodType(ItemStack.class));
        this.tileClear = lookup
            .findVirtual(cropStickTileClass, CropsNhReflectionSpec.MTD_CLEAR, MethodType.methodType(void.class));
        this.tileGetBreakItems = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_GET_BREAK_ITEMS,
            MethodType.methodType(ArrayList.class));
        this.tileOnGrowthTick = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_ON_GROWTH_TICK,
            MethodType.methodType(void.class));
        this.tileCureDisease = lookup.findVirtual(
            cropStickTileClass,
            CropsNhReflectionSpec.MTD_CURE_DISEASE,
            MethodType.methodType(boolean.class));

        Class<?> harvestModCls = Class.forName(CropsNhReflectionSpec.CLS_HARVEST_DROP_MOD, false, cl);
        this.harvestDropModify = lookup.findVirtual(
            harvestModCls,
            CropsNhReflectionSpec.MTD_MODIFY_CROP_DROPS,
            MethodType
                .methodType(void.class, EntityPlayer.class, ItemStack.class, Collection.class, cropStickTileClass));
        Class<?> leftClickCls = Class.forName(CropsNhReflectionSpec.CLS_LEFT_CLICK_HANDLER, false, cl);
        this.spadeOnLeftClick = lookup.findVirtual(
            leftClickCls,
            CropsNhReflectionSpec.MTD_ON_LEFT_CLICK,
            MethodType.methodType(boolean.class, cropStickTileClass, EntityPlayer.class, ItemStack.class));
    }

    static CropsNhApi get() throws Throwable {
        CropsNhApi local = cached;
        if (local == null) {
            synchronized (CropsNhApi.class) {
                local = cached;
                if (local == null) {
                    local = new CropsNhApi();
                    cached = local;
                }
            }
        }
        return local;
    }
}
