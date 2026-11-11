package com.tianluoqaq.tlmnh.common.ai.navigation.eval;

import static com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathContext.floor;
import static com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathType.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathContext;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathRegion;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathType;

/** 现代步行节点合同：占据类型、台阶、下落和转角；方块能力由目标环境提供 */
public class MaidWalkEvaluator {

    protected final MaidPathContext actor;
    protected final MaidPathRegion region;
    protected final Map<Integer, MaidPathNode> nodes = new HashMap<>();
    private final Map<Long, MaidPathType> types = new HashMap<>();
    protected final int width, height;
    public boolean canPassDoors, canOpenDoors, canFloat, canWalkOverFences;

    public MaidWalkEvaluator(MaidPathContext actor) {
        this.actor = actor;
        region = actor.region;
        width = floor(actor.width + NavigationEvalTuning.WALK_EVALUATOR_WIDTH);
        height = floor(actor.height + NavigationEvalTuning.WALK_EVALUATOR_WIDTH);
    }

    public void prepare() {
        nodes.clear();
        types.clear();
    }

    public void done() {
        types.clear();
    }

    protected boolean amphibious() {
        return false;
    }

    protected MaidPathType raw(int x, int y, int z) {
        return region.rawType(x, y, z);
    }

    public MaidPathNode node(int x, int y, int z) {
        return nodes.computeIfAbsent(MaidPathNode.key(x, y, z), ignored -> new MaidPathNode(x, y, z));
    }

    public MaidPathNode start() {
        int x = floor(actor.x), y = floor(actor.y), z = floor(actor.z);
        if (canFloat && region.water(x, y, z)) {
            while (y < region.maxY() && region.water(x, y, z)) y++;
            y--;
        } else if (actor.onGround) y = floor(actor.y + NavigationEvalTuning.MAX_Y_THRESHOLD);
        else {
            while (y > region.minY() && (region.air(x, y, z) || region.landPassable(x, y, z))) y--;
            y++;
        }
        if (!canStart(x, y, z)) {
            int lowX = floor(actor.x - actor.width / NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM),
                highX = floor(actor.x + actor.width / NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM);
            int lowZ = floor(actor.z - actor.width / NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM),
                highZ = floor(actor.z + actor.width / NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM);
            int[][] corners = { { lowX, lowZ }, { lowX, highZ }, { highX, lowZ }, { highX, highZ } };
            for (var corner : corners) if (canStart(corner[0], y, corner[1])) return startNode(corner[0], y, corner[1]);
        }
        return startNode(x, y, z);
    }

    private boolean canStart(int x, int y, int z) {
        var type = type(x, y, z);
        return type != OPEN && actor.cost(type) >= 0;
    }

    protected MaidPathNode startNode(int x, int y, int z) {
        var node = node(x, y, z);
        node.type = type(x, y, z);
        node.cost = actor.cost(node.type);
        return node;
    }

    public MaidPathNode goal(int x, int y, int z) {
        return new MaidPathNode(x, y, z);
    }

    public int neighbors(MaidPathNode[] output, MaidPathNode from) {
        int step = step(from), count = NavigationEvalTuning.COST_INITIAL_VALUE;
        double level = floorLevel(from.x, from.y, from.z);
        var kind = type(from.x, from.y, from.z);
        var south = accepted(
            from.x,
            from.y,
            from.z + 1,
            step,
            level,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP,
            kind);
        if (valid(south, from)) output[count++] = south;
        var west = accepted(
            from.x - 1,
            from.y,
            from.z,
            step,
            level,
            NavigationEvalTuning.UNSET_OR_NOT_FOUND,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            kind);
        if (valid(west, from)) output[count++] = west;
        var east = accepted(
            from.x + 1,
            from.y,
            from.z,
            step,
            level,
            NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            kind);
        if (valid(east, from)) output[count++] = east;
        var north = accepted(
            from.x,
            from.y,
            from.z - 1,
            step,
            level,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.UNSET_OR_NOT_FOUND,
            kind);
        if (valid(north, from)) output[count++] = north;
        var nw = accepted(
            from.x - 1,
            from.y,
            from.z - 1,
            step,
            level,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.UNSET_OR_NOT_FOUND,
            kind);
        if (diagonal(from, west, north, nw)) output[count++] = nw;
        var ne = accepted(
            from.x + 1,
            from.y,
            from.z - 1,
            step,
            level,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.UNSET_OR_NOT_FOUND,
            kind);
        if (diagonal(from, east, north, ne)) output[count++] = ne;
        var sw = accepted(
            from.x - 1,
            from.y,
            from.z + 1,
            step,
            level,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP,
            kind);
        if (diagonal(from, west, south, sw)) output[count++] = sw;
        var se = accepted(
            from.x + 1,
            from.y,
            from.z + 1,
            step,
            level,
            NavigationEvalTuning.COST_INITIAL_VALUE,
            NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP,
            kind);
        if (diagonal(from, east, south, se)) output[count++] = se;
        return count;
    }

