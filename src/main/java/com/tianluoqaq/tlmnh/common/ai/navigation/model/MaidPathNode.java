package com.tianluoqaq.tlmnh.common.ai.navigation.model;

/** 查询局部节点，不复用旧 PathPoint 的二维搜索状态 */
public final class MaidPathNode {

    public final int x, y, z;
    public float g, h, f, walked, cost;
    public boolean closed;
    public int heapIndex = NavigationModelTuning.UNSET_OR_NOT_FOUND;
    public MaidPathNode parent;
    public MaidPathType type = MaidPathType.BLOCKED;

    public MaidPathNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public float distance(MaidPathNode other) {
        float dx = other.x - x, dy = other.y - y, dz = other.z - z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float manhattan(MaidPathNode other) {
        return Math.abs((float) (other.x - x)) + Math.abs((float) (other.y - y)) + Math.abs((float) (other.z - z));
    }

    public static int key(int x, int y, int z) {
        return y & NavigationModelTuning.UNSIGNED_BYTE_MASK
            | (x & NavigationModelTuning.PATH_NODE_BIT_MASK_32767) << NavigationModelTuning.SECOND_BYTE_SHIFT
            | (z & NavigationModelTuning.PATH_NODE_BIT_MASK_32767) << NavigationModelTuning.ALPHA_CHANNEL_SHIFT
            | (x < 0 ? Integer.MIN_VALUE : NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE)
            | (z < 0 ? NavigationModelTuning.PATH_NODE_KEY_PARAM : NavigationModelTuning.PATH_NODE_KEY_ZERO_BASE);
    }
}
