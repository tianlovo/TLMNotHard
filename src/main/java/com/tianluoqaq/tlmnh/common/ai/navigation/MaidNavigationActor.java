package com.tianluoqaq.tlmnh.common.ai.navigation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;

/** 原导航管理器的受控平台边界，路线可达与执行授权由实际导航实现提供 */
public interface MaidNavigationActor {

    boolean effectiveServerAi();

    boolean inWater();

    boolean underWater();

    boolean water(int x, int y, int z);

    boolean air(int x, int y, int z);

    boolean landPassable(int x, int y, int z);

    Point position();

    Point walkTarget();

    MaidSwimManager swim();

    Navigation ground();

    Navigation waterNavigation();

    Navigation current();

    void activate(Navigation navigation);

    interface Navigation {

        Path path();

        boolean done();

        Path create(Point target);

        boolean moveTo(Path path, double speed);

        double speed();

        void stop();
    }

    interface Path {

        boolean canReach();

        boolean done();

        int index();

        int size();

        Point node(int index);

        Point end();

        void advance();
    }

    final class Point {

        public final int x, y, z;

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
