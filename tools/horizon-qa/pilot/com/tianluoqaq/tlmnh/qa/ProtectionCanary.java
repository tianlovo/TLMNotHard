package com.tianluoqaq.tlmnh.qa;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 仅独立测试模组提供，跨重启变体移除此方法以真实改变提供方代码与API */
public final class ProtectionCanary extends Item {

    public static boolean rejectDamageQueries;

    public ProtectionCanary() {
        setUnlocalizedName("tlmnhqapilot.protection_canary");
        setTextureName("minecraft:golden_apple");
    }

    public static int apiRevision() {
        return 1;
    }

    @Override
    public int getDamage(ItemStack stack) {
        if (rejectDamageQueries)
            throw new NoSuchMethodError("QA disallows an external damage callback during persistence");
        return super.getDamage(stack);
    }
}