    protected int step(MaidPathNode node) {
        return actor.cost(type(node.x, node.y + 1, node.z)) >= 0 && type(node.x, node.y, node.z) != STICKY_HONEY
            ? floor(Math.max(NavigationEvalTuning.WALK_EVALUATOR_WIDTH, actor.stepHeight))
            : NavigationEvalTuning.COST_INITIAL_VALUE;
    }

    protected boolean valid(MaidPathNode next, MaidPathNode from) {
        return next != null && !next.closed && (next.cost >= 0 || from.cost < 0);
    }

    private boolean diagonal(MaidPathNode root, MaidPathNode x, MaidPathNode z, MaidPathNode next) {
        if (next == null || x == null || z == null || next.closed || x.y > root.y || z.y > root.y) return false;
        if (x.type == WALKABLE_DOOR || z.type == WALKABLE_DOOR || next.type == WALKABLE_DOOR) return false;
        boolean narrow = x.type == FENCE && z.type == FENCE && actor.width < NavigationEvalTuning.MAX_Y_THRESHOLD;
        return next.cost >= 0 && (z.y < root.y || z.cost >= 0 || narrow) && (x.y < root.y || x.cost >= 0 || narrow);
    }

    protected double floorLevel(int x, int y, int z) {
        return (canFloat || amphibious()) && region.water(x, y, z) ? y + NavigationEvalTuning.MAX_Y_THRESHOLD
            : region.floor(x, y, z);
    }

    protected MaidPathNode accepted(int x, int y, int z, int remainingStep, double fromFloor, int dx, int dz,
        MaidPathType fromType) {
        if (y < region.minY() || y >= region.maxY()) return null;
        if (floorLevel(x, y, z) - fromFloor
            > Math.max(NavigationEvalTuning.WALK_EVALUATOR_MAX_FIRST_FACTOR, actor.stepHeight)) return null;
        var type = type(x, y, z);
        float cost = actor.cost(type);
        MaidPathNode result = cost >= 0 ? update(x, y, z, type, cost) : null;
        if (partial(fromType) && result != null && result.cost >= 0 && !reachable(result)) result = null;
        if (type == WALKABLE || amphibious() && type == WATER) return result;
        if ((result == null || result.cost < 0) && remainingStep > 0
            && (type != FENCE || canWalkOverFences)
            && type != UNPASSABLE_RAIL
            && type != TRAPDOOR
            && type != POWDER_SNOW) {
            result = accepted(x, y + 1, z, remainingStep - 1, fromFloor, dx, dz, fromType);
            if (result != null && (result.type == OPEN || result.type == WALKABLE)
                && actor.width < NavigationEvalTuning.WALK_EVALUATOR_WIDTH) {
                double cx = x - dx + NavigationEvalTuning.MAX_Y_THRESHOLD,
                    cz = z - dz + NavigationEvalTuning.MAX_Y_THRESHOLD,
                    half = actor.width / NavigationEvalTuning.MIDPOINT_DIVISOR_D;
                if (region.collides(
                    cx - half,
                    floorLevel(floor(cx), y + 1, floor(cz)) + NavigationEvalTuning.WALK_EVALUATOR_DOMAIN_FACTOR,
                    cz - half,
                    cx + half,
                    actor.height + floorLevel(result.x, result.y, result.z)
                        - NavigationEvalTuning.WALK_EVALUATOR_DOMAIN_FACTOR_2,
                    cz + half)) result = null;
            }
        }
        if (!amphibious() && type == WATER && !canFloat) {
            if (type(x, y - 1, z) != WATER) return result;
            while (y > region.minY()) {
                type = type(x, --y, z);
                if (type != WATER) return result;
                result = update(x, y, z, type, actor.cost(type));
            }
        }
        if (type == OPEN) {
            int fallen = 0, originalY = y;
            while (type == OPEN) {
                if (--y < region.minY()) return blocked(x, originalY, z);
                if (fallen++ >= actor.maxFall) return blocked(x, y, z);
                type = type(x, y, z);
                cost = actor.cost(type);
                if (type != OPEN && cost >= 0) {
                    result = update(x, y, z, type, cost);
                    break;
                }
                if (cost < 0) return blocked(x, y, z);
            }
        }
        if (partial(type) && result == null) {
            result = node(x, y, z);
            result.closed = true;
            result.type = type;
            result.cost = type.defaultCost;
        }
        return result;
    }

    private static boolean partial(MaidPathType type) {
        return type == FENCE || type == DOOR_WOOD_CLOSED || type == DOOR_IRON_CLOSED;
    }

    private MaidPathNode update(int x, int y, int z, MaidPathType type, float cost) {
        var result = node(x, y, z);
        result.type = type;
        result.cost = Math.max(result.cost, cost);
        return result;
    }

