package com.tianluoqaq.tlmnh.common.combat;

import static org.junit.Assert.*;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.combat.ArrowInterceptSolver.Status;

public class ArrowInterceptSolverTest {

    @Test
    public void firstTickMovesBeforeGravityAndRetainsFloatConstants() {
        var state = new ArrowPhysics.State(BallisticVector.ZERO, new BallisticVector(1, 0, 0));
        var first = ArrowPhysics.OVERWORLD_AIR.step(state);
        assertEquals(1, first.position.x, 0);
        assertEquals(0, first.position.y, 0);
        assertEquals((double) 0.99F, first.velocity.x, 0);
        assertEquals(-(double) 0.05F, first.velocity.y, 0);
        var second = ArrowPhysics.OVERWORLD_AIR.step(first);
        assertEquals(-(double) 0.05F, second.position.y, 0);
        assertEquals((double) 0.8F, ArrowPhysics.OVERWORLD_WATER.drag, 0);
    }

    @Test
    public void zeroGravityLimitMatchesStraightIntercept() {
        var result = ArrowInterceptSolver.solve(
            BallisticVector.ZERO,
            new BallisticVector(20, 0, 0),
            new BallisticVector(0, 0, .5),
            3,
            80,
            new ArrowPhysics(1, 0));
        assertTrue(result.isSolved());
        assertEquals(20 / Math.sqrt(9 - .25), result.flightTicks, 1E-12);
        assertEquals(.5, result.initialVelocity.z, 1E-12);
        assertEquals(3, result.initialVelocity.length(), 1E-12);
    }

    @Test
    public void solvesGravityDragAndMovingTargetsAgainstTickSimulation() {
        var origin = new BallisticVector(100, 70, -80);
        for (var physics : new ArrowPhysics[] { ArrowPhysics.OVERWORLD_AIR, ArrowPhysics.OVERWORLD_WATER }) {
            for (var relative : new BallisticVector[] { new BallisticVector(8, 0, 0), new BallisticVector(12, 3, 4),
                new BallisticVector(10, -2, -5) }) {
                for (var motion : new BallisticVector[] { BallisticVector.ZERO, new BallisticVector(0, 0, .2),
                    new BallisticVector(-.1, 0, .1), new BallisticVector(.15, .02, 0) }) {
                    var target = origin.add(physics == ArrowPhysics.OVERWORLD_WATER ? relative.scale(.5) : relative);
                    var result = ArrowInterceptSolver.solve(origin, target, motion, 3, 100, physics);
                    assertTrue(physics.drag + " / " + relative.x + " / " + motion.z, result.isSolved());
                    var actual = physics
                        .positionAt(new ArrowPhysics.State(origin, result.initialVelocity), result.flightTicks);
                    assertTrue(
                        actual.subtract(target.add(motion.scale(result.flightTicks)))
                            .length() < 1E-9);
                    assertTrue(result.evaluatedIntervals <= 100);
                    assertEquals(3, result.initialVelocity.length(), 1E-9);
                }
            }
        }
    }

    @Test
    public void choosesTheEarlierLowArcAndRejectsOutrunningOrInvalidTargets() {
        var result = ArrowInterceptSolver.solve(
            BallisticVector.ZERO,
            new BallisticVector(20, 0, 0),
            BallisticVector.ZERO,
            3,
            160,
            ArrowPhysics.OVERWORLD_AIR);
        assertTrue(result.isSolved());
        assertTrue(result.flightTicks < 10);
        assertTrue(result.initialVelocity.y > 0);
        assertEquals(
            Status.NO_INTERCEPT_WITHIN_BUDGET,
            ArrowInterceptSolver.solve(
                BallisticVector.ZERO,
                new BallisticVector(20, 0, 0),
                new BallisticVector(4, 0, 0),
                3,
                80,
                ArrowPhysics.OVERWORLD_AIR).status);
        assertEquals(
            Status.NO_INTERCEPT_WITHIN_BUDGET,
            ArrowInterceptSolver.solve(
                BallisticVector.ZERO,
                new BallisticVector(16, 0, 0),
                BallisticVector.ZERO,
                3,
                80,
                ArrowPhysics.OVERWORLD_WATER).status);
        assertEquals(
            Status.TOO_CLOSE,
            ArrowInterceptSolver.solve(
                BallisticVector.ZERO,
                new BallisticVector(.1, 0, 0),
                BallisticVector.ZERO,
                3,
                80,
                ArrowPhysics.OVERWORLD_AIR).status);
        assertEquals(
            Status.INVALID_INPUT,
            ArrowInterceptSolver.solve(
                BallisticVector.ZERO,
                new BallisticVector(10, 0, 0),
                BallisticVector.ZERO,
                Double.NaN,
                80,
                ArrowPhysics.OVERWORLD_AIR).status);
    }

    @Test
    public void doesNotSilentlyFallBackToCurrentPositionAim() {
        var target = new BallisticVector(20, 0, 0);
        var motion = new BallisticVector(0, 0, .3);
        var result = ArrowInterceptSolver
            .solve(BallisticVector.ZERO, target, motion, 3, 100, ArrowPhysics.OVERWORLD_AIR);
        assertTrue(result.isSolved());
        var naive = ArrowPhysics.OVERWORLD_AIR
            .positionAt(new ArrowPhysics.State(BallisticVector.ZERO, new BallisticVector(3, 0, 0)), result.flightTicks);
        assertTrue(
            naive.subtract(target.add(motion.scale(result.flightTicks)))
                .length() > 2);
        assertTrue(result.initialVelocity.z > 0 && result.initialVelocity.y > 0);
    }
}
