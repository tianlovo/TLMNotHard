/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/MaidNavigationManager.java, MIT
 * Adaptation: explicit navigation handles, target-world fluid and path facts
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Navigation;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public final class MaidNavigationManager {

    public enum Mode {
        GROUND,
        WATER
    }

    private final MaidNavigationActor actor;
    private final Navigation basicNavigation, waterNavigation;
    private Mode mode = Mode.GROUND;

    public MaidNavigationManager(MaidNavigationActor actor) {
        this.actor = actor;
        basicNavigation = actor.ground();
        waterNavigation = actor.waterNavigation();
        actor.activate(basicNavigation);
    }

    public Mode mode() {
        return mode;
    }

    public void tick() {
        var swim = actor.swim();
        if (actor.effectiveServerAi()) {
            if (mode != Mode.WATER && actor.inWater() && waterNodes(5)) {
                if (switchTo(Mode.WATER, waterNavigation)) {
                    swim.setWantToSwim(true);
                    swim.setReadyToLand(false);
                }
            } else if (mode != Mode.WATER && actor.underWater() && stuck(actor.position())) {
                if (switchTo(Mode.WATER, waterNavigation)) {
                    swim.setWantToSwim(true);
                    swim.setReadyToLand(false);
                }
            } else if (mode != Mode.WATER && actor.inWater() && targetingUnderWater()) {
                if (switchTo(Mode.WATER, waterNavigation)) {
                    swim.setWantToSwim(true);
                    swim.setReadyToLand(false);
                }
            } else if (mode == Mode.WATER) {
                boolean shouldUseWater = actor.inWater() && targetingUnderWater()
                    || actor.underWater() && stuck(actor.position());
                Point end = end(waterNavigation);
                if (!shouldUseWater && end != null) {
                    if (!waterNodes(2)) {
                        if (!water(end) && !actor.water(end.x, end.y - 1, end.z)) {
                            if (switchTo(Mode.GROUND, basicNavigation)) {
                                swim.setReadyToLand(true);
                                swim.setWantToSwim(false);
                            }
                        } else if (surface(end)) {
                            swim.setWantToSwim(false);
                            swim.setReadyToLand(false);
                        } else {
                            swim.setWantToSwim(true);
                            swim.setReadyToLand(false);
                        }
                    } else if (!actor.inWater()) {
                        if (switchTo(Mode.GROUND, basicNavigation)) {
                            swim.setWantToSwim(false);
                            swim.setReadyToLand(false);
                        }
                    } else if (!actor.underWater()) swim.setWantToSwim(false);
                    else {
                        swim.setWantToSwim(true);
                        swim.setSwimTarget(target(end));
                    }
                } else if (end == null && swim.isGoingToBreath()) swim.setWantToSwim(false);
                else if (shouldUseWater) {
                    swim.setWantToSwim(true);
                    if (end != null) swim.setSwimTarget(target(end));
                }
            }
        }
        if (mode != Mode.WATER) swim.setWantToSwim(false);
    }

    private boolean switchTo(Mode requested, Navigation next) {
        var current = actor.current();
        if (!current.done()) {
            var path = next.create(
                current.path()
                    .end());
            if (path != null && path.canReach() && next.moveTo(path, current.speed())) {
                path.advance();
                actor.activate(next);
                mode = requested;
                current.stop();
                return true;
            }
        } else {
            actor.activate(next);
            next.stop();
            current.stop();
            // 原码此分支没有写mode；忠实保留，不能按注释推断它应该更新
            return true;
        }
        return false;
    }

    private boolean targetingUnderWater() {
        var target = actor.walkTarget();
        return target != null && water(target)
            && actor.water(target.x, target.y + 1, target.z)
            && actor.water(target.x, target.y + 2, target.z);
    }

    private boolean stuck(Point point) {
        return water(point) && !actor.landPassable(point.x, point.y + 1, point.z);
    }

    private boolean waterNodes(int count) {
        var path = actor.current()
            .path();
        if (path == null || path.done() || path.index() > path.size() - count) return false;
        for (int i = path.index(); i < path.index() + count; i++) if (!water(path.node(i))) return false;
        return true;
    }

    private boolean surface(Point p) {
        return water(p) && actor.air(p.x, p.y + 1, p.z) || actor.water(p.x, p.y - 1, p.z) && actor.air(p.x, p.y, p.z)
            || actor.water(p.x, p.y + 1, p.z) && actor.air(p.x, p.y + 2, p.z);
    }

    private boolean water(Point point) {
        return actor.water(point.x, point.y, point.z);
    }

    private static Point end(Navigation navigation) {
        return navigation.path() == null ? null
            : navigation.path()
                .end();
    }

    private static MaidSwimManager.Target target(Point p) {
        return new MaidSwimManager.Target(p.x, p.y, p.z);
    }

    public void resetNavigation() {
        actor.activate(basicNavigation);
        basicNavigation.stop();
        waterNavigation.stop();
        actor.swim()
            .setWantToSwim(false);
        actor.swim()
            .setReadyToLand(false);
        mode = Mode.GROUND;
    }
}
