package com.tianluoqaq.tlmnh.compat.battlegear2.common.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import cpw.mods.fml.common.registry.GameRegistry;

/** 提供方物品、音效与盾牌接口；外部类型保持在惰性API类中 */
public final class BattlegearShieldAccess {

    public static final String BLOCK_SOUND = "battlegear2:shield";
    private static final Set<String> IDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "battlegear2:shield.wood",
                "battlegear2:shield.hide",
                "battlegear2:shield.iron",
                "battlegear2:shield.diamond",
                "battlegear2:shield.gold")));

    private BattlegearShieldAccess() {}

    public static boolean supports(String id) {
        return IDS.contains(id);
    }

    public static Item woodenShieldItem() {
        return GameRegistry.findItem("battlegear2", "shield.wood");
    }

    public static boolean isShield(ItemStack stack) {
        return Api.isShield(stack);
    }

    public static boolean canBlock(ItemStack stack, DamageSource source) {
        return Api.canBlock(stack, source);
    }

    public static float blockAngle(ItemStack stack) {
        return Api.blockAngle(stack);
    }

    public static float reduction(ItemStack stack, DamageSource source) {
        return Api.reduction(stack, source);
    }

    public static Class<?> apiType() {
        return Api.type();
    }

    private static final class Api {

        static boolean isShield(ItemStack stack) {
            return stack.getItem() instanceof mods.battlegear2.api.shield.IShield;
        }

        static boolean canBlock(ItemStack stack, DamageSource source) {
            return ((mods.battlegear2.api.shield.IShield) stack.getItem()).canBlock(stack, source);
        }

        static float blockAngle(ItemStack stack) {
            return ((mods.battlegear2.api.shield.IShield) stack.getItem()).getBlockAngle(stack);
        }

        static float reduction(ItemStack stack, DamageSource source) {
            return ((mods.battlegear2.api.shield.IShield) stack.getItem()).getDamageReduction(stack, source);
        }

        static Class<?> type() {
            return mods.battlegear2.api.shield.IShield.class;
        }
    }
}
