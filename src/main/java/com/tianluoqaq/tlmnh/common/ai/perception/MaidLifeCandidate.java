package com.tianluoqaq.tlmnh.common.ai.perception;

import java.lang.ref.WeakReference;
import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;

/** 发现位置的短期观测，不代表床已认领或物品操作已获许可 */
public final class MaidLifeCandidate {

    public static final int MAXIMUM_AGE = 20;

    public enum Kind {
        BED,
        SNACK
    }

    private final Kind kind;
    private final int dimension, x, y, z;
    private final long observedAt;
    private final transient Object world;
    private final transient MaidPolicyContext context;
    private final transient MaidPolicyContext.Token token;
    private final transient WeakReference<Object> tile;

    public MaidLifeCandidate(Kind kind, int dimension, int x, int y, int z, long observedAt, Object world,
        MaidPolicyContext context, Object tile) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.observedAt = observedAt;
        this.world = Objects.requireNonNull(world, "world");
        this.context = Objects.requireNonNull(context, "context");
        token = context.getToken();
        this.tile = new WeakReference<>(Objects.requireNonNull(tile, "tile"));
    }

    public boolean matches(Object world, MaidPolicyContext context, long gameTime, Object currentTile) {
        long age = gameTime - observedAt;
        return this.world == world && this.context == context
            && context.isCurrent(token)
            && age >= 0
            && age <= MAXIMUM_AGE
            && currentTile != null
            && tile.get() == currentTile;
    }

    public Kind getKind() {
        return kind;
    }

    public int getDimension() {
        return dimension;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public long getObservedAt() {
        return observedAt;
    }
}
