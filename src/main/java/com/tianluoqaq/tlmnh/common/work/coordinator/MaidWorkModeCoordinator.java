package com.tianluoqaq.tlmnh.common.work.coordinator;

import java.util.function.Function;
import java.util.function.Supplier;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingWorkIds;
import com.tianluoqaq.tlmnh.common.work.breeding.BreedingWorkTuning;
import com.tianluoqaq.tlmnh.common.work.breeding.CropsNhBreedingWorkController;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.maintenance.MaintenanceHatchWorkController;
import com.tianluoqaq.tlmnh.common.work.maintenance.MaintenanceWorkIds;
import com.tianluoqaq.tlmnh.common.work.maintenance.MaintenanceWorkTuning;
import com.tianluoqaq.tlmnh.common.work.maintenance.spi.MaintenanceHatchCompatProvider;
import com.tianluoqaq.tlmnh.common.work.notify.MaidMissingConditionNotifier;

/**
 * 维护仓与育种工作模式统一调度器：
 * 同时供 EnhancedCoreBrain（意图仲裁帧）、MaidFaithfulBrain 与 MaidLegacyBrainHost 调用，保证三脑语义一致
 */
public final class MaidWorkModeCoordinator {

    public static final MaidBehaviorId MAINTENANCE_MODULE_ID = MaidBehaviorId
        .builtin(MaintenanceWorkIds.MODULE_PATH);
    public static final MaidBehaviorId BREEDING_MODULE_ID = MaidBehaviorId
        .builtin(BreedingWorkIds.MODULE_PATH);

    private final MaintenanceHatchWorkController maintenance = new MaintenanceHatchWorkController();
    private final CropsNhBreedingWorkController breeding = new CropsNhBreedingWorkController();
    private final MaidMissingConditionNotifier notifier = new MaidMissingConditionNotifier();
    private final Supplier<MaintenanceHatchCompatProvider> maintenanceCompat;
    private final Supplier<CropsNhBreedingCompatProvider> breedingCompat;
    private final Function<EntityMaid, BreedingTaskConfigData> configAccessor;
    private MaidTask lastObservedTask;

    public MaidWorkModeCoordinator(Supplier<MaintenanceHatchCompatProvider> maintenanceCompat,
        Supplier<CropsNhBreedingCompatProvider> breedingCompat,
        Function<EntityMaid, BreedingTaskConfigData> configAccessor) {
        this.maintenanceCompat = maintenanceCompat;
        this.breedingCompat = breedingCompat;
        this.configAccessor = configAccessor;
    }

    public void reset() {
        maintenance.reset();
        breeding.reset();
        notifier.reset();
        lastObservedTask = null;
    }

    public boolean isInWorkSchedule(EntityMaid maid) {
        if (maid == null || maid.worldObj == null) return false;
        Activity activity = MaidLifeRules.activity(maid.getSchedule(), maid.worldObj.getWorldTime());
        return activity == Activity.WORK;
    }

    public boolean shouldSuppressOwnerFollowAndHome(EntityMaid maid) {
        if (maid == null || !isInWorkSchedule(maid)) return false;
        MaidTask task = maid.getTask();
        if (task == MaidTask.MAINTENANCE_HATCH) {
            return maintenance.hasActiveBellDomain(maid);
        }
        if (task == MaidTask.CROPSNH_BREEDING) {
            return breeding.hasActiveBellDomain(maid);
        }
        return false;
    }

    public void offerEnhanced(EntityMaid maid, MaidIntentFrame frame) {
        if (maid == null || frame == null || !maid.getTask().isWorkTask() || !isInWorkSchedule(maid)) {
            syncTaskResetIfChanged(maid);
            return;
        }
        if (maid.isSitting() || maid.isRiding() || maid.isPlayerSleeping()) return;
        MaidTask task = maid.getTask();
        if (task == MaidTask.MAINTENANCE_HATCH) {
            frame.offerTyped(
                MAINTENANCE_MODULE_ID,
                Group.CARE,
                Kind.EXTENSION,
                MaintenanceWorkIds.TARGET_IDLE_CHECK,
                MaintenanceWorkTuning.WORK_UTILITY,
                maid,
                this::tickActiveWork);
        } else if (task == MaidTask.CROPSNH_BREEDING) {
            frame.offerTyped(
                BREEDING_MODULE_ID,
                Group.CARE,
                Kind.EXTENSION,
                BreedingWorkIds.TARGET_BREED_PLOT,
                BreedingWorkTuning.WORK_UTILITY,
                maid,
                this::tickActiveWork);
        }
    }

    public boolean tickStandalone(EntityMaid maid) {
        if (maid == null || !maid.getTask().isWorkTask() || !isInWorkSchedule(maid)) {
            syncTaskResetIfChanged(maid);
            return false;
        }
        if (maid.isSitting() || maid.isRiding() || maid.isPlayerSleeping()) return false;
        tickActiveWork(maid);
        return true;
    }

    private void tickActiveWork(EntityMaid maid) {
        syncTaskResetIfChanged(maid);
        MaidTask task = maid.getTask();
        if (task == MaidTask.MAINTENANCE_HATCH) {
            maintenance.tick(maid, maintenanceCompat.get(), notifier);
        } else if (task == MaidTask.CROPSNH_BREEDING) {
            breeding.tick(maid, configAccessor.apply(maid), breedingCompat.get(), notifier);
        }
    }

    private void syncTaskResetIfChanged(EntityMaid maid) {
        MaidTask nowTask = maid == null ? null : maid.getTask();
        if (nowTask != lastObservedTask) {
            maintenance.reset();
            breeding.reset();
            notifier.reset();
            lastObservedTask = nowTask;
        }
    }
}
