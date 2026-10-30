package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class MaidBoundedPathPlannerTest {

    @Test
    public void cachedMcpAccessReadsCurrentFlagsAndInvokesTheNativeGate() {
        var nativeObject = new McpNavigator();
        var access = MaidBoundedPathPlanner.NativeAccess.resolve(McpNavigator.class);
        var first = access.options(nativeObject);
        assertTrue(first.supported);
        assertTrue(first.canSwim);
        assertFalse(first.enterDoors);
        assertFalse(access.canNavigate(nativeObject));
        nativeObject.canSwim = false;
        nativeObject.canPassOpenWoodenDoors = true;
        nativeObject.onGround = true;
        var second = access.options(nativeObject);
        assertFalse(second.canSwim);
        assertTrue(second.enterDoors);
        assertTrue(access.canNavigate(nativeObject));
        assertTrue(nativeObject.calls == 2);
    }

    @Test
    public void packagedSrgNamesAreSupportedWithoutMcpMembers() {
        var nativeObject = new SrgNavigator();
        var access = MaidBoundedPathPlanner.NativeAccess.resolve(SrgNavigator.class);
        var options = access.options(nativeObject);
        assertTrue(options.supported);
        assertFalse(options.canSwim);
        assertTrue(options.enterDoors);
        assertTrue(access.canNavigate(nativeObject));
    }

    @Test
    public void missingMembersCannotFallBackToPermissiveNavigation() {
        var access = MaidBoundedPathPlanner.NativeAccess.resolve(Object.class);
        var options = access.options(new Object());
        assertFalse(options.supported);
        assertTrue(options.diagnostic.contains("unavailable"));
        try {
            access.canNavigate(new Object());
            fail("Missing native gate must not become true");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("unavailable"));
        }
    }

    @Test
    public void incompatibleFieldTypesAreRefusedBeforeReadingFlags() {
        var access = MaidBoundedPathPlanner.NativeAccess.resolve(WrongTypes.class);
        var options = access.options(new WrongTypes());
        assertFalse(options.supported);
        assertTrue(options.diagnostic.contains("type_mismatch"));
    }

    @Test
    public void aThrowingNativeGateIsReportedInsteadOfPermitted() {
        var access = MaidBoundedPathPlanner.NativeAccess.resolve(ThrowingNavigator.class);
        try {
            access.canNavigate(new ThrowingNavigator());
            fail("Native gate errors must not become true");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("native_canNavigate_unavailable"));
            assertTrue(expected.getCause() != null);
        }
    }

    private static final class McpNavigator {

        private boolean canSwim = true;
        private boolean canPassOpenWoodenDoors;
        private boolean onGround;
        private int calls;

        @SuppressWarnings("unused")
        private boolean canNavigate() {
            calls++;
            return onGround;
        }
    }

    private static final class SrgNavigator {

        @SuppressWarnings("unused")
        private boolean field_75517_m;
        @SuppressWarnings("unused")
        private boolean field_75518_j = true;

        @SuppressWarnings("unused")
        private boolean func_75485_k() {
            return true;
        }
    }

    private static final class WrongTypes {

        @SuppressWarnings("unused")
        private String canSwim = "true";
        @SuppressWarnings("unused")
        private boolean canPassOpenWoodenDoors;

        @SuppressWarnings("unused")
        private boolean canNavigate() {
            return true;
        }
    }

    private static final class ThrowingNavigator {

        @SuppressWarnings("unused")
        private boolean canSwim, canPassOpenWoodenDoors;

        @SuppressWarnings("unused")
        private boolean canNavigate() {
            throw new IllegalStateException("Native gate failed");
        }
    }
}
