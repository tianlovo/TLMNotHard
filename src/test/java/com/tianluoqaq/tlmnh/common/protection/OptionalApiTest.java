package com.tianluoqaq.tlmnh.common.protection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.invoke.MethodType;

import org.junit.Test;

/** 核对可选 API 绑定器在未装模组、缺类、缺成员及签名漂移四类场景下的行为 */
public class OptionalApiTest {

    public static final class SampleProvider {

        public static final String NAMESPACE = "modern_assets";

        public String instanceTag = "not_static";

        public static int compute(String label, int base) {
            return label.length() + base;
        }

        public String greet(String target) {
            return "hi:" + target;
        }
    }

    @Test
    public void absentModReturnsNullWithoutLoadingTargetClass() {
        ClassLoader loader = OptionalApiTest.class.getClassLoader();
        assertNull(OptionalApi.findClass(id -> false, "missing_mod", "no.such.Type", loader));
        assertNull(
            OptionalApi.findStatic(
                id -> false,
                loader,
                "missing_mod",
                "no.such.Type",
                "compute",
                MethodType.methodType(int.class, String.class, int.class)));
        assertNull(
            OptionalApi
                .findStaticGetter(id -> false, loader, "missing_mod", "no.such.Type", "NAMESPACE", String.class));
    }

    @Test
    public void staticVirtualAndFieldHandlesInvokeTargetMembers() throws Throwable {
        ClassLoader loader = OptionalApiTest.class.getClassLoader();
        String owner = SampleProvider.class.getName();
        assertSame(SampleProvider.class, OptionalApi.findClass(id -> true, "sample", owner, loader));

        var staticHandle = OptionalApi.findStatic(
            id -> true,
            loader,
            "sample",
            owner,
            "compute",
            MethodType.methodType(int.class, String.class, int.class));
        assertEquals(9, (int) staticHandle.invokeExact("tlmnh", 4));

        var virtualHandle = OptionalApi.findVirtual(
            id -> true,
            loader,
            "sample",
            owner,
            "greet",
            MethodType.methodType(String.class, String.class));
        assertEquals("hi:reimu", (String) virtualHandle.invokeExact(new SampleProvider(), "reimu"));

        var getter = OptionalApi.findStaticGetter(id -> true, loader, "sample", owner, "NAMESPACE", String.class);
        assertEquals("modern_assets", (String) getter.invokeExact());
    }

    @Test
    public void loadedModWithMissingClassOrMemberReportsExplicitDiagnostics() {
        ClassLoader loader = OptionalApiTest.class.getClassLoader();
        String owner = SampleProvider.class.getName();

        expectIllegalState(
            () -> OptionalApi.findClass(id -> true, "sample", "com.example.NonExistentProvider", loader),
            "NonExistentProvider");

        expectIllegalState(
            () -> OptionalApi
                .findStatic(id -> true, loader, "sample", owner, "missingMethod", MethodType.methodType(void.class)),
            "missingMethod");

        expectIllegalState(
            () -> OptionalApi.findStaticGetter(id -> true, loader, "sample", owner, "MISSING_FIELD", String.class),
            "MISSING_FIELD");
    }

    @Test
    public void mismatchedReturnOrStaticModifierIsRejectedImmediately() {
        ClassLoader loader = OptionalApiTest.class.getClassLoader();
        String owner = SampleProvider.class.getName();

        expectIllegalState(
            () -> OptionalApi.findStatic(
                id -> true,
                loader,
                "sample",
                owner,
                "compute",
                MethodType.methodType(long.class, String.class, int.class)),
            "signature mismatch");

        expectIllegalState(
            () -> OptionalApi.findStatic(
                id -> true,
                loader,
                "sample",
                owner,
                "greet",
                MethodType.methodType(String.class, String.class)),
            "signature mismatch");

        expectIllegalState(
            () -> OptionalApi.findStaticGetter(id -> true, loader, "sample", owner, "instanceTag", String.class),
            "signature mismatch");
    }

    private static void expectIllegalState(Runnable action, String expectedFragment) {
        try {
            action.run();
            fail("Expected IllegalStateException containing: " + expectedFragment);
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage(),
                expected.getMessage()
                    .contains(expectedFragment));
        }
    }
}
