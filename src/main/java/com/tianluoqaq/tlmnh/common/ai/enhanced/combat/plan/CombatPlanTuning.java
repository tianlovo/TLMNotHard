package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan;

/** 增强战斗多步规划权重与连续性参数（Tuning） */
public final class CombatPlanTuning {

    public static final double SWITCH_MARGIN = .06;
    public static final double SELF_WEIGHT = 1.45;
    public static final double OWNER_WEIGHT = .55;
    public static final double PROGRESS_WEIGHT = .95;
    public static final double RESOURCE_WEIGHT = .10;
    public static final double CONTINUITY_WEIGHT = .16;
    public static final int JUMP_PREPARATION_TICKS = 8;
    public static final double ROUTE_ARRIVAL_DISTANCE = .35;
    public static final int COMBAT_PLAN_DEFAULT_ZERO_BASE = 0;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int COMBAT_PLANNER_CANDIDATE_TRACE_PARAM = -2;
    public static final int COMBAT_PLANNER_DAMAGE_FOURTH_PARAM = 2;
    public static final double COMBAT_PLANNER_DECIDE_FACTOR = .15;
    public static final int MIN_UTILITY_THRESHOLD = -100;
    public static final int MIN_HELD_SINCE_THRESHOLD = 8;
    public static final double COMBAT_PLANNER_CANDIDATE_FIFTH_FACTOR = .12;
    public static final double COMBAT_PLANNER_CANDIDATES_FACTOR = .06;
    public static final int COMBAT_PLANNER_MIN_FIRST_PARAM = 5;
    public static final double COMBAT_PLANNER_CANDIDATE_FIFTH_FACTOR_2 = .03;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR = .1;
    public static final int COMBAT_PLANNER_FOLLOWED_NODE = 1;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_2 = .05;
    public static final int COMBAT_PLANNER_MAX_SECOND_PARAM = 20;
    public static final double COMBAT_PLANNER_ATTACK_FACTOR = .2;
    public static final double COMBAT_PLANNER_ATTACK_FACTOR_2 = .22;
    public static final double COMBAT_PLANNER_ATTACK_FACTOR_3 = .4;
    public static final double COMBAT_PLANNER_ATTACK_FACTOR_4 = .10;
    public static final double COMBAT_PLANNER_ATTACK_FACTOR_5 = 80.0;
    public static final double COMBAT_PLANNER_ATTACK_FACTOR_6 = .025;
    public static final double COMBAT_PLANNER_DESTINATION_SCALE_X = -2.5;
    public static final int COMBAT_PLANNER_DESTINATION_PARAM = 3;
    public static final double COMBAT_PLANNER_MAX_SECOND_FACTOR = .8;
    public static final double COMBAT_PLANNER_EQUIVALENT_FACTOR = .0001;
    public static final int MIN_LAST_OWN_HIT_AT_THRESHOLD = 10;
    public static final double COMBAT_PLANNER_JUMP_USEFUL_FACTOR = 1.25;
    public static final double COMBAT_PLANNER_MAX_FIRST_FACTOR = .5;
    public static final int COMBAT_PLANNER_UTILITY = -1000;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_3 = -.1;
    public static final double COMBAT_PLANNER_VEC_SECOND_FACTOR = 1.3;
    public static final double COMBAT_PLANNER_PROGRESS = -.5;
    public static final double COMBAT_PLANNER_MAX_FIRST_FACTOR_2 = .001;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_4 = .42;
    public static final double COMBAT_PLANNER_VERTICAL_SPEED = .08;
    public static final double COMBAT_PLANNER_VERTICAL_SPEED_2 = .98;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_5 = 2.7;
    public static final double COMBAT_PLANNER_VEC_SECOND_FACTOR_2 = .85;
    public static final double COMBAT_PLANNER_MAX_FIRST_FACTOR_3 = .35;
    public static final double COMBAT_PLANNER_UNCERTAINTY = .013;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_6 = .45;
    public static final double COMBAT_PLANNER_MIN_FIRST_FACTOR = .25;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_7 = .005;
    public static final double COMBAT_PLANNER_MAX_FIRST_FACTOR_4 = -.15;
    public static final int COMBAT_PLANNER_MIN_SECOND_PARAM = 15;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_8 = .6;
    public static final double COMBAT_PLANNER_DOMAIN_FACTOR_9 = 20.0;
    public static final long COMBAT_PLANNER_MIN_FIRST_LIMIT = 2L;
    public static final int COMBAT_PLANNER_PHYSICAL_PARAM = 6;
    public static final double COMBAT_PLANNER_PHYSICAL_FACTOR = 1.5;

    private CombatPlanTuning() {}
}
