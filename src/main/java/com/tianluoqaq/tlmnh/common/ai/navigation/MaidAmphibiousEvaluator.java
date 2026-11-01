package com.tianluoqaq.tlmnh.common.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType.*;

/** 现代两栖层：竖直水节点、浅水偏好与临水固体边代价 */
public class MaidAmphibiousEvaluator extends MaidWalkEvaluator {

    private float oldWalkable, oldBorder;

    public MaidAmphibiousEvaluator(MaidPathContext actor) {
        super(actor);
    }

    @Override
    public void prepare() {
        actor.cost(WATER, 0);
        oldWalkable = actor.cost(WALKABLE);
        oldBorder = actor.cost(WATER_BORDER);
        actor.cost(WALKABLE, 6);
        actor.cost(WATER_BORDER, 4);
    }

    @Override
    public void done() {
        actor.cost(WALKABLE, oldWalkable);
        actor.cost(WATER_BORDER, oldBorder);
        super.done();
    }

    @Override
    protected boolean amphibious() {
        return true;
    }

    @Override
    public int neighbors(MaidPathNode[] output, MaidPathNode from) {
        int count = super.neighbors(output, from), step = step(from);
        var kind = type(from.x, from.y, from.z);
        double floor = floorLevel(from.x, from.y, from.z);
        var up = accepted(from.x, from.y + 1, from.z, Math.max(0, step - 1), floor, 0, 0, kind);
        var down = accepted(from.x, from.y - 1, from.z, step, floor, 0, 0, kind);
        if (valid(up, from) && up.type == WATER) output[count++] = up;
        if (valid(down, from) && down.type == WATER && kind != TRAPDOOR) output[count++] = down;
        for (int i = 0; i < count; i++)
            if (output[i].type == WATER && output[i].y < region.seaLevel() - 10) output[i].cost++;
        return count;
    }

    @Override
    protected MaidPathType blockType(int x, int y, int z) {
        if (region.rawType(x, y, z) != WATER) return super.blockType(x, y, z);
        int[][] offsets = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
        for (var d : offsets) if (region.rawType(x + d[0], y + d[1], z + d[2]) == BLOCKED) return WATER_BORDER;
        return WATER;
    }
}
