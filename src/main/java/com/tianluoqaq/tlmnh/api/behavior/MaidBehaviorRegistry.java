package com.tianluoqaq.tlmnh.api.behavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 外部行为扩展注册表；登记时固化元数据快照，首次会话读取后自动冻结 */
public final class MaidBehaviorRegistry {

    private static final Comparator<MaidBehaviorProvider> PROVIDER_ORDER = Comparator
        .comparing(MaidBehaviorProvider::stage)
        .thenComparingInt(MaidBehaviorProvider::order)
        .thenComparing(MaidBehaviorProvider::id);

    private static final MaidBehaviorRegistry GLOBAL = new MaidBehaviorRegistry();

    private final Map<MaidBehaviorId, MaidBehaviorProvider> providers = new LinkedHashMap<>();
    private List<MaidBehaviorProvider> frozenSnapshot;
    private boolean frozen;

    public static MaidBehaviorRegistry global() {
        return GLOBAL;
    }

    public synchronized void register(MaidBehaviorProvider provider) {
        Objects.requireNonNull(provider, "provider");
        var capturedId = Objects.requireNonNull(provider.id(), "provider.id");
        var capturedStage = Objects.requireNonNull(provider.stage(), "provider.stage");
        int capturedOrder = provider.order();
        if (frozen) throw new IllegalStateException("MaidBehaviorRegistry is already frozen: " + capturedId);
        if (capturedId.isBuiltin())
            throw new IllegalArgumentException("External provider cannot use built-in namespace: " + capturedId);
        if (providers.containsKey(capturedId))
            throw new IllegalArgumentException("Duplicate behavior provider id: " + capturedId);
        providers.put(capturedId, new RegisteredProvider(capturedId, capturedStage, capturedOrder, provider));
    }

    public synchronized void freeze() {
        if (!frozen) {
            var sorted = new ArrayList<>(providers.values());
            sorted.sort(PROVIDER_ORDER);
            frozenSnapshot = Collections.unmodifiableList(sorted);
            frozen = true;
        }
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized int size() {
        return providers.size();
    }

    public synchronized List<MaidBehaviorProvider> snapshotAndFreeze() {
        freeze();
        return frozenSnapshot;
    }

    private static final class RegisteredProvider implements MaidBehaviorProvider {

        private final MaidBehaviorId id;
        private final MaidBehaviorStage stage;
        private final int order;
        private final MaidBehaviorProvider delegate;

        RegisteredProvider(MaidBehaviorId id, MaidBehaviorStage stage, int order, MaidBehaviorProvider delegate) {
            this.id = id;
            this.stage = stage;
            this.order = order;
            this.delegate = delegate;
        }

        @Override
        public MaidBehaviorId id() {
            return id;
        }

        @Override
        public MaidBehaviorStage stage() {
            return stage;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
            return delegate.create(initContext);
        }
    }
}
