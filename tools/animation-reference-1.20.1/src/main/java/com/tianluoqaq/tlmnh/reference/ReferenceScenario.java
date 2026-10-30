package com.tianluoqaq.tlmnh.reference;

/** 原地运动学动画夹具，不执行导航，也不将给定步态当作生物AI能力 */
public final class ReferenceScenario {
    public static final String IDLE = "idle";
    public static final String IDLE_WALK_IDLE = "idle_walk_idle";

    private ReferenceScenario() {}

    public static void validate(String scenario, int frames) {
        if (IDLE.equals(scenario)) return;
        if (!IDLE_WALK_IDLE.equals(scenario)) throw new IllegalArgumentException("Unknown reference scenario");
        if (frames != 72) throw new IllegalArgumentException("idle_walk_idle requires exactly 72 steps");
    }

    public static String inputMode(String scenario) {
        return IDLE_WALK_IDLE.equals(scenario) ? "controlled-idle-walk-idle-v1" : "controlled-idle-v0";
    }

    public static String contract(String scenario) {
        return IDLE_WALK_IDLE.equals(scenario) ? "controlled-empty-idle-walk-idle-v1" : "controlled-empty-idle-v1";
    }

    public static boolean walking(int step) {
        return step >= 24 && step < 48;
    }

    public static float amount(int step) {
        return walking(step) ? 0.25F : 0;
    }

    public static float limb(int step) {
        return Math.min(24, Math.max(0, step - 24)) * 0.25F;
    }

    public static double motionX(int step) {
        return walking(step) ? 0.0625 : 0;
    }
}
