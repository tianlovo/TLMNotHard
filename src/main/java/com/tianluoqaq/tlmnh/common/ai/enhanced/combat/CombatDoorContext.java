package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

/** 最终开门请求的精确期待上下文；仅执行边界持有opaque身份，Planner不持有 */
public final class CombatDoorContext {

    private final Object token, behavior, path;
    private final long generation, tick;
    private final int age, index;
    private final CombatMovementFact.Node node;

    public CombatDoorContext(Object token, Object behavior, long generation, CombatMovementFact.Node node, Object path,
        int index, long tick, int age) {
        this.token = token;
        this.behavior = behavior;
        this.generation = generation;
        this.node = node;
        this.path = path;
        this.index = index;
        this.tick = tick;
        this.age = age;
    }

    public boolean matches(Object token, Object behavior, long generation, CombatMovementFact.Node node, Object actual,
        Object owned, int index, long tick, int age) {
        return this.token == token && this.behavior == behavior
            && this.generation == generation
            && this.path == actual
            && actual == owned
            && this.index == index
            && this.tick == tick
            && this.age == age
            && (this.node == null ? node == null : this.node.same(node));
    }
}
