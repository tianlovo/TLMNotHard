package com.tianluoqaq.tlmnh.common.inventory.bauble;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;

/** 只接纳女仆饰品接口及显式绑定的物品适配器 */
public final class MaidBaubleRegistry {

    private static final Map<Item, IMaidBauble> ADAPTERS = new IdentityHashMap<>();

    private MaidBaubleRegistry() {}

    public static void register(Item item, IMaidBauble bauble) {
        ADAPTERS.put(
            Objects.requireNonNull(item, MaidBaubleDiagnostics.ITEM_REQUIRED),
            Objects.requireNonNull(bauble, MaidBaubleDiagnostics.ADAPTER_REQUIRED));
    }

    public static IMaidBauble getBauble(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return null;
        return stack.getItem() instanceof IMaidBauble bauble ? bauble : ADAPTERS.get(stack.getItem());
    }
}
