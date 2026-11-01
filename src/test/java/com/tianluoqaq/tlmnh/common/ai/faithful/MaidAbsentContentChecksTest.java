package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

import org.junit.Test;

public class MaidAbsentContentChecksTest {

    @Test
    public void sourceClocksStayIndependentAndMemoryPreconditionsPauseOnlyIdleChecks() {
        var checks = new MaidAbsentContentChecks();
        List<Integer> draws = new ArrayList<>();
        IntUnaryOperator random = bound -> {
            draws.add(bound);
            return bound - 1;
        };
        checks.visitFindHomeMeal(false, random);
        checks.visitJoy(false, random);
        checks.visitHomeMeal(random);
        checks.visitFindHomeMeal(true, random);
        checks.visitJoy(true, random);
        assertEquals(Arrays.asList(50, 10, 10), draws);
        for (int i = 0; i < 100; i++) {
            checks.visitFindHomeMeal(false, random);
            checks.visitJoy(false, random);
        }
        for (int i = 0; i < 19; i++) checks.visitFindHomeMeal(true, random);
        for (int i = 0; i < 99; i++) checks.visitHomeMeal(random);
        assertEquals(Arrays.asList(50, 10, 10), draws);
        checks.visitFindHomeMeal(true, random);
        for (int i = 0; i < 19; i++) checks.visitJoy(true, random);
        assertEquals(Arrays.asList(50, 10, 10, 10), draws);
        checks.visitJoy(true, random);
        checks.visitHomeMeal(random);
        assertEquals(Arrays.asList(50, 10, 10, 10, 10, 50), draws);
    }

    @Test
    public void brainRefreshRecreatesChecksWithoutAnExtraRandomDraw() {
        var checks = new MaidAbsentContentChecks();
        List<Integer> draws = new ArrayList<>();
        IntUnaryOperator random = bound -> {
            draws.add(bound);
            return 0;
        };
        checks.visitFindHomeMeal(true, random);
        checks.visitJoy(true, random);
        checks.visitHomeMeal(random);
        checks.reset();
        assertEquals(Arrays.asList(10, 10, 50), draws);
        checks.visitFindHomeMeal(false, random);
        checks.visitJoy(false, random);
        checks.visitHomeMeal(random);
        checks.visitFindHomeMeal(true, random);
        checks.visitJoy(true, random);
        assertEquals(Arrays.asList(10, 10, 50, 50, 10, 10), draws);
    }
}
