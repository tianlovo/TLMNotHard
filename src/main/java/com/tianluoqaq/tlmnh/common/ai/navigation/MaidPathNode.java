package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 查询局部节点，不复用旧 PathPoint 的二维搜索状态 */
public final class MaidPathNode {

    public final int x, y, z;
    public float g, h, f, walked, cost;
    public boolean closed;
    public int heapIndex = -1;
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
        return y & 255 | (x & 32767) << 8 | (z & 32767) << 24 | (x < 0 ? Integer.MIN_VALUE : 0) | (z < 0 ? 32768 : 0);
    }
}
