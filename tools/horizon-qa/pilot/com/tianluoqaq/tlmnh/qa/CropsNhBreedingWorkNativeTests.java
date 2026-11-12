package com.tianluoqaq.tlmnh.qa;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.inventory.spi.BackpackContainerCompatProvider;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.breeding.CropsNhBreedingWorkController;
import com.tianluoqaq.tlmnh.common.work.breeding.CropsNhBreedingWorkController.EvaluationResult;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.StatLogic;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.TargetMode;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.BreedingCandidateEntry;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.CropStickCellState;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.PlanStatus;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.SeedInspection;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.StickKind;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;
import com.tianluoqaq.tlmnh.common.work.notify.MissingConditionCode;

/** CropsNH 育种工作模式原生世界集成测试：覆盖 12 项缺条件与锁定背包播报、单果实扩繁除草爬坡、AdventureBackpack 子代存取闭环、变异池与 GT 采矿工具换底矿 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class CropsNhBreedingWorkNativeTests {

    private CropsNhBreedingWorkNativeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "work_breeding_missing")
    public static void breedingMissingConditionsReportAllTwelveRealWorldStatesInOrder(GameTestHelper h) {
        CropsNhNativeQaSupport qa = new CropsNhNativeQaSupport(h, true);
        try {
            qa.registerOwnerOnlineNow();
            CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
            h.assertTrue(compat.isAvailable(), "Real CropsNH compat provider unavailable");

            String cucumberId = CropsNhNativeQaSupport.registeredCropId("Cucumber");
            String stoneLilyId = CropsNhNativeQaSupport.registeredCropId("StoneLily");
            String diareedId = CropsNhNativeQaSupport.registeredCropId("Diareed");
            h.assertTrue("cropsnh:cucumber".equals(cucumberId), "Unexpected Cucumber cropId: " + cucumberId);
            h.assertTrue("cropsnh:stoneLily".equals(stoneLilyId), "Unexpected StoneLily cropId: " + stoneLilyId);
            h.assertTrue("cropsnh:diareed".equals(diareedId), "Unexpected Diareed cropId: " + diareedId);

            EntityMaid maid = qa.spawnBreedingMaid("[QA] Breeding Reporter", qa.cx - 1.5, qa.cropY, qa.cz + 0.5);
            int dim = qa.world.provider.dimensionId;
            CropsNhBreedingWorkController controller = new CropsNhBreedingWorkController();
            MaidMissingConditionNotifier notifier = new MaidMissingConditionNotifier();
            BreedingTaskConfigData cfg = maid.getBreedingTaskConfig();
            EnumSet<MissingConditionCode> seen = EnumSet.noneOf(MissingConditionCode.class);

            // 0) 缺育种铃铛 + 1200t 冷却防刷屏
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_BREEDING_BELL);
            controller.reset();
            controller.tick(maid, cfg, compat, notifier);
            h.assertTrue(
                qa.drainChat()
                    .isEmpty(),
                "Repeated missing condition bypassed 1200-tick cooldown");

            // 1) 铃铛未绑定
            ItemStack bell = new ItemStack(MaidWorkItems.breedingBell);
            maid.getMaidBaubleInventory()
                .setInventorySlotContents(0, bell);
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.UNBOUND_BREEDING_BELL);

            // 2) 铃铛跨维度
            BreedingBellData.bind(bell, dim + 1, qa.cx, qa.soilY, qa.cz);
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.WRONG_DIMENSION_BREEDING_BELL);

            // 3) 作物空间被遮挡
            BreedingBellData.bind(bell, dim, qa.cx, qa.soilY, qa.cz);
            qa.world.setBlock(qa.cx, qa.cropY, qa.cz, Blocks.stone, 0, 3);
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.INVALID_PLOT_OBSTRUCTED);

            // 4) 未选择目标作物/变异池
            qa.world.setBlockToAir(qa.cx, qa.cropY, qa.cz);
            cfg.clear();
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_TARGET_CONFIG);

            // 5) 非世界育种作物或不存在的作物 ID
            cfg.setTargetId("cropsnh:unknown_nonexistent_crop");
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.UNSUPPORTED_IN_WORLD_CROP);

            // 6) 缺 CropsNH 铲子
            cfg.setTargetId(cucumberId);
            maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.melon_seeds, 4));
            maid.getMaidInventory()
                .setInventorySlotContents(1, new ItemStack(Items.carrot, 4));
            maid.getMaidInventory()
                .setInventorySlotContents(2, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 32));
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_CROPSNH_SPADE);

            // 7) 缺母本种子
            CropsNhNativeQaSupport.clearMaidRawInventory(maid);
            maid.getMaidInventory()
                .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_PARENT_SEEDS);

            // 8) 缺作物架
            CropsNhNativeQaSupport.clearMaidRawInventory(maid);
            maid.getMaidInventory()
                .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
            maid.getMaidInventory()
                .setInventorySlotContents(1, new ItemStack(Items.melon_seeds, 4));
            maid.getMaidInventory()
                .setInventorySlotContents(2, new ItemStack(Items.carrot, 4));
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_CROP_STICKS);

            // 9) 缺合规土壤方块
            CropsNhNativeQaSupport.clearMaidRawInventory(maid);
            qa.resetThreeByThreeFoundation(Blocks.dirt, Blocks.stone);
            cfg.setTargetId(stoneLilyId);
            maid.getMaidInventory()
                .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
            maid.getMaidInventory()
                .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 32));
            maid.getMaidInventory()
                .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(stoneLilyId, 12, 12, 15, true, 4));
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_SOIL_BLOCK);

            // 10) 缺达到挖掘等级的工具（黑曜石底垫无合格采矿工具）
            CropsNhNativeQaSupport.clearMaidRawInventory(maid);
            qa.resetThreeByThreeFoundation(Blocks.dirt, Blocks.obsidian);
            cfg.setTargetId(stoneLilyId);
            maid.getMaidInventory()
                .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
            maid.getMaidInventory()
                .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 32));
            maid.getMaidInventory()
                .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(stoneLilyId, 12, 12, 15, true, 4));
            maid.getMaidInventory()
                .setInventorySlotContents(3, new ItemStack(Blocks.stone, 18));
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_HARVEST_TOOL);

            // 11) 缺合规底矿方块（diareed 缺钻石块底矿）
            CropsNhNativeQaSupport.clearMaidRawInventory(maid);
            qa.resetThreeByThreeFoundation(Blocks.stone, Blocks.stone);
            cfg.setTargetId(diareedId);
            maid.getMaidInventory()
                .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
            maid.getMaidInventory()
                .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 32));
            maid.getMaidInventory()
                .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(diareedId, 12, 12, 15, true, 4));
            maid.getMaidInventory()
                .setInventorySlotContents(3, CropsNhNativeQaSupport.createQualifiedGtMiningToolFor(Blocks.obsidian));
            maid.getMaidInventory()
                .setInventorySlotContents(4, new ItemStack(Blocks.stone, 18));
            assertTickEmits(
                h,
                qa,
                controller,
                notifier,
                maid,
                cfg,
                compat,
                seen,
                MissingConditionCode.MISSING_SUBSOIL_BLOCK);
            h.assertTrue(seen.size() == 12, "Did not observe all 12 breeding missing codes: " + seen);

            // 12) 锁定林业背包（damage=1）内虽有铲子、母本与作物架但禁止自动取用，必须按缺件播报；解锁（damage=0）后立即齐件
            CropsNhNativeQaSupport.clearMaidRawInventory(maid);
            qa.resetThreeByThreeFoundation(Blocks.dirt, Blocks.dirt);
            cfg.setTargetId(cucumberId);
            ItemStack foresterBag = CropsNhNativeQaSupport.createForesterBackpack(1);
            CropsNhNativeQaSupport.preloadForestryBackpackNbt(
                foresterBag,
                CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1),
                CropsNhNativeQaSupport.cropsNhItem("cropSticks", 32),
                new ItemStack(Items.melon_seeds, 4),
                new ItemStack(Items.carrot, 4));
            maid.getMaidInventory()
                .setInventorySlotContents(0, foresterBag);
            controller.reset();
            controller.tick(maid, cfg, compat, notifier);
            List<String> lockedMsgs = qa.drainChat();
            h.assertTrue(
                lockedMsgs.size() == 1 && lockedMsgs.get(0)
                    .contains("[QA] Breeding Reporter")
                    && lockedMsgs.get(0)
                        .contains(MissingConditionCode.MISSING_CROPSNH_SPADE.translationKey())
                    && lockedMsgs.get(0)
                        .contains(MissingConditionCode.MISSING_PARENT_SEEDS.translationKey()),
                "Locked Forestry backpack failed to emit missing spade + parent seeds reminder: " + lockedMsgs);

            foresterBag.setItemDamage(0);
            EvaluationResult unlockedEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
            h.assertTrue(
                unlockedEval.missing.isEmpty(),
                "Unlocked Forestry backpack supplies should be extractable and ready, got: " + unlockedEval.missing);

            System.out.println("TLMNH_BREEDING_MISSING_12_PASS codes=" + seen.size() + " lockedBagNotified=true");
            h.succeed();
        } finally {
            qa.unregisterOwnerOnlineNow();
        }
    }

    private static void assertTickEmits(GameTestHelper h, CropsNhNativeQaSupport qa,
        CropsNhBreedingWorkController controller, MaidMissingConditionNotifier notifier, EntityMaid maid,
        BreedingTaskConfigData cfg, CropsNhBreedingCompatProvider compat, EnumSet<MissingConditionCode> seen,
        MissingConditionCode expected) {
        controller.reset();
        controller.tick(maid, cfg, compat, notifier);
        List<String> msgs = qa.drainChat();
        h.assertTrue(
            msgs.size() == 1 && msgs.get(0)
                .contains("[QA] Breeding Reporter")
                && msgs.get(0)
                    .contains(expected.translationKey()),
            "Expected " + expected + " in owner chat, got: " + msgs);
        seen.add(expected);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "work_breeding_storage")
    public static void nestedOnlySpadeTillsAndSplitSuppliesConserveThroughRealController(GameTestHelper h) {
        CropsNhNativeQaSupport qa = new CropsNhNativeQaSupport(h, false);
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        String carrotId = CropsNhNativeQaSupport.registeredCropId("Carrot");
        EntityMaid maid = qa.spawnBreedingMaid("[QA] Storage Breeder", qa.cx - 1.5, qa.cropY, qa.cz + 0.5);
        qa.resetThreeByThreeFoundation(Blocks.stone, Blocks.dirt);
        ItemStack bell = new ItemStack(MaidWorkItems.breedingBell);
        BreedingBellData.bind(bell, qa.world.provider.dimensionId, qa.cx, qa.soilY, qa.cz);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, bell);
        ItemStack bag = CropsNhNativeQaSupport.createAdventureBackpack();
        ItemStack spade = CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1);
        ItemStack tool = CropsNhNativeQaSupport.createQualifiedGtMiningToolFor(Blocks.stone);
        for (ItemStack supply : new ItemStack[] { spade, tool, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 12),
            CropsNhNativeQaSupport.createSeed(carrotId, 14, 14, 15, true, 5), new ItemStack(Blocks.dirt, 5) }) {
            h.assertTrue(backpacks.insertIntoBackpack(bag, supply, false) == null, "Failed to pack supply");
        }
        maid.getMaidInventory()
            .setInventorySlotContents(0, bag);
        maid.getMaidInventory()
            .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 1));
        maid.getMaidInventory()
            .setInventorySlotContents(2, new ItemStack(Blocks.dirt, 4));
        BreedingTaskConfigData cfg = maid.getBreedingTaskConfig();
        cfg.setTargetMode(TargetMode.CROP);
        cfg.setTargetId(carrotId);
        cfg.setStatLogic(StatLogic.AND);
        cfg.setTargetGrowth(31);
        cfg.setTargetGain(31);
        cfg.setTargetResistance(31);
        CropsNhBreedingWorkController controller = new CropsNhBreedingWorkController();
        MaidMissingConditionNotifier notifier = new MaidMissingConditionNotifier();
        // 同步调用真实控制器，固定步数覆盖换土、耕地、跨槽铺架与种植，不推进生长
        for (int step = 0; step < 60; step++) {
            EvaluationResult eval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
            h.assertTrue(eval.missing.isEmpty(), "Supplies became unavailable at step " + step + ": " + eval.missing);
            controller.reset();
            controller.tick(maid, cfg, compat, notifier);
        }
        int placedSticks = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                h.assertTrue(
                    qa.world.getBlock(qa.cx + dx, qa.soilY, qa.cz + dz) == Blocks.farmland,
                    "Nested-only spade failed to till replaced dirt at " + dx + "," + dz);
                CropStickCellState cell = compat.inspectCropStickCell(qa.world, qa.cx + dx, qa.cropY, qa.cz + dz);
                if (dx == 0 ^ dz == 0) {
                    h.assertTrue(cell.kind == StickKind.CROSS_EMPTY, "Expected empty double cross");
                    placedSticks += 2;
                } else {
                    h.assertTrue(
                        cell.kind == StickKind.PLANTED && carrotId.equals(cell.cropId),
                        "Expected carrot parent");
                    placedSticks++;
                }
            }
        }
        int[] remainingSticks = { 0 };
        backpacks
            .forEachStoredStack(bag, st -> { if (compat.isCropSticksItem(st)) remainingSticks[0] += st.stackSize; });
        h.assertTrue(
            placedSticks == 13 && remainingSticks[0] == 0
                && maid.getMaidInventory()
                    .getStackInSlot(1) == null,
            "Split crop sticks were lost or duplicated");
        h.assertTrue(
            CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.dirt) == 0,
            "Nine split dirt blocks were not consumed exactly");
        // 石头按原版掉落圆石，回收量须与九格换土一致
        h.assertTrue(
            CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.cobblestone) == 9,
            "Wrong recovery count for replaced stone soil");
        ItemStack returnedSpade = backpacks.extractFromBackpack(bag, compat::isSpadeItem, null, 1, true);
        ItemStack returnedTool = backpacks.extractFromBackpack(
            bag,
            st -> st.getItem() == tool.getItem() && st.getItemDamage() == tool.getItemDamage(),
            null,
            1,
            true);
        h.assertTrue(returnedSpade != null && returnedTool != null, "Borrowed nested tools were not returned");
        h.assertTrue(CropsNhNativeQaSupport.getGtToolDamage(returnedTool) > 0L, "Nested GT tool took no wear");
        System.out.println(
            "TLMNH_BREEDING_STORAGE_PASS nestedSpade=true farmland=9 sticks=13 dirt=9 recovery=9 toolReturned=true");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2400, batch = "work_breeding_carrot")
    public static void carrotSingleFruitPropagatesClearsWeedsAndClimbsOrAndToMax31(GameTestHelper h) {
        CropsNhNativeQaSupport qa = new CropsNhNativeQaSupport(h, false);
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        String carrotId = CropsNhNativeQaSupport.registeredCropId("Carrot");
        EntityMaid maid = qa.spawnBreedingMaid("[QA] Carrot Breeder", qa.cx - 1.5, qa.cropY, qa.cz + 0.5);

        ItemStack bell = new ItemStack(MaidWorkItems.breedingBell);
        BreedingBellData.bind(bell, qa.world.provider.dimensionId, qa.cx, qa.soilY, qa.cz);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, bell);

        maid.getMaidInventory()
            .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
        maid.getMaidInventory()
            .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64));
        maid.getMaidInventory()
            .setInventorySlotContents(2, new ItemStack(Items.carrot, 1));

        BreedingTaskConfigData cfg = maid.getBreedingTaskConfig();
        cfg.setTargetMode(TargetMode.CROP);
        cfg.setTargetId(carrotId);
        cfg.setStatLogic(StatLogic.OR);
        cfg.setTargetGrowth(16);
        cfg.setTargetGain(16);
        cfg.setTargetResistance(16);
        cfg.setMax31Mode(false);

        h.assertTrue(
            !maid.getMealController()
                .canEatMeal(new ItemStack(Items.carrot), false),
            "Daily meals must reserve real CropsNH alternate planting fruit");
        h.assertTrue(
            maid.getMealController()
                .canEatMeal(new ItemStack(Items.carrot), true),
            "Emergency healing must still accept planting fruit");
        h.assertTrue(
            maid.getMealController()
                .canEatMeal(new ItemStack(Items.bread), false),
            "Breeding must still allow ordinary non-planting meals");
        h.assertTrue(
            !maid.getMealController()
                .tryStartMeal(false, () -> true),
            "Daily meal selection must not borrow the only carrot parent");
        h.assertTrue(
            CropsNhNativeQaSupport.countCropSeedInRawSlots(maid, carrotId) == 1,
            "Rejected daily meal changed the only carrot planting input");

        int[] phase = { 0 };
        int[] andTarget = { 17 };
        long[] lastAccel = { qa.world.getTotalWorldTime() };

        Runnable progress = qa.progressProbe("carrot", maid, () -> phase[0]);
        h.succeedWhen(() -> {
            progress.run();
            long now = qa.world.getTotalWorldTime();
            if (phase[0] == 0) {
                h.assertTrue(
                    qa.findMatchingSeedInPlotOrMaid(maid, carrotId, seed -> true) != null,
                    "Propagation lost its only carrot planting input and all planted parents");
                if (now - lastAccel[0] >= 8) {
                    lastAccel[0] = now;
                    qa.accelerateParentsFirstThenCross(25, 0);
                }
                int plantedParents = 0;
                for (int[] off : CropsNhNativeQaSupport.PARENT_OFFSETS) {
                    CropStickCellState st = compat
                        .inspectCropStickCell(qa.world, qa.cx + off[0], qa.cropY, qa.cz + off[1]);
                    if (st.kind == StickKind.PLANTED && carrotId.equals(st.cropId)) {
                        plantedParents++;
                    }
                }
                int placedCross = 0;
                for (int[] off : CropsNhNativeQaSupport.CROSS_OFFSETS) {
                    CropStickCellState st = compat
                        .inspectCropStickCell(qa.world, qa.cx + off[0], qa.cropY, qa.cz + off[1]);
                    if (st.kind == StickKind.CROSS_EMPTY || st.kind == StickKind.PLANTED) {
                        placedCross++;
                    }
                }
                if (plantedParents == 5 && placedCross == 4) {
                    qa.spawnWeedAt(qa.cx, qa.cropY, qa.cz - 1);
                    h.assertTrue(
                        compat.inspectCropStickCell(qa.world, qa.cx, qa.cropY, qa.cz - 1).kind == StickKind.WEED,
                        "Expected injected weed on north cross cell");
                    phase[0] = 1;
                }
                return false;
            }

            if (phase[0] == 1) {
                CropStickCellState north = compat.inspectCropStickCell(qa.world, qa.cx, qa.cropY, qa.cz - 1);
                if (north.kind == StickKind.CROSS_EMPTY) {
                    // 单果实保活扩繁至 5 母本与除草完成后，换上未达标 14/14/15 母本验证真实世界杂交 OR -> AND 爬坡
                    qa.resetThreeByThreeFoundation(Blocks.farmland, Blocks.dirt);
                    CropsNhNativeQaSupport.clearMaidRawInventory(maid);
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
                    maid.getMaidInventory()
                        .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64));
                    maid.getMaidInventory()
                        .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(carrotId, 14, 14, 15, true, 5));
                    phase[0] = 2;
                    lastAccel[0] = now;
                }
                return false;
            }

            if (phase[0] == 2) {
                if (now - lastAccel[0] >= 8) {
                    lastAccel[0] = now;
                    qa.accelerateParentsFirstThenCross(25, 18);
                }
                SeedInspection orHit = qa.findMatchingSeedInPlotOrMaid(
                    maid,
                    carrotId,
                    s -> cfg.meetsTargetStats(s.growth, s.gain, s.resistance));
                if (orHit != null) {
                    int minStat = Math.min(orHit.growth, Math.min(orHit.gain, orHit.resistance));
                    andTarget[0] = Math.min(20, Math.max(16, minStat + 1));
                    cfg.setStatLogic(StatLogic.AND);
                    cfg.setTargetGrowth(andTarget[0]);
                    cfg.setTargetGain(andTarget[0]);
                    cfg.setTargetResistance(andTarget[0]);
                    phase[0] = 3;
                    lastAccel[0] = now;
                }
                return false;
            }

            if (phase[0] == 3) {
                if (now - lastAccel[0] >= 8) {
                    lastAccel[0] = now;
                    qa.accelerateParentsFirstThenCross(25, 18);
                }
                SeedInspection andHit = qa.findMatchingSeedInPlotOrMaid(
                    maid,
                    carrotId,
                    s -> s.growth >= andTarget[0] && s.gain >= andTarget[0] && s.resistance >= andTarget[0]);
                if (andHit != null) {
                    // 进入末级 30 -> 31 满属性模式验证（仅提供未达标的 30/30/30 母本）
                    qa.resetThreeByThreeFoundation(Blocks.farmland, Blocks.dirt);
                    CropsNhNativeQaSupport.clearMaidRawInventory(maid);
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
                    maid.getMaidInventory()
                        .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64));
                    maid.getMaidInventory()
                        .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(carrotId, 30, 30, 30, true, 5));
                    cfg.setMax31Mode(true);
                    h.assertTrue(!cfg.meetsTargetStats(30, 30, 30), "30/30/30 must not satisfy max31Mode");
                    phase[0] = 4;
                    lastAccel[0] = now;
                }
                return false;
            }

            if (now - lastAccel[0] >= 8) {
                lastAccel[0] = now;
                qa.accelerateParentsFirstThenCross(25, 20);
            }
            SeedInspection maxHit = qa.findMatchingSeedInPlotOrMaid(
                maid,
                carrotId,
                s -> s.growth == 31 && s.gain == 31 && s.resistance == 31);
            if (maxHit != null) {
                System.out.println(
                    "TLMNH_BREEDING_CARROT_PASS singleFruitPropagate=5 weedCleared=true orGoal=16 andGoal="
                        + andTarget[0]
                        + " max31=31/31/31");
                return true;
            }
            return false;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2400, batch = "work_breeding_cross_and_pool")
    public static void melonAndCarrotWorldCrossIntoCucumberAutoPurifyAndMutationPoolWorks(GameTestHelper h) {
        CropsNhNativeQaSupport qa = new CropsNhNativeQaSupport(h, false);
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        String melonId = CropsNhNativeQaSupport.registeredCropId("Melon");
        String carrotId = CropsNhNativeQaSupport.registeredCropId("Carrot");
        String cucumberId = CropsNhNativeQaSupport.registeredCropId("Cucumber");

        EntityMaid maid = qa.spawnBreedingMaid("[QA] Cross Breeder", qa.cx - 1.5, qa.cropY, qa.cz + 0.5);
        requireBrainProfile(h, maid, MaidBrainProfile.ENHANCED);
        ItemStack bell = new ItemStack(MaidWorkItems.breedingBell);
        BreedingBellData.bind(bell, qa.world.provider.dimensionId, qa.cx, qa.soilY, qa.cz);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, bell);

        // 仅在 0 号槽放置真实 AdventureBackpack，铲子、作物架与双母本全部置于背包内，其余裸槽全空
        ItemStack advBag = CropsNhNativeQaSupport.createAdventureBackpack();
        h.assertTrue(backpacks.isSupportedBackpack(advBag), "AdventureBackpack was not recognized by CompatServices");
        h.assertTrue(
            backpacks.insertIntoBackpack(advBag, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1), false)
                == null,
            "Failed to insert reinforcedSpade into AdventureBackpack");
        h.assertTrue(
            backpacks.insertIntoBackpack(advBag, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64), false) == null,
            "Failed to insert cropSticks into AdventureBackpack");
        h.assertTrue(
            backpacks.insertIntoBackpack(advBag, CropsNhNativeQaSupport.createSeed(melonId, 20, 20, 20, true, 4), false)
                == null,
            "Failed to insert melon seeds into AdventureBackpack");
        h.assertTrue(
            backpacks
                .insertIntoBackpack(advBag, CropsNhNativeQaSupport.createSeed(carrotId, 20, 20, 20, true, 4), false)
                == null,
            "Failed to insert carrot seeds into AdventureBackpack");
        maid.getMaidInventory()
            .setInventorySlotContents(0, advBag);

        BreedingTaskConfigData cfg = maid.getBreedingTaskConfig();
        cfg.setTargetMode(TargetMode.CROP);
        cfg.setTargetId(cucumberId);
        cfg.setStatLogic(StatLogic.AND);
        cfg.setTargetGrowth(1);
        cfg.setTargetGain(1);
        cfg.setTargetResistance(1);
        cfg.setMax31Mode(false);

        EvaluationResult initialEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
        h.assertTrue(
            initialEval.missing.isEmpty() && initialEval.plan != null
                && initialEval.plan.status == PlanStatus.READY_DETERMINISTIC_CROSS,
            "Expected READY_DETERMINISTIC_CROSS from AdventureBackpack supplies, got: " + initialEval.missing);

        h.assertTrue(
            initialEval.plan.parentCropIds.contains(melonId) && initialEval.plan.parentCropIds.contains(carrotId),
            "Cucumber deterministic plan must preserve both melon and carrot parents");

        String foodPoolId = "cropsnh_mutationPool.food";
        boolean poolListed = false;
        for (BreedingCandidateEntry entry : compat.listInWorldCandidates(true)) {
            if (foodPoolId.equals(entry.id)) {
                poolListed = true;
                break;
            }
        }
        h.assertTrue(poolListed, "Expected cropsnh_mutationPool.food in listInWorldCandidates(true)");

        int[] phase = { 0 };
        boolean[] sawCucumberInAdvBag = { false };
        long[] lastAccel = { qa.world.getTotalWorldTime() };

        Runnable progress = qa.progressProbe("cross_pool", maid, () -> phase[0]);
        h.succeedWhen(() -> {
            progress.run();
            long now = qa.world.getTotalWorldTime();
            if (phase[0] == 0) {
                h.assertTrue(
                    CropsNhNativeQaSupport.countCropSeedInRawSlots(maid, cucumberId) == 0,
                    "Cucumber offspring bypassed AdventureBackpack into raw maid slots");
                ItemStack liveBag = maid.getMaidInventory()
                    .getStackInSlot(0);
                if (liveBag != null && CropsNhNativeQaSupport.countCropSeedInBackpack(liveBag, cucumberId) > 0) {
                    sawCucumberInAdvBag[0] = true;
                }
                if (now - lastAccel[0] >= 8) {
                    lastAccel[0] = now;
                    qa.accelerateParentsFirstThenCross(25, 20);
                }
                EvaluationResult eval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
                int cucumberParents = 0;
                for (int[] off : CropsNhNativeQaSupport.PARENT_OFFSETS) {
                    CropStickCellState st = compat
                        .inspectCropStickCell(qa.world, qa.cx + off[0], qa.cropY, qa.cz + off[1]);
                    if (st.kind == StickKind.PLANTED && cucumberId.equals(st.cropId)) {
                        cucumberParents++;
                    }
                }
                if (sawCucumberInAdvBag[0] && eval.plan != null
                    && eval.plan.status == PlanStatus.READY_PURIFY
                    && cucumberParents >= 1) {
                    // 切入变异池实际目标模式，并切换至 Legacy 原始脑验证双脑工作入口一致
                    MaidPolicyRuntime beforeSwitch = maid.getBrainCoordinator()
                        .peekPolicyRuntime();
                    h.assertTrue(
                        beforeSwitch != null && beforeSwitch.isReady()
                            && beforeSwitch.getProfile() == MaidBrainProfile.ENHANCED,
                        "Cucumber world cross and auto-purification must complete under Enhanced");
                    MaidBrainProfile previousProfile = beforeSwitch.getProfile();
                    MaidPolicyRuntime.SelectionResult switchResult = requireBrainProfile(
                        h,
                        maid,
                        MaidBrainProfile.LEGACY_PROTOTYPE);
                    MaidBrainProfile nextProfile = maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .getProfile();
                    System.out.println(
                        "TLMNH_BREEDING_PROFILE_SWITCH before=" + previousProfile
                            + " after="
                            + nextProfile
                            + " result="
                            + switchResult);
                    h.assertTrue(
                        switchResult == MaidPolicyRuntime.SelectionResult.SELECTED
                            && previousProfile == MaidBrainProfile.ENHANCED
                            && nextProfile == MaidBrainProfile.LEGACY_PROTOTYPE,
                        "Cucumber-to-pool handoff must perform an actual Enhanced-to-Legacy transition");
                    qa.resetThreeByThreeFoundation(Blocks.farmland, Blocks.dirt);
                    CropsNhNativeQaSupport.clearMaidRawInventory(maid);
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
                    maid.getMaidInventory()
                        .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64));
                    maid.getMaidInventory()
                        .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(melonId, 14, 14, 15, true, 4));
                    maid.getMaidInventory()
                        .setInventorySlotContents(3, CropsNhNativeQaSupport.createSeed(carrotId, 14, 14, 15, true, 4));
                    cfg.setTargetMode(TargetMode.MUTATION_POOL);
                    cfg.setTargetId(foodPoolId);
                    cfg.setStatLogic(StatLogic.OR);
                    cfg.setTargetGrowth(16);
                    cfg.setTargetGain(16);
                    cfg.setTargetResistance(16);
                    EvaluationResult poolEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
                    h.assertTrue(
                        poolEval.missing.isEmpty() && poolEval.plan != null
                            && poolEval.plan.status == PlanStatus.READY_POOL_CROSS,
                        "Expected READY_POOL_CROSS for cropsnh_mutationPool.food");
                    phase[0] = 1;
                    lastAccel[0] = now;
                }
                return false;
            }

            MaidPolicyRuntime active = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            h.assertTrue(
                active != null && active.isReady() && active.getProfile() == MaidBrainProfile.LEGACY_PROTOTYPE,
                "Mutation-pool world breeding must run under a ready Legacy profile");
            if (now - lastAccel[0] >= 8) {
                lastAccel[0] = now;
                qa.accelerateParentsFirstThenCross(25, 20);
            }
            SeedInspection poolOffspring = qa
                .findMatchingSeedInPlotOrMaid(maid, "", s -> cfg.meetsTargetStats(s.growth, s.gain, s.resistance));
            if (poolOffspring != null) {
                System.out.println(
                    "TLMNH_BREEDING_CROSS_POOL_PASS crossChild=" + cucumberId
                        + " advBagClosedLoop=true autoPurify=true pool="
                        + foodPoolId
                        + " poolOffspring="
                        + poolOffspring.cropId);
                return true;
            }
            return false;
        });
    }

    private static MaidPolicyRuntime.SelectionResult requireBrainProfile(GameTestHelper h, EntityMaid maid,
        MaidBrainProfile requested) {
        MaidPolicyRuntime.SelectionResult result = maid.selectBrainProfileForDevelopment(requested);
        MaidPolicyRuntime active = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        h.assertTrue(
            (result == MaidPolicyRuntime.SelectionResult.SELECTED
                || result == MaidPolicyRuntime.SelectionResult.UNCHANGED) && active != null
                && active.isReady()
                && active.getProfile() == requested,
            "Breeding profile request=" + requested
                + " result="
                + result
                + " active="
                + (active == null ? "none" : active.getProfile())
                + " ready="
                + (active != null && active.isReady()));
        System.out.println(
            "TLMNH_BREEDING_PROFILE requested=" + requested
                + " result="
                + result
                + " active="
                + active.getProfile()
                + " ready="
                + active.isReady());
        return result;
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2400, batch = "work_breeding_foundation")
    public static void stoneLilyAndDiareedReplaceWrongFoundationRecoverObsidianRejectBadToolsAndWearGtMiningTool(
        GameTestHelper h) {
        CropsNhNativeQaSupport qa = new CropsNhNativeQaSupport(h, false);
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        String stoneLilyId = CropsNhNativeQaSupport.registeredCropId("StoneLily");
        String diareedId = CropsNhNativeQaSupport.registeredCropId("Diareed");

        // 3x3 初始铺错误底土 dirt，并在两格下垫放置黑曜石（其余下垫放 dirt），验证黑曜石挖掘拒绝、回收与 GT 采矿工具磨损
        qa.resetThreeByThreeFoundation(Blocks.dirt, Blocks.dirt);
        qa.world.setBlock(qa.cx, qa.subSoilY, qa.cz, Blocks.obsidian, 0, 3);
        qa.world.setBlock(qa.cx - 1, qa.subSoilY, qa.cz - 1, Blocks.obsidian, 0, 3);

        qa.f.owner.setPosition(qa.cx - 6.0, qa.cropY, qa.cz + 0.5);
        EntityMaid maid = qa.spawnBreedingMaid("[QA] Foundation Miner", qa.cx - 5.5, qa.cropY, qa.cz + 0.5);
        ItemStack bell = new ItemStack(MaidWorkItems.breedingBell);
        BreedingBellData.bind(bell, qa.world.provider.dimensionId, qa.cx, qa.soilY, qa.cz);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, bell);

        maid.getMaidInventory()
            .setInventorySlotContents(0, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1));
        maid.getMaidInventory()
            .setInventorySlotContents(1, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64));
        maid.getMaidInventory()
            .setInventorySlotContents(2, CropsNhNativeQaSupport.createSeed(stoneLilyId, 18, 18, 20, true, 6));
        maid.getMaidInventory()
            .setInventorySlotContents(3, new ItemStack(Blocks.stone, 24));

        BreedingTaskConfigData cfg = maid.getBreedingTaskConfig();
        cfg.setTargetMode(TargetMode.CROP);
        cfg.setTargetId(stoneLilyId);
        cfg.setStatLogic(StatLogic.AND);
        cfg.setTargetGrowth(31);
        cfg.setTargetGain(31);
        cfg.setTargetResistance(31);

        // 1) 无采矿工具：必须报 MISSING_HARVEST_TOOL 且不破坏黑曜石
        EvaluationResult noToolEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
        h.assertTrue(
            noToolEval.missing.size() == 1
                && noToolEval.missing.get(0).code == MissingConditionCode.MISSING_HARVEST_TOOL,
            "Expected MISSING_HARVEST_TOOL without mining tool");

        ItemStack lowTool = CropsNhNativeQaSupport.createLowGtMiningToolRejectedBy(Blocks.obsidian);
        ItemStack highWrench = CropsNhNativeQaSupport.createGtTool("WRENCH", "Cobalt");
        ItemStack qualifiedTool = CropsNhNativeQaSupport.createQualifiedGtMiningToolFor(Blocks.obsidian);
        Item miningToolItem = qualifiedTool.getItem();
        int miningToolMeta = qualifiedTool.getItemDamage();
        h.assertTrue(
            CropsNhNativeQaSupport.getGtToolDamage(qualifiedTool) == 0L,
            "Fresh GT mining tool must start at 0 tool damage");

        h.assertTrue(
            !cfg.meetsTargetStats(18, 18, 20),
            "Foundation fixture must preserve mature parents below the requested breeding goal");
        boolean[] stoneConserved = { false };
        boolean[] diamondConserved = { false };
        int[] diamondStageSticks = { 0 };
        int[] phase = { 0 };
        long[] phaseSince = { qa.world.getTotalWorldTime() };
        long[] wearAfterStoneLily = { 0L };

        Runnable progress = qa.progressProbe("foundation", maid, () -> phase[0]);
        h.succeedWhen(() -> {
            progress.run();
            long now = qa.world.getTotalWorldTime();

            if (phase[0] == 0) {
                if (now - phaseSince[0] < 24) return false;
                h.assertTrue(
                    qa.world.getBlock(qa.cx, qa.subSoilY, qa.cz) == Blocks.obsidian,
                    "Obsidian broken without mining tool");
                // 2) 放入低等级 GT 采矿工具与高等级 GT 扳手：仍须拒绝且不产生磨损
                maid.getMaidInventory()
                    .setInventorySlotContents(4, lowTool);
                maid.getMaidInventory()
                    .setInventorySlotContents(5, highWrench);
                EvaluationResult lowEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
                h.assertTrue(
                    lowEval.missing.size() == 1
                        && lowEval.missing.get(0).code == MissingConditionCode.MISSING_HARVEST_TOOL,
                    "Low-tier GT mining tool or GT wrench falsely passed obsidian harvest check");
                phase[0] = 1;
                phaseSince[0] = now;
                return false;
            }

            if (phase[0] == 1) {
                if (now - phaseSince[0] < 24) return false;
                h.assertTrue(
                    qa.world.getBlock(qa.cx, qa.subSoilY, qa.cz) == Blocks.obsidian
                        && CropsNhNativeQaSupport.getGtToolDamage(lowTool) == 0L,
                    "Low-tier GT mining tool broke obsidian or took wear");
                // 3) 换上合格高等级 GT 采矿工具：女仆自主寻路靠近、换全田石质底土/下垫、回收黑曜石并真实磨损 GT 采矿工具
                maid.getMaidInventory()
                    .setInventorySlotContents(4, qualifiedTool);
                maid.getMaidInventory()
                    .setInventorySlotContents(5, null);
                EvaluationResult okEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
                h.assertTrue(
                    okEval.missing.isEmpty(),
                    "Qualified GT mining tool still reported missing: " + okEval.missing);
                phase[0] = 2;
                phaseSince[0] = now;
                return false;
            }

            if (phase[0] == 2) {
                if (now - phaseSince[0] >= 8) {
                    phaseSince[0] = now;
                    qa.accelerateParentsFirstThenCross(25, 0);
                }
                boolean allStoneReady = true;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (qa.world.getBlock(qa.cx + dx, qa.soilY, qa.cz + dz) != Blocks.stone
                            || qa.world.getBlock(qa.cx + dx, qa.subSoilY, qa.cz + dz) != Blocks.stone) {
                            allStoneReady = false;
                        }
                    }
                }
                if (allStoneReady && !stoneConserved[0]) {
                    h.assertTrue(
                        qa.countCropSticksInMaidAndPlot(maid) == 64,
                        "Stone foundation work lost or duplicated crop sticks before breeding");
                    stoneConserved[0] = true;
                    System.out.println("TLMNH_BREEDING_STICKS_CONSERVED stage=stone total=64");
                }
                int matureLilyParents = 0;
                for (int[] off : CropsNhNativeQaSupport.PARENT_OFFSETS) {
                    CropStickCellState st = compat
                        .inspectCropStickCell(qa.world, qa.cx + off[0], qa.cropY, qa.cz + off[1]);
                    if (st.kind == StickKind.PLANTED && stoneLilyId.equals(st.cropId)
                        && st.mature
                        && st.growthRequirementsMet) {
                        matureLilyParents++;
                    }
                }
                if (allStoneReady && matureLilyParents == 5) {
                    int recoveredObsidian = CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.obsidian);
                    int recoveredDirt = CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.dirt);
                    long wear = CropsNhNativeQaSupport.getGtToolDamage(qualifiedTool);
                    h.assertTrue(
                        recoveredObsidian == 2,
                        "Expected 2 recovered obsidian in maid inventory, got " + recoveredObsidian);
                    h.assertTrue(recoveredDirt == 16, "Expected exactly 16 recovered dirt, got " + recoveredDirt);
                    h.assertTrue(
                        CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.stone) == 6,
                        "Replacing 18 foundation blocks must consume exactly 18 of 24 stone");
                    h.assertTrue(wear > 0L, "Qualified GT mining tool took 0 tool damage mining obsidian");
                    wearAfterStoneLily[0] = wear;

                    // 4) 切换目标为 diareed，并把采矿工具、铲子、作物架、钻石块底矿与石底土全部收纳进 AdventureBackpack 验证嵌套取用与磨损归还
                    ItemStack advBag = CropsNhNativeQaSupport.createAdventureBackpack();
                    h.assertTrue(
                        backpacks
                            .insertIntoBackpack(advBag, CropsNhNativeQaSupport.cropsNhItem("reinforcedSpade", 1), false)
                            == null
                            && backpacks
                                .insertIntoBackpack(advBag, CropsNhNativeQaSupport.cropsNhItem("cropSticks", 64), false)
                                == null
                            && backpacks.insertIntoBackpack(advBag, qualifiedTool.copy(), false) == null
                            && backpacks.insertIntoBackpack(
                                advBag,
                                CropsNhNativeQaSupport.createSeed(diareedId, 18, 18, 20, true, 6),
                                false) == null
                            && backpacks.insertIntoBackpack(advBag, new ItemStack(Blocks.diamond_block, 12), false)
                                == null
                            && backpacks.insertIntoBackpack(advBag, new ItemStack(Blocks.stone, 18), false) == null,
                        "Failed to pack diareed supplies into AdventureBackpack");
                    diamondStageSticks[0] = 64 + qa.countCropSticksInPlot();
                    CropsNhNativeQaSupport.clearMaidRawInventory(maid);
                    maid.getMaidInventory()
                        .setInventorySlotContents(0, advBag);
                    cfg.setTargetId(diareedId);
                    EvaluationResult bagEval = CropsNhBreedingWorkController.evaluatePlotAndMissing(maid, cfg, compat);
                    h.assertTrue(
                        bagEval.missing.isEmpty(),
                        "Diareed supplies inside AdventureBackpack falsely reported missing: " + bagEval.missing);
                    phase[0] = 3;
                    phaseSince[0] = now;
                }
                return false;
            }

            if (now - phaseSince[0] >= 8) {
                phaseSince[0] = now;
                qa.accelerateParentsFirstThenCross(40, 0);
            }
            boolean allDiamondSubSoil = true;
            int soilStone = 0;
            int soilCobblestone = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    net.minecraft.block.Block soil = qa.world.getBlock(qa.cx + dx, qa.soilY, qa.cz + dz);
                    if (soil == Blocks.stone) soilStone++;
                    else if (soil == Blocks.cobblestone) soilCobblestone++;
                    CropsNhBreedingCompatProvider.CellFoundationCheck foundation = compat.checkCellFoundation(
                        qa.world,
                        qa.cx + dx,
                        qa.soilY,
                        qa.cz + dz,
                        diareedId,
                        java.util.Collections.emptyList());
                    if (qa.world.getBlock(qa.cx + dx, qa.subSoilY, qa.cz + dz) != Blocks.diamond_block
                        || !foundation.soilOk
                        || !foundation.subSoilOk
                        || soil != Blocks.stone && soil != Blocks.cobblestone) {
                        allDiamondSubSoil = false;
                    }
                }
            }
            if (allDiamondSubSoil && !diamondConserved[0]) {
                h.assertTrue(
                    qa.countCropSticksInMaidAndPlot(maid) == diamondStageSticks[0],
                    "Diamond foundation work lost or duplicated crop sticks before breeding");
                diamondConserved[0] = true;
                System.out.println("TLMNH_BREEDING_STICKS_CONSERVED stage=diamond total=" + diamondStageSticks[0]);
            }
            int matureDiareedParents = 0;
            for (int[] off : CropsNhNativeQaSupport.PARENT_OFFSETS) {
                CropStickCellState st = compat.inspectCropStickCell(qa.world, qa.cx + off[0], qa.cropY, qa.cz + off[1]);
                if (st.kind == StickKind.PLANTED && diareedId.equals(st.cropId)
                    && st.mature
                    && st.growthRequirementsMet) {
                    matureDiareedParents++;
                }
            }
            if (allDiamondSubSoil && matureDiareedParents == 5) {
                h.assertTrue(
                    CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.diamond_block) == 3,
                    "Nine subsoil cells must consume exactly 9 of 12 nested diamond blocks");
                // 旧底土与下垫的 18 个石头挖成圆石，回填可复用圆石；两种石料分别核账
                h.assertTrue(
                    CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.stone) + soilStone == 18,
                    "Supplied stone must remain exactly 18 across inventory and valid soil");
                h.assertTrue(
                    CropsNhNativeQaSupport.countBlockItemsInMaid(maid, Blocks.cobblestone) + soilCobblestone == 18,
                    "Mining 18 old stone foundation blocks must conserve exactly 18 cobblestone");
                h.assertTrue(
                    stoneConserved[0] && diamondConserved[0],
                    "Both foundation stages must pass crop-stick conservation before breeding");
                ItemStack returnedTool = CropsNhNativeQaSupport.findStackInMaidOrBackpacks(
                    maid,
                    s -> s.getItem() == miningToolItem && s.getItemDamage() == miningToolMeta);
                h.assertTrue(returnedTool != null, "Worn GT mining tool was lost after mining from AdventureBackpack");
                long finalWear = CropsNhNativeQaSupport.getGtToolDamage(returnedTool);
                h.assertTrue(
                    finalWear > wearAfterStoneLily[0],
                    "Expected additional GT mining tool wear mining stone foundation from AdventureBackpack");
                System.out.println(
                    "TLMNH_BREEDING_FOUNDATION_PASS obsidianRecovered=2 wearStoneLily=" + wearAfterStoneLily[0]
                        + " wearDiareed="
                        + finalWear
                        + " diamondSubSoil=9 soilStone="
                        + soilStone
                        + " soilCobblestone="
                        + soilCobblestone
                        + " advBagToolsAndBlocks=true");
                return true;
            }
            return false;
        });
    }
}
