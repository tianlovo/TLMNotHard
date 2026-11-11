package com.tianluoqaq.tlmnh.api.behavior;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;

/** 仅在获胜会话 execute 阶段开放的手部使用权入口；校验器始终只接收物品只读副本 */
public interface MaidHandAccess {

    Lease tryAcquireHand(Hand hand, Kind kind, Predicate<ItemStack> itemValidator, BooleanSupplier extraGuard);

    boolean isCurrent(Lease lease);

    boolean releaseHand(Lease lease);
}
