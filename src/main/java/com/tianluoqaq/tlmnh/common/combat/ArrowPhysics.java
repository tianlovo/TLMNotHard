package com.tianluoqaq.tlmnh.common.combat;

/** 原生离散更新：当前身体盒水流先推力，随后移动、介质阻力及实际provider重力。 */
public final class ArrowPhysics {

    // 固定1.7.10 EntityArrow.onUpdate中的float常量，保留实际提升到double的值
    public static final ArrowPhysics OVERWORLD_AIR = new ArrowPhysics((double) 0.99F, (double) 0.05F);
    public static final ArrowPhysics OVERWORLD_WATER = new ArrowPhysics((double) 0.8F, (double) 0.05F);
    public final double drag, gravity;
    private final java.util.function.Function<BallisticVector, Medium> medium;

    public ArrowPhysics(double drag, double gravity) {
        this(drag, gravity, null);
    }

    private ArrowPhysics(double drag, double gravity, java.util.function.Function<BallisticVector, Medium> medium) {
        if (!Double.isFinite(drag) || drag <= 0 || drag > 1 || !Double.isFinite(gravity) || gravity < 0)
            throw new IllegalArgumentException("Invalid arrow physics profile");
        this.drag = drag;
        this.gravity = gravity;
        this.medium = medium;
    }

    /** 保留原生float实际提升后的重力值，不把非主世界查询量化回默认常数 */
    public ArrowPhysics withGravity(float actualGravity) {
        return new ArrowPhysics(drag, (double) actualGravity, medium);
    }

    public ArrowPhysics withMedium(java.util.function.Function<BallisticVector, Medium> query) {
        return new ArrowPhysics(drag, gravity, java.util.Objects.requireNonNull(query));
    }

    public boolean spatial() {
        return medium != null;
    }

    public Medium mediumAt(BallisticVector position) {
        return medium == null ? new Medium(drag, BallisticVector.ZERO) : medium.apply(position);
    }

    public State step(State state) {
        return step(state, mediumAt(state.position));
    }

    public State step(State state, Medium sample) {
        // Entity.onEntityUpdate先在当前身体盒采水流并加速度，再由EntityArrow移动及衰减。
        var moving = state.velocity.add(sample.push);
        return new State(
            state.position.add(moving),
            new BallisticVector(moving.x * sample.drag, moving.y * sample.drag - gravity, moving.z * sample.drag));
    }

    public BallisticVector positionAt(State initial, double ticks) {
        if (!Double.isFinite(ticks) || ticks < 0 || ticks > ArrowInterceptSolver.MAX_FLIGHT_TICKS)
            throw new IllegalArgumentException("Flight time outside the supported budget");
        State current = initial;
        int whole = (int) ticks;
        for (int i = 0; i < whole; i++) current = step(current);
        double fraction = ticks - whole;
        return fraction == 0 ? current.position
            : current.position.add(
                current.velocity.add(mediumAt(current.position).push)
                    .scale(fraction));
    }

    public static final class Medium {

        public final double drag;
        public final BallisticVector push;

        public Medium(double drag, BallisticVector push) {
            if (!Double.isFinite(drag) || drag <= 0 || drag > 1)
                throw new IllegalArgumentException("Invalid medium drag");
            this.drag = drag;
            this.push = java.util.Objects.requireNonNull(push);
        }
    }

    public static final class State {

        public final BallisticVector position, velocity;

        public State(BallisticVector position, BallisticVector velocity) {
            this.position = java.util.Objects.requireNonNull(position, "position");
            this.velocity = java.util.Objects.requireNonNull(velocity, "velocity");
        }
    }
}
