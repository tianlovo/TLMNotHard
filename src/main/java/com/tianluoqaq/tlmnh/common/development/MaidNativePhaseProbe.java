package com.tianluoqaq.tlmnh.common.development;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidAfterNavigationGate;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

/** 真实原生AI调用栈的阶段组件，不登记实体、不冒充世界自然tick或跟随成绩 */
public final class MaidNativePhaseProbe {

    private MaidNativePhaseProbe() {}

    public static void run(WorldServer world) {
        run(world, false);
    }

    public static void run(WorldServer world, boolean earlyDispatchFault) {
        if (!Boolean.getBoolean("tlmnh.devCommands") || world == null || world.isRemote)
            throw new IllegalArgumentException("Development server required");
        var output = new JsonObject();
        output.addProperty("schema", "tlmnh-native-ai-phase-v1");
        output.addProperty("version", Tags.VERSION);
        output.addProperty("executionFault", earlyDispatchFault ? "early_component" : "none");
        output.addProperty("naturalWorldTickVerified", false);
        output.addProperty("fullFollowVerified", false);
        var rows = new JsonArray();
        output.add("cases", rows);
        Throwable failure = null;
        try {
            for (String id : new String[] { "legacy_before_navigation", "normal", "missing_booking", "duplicate_hooks",
                "suspended_between", "owner_changed_between", "identity_changed_between",
                "entity_clock_changed_between", "reset_in_callback" }) {
                try (var maid = new ObservedMaid(world)) {
                    if (!output.has("runtimeIdentity")) {
                        var implementations = new JsonObject();
                        implementations.addProperty(
                            "maid",
                            maid.getClass()
                                .getName());
                        implementations.addProperty(
                            "component",
                            maid.component.getClass()
                                .getName());
                        implementations.addProperty(
                            "navigator",
                            maid.getNavigator()
                                .getClass()
                                .getName());
                        implementations.addProperty(
                            "moveHelper",
                            maid.getMoveHelper()
                                .getClass()
                                .getName());
                        output.add("implementationClasses", implementations);
                        output.add(
                            "runtimeIdentity",
                            DevelopmentArtifactIdentity.capture(
                                MaidNativePhaseProbe.class,
                                MaidBrainCoordinator.class,
                                MaidAfterNavigationGate.class,
                                MaidPolicyRuntime.class,
                                EntityMaid.class,
                                DevelopmentArtifactIdentity.class,
                                maid.getClass(),
                                maid.component.getClass(),
                                maid.getNavigator()
                                    .getClass(),
                                maid.getMoveHelper()
                                    .getClass()));
                    }
                    var brain = maid.getBrainCoordinator();
                    boolean legacy = id.equals("legacy_before_navigation");
                    if (!legacy) brain.setAfterNavigationActionsForDevelopment(maid.component);
                    var componentRuntime = brain.getPolicyRuntime();
                    var initialToken = componentRuntime.getContext()
                        .getToken();
                    long oldFollow = brain.getFollowController()
                        .getTicks();
                    long oldCombat = brain.getCombatController()
                        .getTicks();
                    if (id.equals("suspended_between")) maid.midpoint = () -> brain.setSuspended(true);
                    if (id.equals("owner_changed_between")) maid.midpoint = () -> maid.func_152115_b(
                        UUID.randomUUID()
                            .toString());
                    if (id.equals("identity_changed_between")) maid.midpoint = maid::replaceIdentity;
                    if (id.equals("entity_clock_changed_between")) maid.midpoint = () -> maid.ticksExisted++;
                    if (id.equals("reset_in_callback")) maid.onCallback = brain::resetTask;
                    if (earlyDispatchFault && id.equals("normal")) componentRuntime.tickAfterNativeNavigation();
                    if (id.equals("missing_booking")) brain.afterNativeNavigationTick();
                    else maid.runNativeAi();
                    if (id.equals("duplicate_hooks")) {
                        brain.afterNativeNavigationTick();
                        brain.updateTask();
                        brain.afterNativeNavigationTick();
                    }
                    boolean executed = id.equals("normal") || id.equals("duplicate_hooks")
                        || id.equals("reset_in_callback");
                    List<String> expected = id.equals("missing_booking") ? Collections.emptyList()
                        : executed ? Arrays.asList("navigation", "hook_begin", "component", "hook_end", "move")
                            : Arrays.asList("navigation", "hook_begin", "hook_end", "move");
                    var row = new JsonObject();
                    row.addProperty("id", id);
                    row.add("trace", new Gson().toJsonTree(maid.trace));
                    row.addProperty("callbacks", maid.callbacks);
                    row.addProperty("legacyCountersUnchanged", !legacy);
                    row.addProperty("followTicksBefore", oldFollow);
                    row.addProperty(
                        "followTicksAfter",
                        brain.getFollowController()
                            .getTicks());
                    row.addProperty("combatTicksBefore", oldCombat);
                    row.addProperty(
                        "combatTicksAfter",
                        brain.getCombatController()
                            .getTicks());
                    row.addProperty("initialRuntimeRetired", componentRuntime.isRetired());
                    row.addProperty(
                        "initialTokenCurrentAtEnd",
                        componentRuntime.getContext()
                            .isCurrent(initialToken));
                    row.addProperty("followTicksAtNavigation", maid.followTicksAtNavigation);
                    row.addProperty("navigationCalls", maid.navigationCalls);
                    row.addProperty("moveCalls", maid.moveCalls);
                    row.add("cleanup", maid.cleanupObservation);
                    rows.add(row);
                    require(maid.trace.equals(expected), "Actual AI phase sequence differs: " + id + " " + maid.trace);
                    require(maid.callbacks == (executed ? 1 : 0), "Callback count differs");
                    if (id.equals("identity_changed_between")) require(
                        componentRuntime.isRetired() && brain.peekPolicyRuntime() == null,
                        "Old identity survived native midpoint");
                    if (legacy) {
                        require(
                            maid.followTicksAtNavigation == oldFollow + 1 && brain.getFollowController()
                                .getTicks() == oldFollow + 1
                                && brain.getCombatController()
                                    .getTicks() == oldCombat + 1,
                            "Legacy was deferred or dispatched twice");
                    } else require(
                        oldFollow == brain.getFollowController()
                            .getTicks() && oldCombat
                                == brain.getCombatController()
                                    .getTicks(),
                        "Development component also dispatched legacy controllers");
                }
            }
        } catch (Exception | LinkageError problem) {
            failure = problem;
            output.addProperty(
                "errorType",
                problem.getClass()
                    .getSimpleName());
        }
        output.addProperty("executionComplete", failure == null);
        output.addProperty("suppressedFailureCount", failure == null ? 0 : failure.getSuppressed().length);
        TLMNotHard.LOG.info("TLMNH_NATIVE_AI_PHASE_CAPTURE {}", output);
        if (failure != null) throw new IllegalStateException("Native AI phase capture incomplete", failure);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Cleanup {

        void run() throws Exception;
    }

    private static Throwable cleanup(Cleanup action, Throwable first) {
        try {
            action.run();
        } catch (Exception | LinkageError failure) {
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
        }
        return first;
    }

    private static Field field(Class<?> type) {
        Field found = null;
        for (Field candidate : EntityLiving.class.getDeclaredFields()) {
            if (candidate.getType() != type) continue;
            require(found == null, "Ambiguous native helper field");
            found = candidate;
        }
        require(found != null, "Missing native helper field");
        found.setAccessible(true);
        return found;
    }

    private static final class ObservedMaid extends EntityMaid implements AutoCloseable {

        final List<String> trace = new ArrayList<>();
        final JsonObject cleanupObservation = new JsonObject();
        final Field navigatorField = field(PathNavigate.class), moveField = field(EntityMoveHelper.class);
        final Object originalNavigator, originalMove;
        int callbacks, navigationCalls, moveCalls;
        long followTicksAtNavigation;
        Runnable midpoint = () -> {}, onCallback = () -> {};
        final MaidPolicyRuntime.AfterNavigationActions component = new MaidPolicyRuntime.AfterNavigationActions() {

            public void tick(MaidPolicyContext.Token token) {
                require(
                    getBrainCoordinator().getPolicyRuntime()
                        .getContext()
                        .isCurrent(token),
                    "Stale component token");
                trace.add("component");
                callbacks++;
                onCallback.run();
            }

            public void cancel() {}
        };

        ObservedMaid(World world) throws Exception {
            super(world);
            originalNavigator = navigatorField.get(this);
            originalMove = moveField.get(this);
            navigatorField.set(this, new PathNavigate(this, world) {

                @Override
                public void onUpdateNavigation() {
                    trace.add("navigation");
                    navigationCalls++;
                    followTicksAtNavigation = getBrainCoordinator().getFollowController()
                        .getTicks();
                    super.onUpdateNavigation();
                    midpoint.run();
                }
            });
            moveField.set(this, new EntityMoveHelper(this) {

                @Override
                public void onUpdateMoveHelper() {
                    trace.add("move");
                    moveCalls++;
                    super.onUpdateMoveHelper();
                }
            });
            require(getNavigator() == navigatorField.get(this), "Native navigator observer not installed");
        }

        void runNativeAi() {
            ticksExisted++;
            super.updateAITasks();
        }

        void replaceIdentity() {
            entityUniqueID = UUID.randomUUID();
        }

        @Override
        protected void updateAITick() {
            trace.add("hook_begin");
            super.updateAITick();
            trace.add("hook_end");
        }

        @Override
        public void close() throws Exception {
            var runtime = getBrainCoordinator().peekPolicyRuntime();
            Throwable failure = cleanup(() -> {
                getBrainCoordinator().onUnload();
                require(runtime == null || runtime.isRetired(), "Component runtime survived fixture cleanup");
            }, null);
            failure = cleanup(() -> navigatorField.set(this, originalNavigator), failure);
            failure = cleanup(() -> moveField.set(this, originalMove), failure);
            failure = cleanup(this::setDead, failure);
            cleanupObservation.addProperty("runtimeRetired", runtime == null || runtime.isRetired());
            cleanupObservation.addProperty("navigatorRestored", getNavigator() == originalNavigator);
            cleanupObservation.addProperty("moveHelperRestored", getMoveHelper() == originalMove);
            cleanupObservation.addProperty("actorDead", isDead);
            cleanupObservation.addProperty("allCleanupStepsSucceeded", failure == null);
            if (failure instanceof Exception problem) throw problem;
            if (failure instanceof LinkageError problem) throw problem;
        }
    }
}
