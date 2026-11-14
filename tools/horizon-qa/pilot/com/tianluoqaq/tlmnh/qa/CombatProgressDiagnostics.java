package com.tianluoqaq.tlmnh.qa;

import java.lang.reflect.Field;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCombatController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrain;

/** 只读现有规划值，独立保存校准期复视停滞诊断 */
final class CombatProgressDiagnostics {

    private static final Field PLANNER = field(EnhancedCombatController.class, "planner");
    private static final Field REOBSERVATION = field(CombatPlanner.class, "reobservation");

    private CombatProgressDiagnostics() {}

    static JsonObject capture(EnhancedCombatController controller, CombatSnapshot snapshot, int frame) {
        var result = new JsonObject();
        result.addProperty("frame", frame);
        if (snapshot == null) return result;
        result.addProperty("bodyComplete", snapshot.bodyComplete);
        result.addProperty("bodyContinuationComplete", snapshot.bodyContinuationComplete);
        result.addProperty("friendsComplete", snapshot.friendsComplete);
        result.addProperty("oldestCoverage", snapshot.oldestCoverage);
        result.addProperty("geometryAt", snapshot.geometry == null ? -1 : snapshot.geometry.observedAt);
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
            Object reobservation = REOBSERVATION.get(PLANNER.get(controller));
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
