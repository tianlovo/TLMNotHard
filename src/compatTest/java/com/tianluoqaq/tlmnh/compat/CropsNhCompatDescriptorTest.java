package com.tianluoqaq.tlmnh.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.maintenance.spi.MaintenanceHatchCompatProvider;
import com.tianluoqaq.tlmnh.compat.cropsnh.CropsNhCompat;
import com.tianluoqaq.tlmnh.compat.cropsnh.CropsNhIds;

/** 验证维护仓与 CropsNH 兼容描述符及离线安全降级边界 */
public class CropsNhCompatDescriptorTest {

    @Test
    public void cropsNhDescriptorMatchesVerifiedGtnhJarMetadata() {
        assertEquals(KnownModIds.CROPS_NH, CropsNhCompat.INSTANCE.modId());
        assertEquals(CropsNhIds.PACKAGE, CropsNhCompat.INSTANCE.packageSegment());
        assertEquals(CropsNhIds.REVIEWED_VERSION, CropsNhCompat.INSTANCE.reviewedVersion());
        assertEquals(CompatDescriptor.SideScope.COMMON, CropsNhCompat.INSTANCE.sideScope());
    }

    @Test
    public void noopProvidersFailClosedWithoutLoadingOptionalMods() {
        assertFalse(MaintenanceHatchCompatProvider.NOOP.isAvailable());
        assertFalse(CropsNhBreedingCompatProvider.NOOP.isAvailable());
        var plan = CropsNhBreedingCompatProvider.NOOP.resolveTargetPlan(false, "", null);
        assertEquals(CropsNhBreedingCompatProvider.PlanStatus.UNSUPPORTED_IN_WORLD, plan.status);
    }
}
