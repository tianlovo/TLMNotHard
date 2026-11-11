package com.tianluoqaq.tlmnh.client.render;

/** 实例私有的单调时间，暂停和恢复时重新对齐游戏时间输入 */
public final class AnimationClock {

    private boolean initialized, wasPaused;
    private double lastInput, ticks;

    public double advance(double gameTicks, boolean paused) {
        if (!Double.isFinite(gameTicks) || gameTicks < 0) throw new IllegalArgumentException("Invalid animation time");
        if (!initialized) {
            initialized = true;
            ticks = gameTicks;
        } else if (!paused && !wasPaused && gameTicks >= lastInput) {
            ticks += gameTicks - lastInput;
        }
        lastInput = gameTicks;
        wasPaused = paused;
        return ticks;
    }

    public double ticks() {
        return ticks;
    }
}
