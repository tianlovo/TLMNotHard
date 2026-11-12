package com.tianluoqaq.tlmnh.client.gui.work;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerProtocol;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;

/** 任务列表的显示顺序、原生动作号与完整物品图标 */
public final class MaidWorkTaskMenu {

    private static final List<MaidTask> UNIFIED = Collections.unmodifiableList(
        Arrays.asList(MaidTask.IDLE, MaidTask.ATTACK, MaidTask.MAINTENANCE_HATCH, MaidTask.CROPSNH_BREEDING));
    private static final List<MaidTask> SEPARATE = Collections.unmodifiableList(
        Arrays.asList(
            MaidTask.IDLE,
            MaidTask.ATTACK,
            MaidTask.RANGED_ATTACK,
            MaidTask.MAINTENANCE_HATCH,
            MaidTask.CROPSNH_BREEDING));

    private MaidWorkTaskMenu() {}

    public static List<MaidTask> tasks(boolean unifiedCombat) {
        return unifiedCombat ? UNIFIED : SEPARATE;
    }

    public static int action(MaidTask task) {
        return switch (task) {
            case IDLE -> MaidContainerProtocol.ACTION_IDLE;
            case ATTACK -> MaidContainerProtocol.ACTION_ATTACK;
            case RANGED_ATTACK -> MaidContainerProtocol.ACTION_RANGED;
            case MAINTENANCE_HATCH -> MaidContainerProtocol.ACTION_MAINTENANCE_HATCH;
            case CROPSNH_BREEDING -> MaidContainerProtocol.ACTION_CROPSNH_BREEDING;
        };
    }

    public static MaidTask task(int action) {
        return switch (action) {
            case MaidContainerProtocol.ACTION_IDLE -> MaidTask.IDLE;
            case MaidContainerProtocol.ACTION_ATTACK -> MaidTask.ATTACK;
            case MaidContainerProtocol.ACTION_RANGED -> MaidTask.RANGED_ATTACK;
            case MaidContainerProtocol.ACTION_MAINTENANCE_HATCH -> MaidTask.MAINTENANCE_HATCH;
            case MaidContainerProtocol.ACTION_CROPSNH_BREEDING -> MaidTask.CROPSNH_BREEDING;
            default -> null;
        };
    }

    public static String titleKey(MaidTask task, boolean unifiedCombat) {
        return switch (task) {
            case IDLE -> MaidWorkGuiIds.IDLE;
            case ATTACK -> unifiedCombat ? MaidWorkGuiIds.UNIFIED_ATTACK : MaidWorkGuiIds.ATTACK;
            case RANGED_ATTACK -> MaidWorkGuiIds.RANGED_ATTACK;
            case MAINTENANCE_HATCH -> MaidWorkGuiIds.MAINTENANCE;
            case CROPSNH_BREEDING -> MaidWorkGuiIds.BREEDING;
        };
    }

    public static ItemStack icon(MaidTask task) {
        return switch (task) {
            case IDLE -> new ItemStack(Items.feather);
            case ATTACK -> new ItemStack(Items.diamond_sword);
            case RANGED_ATTACK -> new ItemStack(Items.bow);
            case MAINTENANCE_HATCH -> {
                var icon = CompatServices.maintenanceHatch()
                    .taskIcon();
                yield icon == null ? new ItemStack(MaidWorkItems.maintenanceBell) : icon;
            }
            case CROPSNH_BREEDING -> {
                var icon = CompatServices.cropsNhBreeding()
                    .taskIcon();
                yield icon == null ? new ItemStack(MaidWorkItems.breedingBell) : icon;
            }
        };
    }
}
