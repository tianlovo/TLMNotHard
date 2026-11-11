package com.tianluoqaq.tlmnh.common.ai.enhanced.behavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorInitContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorModule;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorOfferSink;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorProvider;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorStage;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidExternalBehaviorSlot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidSessionCallbackScope;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidBehaviorExtensionData;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidBehaviorExtensionData.ModulePersistentAdapter;
import com.tianluoqaq.tlmnh.common.ai.enhanced.support.MaidTargetCooldowns;

public class MaidTargetCooldownsAndExtensionDataTest {

    @Test
    public void uuidFifoEvictsOldestAt32WhileIdentityClearOnFullClearsAll() {
        var fifo = MaidTargetCooldowns.<UUID>forUuidFifo(32);
        var ids = new UUID[33];
        for (int i = 0; i < 32; i++) {
            ids[i] = UUID.randomUUID();
            fifo.record(ids[i], 100L + i);
        }
        assertEquals(32, fifo.size());
        assertTrue(fifo.contains(ids[0]));

        ids[32] = UUID.randomUUID();
        fifo.record(ids[32], 200L);
        assertEquals(32, fifo.size());
        assertFalse(fifo.contains(ids[0]));
        assertTrue(fifo.contains(ids[1]));
        assertTrue(fifo.contains(ids[32]));

        var clearOnFull = MaidTargetCooldowns.<Object>forIdentityClearOnFull(32);
        var keys = new Object[33];
        for (int i = 0; i < 32; i++) {
            keys[i] = new Object();
            clearOnFull.record(keys[i], 100L);
        }
        assertEquals(32, clearOnFull.size());
        keys[32] = new Object();
        clearOnFull.record(keys[32], 100L);
        assertEquals(1, clearOnFull.size());
        assertFalse(clearOnFull.contains(keys[0]));
        assertTrue(clearOnFull.contains(keys[32]));
    }

    @Test
    public void cooldownsExpireOnTimeDisplacementAndWorldClockRollback() {
        var cooldowns = MaidTargetCooldowns.<String>forUuidFifo(8);
        assertFalse(cooldowns.advanceTick(100L));
        cooldowns.record("stationary", 200L, 10.0, 64.0, 10.0, "worldA");
        cooldowns.record("moved", 200L, 10.0, 64.0, 10.0, "worldA");
        cooldowns.record("soon", 105L, 0.0, 64.0, 0.0, "worldA");

        cooldowns.purgeExpired(110L, (key, entry) -> "moved".equals(key) && entry.distanceSq(13.0, 64.0, 10.0) > 4.0);
        assertTrue(cooldowns.contains("stationary"));
        assertFalse(cooldowns.contains("moved"));
        assertFalse(cooldowns.contains("soon"));

        // World clock rollback clears remaining entries
        assertTrue(cooldowns.advanceTick(50L));
        assertEquals(0, cooldowns.size());
    }

    @Test
    public void zeroExtensionSaveOmitsBehaviorExtKeyAndDoesNotMutateInputSnapshot() {
        var base = new NBTTagCompound();
        base.setInteger("Schema", 1);

        var flushed = MaidBehaviorExtensionData.flush(base, Collections.emptyList());
        assertEquals(1, flushed.getInteger("Schema"));
        assertFalse(flushed.hasKey(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT));

        var emptyModule = adapter(MaidBehaviorId.of("mod_a", "empty"), false, tag -> {});
        var flushedWithEmpty = MaidBehaviorExtensionData.flush(base, Collections.singletonList(emptyModule));
        assertFalse(flushedWithEmpty.hasKey(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT));
        assertFalse(base.hasKey(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT));
    }

    @Test
    public void copyOnReadAndWritePreventAliasingAndPreserveUninstalledAddonSlices() {
        var base = new NBTTagCompound();
        var ext = new NBTTagCompound();
        var uninstalled = new NBTTagCompound();
        uninstalled.setString("Marker", "keep-me");
        ext.setTag("uninstalled_mod:old_behavior", uninstalled);
        var existingModA = new NBTTagCompound();
        existingModA.setInteger("Counter", 9);
        ext.setTag("mod_a:counter", existingModA);
        base.setTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT, ext);

