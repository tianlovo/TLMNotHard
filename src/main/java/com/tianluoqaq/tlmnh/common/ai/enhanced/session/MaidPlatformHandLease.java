package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;

/** 平台统一手部使用权签发器；自动绑定当前策略运行时、Token 与暂停状态守卫 */
public final class MaidPlatformHandLease implements MaidPlatformBehaviorContext.IsolatedHandAccess.Backend {

    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final Supplier<MaidPolicyRuntime> runtimeSupplier;
    private final BooleanSupplier notSuspendedGuard;

    public MaidPlatformHandLease(EntityMaid maid, ModernFollowCoreActor actor,
        Supplier<MaidPolicyRuntime> runtimeSupplier, BooleanSupplier notSuspendedGuard) {
        this.maid = Objects.requireNonNull(maid, EnhancedSessionIds.ID_MAID);
        this.actor = Objects.requireNonNull(actor, EnhancedSessionIds.ID_ACTOR);
        this.runtimeSupplier = Objects.requireNonNull(runtimeSupplier, EnhancedSessionIds.ID_RUNTIMESUPPLIER);
        this.notSuspendedGuard = Objects.requireNonNull(notSuspendedGuard, EnhancedSessionIds.ID_NOTSUSPENDEDGUARD);
    }

    /** 供内置模块按当前真实手持引用申请租约，免除业务模块直接访问顶层协调器 */
    public Lease tryAcquireExact(Object owner, ItemStack item, Hand hand, Kind kind, BooleanSupplier extraGuard) {
        Objects.requireNonNull(owner, EnhancedSessionIds.ID_OWNER);
        Objects.requireNonNull(hand, EnhancedSessionIds.ID_HAND);
        Objects.requireNonNull(kind, EnhancedSessionIds.ID_KIND);
        if (item == null || !actor.isCurrent()) return null;
        var runtime = runtimeSupplier.get();
        if (runtime == null || !runtime.isReady()) return null;
        var token = runtime.getContext()
            .getToken();
        World expectedWorld = maid.worldObj;
        String expectedOwner = maid.func_152113_b();
        return maid.getItemUseState()
            .tryAcquire(
                owner,
                item,
                hand,
                kind,
                () -> maid.worldObj == expectedWorld && expectedWorld != null
                    && !expectedWorld.isRemote
                    && maid.isEntityAlive()
                    && Objects.equals(expectedOwner, maid.func_152113_b())
                    && currentHandStack(hand) == item
                    && runtimeSupplier.get() == runtime
                    && runtime.isReady()
                    && runtime.getContext()
                        .isCurrent(token)
                    && notSuspendedGuard.getAsBoolean()
                    && (extraGuard == null || extraGuard.getAsBoolean()));
    }

    /** 供外部扩展模块申请租约；初始判定与每帧续期均只向校验器传递物品独立副本 */
    public Lease tryAcquireCopyValidated(Object owner, Hand hand, Kind kind, Predicate<ItemStack> itemValidator,
        BooleanSupplier extraGuard) {
        Objects.requireNonNull(owner, EnhancedSessionIds.ID_OWNER);
        Objects.requireNonNull(hand, EnhancedSessionIds.ID_HAND);
        Objects.requireNonNull(kind, EnhancedSessionIds.ID_KIND);
        Objects.requireNonNull(itemValidator, EnhancedSessionIds.ID_ITEMVALIDATOR);
        if (!actor.isCurrent()) return null;
        var live = currentHandStack(hand);
        if (live == null || live.stackSize <= 0) return null;
        if (!itemValidator.test(live.copy())) return null;
        if (!actor.isCurrent() || currentHandStack(hand) != live) return null;
        return tryAcquireExact(owner, live, hand, kind, () -> {
            var current = currentHandStack(hand);
            if (current == null || current.stackSize <= 0 || current != live) return false;
            return itemValidator.test(current.copy()) && (extraGuard == null || extraGuard.getAsBoolean());
        });
    }

    public boolean isCurrent(Lease lease) {
        return maid.getItemUseState()
            .isCurrent(lease);
    }

    public boolean release(Lease lease) {
        return maid.getItemUseState()
            .release(lease);
    }

    private ItemStack currentHandStack(Hand hand) {
        return hand == Hand.MAIN ? maid.getHeldItem() : maid.getOffhandItem();
    }
}
