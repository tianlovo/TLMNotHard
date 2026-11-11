package com.tianluoqaq.tlmnh.common.life;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;

/** 上游日程边界与原生蛋糕的有界移植规则 */
public final class MaidLifeRules {

    public enum Activity {
        WORK,
        IDLE,
        REST
    }

    private MaidLifeRules() {}

    public static boolean isRest(MaidSchedule schedule, long worldTime) {
        return activity(schedule, worldTime) == Activity.REST;
    }

    public static Activity activity(MaidSchedule schedule, long worldTime) {
        long time = Math.floorMod(worldTime, MaidLifeTuning.LIFE_RULES_FLOOR_MOD_SECOND_LIMIT);
        if (schedule == MaidSchedule.ALL) return Activity.WORK;
        if (schedule == MaidSchedule.NIGHT) return time < MaidLifeTuning.MIN_TIME_THRESHOLD ? Activity.REST
            : time < MaidLifeTuning.MIN_TIME_THRESHOLD_2 ? Activity.IDLE : Activity.WORK;
        return time < MaidLifeTuning.MIN_TIME_THRESHOLD_2 ? Activity.WORK
            : time < MaidLifeTuning.MIN_TIME_THRESHOLD_3 ? Activity.IDLE : Activity.REST;
    }

    /** 当前原生 API 的六口候选，实际整合包口数由服务端夹具另行核对 */
    public static int nextCakeBites(int bites) {
        if (bites < 0 || bites > MaidLifeTuning.HOME_SETTINGS_DECODE_FIRST_PARAM_2)
            throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_NATIVE_CAKE_BITES + bites);
        return bites == MaidLifeTuning.HOME_SETTINGS_DECODE_FIRST_PARAM_2 ? MaidLifeTuning.UNSET_OR_NOT_FOUND
            : bites + 1;
    }
}
