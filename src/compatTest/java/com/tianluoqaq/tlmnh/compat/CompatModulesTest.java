package com.tianluoqaq.tlmnh.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;

/** 核对 16 个外部模组描述类的包名映射、版本元数据与 ExternalFeature 依赖覆盖 */
public class CompatModulesTest {

    @Test
    public void allDescriptorsFollowPackageNamingAndMetadataInvariants() {
        var descriptors = CompatModules.all();
        assertEquals(16, descriptors.size());
        Set<String> modIds = new HashSet<>();
        Set<String> packages = new HashSet<>();
        for (CompatDescriptor descriptor : descriptors) {
            assertNotNull(descriptor);
            String modId = CompatDescriptor.requireNonEmpty(descriptor.modId(), "modId");
            String pkg = CompatDescriptor.requireNonEmpty(descriptor.packageSegment(), "packageSegment");
            CompatDescriptor.requireNonEmpty(descriptor.reviewedVersion(), "reviewedVersion");
            CompatDescriptor.requireNonEmpty(descriptor.capabilitySummary(), "capabilitySummary");
            assertNotNull(descriptor.sideScope());
            assertNotNull(descriptor.primaryPhase());
            assertNotNull(descriptor.requiredPeerModIds());

            String expectedPkg = modId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "");
            assertEquals("Package segment mismatch for " + modId, expectedPkg, pkg);
            assertEquals(
                "Descriptor class must reside in its own compat.<modpkg> package",
                "com.tianluoqaq.tlmnh.compat." + pkg,
                descriptor.getClass()
                    .getPackage()
                    .getName());
            assertTrue("Duplicate modId: " + modId, modIds.add(modId));
            assertTrue("Duplicate package: " + pkg, packages.add(pkg));
        }
    }

    @Test
    public void externalFeatureDependenciesAreCoveredByDescriptorsOrCorePlatform() throws Exception {
        Set<String> known = new HashSet<>();
        for (CompatDescriptor descriptor : CompatModules.all()) {
            known.add(descriptor.modId());
            known.addAll(descriptor.requiredPeerModIds());
        }
        known.add(KnownModIds.DREAMCRAFT);

        Field depsField = ExternalFeature.class.getDeclaredField("dependencies");
        depsField.setAccessible(true);
        for (ExternalFeature feature : ExternalFeature.values()) {
            String[] deps = (String[]) depsField.get(feature);
            for (String dep : deps) {
                assertTrue("Feature " + feature.id + " references unregistered modId " + dep, known.contains(dep));
            }
        }
    }

    @Test
    public void serviceLoaderBindsCompatProvidersAutomatically() {
        assertFalse(
            "ServiceLoader should replace NOOP shield provider on compatTest classpath",
            CompatServices.shields() == com.tianluoqaq.tlmnh.common.combat.spi.ShieldCompatProvider.NOOP);
        assertFalse(
            "ServiceLoader should replace NOOP door provider on compatTest classpath",
            CompatServices.doors() == com.tianluoqaq.tlmnh.common.ai.spi.DoorCompatProvider.NOOP);
    }
}
