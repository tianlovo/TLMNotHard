package com.tianluoqaq.tlmnh.common.life;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;

/** 上游日程边界与原生蛋糕的有界移植规则 */
public final class MaidLifeRules {

    public enum Activity {
        WORK,
        IDLE,
        REST
    }

    public static final int SNACK_INTERVAL = 45 * 20;
    public static final int REWARD_INTERVAL = 3 * 60 * 20;
    public static final double SNACK_OWNER_RANGE_SQ = 8 * 8;

    private MaidLifeRules() {}

    public static boolean isRest(MaidSchedule schedule, long worldTime) {
        return activity(schedule, worldTime) == Activity.REST;
    }

    public static Activity activity(MaidSchedule schedule, long worldTime) {
        long time = Math.floorMod(worldTime, 24000L);
        if (schedule == MaidSchedule.ALL) return Activity.WORK;
        if (schedule == MaidSchedule.NIGHT)
            return time < 8000 ? Activity.REST : time < 12000 ? Activity.IDLE : Activity.WORK;
        return time < 12000 ? Activity.WORK : time < 16000 ? Activity.IDLE : Activity.REST;
    }

    /** 当前原生 API 的六口候选，实际整合包口数由服务端夹具另行核对 */
    public static int nextCakeBites(int bites) {
        if (bites < 0 || bites > 5) throw new IllegalArgumentException("Invalid native cake bites: " + bites);
        return bites == 5 ? -1 : bites + 1;
    }
}
