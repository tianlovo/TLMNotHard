package com.tianluoqaq.tlmnh.common.ai.navigation.eval;

import static com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathType.*;

import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathContext;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathType;

/** 现代两栖层：竖直水节点、浅水偏好与临水固体边代价 */
public class MaidAmphibiousEvaluator extends MaidWalkEvaluator {

    private float oldWalkable, oldBorder;

    public MaidAmphibiousEvaluator(MaidPathContext actor) {
        super(actor);
    }

    @Override
    public void prepare() {
        super.prepare();
        actor.cost(WATER, NavigationEvalTuning.COST_INITIAL_VALUE);
        oldWalkable = actor.cost(WALKABLE);
        oldBorder = actor.cost(WATER_BORDER);
        actor.cost(WALKABLE, NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM);
        actor.cost(WATER_BORDER, NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM_2);
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
        var up = accepted(
            from.x,
            from.y + 1,
            from.z,
            Math.max(NavigationEvalTuning.COST_INITIAL_VALUE, step - 1),
            floor,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            kind);
        var down = accepted(
            from.x,
            from.y - 1,
            from.z,
            step,
            floor,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            kind);
        if (valid(up, from) && up.type == WATER) output[count++] = up;
        if (valid(down, from) && down.type == WATER && kind != TRAPDOOR) output[count++] = down;
        for (int i = 0; i < count; i++) if (output[i].type == WATER
            && output[i].y < region.seaLevel() - NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_NEIGHBORS_PARAM)
            output[i].cost++;
        return count;
    }

    @Override
    protected MaidPathType blockType(int x, int y, int z) {
        if (region.rawType(x, y, z) != WATER) return super.blockType(x, y, z);
        int[][] offsets = {
            { NavigationEvalTuning.COST_INITIAL_VALUE, NavigationEvalTuning.UNSET_OR_NOT_FOUND,
                NavigationEvalTuning.COST_INITIAL_VALUE },
            { NavigationEvalTuning.COST_INITIAL_VALUE, NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP,
                NavigationEvalTuning.COST_INITIAL_VALUE },
            { NavigationEvalTuning.COST_INITIAL_VALUE, NavigationEvalTuning.COST_INITIAL_VALUE,
                NavigationEvalTuning.UNSET_OR_NOT_FOUND },
            { NavigationEvalTuning.COST_INITIAL_VALUE, NavigationEvalTuning.COST_INITIAL_VALUE,
                NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP },
            { NavigationEvalTuning.UNSET_OR_NOT_FOUND, NavigationEvalTuning.COST_INITIAL_VALUE,
                NavigationEvalTuning.COST_INITIAL_VALUE },
            { NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP, NavigationEvalTuning.COST_INITIAL_VALUE,
                NavigationEvalTuning.COST_INITIAL_VALUE } };
        for (var d : offsets) if (region.rawType(
            x + d[NavigationEvalTuning.OFFSET_X_INDEX],
            y + d[NavigationEvalTuning.OFFSET_Y_INDEX],
            z + d[NavigationEvalTuning.OFFSET_Z_INDEX]) == BLOCKED) return WATER_BORDER;
        return WATER;
    }
}
