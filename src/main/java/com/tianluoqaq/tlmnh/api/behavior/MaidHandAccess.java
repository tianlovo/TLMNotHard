package com.tianluoqaq.tlmnh.api.behavior;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;

/** 仅在获胜会话 execute 阶段开放的手部使用权入口；校验器始终只接收物品只读副本 */
public interface MaidHandAccess {

    enum Hand {
        MAIN,
        OFF
    }

    enum Kind {
        BOW,
        SHIELD,
        FOOD,
        SNOWBALL
    }

    /** 手部持续使用租约句柄；不含实体或可变库存引用 */
    interface Lease {

        Hand hand();

        Kind kind();

        long generation();
    }

    Lease tryAcquireHand(Hand hand, Kind kind, Predicate<ItemStack> itemValidator, BooleanSupplier extraGuard);

    boolean isCurrent(Lease lease);

    boolean releaseHand(Lease lease);
}