    private MaidPathNode blocked(int x, int y, int z) {
        var node = node(x, y, z);
        node.type = BLOCKED;
        node.cost = NavigationEvalTuning.UNSET_OR_NOT_FOUND;
        return node;
    }

    private boolean reachable(MaidPathNode node) {
        double half = actor.width / NavigationEvalTuning.MIDPOINT_DIVISOR_D;
        double dx = node.x - actor.x + actor.width / NavigationEvalTuning.MIDPOINT_DIVISOR_D,
            dy = node.y - actor.y + actor.height / NavigationEvalTuning.MIDPOINT_DIVISOR_D,
            dz = node.z - actor.z + actor.width / NavigationEvalTuning.MIDPOINT_DIVISOR_D;
        int steps = (int) Math.ceil(
            Math.sqrt(dx * dx + dy * dy + dz * dz)
                / ((actor.width * NavigationEvalTuning.MIDPOINT_DIVISOR_D + actor.height)
                    / NavigationEvalTuning.WALK_EVALUATOR_STEPS));
        double scale = NavigationEvalTuning.WALK_EVALUATOR_WIDTH / steps;
        double x = actor.x, y = actor.y, z = actor.z;
        for (int i = 1; i <= steps; i++) {
            x += dx * scale;
            y += dy * scale;
            z += dz * scale;
            if (region.collides(x - half, y, z - half, x + half, y + actor.height, z + half)) return false;
        }
        return true;
    }

    public MaidPathType type(int x, int y, int z) {
        long key = ((long) x & NavigationEvalTuning.WALK_EVALUATOR_BIT_MASK_67108863)
            << NavigationEvalTuning.WALK_EVALUATOR_BIT_SHIFT_38
            | ((long) z & NavigationEvalTuning.WALK_EVALUATOR_BIT_MASK_67108863)
                << NavigationEvalTuning.WALK_EVALUATOR_BIT_SHIFT_12
            | (long) y & NavigationEvalTuning.WALK_EVALUATOR_BIT_MASK_4095;
        return types.computeIfAbsent(key, ignored -> occupiedType(x, y, z));
    }

    /** MoveControl checks one cell; path search separately checks the whole occupied body */
    public MaidPathType cellType(int x, int y, int z) {
        return blockType(x, y, z);
    }

    private MaidPathType occupiedType(int x, int y, int z) {
        var found = EnumSet.noneOf(MaidPathType.class);
        var first = BLOCKED;
        int ax = floor(actor.x), ay = floor(actor.y), az = floor(actor.z);
        boolean onRail = region.rail(ax, ay, az) || region.rail(ax, ay - 1, az);
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) for (int dz = 0; dz < width; dz++) {
            var type = blockType(x + dx, y + dy, z + dz);
            if (type == DOOR_WOOD_CLOSED && canOpenDoors && canPassDoors) type = WALKABLE_DOOR;
            if (type == DOOR_OPEN && !canPassDoors) type = BLOCKED;
            if (type == RAIL && !onRail) type = UNPASSABLE_RAIL;
            if (dx == 0 && dy == 0 && dz == 0) first = type;
            found.add(type);
        }
        if (found.contains(FENCE)) return FENCE;
        if (found.contains(UNPASSABLE_RAIL)) return UNPASSABLE_RAIL;
        var chosen = BLOCKED;
        for (var type : found) {
            if (actor.cost(type) < 0) return type;
            if (actor.cost(type) >= actor.cost(chosen)) chosen = type;
        }
        return first == OPEN && actor.cost(chosen) == 0
            && width <= NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP ? OPEN : chosen;
    }

    protected MaidPathType blockType(int x, int y, int z) {
        var type = raw(x, y, z);
        if (type == OPEN && y >= region.minY() + 1) {
            var below = raw(x, y - 1, z);
            type = below != WALKABLE && below != OPEN && below != WATER && below != LAVA ? WALKABLE : OPEN;
            if (below == DAMAGE_FIRE || below == DAMAGE_OTHER || below == STICKY_HONEY || below == DAMAGE_CAUTIOUS)
                type = below;
            if (below == POWDER_SNOW) type = DANGER_POWDER_SNOW;
        }
        if (type == WALKABLE) {
            for (int dx = NavigationEvalTuning.UNSET_OR_NOT_FOUND; dx <= 1; dx++)
                for (int dy = NavigationEvalTuning.UNSET_OR_NOT_FOUND; dy <= 1; dy++)
                    for (int dz = NavigationEvalTuning.UNSET_OR_NOT_FOUND; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        var neighbor = region.rawType(x + dx, y + dy, z + dz);
                        if (neighbor == DAMAGE_OTHER) return DANGER_OTHER;
                        if (neighbor == DAMAGE_FIRE || neighbor == LAVA) return DANGER_FIRE;
                        if (region.water(x + dx, y + dy, z + dz)) return WATER_BORDER;
                        if (neighbor == DAMAGE_CAUTIOUS) return DAMAGE_CAUTIOUS;
                    }
        }
        return type;
    }
}
