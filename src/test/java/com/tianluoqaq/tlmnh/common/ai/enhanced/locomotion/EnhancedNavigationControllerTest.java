package com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.budget.MaidNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedNavigationController.Outcome;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Path;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.WalkTarget;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathCreation;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathCreation.Result;

public class EnhancedNavigationControllerTest {

    @Test
    public void runningRouteCannotEraseTheSameActorsQueuedModeSwitch() {
        var budget = new MaidNavigationBudget(1);
        var identity = new UUID(0, 1);
        var other = new UUID(0, 2);
        var f = new Fixture();
        var controller = new EnhancedNavigationController(f.actor, target -> {
            assertTrue(budget.acquire(identity, f.time));
            return new MaidPathCreation(Result.FOUND, new Path() {

                @Override
                public Object getIdentity() {
                    return this;
                }

                @Override
                public boolean canReach() {
                    return true;
                }
            });
        }, () -> budget.cancel(identity));
        assertEquals(Outcome.RUNNING, controller.tick());
        // 平台水陆切换在同tick沿用此身份申请第二次查询，额度已用尽
        assertFalse(budget.acquire(identity, f.time));
        assertEquals(1, budget.queued());
        f.time++;
        assertEquals(Outcome.RUNNING, controller.tick());
        assertEquals(1, budget.queued());
        assertFalse(budget.acquire(other, f.time));
        assertTrue(budget.acquire(identity, f.time));
        budget.cancel(other);
        assertFalse(budget.acquire(identity, f.time));
        f.hasTarget = false;
        assertEquals(Outcome.IDLE, controller.tick());
        assertEquals(1, budget.queued());
        controller.cancel();
        assertEquals(0, budget.queued());
    }

    @Test
    public void repeatedBudgetWaitingPreservesRouteAndFailureMemoryThenQueriesTheLiveGoal() {
        var f = new Fixture();
        assertEquals(Outcome.RUNNING, f.controller.tick());
        var route = f.installed;
        f.since = 17L;
        f.target = new BlockPos(12, 0, 0);
        f.result = Result.WAITING_BUDGET;
        for (int i = 1; i <= 25; i++) {
            f.time = i;
            assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
            assertSame(route, f.installed);
            assertSame(route, f.memory);
            assertEquals(Long.valueOf(17), f.since);
            assertEquals(0, f.controller.failureCount());
        }
        f.target = new BlockPos(16, 0, 0);
        f.result = Result.FOUND;
        f.time++;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertEquals(16, f.requested.x);
        assertNotSame(route, f.installed);
        assertSame(f.installed, f.memory);
        assertNull(f.since);
        assertEquals(25, f.controller.waitingCount());
    }

    @Test
    public void OnlyActualFailureStartsCooldownAndMovingTargetInvalidatesIt() {
        var f = new Fixture();
        f.result = Result.WAITING_BUDGET;
        assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
        f.time = 1;
        f.result = Result.NOT_NAVIGABLE;
        assertEquals(Outcome.NOT_NAVIGABLE, f.controller.tick());
        assertNull(f.since);
        f.time = 2;
        f.result = Result.UNREACHABLE;
        assertEquals(Outcome.UNREACHABLE, f.controller.tick());
        assertEquals(Long.valueOf(2), f.since);
        assertEquals(1, f.controller.failureCount());
        int before = f.requests;
        f.time = 3;
        f.result = Result.FOUND;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        assertEquals(before, f.requests);
        f.target = new BlockPos(14, 0, 0);
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertNull(f.since);
        f.controller.cancel();
        assertEquals(Outcome.IDLE, f.controller.outcome());
    }

    private static final class Fixture {

