package com.tianluoqaq.tlmnh.common.ai.policy;

/** 单实体同一原生AI tick的预约只可消费一次；清理不会重新开放本tick的预约 */
public final class MaidAfterNavigationGate {

    private Object bookedWorld;
    private long bookedWorldTick;
    private int bookedEntityTick;
    private boolean hasBookedStamp;
    private MaidPolicyRuntime pendingRuntime;
    private MaidPolicyContext.Token pendingToken;

    public boolean book(Object world, long worldTick, int entityTick, MaidPolicyRuntime runtime) {
        if (world == null || runtime == null || !runtime.requiresAfterNativeNavigation() || !runtime.isReady()) {
            clear();
            return false;
        }
        if (hasBookedStamp && bookedWorld == world && bookedWorldTick == worldTick && bookedEntityTick == entityTick)
            return false;
        clear();
        bookedWorld = world;
        bookedWorldTick = worldTick;
        bookedEntityTick = entityTick;
        hasBookedStamp = true;
        pendingRuntime = runtime;
        pendingToken = runtime.getContext()
            .getToken();
        return true;
    }

    public boolean consume(Object world, long worldTick, int entityTick, MaidPolicyRuntime runtime) {
        var expectedRuntime = pendingRuntime;
        var token = pendingToken;
        clear();
        return expectedRuntime != null && expectedRuntime == runtime
            && bookedWorld == world
            && bookedWorldTick == worldTick
            && bookedEntityTick == entityTick
            && runtime.requiresAfterNativeNavigation()
            && runtime.isReady()
            && runtime.getContext()
                .isCurrent(token);
    }

    public void clear() {
        pendingRuntime = null;
        pendingToken = null;
    }
}
