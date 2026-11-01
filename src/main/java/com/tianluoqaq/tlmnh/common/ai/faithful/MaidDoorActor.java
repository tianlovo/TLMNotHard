package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Set;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 原PATH/DOORS_TO_CLOSE边界；路径是本帧只读引用，门记忆跨行为刷新保留 */
public interface MaidDoorActor {

    enum Kind {
        NONE,
        WOOD_DOOR,
        FENCE_GATE
    }

    final class Position {

        public final int dimension;
        public final Point point;

        public Position(int dimension, Point point) {
            this.dimension = dimension;
            this.point = java.util.Objects.requireNonNull(point, "point");
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Position pos && dimension == pos.dimension && point.equals(pos.point);
        }

        @Override
        public int hashCode() {
            return dimension * 31 + point.hashCode();
        }
    }

    interface DoorPath {

        boolean notStarted();

        boolean done();

        Point previous();

        Point next();
    }

    boolean isCurrent();

    DoorPath doorPath();

    Set<Position> doorsToClose();

    void rememberDoors(Set<Position> positions);

    Position doorPosition(Point point);

    Point doorFeet();

    boolean canOpenDoor();

    boolean canOpenFenceGate();

    Kind doorKind(Position pos);

    boolean doorOpen(Position pos);

    boolean setDoorOpen(Position pos, boolean value);

    boolean doorTooFar(Position pos, double distance);

    boolean otherMaidsComingThrough(Position pos);
}