        long time;
        boolean hasTarget = true, done, stuck, reachable = true, observeBlockage, corridorClear;
        int requests;
        Long since;
        BlockPos target = new BlockPos(8, 0, 0), requested;
        BlockPos position = new BlockPos(0, 0, 0);
        Path installed, memory;
        Result result = Result.FOUND;
        final WalkTarget walk = new WalkTarget() {

            @Override
            public BlockPos currentBlockPosition() {
                return target;
            }

            @Override
            public float getSpeedModifier() {
                return .5F;
            }

            @Override
            public int getCloseEnoughDistance() {
                return 2;
            }

            @Override
            public boolean isEntityTrackerSpectator() {
                return false;
            }
        };
        final FollowMoveActor actor = (FollowMoveActor) Proxy.newProxyInstance(
            FollowMoveActor.class.getClassLoader(),
            new Class<?>[] { FollowMoveActor.class, EnhancedRouteObstacleActor.class },
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "worldGameTime" -> time;
                    case "stopNavigation" -> {
                        installed = null;
                        yield null;
                    }
                    case "eraseWalkTarget" -> {
                        hasTarget = false;
                        yield null;
                    }
                    case "getWalkTarget" -> hasTarget ? walk : null;
                    case "blockPosition" -> position;
                    case "navigationPath" -> installed;
                    case "navigationIsDone" -> done;
                    case "navigationIsStuck" -> stuck;
                    case "observeBlockedRoute" -> observeBlockage ? (BooleanSupplier) () -> corridorClear : null;
                    case "moveTo" -> {
                        installed = (Path) args[0];
                        yield true;
                    }
                    case "getCantReachSince" -> since;
                    case "setCantReachSince" -> {
                        since = (Long) args[0];
                        yield null;
                    }
                    case "eraseCantReachSince" -> {
                        since = null;
                        yield null;
                    }
                    case "setPathMemory" -> {
                        memory = (Path) args[0];
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected actor call: " + method.getName());
                };
            });
        final EnhancedNavigationController controller = new EnhancedNavigationController(actor, pos -> {
            requested = pos;
            requests++;
            Path path = result == Result.FOUND ? new Path() {

                final boolean reaches = reachable;

                @Override
                public Object getIdentity() {
                    return this;
                }

                @Override
                public boolean canReach() {
                    return reaches;
                }
            } : null;
            return new MaidPathCreation(result, path);
        }, () -> {});
    }

    @Test
    public void exhaustedZeroProgressPartialRouteWaitsWithoutErasingGoalOrRefundingFailureMemory() {
        var f = new Fixture();
        f.reachable = false;
        assertEquals(Outcome.PARTIAL_PATH, f.controller.tick());
        f.done = true;
        f.time = 1;
        assertEquals(Outcome.RECOVERY_WAIT, f.controller.tick());
        assertTrue(f.hasTarget);
        assertNull(f.installed);
        assertNull(f.memory);
        assertEquals(Long.valueOf(0), f.since);
        assertEquals(1, f.controller.recoveryCount());
        int before = f.requests;
        f.time = 20;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        assertEquals(before, f.requests);
        f.time = 21;
        f.done = false;
        f.reachable = true;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertNull(f.since);
    }

    @Test
    public void completedPartialWithActualProgressCanContinueWithoutDelay() {
        var f = new Fixture();
        f.reachable = false;
        assertEquals(Outcome.PARTIAL_PATH, f.controller.tick());
        f.position = new BlockPos(4, 0, 0);
        f.time = 1;
        f.done = true;
        assertEquals(Outcome.PARTIAL_PATH, f.controller.tick());
        assertEquals(2, f.requests);
        assertEquals(0, f.controller.recoveryCount());
    }

    @Test
    public void stationaryLiveRouteRecoversThenQuotaWaitingIsNotAnotherFailure() {
        var f = new Fixture();
        assertEquals(Outcome.RUNNING, f.controller.tick());
        f.time = 39;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        f.time = 40;
        assertEquals(Outcome.RECOVERY_WAIT, f.controller.tick());
        assertEquals(Long.valueOf(40), f.since);
        f.result = Result.WAITING_BUDGET;
        f.time = 60;
        assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
        assertEquals(0, f.controller.failureCount());
        assertEquals(Long.valueOf(40), f.since);
        f.target = new BlockPos(14, 0, 0);
        f.result = Result.FOUND;
        f.time++;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertNull(f.since);
    }

    @Test
    public void actualUnreachableResultStopsOldRouteButRetainsCurrentGoal() {
        var f = new Fixture();
        assertEquals(Outcome.RUNNING, f.controller.tick());
        f.target = new BlockPos(12, 0, 0);
        f.time = 1;
        f.result = Result.UNREACHABLE;
        assertEquals(Outcome.UNREACHABLE, f.controller.tick());
        assertTrue(f.hasTarget);
        assertNull(f.installed);
        assertNull(f.memory);
    }

    @Test
    public void openedOriginalCorridorRetriesEarlyThroughQuotaAndCannotGrantRepeatedFailureRetries() {
        var f = new Fixture();
        f.observeBlockage = true;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        f.time = 40;
        assertEquals(Outcome.RECOVERY_WAIT, f.controller.tick());
        int before = f.requests;
        for (f.time = 41; f.time <= 44; f.time++) {
            assertEquals(Outcome.COOLDOWN, f.controller.tick());
            assertEquals(before, f.requests);
        }
        f.corridorClear = true;
        f.result = Result.WAITING_BUDGET;
        assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
        assertEquals(before + 1, f.requests);
        assertEquals(Long.valueOf(40), f.since);
        // 预算等待不能把已经确认的机会丢回原20tick冷却，也不能成为新失败
        f.time++;
        assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
        assertEquals(before + 2, f.requests);
        assertEquals(0, f.controller.failureCount());
        f.time++;
        f.result = Result.UNREACHABLE;
        assertEquals(Outcome.UNREACHABLE, f.controller.tick());
        before = f.requests;
        f.result = Result.FOUND;
        f.time++;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        assertEquals(before, f.requests);
        f.time = 66;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        f.time = 67;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertNull(f.since);
    }

    @Test
    public void earlyObstacleWaitCannotSurviveCancellationOrTimeRollback() {
        var f = new Fixture();
        f.observeBlockage = true;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        f.time = 40;
        assertEquals(Outcome.RECOVERY_WAIT, f.controller.tick());
        f.corridorClear = true;
        f.result = Result.WAITING_BUDGET;
        f.time++;
        assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
        f.controller.cancel();
        f.corridorClear = false;
        f.result = Result.UNREACHABLE;
        f.time++;
        assertEquals(Outcome.UNREACHABLE, f.controller.tick());
        int before = f.requests;
        f.corridorClear = true;
        f.time++;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        assertEquals(before, f.requests);
        f.time = 0;
        f.result = Result.FOUND;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertEquals(before + 1, f.requests);
    }

    @Test
    public void pendingEarlyRetryLosesItsGrantWhenTheOriginalCorridorBecomesUnreadableOrBlockedAgain() {
        var f = new Fixture();
        f.observeBlockage = true;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        f.time = 40;
        assertEquals(Outcome.RECOVERY_WAIT, f.controller.tick());
        f.corridorClear = true;
        f.result = Result.WAITING_BUDGET;
        f.time++;
        assertEquals(Outcome.WAITING_BUDGET, f.controller.tick());
        int before = f.requests;
        f.corridorClear = false;
        f.time++;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        assertEquals(before, f.requests);
        assertEquals(0, f.controller.failureCount());
        f.corridorClear = true;
        f.time++;
        assertEquals(Outcome.COOLDOWN, f.controller.tick());
        assertEquals(before, f.requests);
        f.time = 60;
        f.result = Result.FOUND;
        assertEquals(Outcome.RUNNING, f.controller.tick());
    }

    @Test
    public void sameFrameReachablePreflightInstallsWithoutASecondQueryAndOldFrameCannotInstall() {
        var f = new Fixture();
        Path prepared = new Path() {

            public Object getIdentity() {
                return this;
            }

            public boolean canReach() {
                return true;
            }
        };
        f.controller.acceptPreflight(f.target, new MaidPathCreation(Result.FOUND, prepared), 0);
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertEquals(0, f.requests);
        assertSame(prepared, f.installed);
        f.target = new BlockPos(14, 0, 0);
        f.controller.acceptPreflight(f.target, new MaidPathCreation(Result.FOUND, prepared), 0);
        f.time = 1;
        assertEquals(Outcome.RUNNING, f.controller.tick());
        assertEquals(1, f.requests);
        assertNotSame(prepared, f.installed);
    }
}
