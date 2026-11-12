package com.tianluoqaq.tlmnh.common.work.maintenance.spi;

import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** 普通格雷维护仓检测、副手格雷扳手准入与无损检修窄接口 */
public interface MaintenanceHatchCompatProvider {

    MaintenanceHatchCompatProvider NOOP = new MaintenanceHatchCompatProvider() {};

    final class MaintenanceTarget {

        public final int x;
        public final int y;
        public final int z;
        public final int repairDeficit;

        public MaintenanceTarget(int x, int y, int z, int repairDeficit) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.repairDeficit = repairDeficit;
        }
    }

    default boolean isAvailable() {
        return false;
    }

    default ItemStack taskIcon() {
        return null;
    }

    default boolean isValidMaintenanceWrench(ItemStack offhandStack) {
        return false;
    }

    default boolean isOrdinaryMaintenanceHatch(World world, int x, int y, int z) {
        return false;
    }

    default List<MaintenanceTarget> findNeedingMaintenanceHatches(World world, List<int[]> boundChunks) {
        return Collections.emptyList();
    }

    default boolean performZeroSideEffectMaintenance(World world, int x, int y, int z) {
        return false;
    }
}
