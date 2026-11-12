package com.tianluoqaq.tlmnh.compat;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider;
import com.tianluoqaq.tlmnh.compat.adventurebackpack.common.flight.AdventureBackpackFlightCompat;
import com.tianluoqaq.tlmnh.compat.gravisuite.common.flight.GraviSuiteFlightCompat;
import com.tianluoqaq.tlmnh.compat.ic2.common.flight.Ic2FlightCompat;

/** 四类飞行与喷气背包统一零耗能能力分发器，避免各模组适配子包之间横向耦合 */
public final class FlightCompatDispatcher implements FlightEquipmentCompatProvider {

    public static final FlightCompatDispatcher INSTANCE = new FlightCompatDispatcher();

    private FlightCompatDispatcher() {}

    @Override
    public FlightCapability inspectChestArmor(ItemStack chestStack, boolean maidInWater) {
        if (chestStack == null || chestStack.stackSize <= 0) return FlightCapability.NONE;
        FlightCapability gravi = GraviSuiteFlightCompat.inspectChest(chestStack);
        if (gravi != FlightCapability.NONE) return gravi;
        FlightCapability ic2 = Ic2FlightCompat.inspectChest(chestStack);
        if (ic2 != FlightCapability.NONE) return ic2;
        return AdventureBackpackFlightCompat.inspect(chestStack, maidInWater);
    }

    @Override
    public FlightCapability inspectBauble(ItemStack baubleStack, boolean maidInWater) {
        if (baubleStack == null || baubleStack.stackSize <= 0) return FlightCapability.NONE;
        FlightCapability gravi = GraviSuiteFlightCompat.inspectBauble(baubleStack);
        if (gravi != FlightCapability.NONE) return gravi;
        FlightCapability ic2 = Ic2FlightCompat.inspectBauble(baubleStack);
        if (ic2 != FlightCapability.NONE) return ic2;
        return AdventureBackpackFlightCompat.inspect(baubleStack, maidInWater);
    }

    @Override
    public boolean isWearableFlightBauble(ItemStack stack) {
        return AdventureBackpackFlightCompat.isWearableFlightPack(stack) || Ic2FlightCompat.isStandaloneJetpack(stack)
            || GraviSuiteFlightCompat.isStandaloneJetpack(stack);
    }
}
