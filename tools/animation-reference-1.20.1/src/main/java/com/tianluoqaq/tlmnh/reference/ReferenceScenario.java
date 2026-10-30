package com.tianluoqaq.tlmnh.reference;

/** 原地运动学动画夹具，不执行导航，也不将给定步态当作生物AI能力 */
public final class ReferenceScenario {
    public static final String IDLE = "idle";
    public static final String IDLE_WALK_IDLE = "idle_walk_idle";
    public static final String HEAD_TURN = "head_turn";

    private ReferenceScenario() {}

    public static void validate(String scenario, int frames) {
        if (IDLE.equals(scenario)) return;
        if (!sequenced(scenario)) throw new IllegalArgumentException("Unknown reference scenario");
        if (frames != 72) throw new IllegalArgumentException(scenario + " requires exactly 72 steps");
    }

    public static String inputMode(String scenario) {
        if (HEAD_TURN.equals(scenario)) return "controlled-head-turn-v1";
        return IDLE_WALK_IDLE.equals(scenario) ? "controlled-idle-walk-idle-v1" : "controlled-idle-v0";
    }

    public static String contract(String scenario) {
        if (HEAD_TURN.equals(scenario)) return "controlled-empty-head-turn-v1";
        return IDLE_WALK_IDLE.equals(scenario) ? "controlled-empty-idle-walk-idle-v1" : "controlled-empty-idle-v1";
    }

    public static boolean sequenced(String scenario) {
        return IDLE_WALK_IDLE.equals(scenario) || HEAD_TURN.equals(scenario);
    }

    /** 实体角度输入为度，Gecko renderer 的符号转换由原版执行 */
    public static float headYaw(String scenario, int step) {
        if (!HEAD_TURN.equals(scenario)) return 0;
        return step >= 18 && step < 36 ? 30 : step >= 36 && step < 54 ? -45 : 0;
    }

    public static float headPitch(String scenario, int step) {
        if (!HEAD_TURN.equals(scenario)) return 0;
        return step >= 18 && step < 36 ? 15 : step >= 36 && step < 54 ? -20 : 0;
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
