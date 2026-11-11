package com.tianluoqaq.tlmnh.common.ai.enhanced.behavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.Test;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorInitContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorModule;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorOfferSink;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorProvider;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorRegistry;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorStage;

public class MaidBehaviorRegistryAndIdTest {

    @Test
    public void builtinNamespaceRetainsItsPublishedFieldContract() throws ReflectiveOperationException {
        var field = MaidBehaviorId.class.getField("BUILTIN_NAMESPACE");
        assertEquals(String.class, field.getType());
        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertEquals("touhou_little_maid", field.get(null));
    }

    @Test
    public void validCanonicalModuleIdentifiersParseAndMaintainValueEquality() {
        var simple = MaidBehaviorId.parse("addon_mod:patrol_yard");
        var hyphenated = MaidBehaviorId.parse("addon-mod:patrol");
        var nested = MaidBehaviorId.of("addon_mod", "combat/kite-bow.v1");
        var builtin = MaidBehaviorId.builtin("unified_combat");

        assertEquals("addon_mod", simple.namespace());
        assertEquals("addon-mod", hyphenated.namespace());
        assertEquals("patrol_yard", simple.path());
        assertEquals("addon_mod:patrol_yard", simple.toString());
        assertEquals(MaidBehaviorId.of("addon_mod", "patrol_yard"), simple);
        assertEquals(
            MaidBehaviorId.of("addon_mod", "patrol_yard")
                .hashCode(),
            simple.hashCode());
        assertNotEquals(simple, nested);
        assertFalse(simple.isBuiltin());
        assertTrue(builtin.isBuiltin());
        assertEquals("touhou_little_maid:unified_combat", builtin.toString());
    }

    @Test
    public void malformedOrTraversalModuleIdentifiersAreRejected() {
        String[] invalid = { "", "no_colon", ":missing_namespace", "missing_path:", "UpperMod:patrol",
            "addon_mod:UpperPath", "addon|mod:patrol", "addon space:patrol", ".:patrol", "..:patrol",
            "addon_mod:/leading", "addon_mod:trailing/", "addon_mod:double//slash", "addon_mod:back\\slash",
            "addon_mod:.", "addon_mod:..", "addon_mod:sub/./leaf", "addon_mod:sub/../leaf", "addon_mod:two:colons" };
        for (String candidate : invalid) {
            try {
                MaidBehaviorId.parse(candidate);
                fail("Expected IllegalArgumentException for malformed id: " + candidate);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void registryBlocksBuiltinNamespaceAndDuplicateModuleIdentifiers() {
        var registry = new MaidBehaviorRegistry();
        try {
            registry.register(stubProvider(MaidBehaviorId.builtin("spoofed"), MaidBehaviorStage.AMBIENT_IDLE, 0));
            fail("Expected builtin namespace to be rejected on external registry");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        var id = MaidBehaviorId.of("addon_a", "gather");
        registry.register(stubProvider(id, MaidBehaviorStage.CARE_AND_AUTONOMOUS, 10));
        try {
            registry.register(stubProvider(id, MaidBehaviorStage.AMBIENT_IDLE, 20));
            fail("Expected duplicate module id to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void registrySortsDeterministicallyByStageOrderAndIdAndFreezesAfterFirstSnapshot() {
        var registry = new MaidBehaviorRegistry();
        var ambientLate = stubProvider(MaidBehaviorId.of("mod_b", "wander_ext"), MaidBehaviorStage.AMBIENT_IDLE, 5);
        var combatSecond = stubProvider(
            MaidBehaviorId.of("mod_b", "tactic"),
            MaidBehaviorStage.COMBAT_AND_RECOVERY,
            10);
        var combatFirstTie = stubProvider(
            MaidBehaviorId.of("mod_a", "tactic"),
            MaidBehaviorStage.COMBAT_AND_RECOVERY,
            10);
        var combatEarly = stubProvider(MaidBehaviorId.of("mod_z", "evade"), MaidBehaviorStage.COMBAT_AND_RECOVERY, -5);
        var care = stubProvider(MaidBehaviorId.of("mod_a", "lantern"), MaidBehaviorStage.CARE_AND_AUTONOMOUS, 0);

        registry.register(ambientLate);
        registry.register(combatSecond);
        registry.register(care);
        registry.register(combatFirstTie);
        registry.register(combatEarly);

        assertFalse(registry.isFrozen());
        List<MaidBehaviorProvider> snapshot = registry.snapshotAndFreeze();
        assertTrue(registry.isFrozen());
        assertEquals(5, snapshot.size());
        assertEquals(
            MaidBehaviorId.of("mod_z", "evade"),
            snapshot.get(0)
                .id());
        assertEquals(
            MaidBehaviorId.of("mod_a", "tactic"),
            snapshot.get(1)
                .id());
        assertEquals(
            MaidBehaviorId.of("mod_b", "tactic"),
            snapshot.get(2)
                .id());
        assertEquals(
            MaidBehaviorId.of("mod_a", "lantern"),
            snapshot.get(3)
                .id());
        assertEquals(
            MaidBehaviorId.of("mod_b", "wander_ext"),
            snapshot.get(4)
                .id());

        assertEquals(snapshot, registry.snapshotAndFreeze());

        try {
            registry.register(stubProvider(MaidBehaviorId.of("mod_c", "late"), MaidBehaviorStage.AMBIENT_IDLE, 0));
            fail("Expected registration after freeze to throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void mutableProviderMetadataDriftAfterRegisterDoesNotCorruptFreezeOrBypassSlotGuard() {
        var registry = new MaidBehaviorRegistry();
        var registeredId = MaidBehaviorId.of("mod_drift", "stable");
        var driftedId = MaidBehaviorId.of("mod_drift", "mutated");
        final boolean[] mutated = { false };

        registry.register(new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                if (mutated[0]) throw new RuntimeException("id() should not be re-queried after register");
                return registeredId;
            }

            @Override
            public MaidBehaviorStage stage() {
                if (mutated[0]) throw new RuntimeException("stage() should not be re-queried after register");
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public int order() {
                if (mutated[0]) throw new RuntimeException("order() should not be re-queried after register");
                return 7;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return driftedId;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.AMBIENT_IDLE;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {}
                };
            }
        });

        mutated[0] = true;
        var frozen = registry.snapshotAndFreeze();
        assertEquals(1, frozen.size());
        assertEquals(
            registeredId,
            frozen.get(0)
                .id());
        assertEquals(
            MaidBehaviorStage.CARE_AND_AUTONOMOUS,
            frozen.get(0)
                .stage());
        assertEquals(
            7,
            frozen.get(0)
                .order());
    }

    private static MaidBehaviorProvider stubProvider(MaidBehaviorId id, MaidBehaviorStage stage, int order) {
        return new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public MaidBehaviorStage stage() {
                return stage;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return id;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return stage;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {}
                };
            }
        };
    }
}
