package com.tianluoqaq.tlmnh.common.inventory.bauble;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;

/** 只接纳女仆饰品接口及显式绑定的物品适配器 */
public final class MaidBaubleRegistry {

    private static final IMaidBauble EQUIPMENT_ADAPTER = new IMaidBauble() {};
    private static final Map<Item, IMaidBauble> ADAPTERS = new IdentityHashMap<>();

    private MaidBaubleRegistry() {}

    public static void register(Item item, IMaidBauble bauble) {
        ADAPTERS.put(
            Objects.requireNonNull(item, MaidBaubleDiagnostics.ITEM_REQUIRED),
            Objects.requireNonNull(bauble, MaidBaubleDiagnostics.ADAPTER_REQUIRED));
    }

    public static IMaidBauble getBauble(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return null;
        if (stack.getItem() instanceof IMaidBauble bauble) return bauble;
        var registered = ADAPTERS.get(stack.getItem());
        if (registered != null) return registered;
        // 外部装备必须来自实际物品注册表，未注册物品不触发可选提供方加载
        if (Item.itemRegistry.getNameForObject(stack.getItem()) == null) return null;
        return CompatServices.spaceSurvival()
            .isSpaceBaubleItem(stack)
            || CompatServices.flightEquipment()
                .isWearableFlightBauble(stack) ? EQUIPMENT_ADAPTER : null;
    }
}
