package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatDiagnosticScope;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatMovementFact;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrain;

/** 受控QA输出，只序列化已缓存的纯值；不申请服务或读动态网络state */
final class CombatCachedDiagnostics {

    private static final Gson JSON = new Gson();

    private CombatCachedDiagnostics() {}

    static JsonObject budget(World world, List<EntityMaid> maids, long now, CombatWorldBudget.Diagnostics diagnostic) {
        var out = new JsonObject();
        var scope = CombatDiagnosticScope.current(world);
        if (scope == null) return out;
        out.addProperty("schema", "combat-request-stage-cache-v1");
        out.addProperty("worldTick", now);
        out.addProperty("diagnosticOverhead", "Explicit QA only; not production load or effect admission");
        out.addProperty("droppedOwners", scope.droppedOwners);
        var actors = new JsonArray();
        for (int index = 0; index < maids.size() && index < 32; index++) {
            var maid = maids.get(index);
            var row = new JsonObject();
            row.addProperty("index", index);
            row.addProperty(
                "uuid",
                maid.getUniqueID()
                    .toString());
            row.add("request", JSON.toJsonTree(CombatWorldBudget.requestDiagnostic(world, maid.getUniqueID())));
            for (var entry : diagnostic.entries) if (entry.id.equals(maid.getUniqueID())) {
                var actual = new JsonObject();
                actual.addProperty("phase", entry.phase.name());
                actual.addProperty("requestSerial", entry.requestSerial);
                actual.addProperty("pending", entry.pending);
                actual.addProperty("since", entry.since);
                actual.addProperty("completedAt", entry.completedAt);
                actual.addProperty("round", entry.preparationRound);
                actual.addProperty("startedAt", entry.startedAt);
                actual.addProperty("expiresAt", entry.expiresAt);
                actual.addProperty("sampledTicks", entry.sampledTicks);
                actual.addProperty("oldestCoverage", entry.oldestCoverage);
                row.add("actualEntry", actual);
            }
            var core = maid.getBrainCoordinator()
                .peekEnhancedCore();
            if (core != null) row.add(
                "observation",
                JSON.toJsonTree(
                    core.perception()
                        .combatDiagnostic(world)));
            actors.add(row);
        }
        out.add("actors", actors);
        return out;
    }

    static final class Environment {

        private int rows, dropped;
        private long lastTick = Long.MIN_VALUE, lastRejectSequence = Long.MIN_VALUE;
        private long conditionalStage;
        private int conditionalSnapshots, conditionalDropped;
        private boolean conditionalFirstLight;
        private CombatTerrain.Route conditionalSourceRoute;
        private CombatMovementFact.Node conditionalSourceNode, conditionalLatestNode;
        private Gson conditionalJson;

