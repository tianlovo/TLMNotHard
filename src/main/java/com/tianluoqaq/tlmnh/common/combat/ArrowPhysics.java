package com.tianluoqaq.tlmnh.common.combat;

/** 固定介质内的原版离散更新：先按速度移动，再乘阻力，最后减重力 */
public final class ArrowPhysics {

    // 固定1.7.10 EntityArrow.onUpdate中的float常量，保留实际提升到double的值
    public static final ArrowPhysics OVERWORLD_AIR = new ArrowPhysics((double) 0.99F, (double) 0.05F);
    public static final ArrowPhysics OVERWORLD_WATER = new ArrowPhysics((double) 0.8F, (double) 0.05F);
    public final double drag, gravity;

    public ArrowPhysics(double drag, double gravity) {
        if (!Double.isFinite(drag) || drag <= 0 || drag > 1 || !Double.isFinite(gravity) || gravity < 0)
            throw new IllegalArgumentException("Invalid arrow physics profile");
        this.drag = drag;
        this.gravity = gravity;
    }

    public State step(State state) {
        return new State(
            state.position.add(state.velocity),
            new BallisticVector(state.velocity.x * drag, state.velocity.y * drag - gravity, state.velocity.z * drag));
    }

    public BallisticVector positionAt(State initial, double ticks) {
        if (!Double.isFinite(ticks) || ticks < 0 || ticks > ArrowInterceptSolver.MAX_FLIGHT_TICKS)
            throw new IllegalArgumentException("Flight time outside the supported budget");
        State current = initial;
        int whole = (int) ticks;
        for (int i = 0; i < whole; i++) current = step(current);
        return current.position.add(current.velocity.scale(ticks - whole));
    }

    public static final class State {

        public final BallisticVector position, velocity;

        public State(BallisticVector position, BallisticVector velocity) {
            this.position = java.util.Objects.requireNonNull(position, "position");
            this.velocity = java.util.Objects.requireNonNull(velocity, "velocity");
        }
    }
}
