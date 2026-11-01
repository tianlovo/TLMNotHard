package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 每次查询的方块/碰撞边界，不访问客户端或主动加载区块 */
public interface MaidPathRegion {

    MaidPathType rawType(int x, int y, int z);

    default MaidPathType groundRawType(int x, int y, int z) {
        return rawType(x, y, z);
    }

    default boolean climbable(int x, int y, int z) {
        return false;
    }

    default boolean landPassable(int x, int y, int z) {
        var type = rawType(x, y, z);
        return type == MaidPathType.OPEN || type == MaidPathType.DOOR_OPEN || type == MaidPathType.RAIL;
    }

    default boolean air(int x, int y, int z) {
        return rawType(x, y, z) == MaidPathType.OPEN;
    }

    default boolean solid(int x, int y, int z) {
        return rawType(x, y, z) == MaidPathType.BLOCKED;
    }

    boolean water(int x, int y, int z);

    boolean fluidEmpty(int x, int y, int z);

    double floor(int x, int y, int z);

    boolean collides(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    boolean rail(int x, int y, int z);

    int minY();

    int maxY();

    int seaLevel();
}
