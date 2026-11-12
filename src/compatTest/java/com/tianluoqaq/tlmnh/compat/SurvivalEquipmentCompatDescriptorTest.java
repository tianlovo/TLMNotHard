package com.tianluoqaq.tlmnh.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.combat.spi.HealingAxeCompatProvider;
import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider;
import com.tianluoqaq.tlmnh.common.life.fall.spi.FallRescueBoatCompatProvider;
import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider;
import com.tianluoqaq.tlmnh.common.life.hazard.spi.HotItemHazardCompatProvider;
import com.tianluoqaq.tlmnh.common.life.hunger.spi.HungerPolicyCompatProvider;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider;
import com.tianluoqaq.tlmnh.compat.galaxyspace.GalaxySpaceCompat;
import com.tianluoqaq.tlmnh.compat.gravisuite.GraviSuiteCompat;
import com.tianluoqaq.tlmnh.compat.hungeroverhaul.HungerOverhaulCompat;
import com.tianluoqaq.tlmnh.compat.ic2.Ic2Compat;

/** 验证生存与装备适配四个新模组描述符元数据及离线安全降级行为 */
public class SurvivalEquipmentCompatDescriptorTest {

    @Test
    public void descriptorsMatchVerifiedGtnhModIdsAndCommonScope() {
        assertEquals(KnownModIds.HUNGER_OVERHAUL, HungerOverhaulCompat.INSTANCE.modId());
        assertEquals(CompatDescriptor.SideScope.COMMON, HungerOverhaulCompat.INSTANCE.sideScope());

        assertEquals(KnownModIds.IC2, Ic2Compat.INSTANCE.modId());
        assertEquals(CompatDescriptor.SideScope.COMMON, Ic2Compat.INSTANCE.sideScope());

        assertEquals(KnownModIds.GRAVI_SUITE, GraviSuiteCompat.INSTANCE.modId());
        assertEquals(CompatDescriptor.SideScope.COMMON, GraviSuiteCompat.INSTANCE.sideScope());
        assertTrue(
            GraviSuiteCompat.INSTANCE.requiredPeerModIds()
                .contains(KnownModIds.IC2));

        assertEquals(KnownModIds.GALAXY_SPACE, GalaxySpaceCompat.INSTANCE.modId());
        assertEquals(CompatDescriptor.SideScope.COMMON, GalaxySpaceCompat.INSTANCE.sideScope());
        assertTrue(
            GalaxySpaceCompat.INSTANCE.requiredPeerModIds()
                .contains(KnownModIds.GALACTICRAFT_CORE));
    }

    @Test
    public void noopProvidersRemainSafeWithoutOptionalModsLoaded() {
        assertFalse(HungerPolicyCompatProvider.NOOP.isHungerOverhaulActive());
        assertEquals(18, HungerPolicyCompatProvider.NOOP.minHungerToHeal(18));
        assertTrue(HungerPolicyCompatProvider.NOOP.drainsExhaustionOnHeal());

        assertFalse(HealingAxeCompatProvider.NOOP.isHealingAxe(null));
        assertEquals(
            HealingAxeCompatProvider.StrikeKind.NOT_APPLICABLE,
            HealingAxeCompatProvider.NOOP.resolveMainhandStrike(null, null).kind);

        assertFalse(HotItemHazardCompatProvider.NOOP.isAvailable());
        assertEquals(0.0F, HotItemHazardCompatProvider.NOOP.inspectItemHeatDamage(null), 0.0001F);

        assertFalse(SpecialArmorCompatProvider.NOOP.canBootsFullyAbsorbFall(null, null, 12.0F));
        assertEquals(
            FlightEquipmentCompatProvider.FlightCapability.NONE,
            FlightCompatDispatcher.INSTANCE.inspectChestArmor(null, false));
        assertFalse(SpaceSurvivalCompatProvider.NOOP.isAvailable());
        assertTrue(SpaceSurvivalCompatProvider.NOOP.sampleAtmosphere(null).breathableAtmosphere);
        assertFalse(FallRescueBoatCompatProvider.NOOP.isCustomBoatItem(null));
    }
}
