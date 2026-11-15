package com.tianluoqaq.tlmnh.qa;

import java.lang.reflect.Field;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.ThreatEstimator;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;

/** 只读既有规划和物品状态，独立保存校准期行为诊断 */
final class CombatProgressDiagnostics {

    private static final Field PLANNER = field(EnhancedCombatController.class, "planner");
    private static final Field REOBSERVATION = field(CombatPlanner.class, "reobservation");
    private static final Field OWNER_PRESSURE = field(CombatPlanner.class, "ownerPressureWindow");
    private static final Field SHIELD_LEASE = field(MaidShieldController.class, "lease");
    private static final Field SHIELD_CAUSE = field(MaidShieldController.class, "cause");
    private static final Field SHIELD_ACTIVE = field(MaidShieldController.class, "activeTaskRunning");
    private static final Field SHIELD_PASSIVE = field(MaidShieldController.class, "passiveRemaining");

    private CombatProgressDiagnostics() {}

    static JsonObject capture(EnhancedCombatController controller, CombatSnapshot snapshot, int frame,
        MaidItemUseState.Lease use, MaidShieldController shield) {
        var result = new JsonObject();
        result.addProperty("frame", frame);
        if (snapshot == null) return result;
        result.addProperty("bodyComplete", snapshot.bodyComplete);
        result.addProperty("bodyContinuationComplete", snapshot.bodyContinuationComplete);
        result.addProperty("friendsComplete", snapshot.friendsComplete);
        result.addProperty("oldestCoverage", snapshot.oldestCoverage);
        result.addProperty("geometryAt", snapshot.geometry == null ? -1 : snapshot.geometry.observedAt);
        var self = new JsonObject();
        self.addProperty("reach", finite(snapshot.self.reach));
        self.addProperty("movementPerTick", finite(snapshot.self.movementPerTick));
        self.addProperty("recentlyHurt", snapshot.self.recentlyHurt);
        self.addProperty("drawing", snapshot.self.drawing);
        self.addProperty("equipped", snapshot.self.equipped);
        self.addProperty("cadenceReady", snapshot.self.cadenceReady);
        self.addProperty("ownerPresent", snapshot.self.ownerPosition != null);
        result.add("selfPressureInputs", self);
        var target = snapshot.target(snapshot.selectedTarget);
        if (target != null) {
            var row = new JsonObject();
            row.addProperty("entityId", target.entityId);
            row.addProperty("ageTicks", snapshot.tick - target.observedAt);
            row.addProperty("attackingOwner", target.attackingOwner);
            row.addProperty("visible", target.visible);
            row.addProperty("ranged", target.ranged);
            row.addProperty("damageSource", target.damageSource.name());
            row.addProperty("ownerInRangedFireLane", ThreatEstimator.ownerInRangedFireLane(snapshot, target));
            result.add("targetPressureInputs", row);
        }
        var plan = controller.lastPlan();
        if (plan != null) {
            var row = new JsonObject();
            row.addProperty("strategy", plan.strategy.name());
            row.addProperty("riskScope", plan.riskScope.name());
            row.addProperty("risk", plan.diagnosticTotalRisk());
            row.addProperty("utility", plan.diagnosticCombatUtility());
            row.addProperty("expectedDamage", finite(plan.expectedDamage));
            row.addProperty("weaponSlot", plan.weaponSlot);
            row.addProperty("observedAgeTicks", snapshot.tick - plan.observedAt);
            row.addProperty("stageState", plan.stageState.name());
            row.addProperty("stageReason", plan.stageReason);
            row.addProperty("stageId", plan.stageId);
            row.addProperty("stagePhase", plan.stagePhase);
            row.addProperty("normalProgressAdmitted", plan.normalProgressAdmitted);
            row.addProperty("necessaryComparisonComplete", plan.necessaryComparisonComplete);
            row.addProperty("candidateTraceAvailable", plan.trace != null);
            result.add("planReadback", row);
        }
        var friends = new JsonArray();
        for (var friend : snapshot.friends) {
            var row = new JsonObject();
            row.addProperty("entityId", friend.entityId);
            row.addProperty("current", friend.current);
            row.addProperty("observedAt", friend.observedAt);
            friends.add(row);
        }
        result.add("friends", friends);
        var fact = snapshot.movementFact;
        if (fact != null) {
            var row = new JsonObject();
            row.addProperty("outcome", fact.outcome.name());
            row.addProperty("reason", fact.reason);
            row.addProperty("tick", fact.tick);
            row.addProperty("stage", fact.node.stage);
            row.addProperty("index", fact.node.index);
            row.addProperty("pathIdentity", fact.pathIdentity);
            row.addProperty("nativePathIndex", fact.nativePathIndex);
            row.addProperty("nativePathLength", fact.nativePathLength);
            row.addProperty("inArea", fact.inArea);
            row.addProperty("tailMatches", fact.nativeTailMatches);
            result.add("movementFact", row);
        }
        try {
            Object planner = PLANNER.get(controller);
            result.addProperty("ownerPressureWindow", OWNER_PRESSURE.getBoolean(planner));
            var useRow = new JsonObject();
            useRow.addProperty("present", use != null);
            if (use != null) {
                useRow.addProperty(
                    "hand",
                    use.getHand()
                        .name());
                useRow.addProperty(
                    "kind",
                    use.getKind()
                        .name());
                useRow.addProperty("generation", use.getGeneration());
            }
            // 只读租约对象身份，不调用有效性供应器或盾提供方
            Object shieldLease = SHIELD_LEASE.get(shield);
            useRow.addProperty("shieldLeaseMatchesUseObject", shieldLease != null && shieldLease == use);
            useRow.addProperty("shieldCause", String.valueOf(SHIELD_CAUSE.get(shield)));
            useRow.addProperty("shieldActiveTaskRunning", SHIELD_ACTIVE.getBoolean(shield));
            useRow.addProperty("shieldPassiveRemaining", SHIELD_PASSIVE.getInt(shield));
            result.add("itemUseReadback", useRow);
            Object reobservation = REOBSERVATION.get(planner);
            if (reobservation == null) return result;
            var row = new JsonObject();
            for (String name : new String[] { "id", "state", "reason", "cursor", "lastSeenAt", "retryAt" }) {
                Object value = field(reobservation.getClass(), name).get(reobservation);
                row.addProperty(name, String.valueOf(value));
            }
            for (String name : new String[] { "route", "proof" }) {
                var route = (CombatTerrain.Route) field(reobservation.getClass(), name).get(reobservation);
                if (route == null) continue;
                var routeRow = new JsonObject();
                routeRow.addProperty("provedAt", route.provedAt);
                var nodes = new JsonArray();
                for (var node : route.nodes) {
                    var point = new JsonArray();
                    point.add(new JsonPrimitive(node.x));
                    point.add(new JsonPrimitive(node.y));
                    point.add(new JsonPrimitive(node.z));
                    nodes.add(point);
                }
                routeRow.add("nodes", nodes);
                row.add(name, routeRow);
            }
            result.add("reobservation", row);
            return result;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Read-only combat progress diagnostics unavailable", failure);
        }
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field result = owner.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Reviewed combat diagnostic field missing", failure);
        }
    }
}