        void take(World world, EnhancedCoreBrain core, CombatPlan plan, int phase, int age, long tick) {
            if (CombatDiagnosticScope.current(world) == null || tick == lastTick) return;
            lastTick = tick;
            if (rows >= 560) {
                dropped++;
                return;
            }
            var row = new JsonObject();
            row.addProperty("schema", "combat-environment-native-cache-v1");
            row.addProperty("worldTick", tick);
            row.addProperty("phase", phase);
            row.addProperty("age", age);
            row.addProperty("cacheOnly", true);
            row.addProperty("diagnosticOverhead", "Explicit QA only; not production load or effect admission");
            var nativeCache = core.actor()
                .combatNativeDiagnostic();
            if (nativeCache != null && nativeCache.tick != tick) nativeCache = nativeCache.withoutEvents();
            if (nativeCache != null && nativeCache.lastReject != null) {
                if (nativeCache.lastReject.sequence == lastRejectSequence)
                    nativeCache = nativeCache.withoutLastReject();
                else lastRejectSequence = nativeCache.lastReject.sequence;
            }
            row.add("native", JSON.toJsonTree(nativeCache));
            row.add(
                "observation",
                JSON.toJsonTree(
                    core.perception()
                        .combatDiagnostic(world)));
            if (plan != null) {
                var p = new JsonObject();
                p.addProperty("macro", plan.macro.name());
                p.addProperty("strategy", plan.strategy.name());
                p.addProperty("stageId", plan.stageId);
                p.addProperty("stagePhase", plan.stagePhase);
                p.addProperty("stageState", plan.stageState.name());
                p.addProperty("simulated", plan.simulated);
                p.addProperty("observedAt", plan.observedAt);
                p.addProperty("alignmentWork", plan.trace == null ? null : plan.trace.alignmentWork);
                if (plan.movementNode != null) {
                    p.addProperty("movementRole", plan.movementNode.role.name());
                    p.addProperty("parentStage", plan.movementNode.parentStage);
                    p.addProperty("parentCursor", plan.movementNode.parentCursor);
                    p.add("alignmentC", JSON.toJsonTree(plan.movementNode.anchor));
                    p.add("exactParentB", JSON.toJsonTree(plan.movementNode.parentNext));
                }
                if (plan.alignmentProof != null) {
                    p.addProperty("alignmentGeometryAt", plan.alignmentProof.geometry.observedAt);
                    p.addProperty("alignmentAdmittedAt", plan.alignmentProof.admittedAt);
                    p.addProperty("alignmentHalfWidth", plan.alignmentProof.halfWidth);
                    p.addProperty("alignmentHeight", plan.alignmentProof.height);
                }
                p.addProperty(
                    "movementNode",
                    plan.movementNode == null ? null
                        : plan.movementNode.stage + "/"
                            + plan.movementNode.index
                            + "@"
                            + plan.movementNode.foot.x
                            + ","
                            + plan.movementNode.foot.y
                            + ","
                            + plan.movementNode.foot.z);
                boolean observedOnly = plan.strategy == CombatPlan.Strategy.REOBSERVE;
                p.addProperty("riskScope", observedOnly ? "OBSERVED_ONLY_TOTAL_UNKNOWN" : "COMPLETE_COMBAT");
                p.addProperty(
                    "expectedRisk",
                    observedOnly || !Double.isFinite(plan.expectedRisk) ? null : plan.expectedRisk);
                p.addProperty("utility", observedOnly || !Double.isFinite(plan.utility) ? null : plan.utility);
                row.add("plan", p);
            }
            var snapshot = core.combat()
                .lastSnapshot();
            if (snapshot != null)
                row.add("request", JSON.toJsonTree(CombatWorldBudget.requestDiagnostic(world, snapshot.selfId)));
            var fact = snapshot == null ? null : snapshot.movementFact;
            row.add("movementFact", JSON.toJsonTree(fact));
            row.addProperty(
                "lastFact",
                fact == null ? null
                    : fact.outcome + "/"
                        + fact.tick
                        + "/"
                        + fact.reason
                        + "/path="
                        + fact.pathIdentity
                        + "/index="
                        + fact.nativePathIndex
                        + "/length="
                        + fact.nativePathLength);
            conditionalReplay(row, snapshot, plan, phase);
            append("unified-combat-environment-cached.jsonl", row.toString() + "\n");
            rows++;
        }

