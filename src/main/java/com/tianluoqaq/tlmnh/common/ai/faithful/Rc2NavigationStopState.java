package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;

/** 单导航器、所属世界线程内的停滞反馈；空路径本身不构成原因 */
public final class Rc2NavigationStopState {

    public static final class Event {

        public final String cause = "RC2_NATIVE_WATCHDOG";
        public final long installationGeneration, worldTick;
        public final int entityTick;
        private final transient Object observer, path, world;

        private Event(Object observer, Object path, Object world, long generation, long worldTick, int entityTick) {
            this.observer = observer;
            this.path = path;
            this.world = world;
            installationGeneration = generation;
            this.worldTick = worldTick;
            this.entityTick = entityTick;
        }
    }

    private Object observer, path, world;
    private MaidPolicyContext context;
    private Token token;
    private long generation;
    private Event pending, event;

    public long arm(Object observer, Object path, Object world, MaidPolicyContext context, Token token) {
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(context, "context");
        if (!context.isCurrent(token)) throw new IllegalStateException("Stale navigation observation lease");
        if (this.observer != null && this.observer != observer)
            throw new IllegalStateException("Navigator already observed by another actor");
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("Navigation generation exhausted");
        this.observer = observer;
        this.path = path;
        this.world = world;
        this.context = context;
        this.token = token;
        pending = event = null;
        return ++generation;
    }

    public boolean isObserving() {
        return observer != null && context != null && context.isCurrent(token);
    }

    /** 只由原生 pathFollow 的 watchdog 清空调用点调用 */
    public void beforeWatchdogClear(Object actualPath, Object actualWorld, long worldTick, int entityTick) {
        pending = null;
        if (isObserving() && actualPath == path && actualWorld == world)
            pending = new Event(observer, path, world, generation, worldTick, entityTick);
    }

    /** 必须观察到原调用返回且同帧路径确实为空，才形成可消费的反馈 */
    public void afterWatchdogClear(Object actualPath, Object actualWorld, long worldTick, int entityTick) {
        Event before = pending;
        pending = null;
        if (before != null && actualPath == null
            && isObserving()
            && before.observer == observer
            && before.path == path
            && before.world == actualWorld
            && actualWorld == world
            && before.installationGeneration == generation
            && before.worldTick == worldTick
            && before.entityTick == entityTick) event = before;
    }

    public boolean matches(Object observer, Object path, Object world, long generation, Token token, long worldTick,
        int entityTick) {
        return event != null && isObserving()
            && this.token == token
            && event.observer == observer
            && event.path == path
            && event.world == world
            && event.installationGeneration == generation
            && event.worldTick == worldTick
            && event.entityTick == entityTick;
    }

    /** 诊断保留到下一次安装或释放；授权仍需 matches 校验 */
    public Event peekEvent() {
        return event;
    }

    public long getGeneration() {
        return generation;
    }

    public void release(Object observer) {
        if (this.observer != observer) return;
        this.observer = path = world = null;
        context = null;
        token = null;
        pending = event = null;
    }
}
