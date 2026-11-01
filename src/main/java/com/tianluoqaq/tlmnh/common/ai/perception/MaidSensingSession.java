package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.EnumMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Kind;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Request;

/** 实例绑定构造时的世界和身份；取消仅释放本实例的扫描请求 */
public final class MaidSensingSession {

    private final World world;
    private final UUID scope = UUID.randomUUID();
    private final MaidSensingBudget budget;
    private final BooleanSupplier current;
    private final EnumMap<Kind, Request> requests = new EnumMap<>(Kind.class);

    public MaidSensingSession(World world, UUID actor, BooleanSupplier current) {
        this.world = world;
        this.current = current;
        budget = MaidWorldSensingBudget.forWorld(world);
        for (var kind : Kind.values()) requests.put(kind, new Request(actor, scope, kind));
    }

    public boolean acquire(Kind kind) {
        if (!current.getAsBoolean()) throw new IllegalStateException("Expired sensing session");
        return budget.acquire(requests.get(kind), world.getTotalWorldTime());
    }

    public void cancel(Kind kind) {
        budget.cancel(requests.get(kind));
    }

    public void cancelAll() {
        budget.cancelScope(scope);
    }

    public boolean queued(Kind kind) {
        return budget.queued(requests.get(kind));
    }
}