        var idA = MaidBehaviorId.of("mod_a", "counter");
        var slice = MaidBehaviorExtensionData.extractModuleTag(base, idA);
        assertFalse(slice.blocked);
        assertEquals(9, slice.tag.getInteger("Counter"));
        // Mutating read copy must not alter base snapshot
        slice.tag.setInteger("Counter", 999);
        assertEquals(
            9,
            base.getCompoundTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT)
                .getCompoundTag("mod_a:counter")
                .getInteger("Counter"));

        var leakedWriteRef = new AtomicReference<NBTTagCompound>();
        var writer = adapter(idA, false, candidate -> {
            candidate.setInteger("Counter", 10);
            leakedWriteRef.set(candidate);
        });
        var saved = MaidBehaviorExtensionData.flush(base, Collections.singletonList(writer));
        // Mutating candidate after writePersistentData returns must not alter saved output
        leakedWriteRef.get()
            .setInteger("Counter", 777);

        var savedExt = saved.getCompoundTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT);
        assertEquals(
            10,
            savedExt.getCompoundTag("mod_a:counter")
                .getInteger("Counter"));
        assertEquals(
            "keep-me",
            savedExt.getCompoundTag("uninstalled_mod:old_behavior")
                .getString("Marker"));
        // Input base must remain at 9
        assertEquals(
            9,
            base.getCompoundTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT)
                .getCompoundTag("mod_a:counter")
                .getInteger("Counter"));
    }

    @Test
    public void oversizedAndExcessivelyNestedTagsAreRejectedWhileKeepingPriorSavedData() {
        var id = MaidBehaviorId.of("mod_quota", "state");
        var base = new NBTTagCompound();
        var ext = new NBTTagCompound();
        var prior = new NBTTagCompound();
        prior.setInteger("SafeVersion", 3);
        ext.setTag(id.toString(), prior);
        base.setTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT, ext);

        // 1. Oversized (> 16 KB) rejection
        var faultedOversize = new AtomicBoolean();
        var oversizeWriter = new ModulePersistentAdapter() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public boolean isFaulted() {
                return faultedOversize.get();
            }

            @Override
            public void writePersistentData(NBTTagCompound candidate) {
                candidate.setByteArray("Huge", new byte[MaidBehaviorExtensionData.MAX_MODULE_BYTES + 128]);
            }

            @Override
            public void onPersistenceFault(Throwable cause) {
                faultedOversize.set(true);
            }
        };
        var afterOversize = MaidBehaviorExtensionData.flush(base, Collections.singletonList(oversizeWriter));
        assertTrue(faultedOversize.get());
        assertEquals(
            3,
            afterOversize.getCompoundTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT)
                .getCompoundTag(id.toString())
                .getInteger("SafeVersion"));

        // 2. Depth 8 succeeds, depth 9 compound fails, depth 9 NBTTagList fails
        var depth8 = buildCompoundChain(8);
        assertTrue(MaidBehaviorExtensionData.validateCandidate(depth8).valid);

        var depth9 = buildCompoundChain(9);
        assertFalse(MaidBehaviorExtensionData.validateCandidate(depth9).valid);

        var listDepth9 = new NBTTagCompound();
        var outerList = new NBTTagList();
        var currentCompound = listDepth9;
        for (int i = 0; i < 6; i++) {
            var next = new NBTTagCompound();
            currentCompound.setTag("c" + i, next);
            currentCompound = next;
        }
        // currentCompound is at depth 7 -> outerList at depth 8 -> innerCompound at depth 9
        var innerCompound = new NBTTagCompound();
        innerCompound.setInteger("Leaf", 1);
        outerList.appendTag(innerCompound);
        currentCompound.setTag("list", outerList);
        assertFalse(MaidBehaviorExtensionData.validateCandidate(listDepth9).valid);

        // 3. Self-referential NBTTagList cycle must be rejected before any .copy() without StackOverflowError
        var cyclicFaulted = new AtomicBoolean();
        var cyclicListWriter = new ModulePersistentAdapter() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public boolean isFaulted() {
                return cyclicFaulted.get();
            }

            @Override
            public void writePersistentData(NBTTagCompound candidate) {
                var outer = new NBTTagList();
                var inner = new NBTTagList();
                inner.appendTag(new NBTTagString("seed"));
                outer.appendTag(inner);
                // Create nested list cycle outer -> inner -> outer (both element type 9 / list)
                var cyclicOuter = new NBTTagList();
                var cyclicInner = new NBTTagList();
                cyclicOuter.appendTag(cyclicInner);
                cyclicInner.appendTag(cyclicOuter);
                candidate.setTag("CycleList", cyclicOuter);
            }

            @Override
            public void onPersistenceFault(Throwable cause) {
                cyclicFaulted.set(true);
            }
        };
        var afterCyclic = MaidBehaviorExtensionData.flush(base, Collections.singletonList(cyclicListWriter));
        assertTrue(cyclicFaulted.get());
        assertEquals(
            3,
            afterCyclic.getCompoundTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT)
                .getCompoundTag(id.toString())
                .getInteger("SafeVersion"));
    }

    @Test
    public void malformedNonCompoundTagsPauseModulePersistenceWithoutClobberingSaveData() {
        var id = MaidBehaviorId.of("mod_safe", "paused");
        var scope = new MaidSessionCallbackScope();
        var provider = new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.AMBIENT_IDLE;
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
                        return MaidBehaviorStage.AMBIENT_IDLE;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {}

                    @Override
                    public void writePersistentData(NBTTagCompound tag) {
                        tag.setInteger("ShouldNotWrite", 1);
                    }
                };
            }
        };

        var slot = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, provider, scope, new MaidBehaviorContext() {

            @Override
            public com.tianluoqaq.tlmnh.api.behavior.MaidObservationView observation() {
                throw new IllegalStateException();
            }

            @Override
            public com.tianluoqaq.tlmnh.api.behavior.MaidActionAccess actions() {
                throw new IllegalStateException();
            }

            @Override
            public com.tianluoqaq.tlmnh.api.behavior.MaidHandAccess hands() {
                throw new IllegalStateException();
            }
        }, () -> {});

        // Case A: module key is a string instead of NBTTagCompound
        var malformedChild = new NBTTagCompound();
        var ext = new NBTTagCompound();
        ext.setTag(id.toString(), new NBTTagString("corrupted-string"));
        malformedChild.setTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT, ext);

        slot.loadPersistentData(malformedChild);
        assertTrue(slot.isHealthy());
        assertTrue(slot.isPersistencePaused());

        var flushedChild = MaidBehaviorExtensionData.flush(malformedChild, Arrays.asList(slot));
        assertEquals(
            "corrupted-string",
            flushedChild.getCompoundTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT)
                .getString(id.toString()));

        // Case B: parent BehaviorExt itself is a string instead of NBTTagCompound
        var malformedParent = new NBTTagCompound();
        malformedParent.setTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT, new NBTTagString("parent-corrupted"));
        var flushedParent = MaidBehaviorExtensionData.flush(malformedParent, Arrays.asList(slot));
        assertNotNull(flushedParent.getTag(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT));
        assertEquals("parent-corrupted", flushedParent.getString(MaidBehaviorExtensionData.TAG_BEHAVIOR_EXT));
    }

    private static NBTTagCompound buildCompoundChain(int totalCompoundDepth) {
        var root = new NBTTagCompound();
        var cursor = root;
        for (int d = 1; d < totalCompoundDepth; d++) {
            var child = new NBTTagCompound();
            cursor.setTag("d" + d, child);
            cursor = child;
        }
        cursor.setInteger("v", 1);
        return root;
    }

    private static ModulePersistentAdapter adapter(MaidBehaviorId id, boolean faulted,
        java.util.function.Consumer<NBTTagCompound> writer) {
        return new ModulePersistentAdapter() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public boolean isFaulted() {
                return faulted;
            }

            @Override
            public void writePersistentData(NBTTagCompound candidate) {
                writer.accept(candidate);
            }

            @Override
            public void onPersistenceFault(Throwable cause) {}
        };
    }
}
