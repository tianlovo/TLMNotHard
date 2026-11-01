package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 每次查询的方块/碰撞边界，不访问客户端或主动加载区块 */
public interface MaidPathRegion {

    MaidPathType rawType(int x, int y, int z);

    boolean water(int x, int y, int z);

    boolean fluidEmpty(int x, int y, int z);

    double floor(int x, int y, int z);

    boolean collides(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    boolean rail(int x, int y, int z);

    int minY();

    int maxY();

    int seaLevel();
}