        /** Existing QA scope/row gate already passed; only immutable cache fields are read here */
        private void conditionalReplay(JsonObject row, CombatSnapshot snapshot, CombatPlan plan, int phase) {
            if (phase != 5 || snapshot == null || plan == null) return;
            String kind;
            if (conditionalSourceRoute == null) {
                if (!plan.simulated || !plan.normalProgressAdmitted
                    || !plan.necessaryComparisonComplete
                    || plan.stageId <= 0
                    || plan.route == null
                    || !plan.route.conditionalDoor
                    || plan.movementNode == null
                    || plan.movementNode.role != CombatMovementFact.Role.ORDINARY
                    || plan.movementNode.stage != plan.stageId * 2
                    || plan.strategy != CombatPlan.Strategy.TASK) return;
                conditionalStage = plan.stageId;
                conditionalSourceRoute = plan.route;
                conditionalSourceNode = conditionalLatestNode = plan.movementNode;
                kind = "FIRST_CONDITIONAL_FULL";
            } else {
                if (plan.stageId != conditionalStage) return;
                if (plan.movementNode != null && plan.movementNode.role == CombatMovementFact.Role.ORDINARY
                    && plan.movementNode.stage == conditionalStage * 2) conditionalLatestNode = plan.movementNode;
                if (!plan.simulated) {
                    if (conditionalFirstLight) return;
                    conditionalFirstLight = true;
                    kind = "FIRST_FOLLOWING_LIGHT";
                } else kind = "FOLLOWING_FULL";
            }
            if (conditionalSnapshots >= 6) {
                conditionalDropped++;
                return;
            }
            var out = new JsonObject();
            out.addProperty("kind", kind);
            out.addProperty("sequence", ++conditionalSnapshots);
            out.addProperty("maximumSnapshots", 6);
            out.addProperty("sourceStage", conditionalStage);
            out.addProperty("snapshotTick", snapshot.tick);
            out.addProperty("planObservedAt", plan.observedAt);
            out.addProperty("normalProgressAdmitted", plan.normalProgressAdmitted);
            out.addProperty("necessaryComparisonComplete", plan.necessaryComparisonComplete);
            if (snapshot.geometry == null
                || snapshot.geometry.sizeX * snapshot.geometry.sizeY * snapshot.geometry.sizeZ > 486
                || snapshot.targets.size() > 128
                || snapshot.projectiles.size() > 32
                || snapshot.friends.size() > 32
                || snapshot.weapons.size() > 7
                || plan.trace != null && plan.trace.candidates.size() > 12) {
                conditionalDropped++;
                out.addProperty("status", "CACHED_OUTPUT_BOUND_EXCEEDED");
            } else try {
                if (conditionalJson == null) conditionalJson = new GsonBuilder()
                    .registerTypeAdapter(
                        CombatTerrain.Route.class,
                        (JsonSerializer<CombatTerrain.Route>) (route, type, context) -> cachedRoute(route))
                    .create();
                out.add("sourceNode", JSON.toJsonTree(conditionalSourceNode));
                out.add("latestParentNode", JSON.toJsonTree(conditionalLatestNode));
                out.add("sourceRoute", conditionalJson.toJsonTree(conditionalSourceRoute));
                out.add("currentRoute", conditionalJson.toJsonTree(plan.route));
                out.add("snapshot", conditionalJson.toJsonTree(snapshot));
                out.add("trace", conditionalJson.toJsonTree(plan.trace));
                out.addProperty("status", "PURE_CACHED_VALUES");
            } catch (IllegalArgumentException failure) {
                conditionalDropped++;
                out.addProperty("status", "CACHED_SERIALIZATION_REJECTED");
                out.addProperty(
                    "failureType",
                    failure.getClass()
                        .getName());
            }
            row.add("conditionalReplay", out);
        }

        private static JsonObject cachedRoute(CombatTerrain.Route route) {
            var out = new JsonObject();
            if (route.nodes.size() > 147 || route.points.size() > 441) {
                out.addProperty("status", "CACHED_ROUTE_BOUND_EXCEEDED");
                out.addProperty("nodes", route.nodes.size());
                out.addProperty("points", route.points.size());
                return out;
            }
            out.add("nodes", JSON.toJsonTree(route.nodes));
            out.add("points", JSON.toJsonTree(route.points));
            out.add("destination", JSON.toJsonTree(route.destination));
            out.add("entrance", JSON.toJsonTree(route.entrance));
            out.add("escape", JSON.toJsonTree(route.escape));
            out.add("peek", JSON.toJsonTree(route.peek));
            out.addProperty("conditionalDoor", route.conditionalDoor);
            out.addProperty("stopBeforeDoor", Double.isFinite(route.stopBeforeDoor) ? route.stopBeforeDoor : null);
            out.addProperty("length", route.length);
            out.addProperty("protection", route.protection);
            out.addProperty("firingLine", route.firingLine);
            out.addProperty("provedAt", route.provedAt);
            out.addProperty("defenseNode", route.defenseNode);
            out.addProperty("defenseDistance", route.defenseDistance);
            return out;
        }

        void finish() {
            com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.HodgePopulationProbe
                .retainDefaultOffControl();
            var out = new JsonObject();
            out.addProperty("rows", rows);
            out.addProperty("dropped", dropped);
            out.addProperty("maximumRows", 560);
            out.addProperty("conditionalSnapshots", conditionalSnapshots);
            out.addProperty("conditionalDropped", conditionalDropped);
            out.addProperty("maximumConditionalSnapshots", 6);
            try {
                append("unified-combat-environment-cached-summary.json", out.toString() + "\n");
            } finally {
                conditionalSourceRoute = null;
                conditionalSourceNode = conditionalLatestNode = null;
                conditionalJson = null;
            }
        }

        private static void append(String file, String text) {
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), file),
                    text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cached QA diagnostic write failed: " + file, failure);
            }
        }
    }
}
