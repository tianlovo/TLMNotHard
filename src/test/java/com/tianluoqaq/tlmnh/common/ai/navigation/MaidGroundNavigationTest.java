package com.tianluoqaq.tlmnh.common.ai.navigation;

import static org.junit.Assert.*;

import java.util.ArrayList;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidNavigationManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimmingActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public class MaidGroundNavigationTest {

    @Test
    public void emptySwitchChangesNavigationButDoesNotWriteMode() {
        var actor = new Actor();
        var manager = new MaidNavigationManager(actor);
        manager.tick();
        assertSame(actor.water, actor.active);
        assertEquals(MaidNavigationManager.Mode.GROUND, manager.mode());
        assertFalse(actor.swim.wantToSwim());
        assertEquals(0, actor.water.creations);
    }

    @Test
    public void failedSwitchPreservesRouteAndSuccessfulSwitchAdvancesNewRoute() {
        var actor = new Actor();
        var manager = new MaidNavigationManager(actor);
        var old = path(true, false);
        actor.ground.path = old;
        actor.ground.speed = 1.25;
        actor.water.next = path(false, true);
        manager.tick();
        assertSame(actor.ground, actor.active);
        assertSame(old, actor.ground.path);
        assertFalse(actor.swim.wantToSwim());
        assertEquals(0, actor.ground.stops);
        actor.water.next = path(true, true);
        manager.tick();
        assertSame(actor.water, actor.active);
        assertEquals(MaidNavigationManager.Mode.WATER, manager.mode());
        assertEquals(1, actor.water.path.index());
        assertEquals(1.25, actor.water.speed, 0);
        assertNull(actor.ground.path);
        assertEquals(1, actor.ground.stops);
        assertTrue(actor.swim.wantToSwim());
    }

    @Test
    public void continuationKeepsOnlyItsOriginCancellationIdentity() {
        var owner = path(true, false);
        var continuation = path(true, true);
        var unrelated = path(true, false);
        assertFalse(MaidPathOwnership.owns(continuation, owner));
        continuation.continueCancellation(owner);
        assertTrue(MaidPathOwnership.owns(continuation, owner));
        assertFalse(MaidPathOwnership.owns(continuation, unrelated));
        var next = path(true, false);
        next.continueCancellation(continuation);
        assertTrue(MaidPathOwnership.owns(next, owner));
        assertTrue(MaidPathOwnership.owns(next, continuation));
        assertFalse(MaidPathOwnership.owns(unrelated, owner));
    }

    private static MaidModernPath path(boolean reachable, boolean water) {
        var points = new ArrayList<MaidPathNode>();
        for (int x = 0; x < 5; x++) {
            var node = new MaidPathNode(x, 1, 0);
            node.type = MaidPathType.WATER;
            points.add(node);
        }
        return new MaidModernPath(new MaidPathSearch.Result(points, reachable, 5, 4, 1, 0, water));
    }

    private static final class Nav implements MaidNavigationActor.Navigation {

        MaidModernPath path, next;
        double speed;
        int stops, creations;

        @Override
        public MaidNavigationActor.Path path() {
            return path;
        }

        @Override
        public boolean done() {
            return path == null || path.done();
        }

        @Override
        public MaidNavigationActor.Path create(Point target) {
            creations++;
            return next;
        }

        @Override
        public boolean moveTo(MaidNavigationActor.Path path, double speed) {
            this.path = (MaidModernPath) path;
            this.speed = speed;
            return true;
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public void stop() {
            stops++;
            path = null;
        }
    }

    private static final class Actor implements MaidNavigationActor {

        final Nav ground = new Nav(), water = new Nav();
        Nav active;
        final MaidSwimManager swim = new MaidSwimManager(new MaidSwimmingActor() {

            @Override
            public void configureWaterPath() {}

            @Override
            public boolean isClient() {
                return false;
            }

            @Override
            public boolean onGround() {
                return false;
            }

            @Override
            public boolean isSitting() {
                return false;
            }

            @Override
            public boolean isPassenger() {
                return false;
            }

            @Override
            public boolean isSleeping() {
                return false;
            }

            @Override
            public void setSwimming(boolean value) {}

            @Override
            public void setSwimmingPose() {}

            @Override
            public void setStandingPose() {}
        });

        @Override
        public boolean effectiveServerAi() {
            return true;
        }

        @Override
        public boolean inWater() {
            return true;
        }

        @Override
        public boolean underWater() {
            return true;
        }

        @Override
        public boolean water(int x, int y, int z) {
            return y >= 1 && y <= 4;
        }

        @Override
        public boolean air(int x, int y, int z) {
            return y > 4;
        }

        @Override
        public boolean landPassable(int x, int y, int z) {
            return false;
        }

        @Override
        public Point position() {
            return new Point(0, 1, 0);
        }

        @Override
        public Point walkTarget() {
            return new Point(4, 1, 0);
        }

        @Override
        public MaidSwimManager swim() {
            return swim;
        }

        @Override
        public Navigation ground() {
            return ground;
        }

        @Override
        public Navigation waterNavigation() {
            return water;
        }

        @Override
        public Navigation current() {
            return active;
        }

        @Override
        public void activate(Navigation navigation) {
            active = (Nav) navigation;
        }
    }
}
