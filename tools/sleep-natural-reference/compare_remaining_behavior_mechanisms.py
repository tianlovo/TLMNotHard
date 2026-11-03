"""Four bounded normal-root mechanisms; preserve outcome failures and platform differences separately from collection integrity."""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs
from check import require, sha
from compare_follow_mechanisms import (
    ORIGINAL_CLASSES, PRODUCTION, SPAWN, input_differences, original_identity, verify_port_results,
)
from compare_port import artifact
from compare_terrain_life_mechanisms import (
    FOLLOW_OBSERVER, TERRAIN_OBSERVER, TERRAIN_PRODUCTION, binding, enclosure_cells, native_binary, owner_inputs,
)

PREFIX = "com.tianluoqaq.tlmnh."
MECHANISMS = ("closed_door_recovery", "melee_boundary_variant", "owner_dimension_arrow", "sleep_defense_crisis")
SUFFIXES = ("ClosedDoorRecovery", "MeleeBoundaryVariant", "OwnerDimensionArrow", "SleepDefenseCrisis")
EXPECTED = {arm + suffix for arm in ("faithful", "enhanced") for suffix in SUFFIXES}
OBSERVER = [PREFIX + "qa.RemainingBehaviorMechanismPortTests" + suffix for suffix in ("", "$Collector")]
EXTRA_PRODUCTION = [PREFIX + name for name in (
    "common.combat.MaidBowAttack", "common.combat.MaidItemUseState", "common.combat.MaidProjectileSource",
    "common.combat.MaidProjectileImpactProtection", "common.lifecycle.MaidDimensionTravel")]
ORIGINAL = ORIGINAL_CLASSES | {PREFIX + "reference.sleep." + name for name in (
    "TerrainLifeMechanismGameTests", "TerrainLifeMechanismGameTests$Session",
    "RemainingBehaviorMechanismGameTests", "RemainingBehaviorMechanismGameTests$Session")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid.entity." + name for name in (
    "ai.brain.task.MaidBedTask", "ai.brain.task.MaidPanicTask", "ai.brain.task.MaidMeleeAttack",
    "ai.brain.task.MaidShootTargetTask", "task.TaskAttack", "task.TaskBowAttack")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid.event." + name for name in ("EntityHurtEvent", "EntityJoinWorldEvent")}


def expected_layout(mechanism):
    cells = {(x, 0, z): "stone" for x in range(33) for z in range(33)}
    cells.update(enclosure_cells(33, 12))
    if mechanism == "closed_door_recovery":
        cells.update({(x, y, z): "stone" for x in range(1, 32) for y in range(1, 4) for z in (3, 5)})
        cells.update({(x, 3, 4): "stone" for x in range(1, 32)})
        cells.update({(x, y, 4): name for x, name in ((9, "wood_door"), (11, "iron_door")) for y in (1, 2)})
    if mechanism == "sleep_defense_crisis":
        cells.update({(9, 1, 4): "bed_head", (9, 1, 5): "bed_foot"})
    return cells


def inspect_layout(rows, mechanism, original):
    cells = {}
    names = {"minecraft:stone": "stone", "minecraft:glass": "glass", "minecraft:oak_door": "wood_door",
             "minecraft:wooden_door": "wood_door", "minecraft:iron_door": "iron_door"}
    for row in rows:
        point = tuple(row[key] for key in ("x", "y", "z"))
        require(all(type(n) is int for n in point) and point not in cells, "duplicate/noninteger initial arena cell")
        name = names.get(row["registry"])
        if row["registry"] in ("touhou_little_maid:maid_bed", "tlmnh:maid_bed"):
            require(point in ((9, 1, 4), (9, 1, 5)), "bed outside declared head/foot")
            name = "bed_head" if point[2] == 4 else "bed_foot"
        require(name is not None, "unmapped block in complete owned arena")
        if original:
            state = row["rawBlockState"]
            if name in ("stone", "glass"):
                require(state == "Block{minecraft:" + name + "}", "unexpected original solid state")
            elif name in ("wood_door", "iron_door"):
                expected = "Block{" + row["registry"] + "}[facing=east,half=" + ("lower" if point[1] == 1 else "upper")
                expected += ",hinge=left,open=false,powered=false]"
                require(state == expected,
                        "original door orientation/open input differs")
            else:
                expected = "Block{" + row["registry"] + "}[facing=north,occupied=false,part="
                expected += ("head" if name == "bed_head" else "foot") + "]"
                require(state == expected, "original bed input differs")
        else:
            expected_meta = (0 if point[1] == 1 else 8) if name.endswith("door") else 10 if name == "bed_head" else 2 if name == "bed_foot" else 0
            require(type(row["metadata"]) is int and row["metadata"] == expected_meta, "native arena metadata differs")
        cells[point] = name
    require(cells == expected_layout(mechanism), "actual complete arena blueprint differs")


def metrics(data):
    frames = data["frames"]
    events = data["events"]
    damage = data["combatDamageEvents"]
    migration = data.get("migrationFrame", -1)
    crisis = data.get("crisisFrame", -1)
    defense = data.get("defenseFrame", -1)
    accepted = [row for row in damage if not row["canceled"] and row["amount"] > 0]
    return {
        "observed_frames": len(frames), "terminal_reason": data.get("terminalReason", "window_end"),
        "collector_behavior_success": data.get("behaviorSuccess"),
        "first_arrival": data.get("firstArrival", -1), "second_arrival": data.get("secondArrival", -1),
        "second_closed_frame": data.get("secondClosedFrame", -1), "second_opened_frame": data.get("secondOpenedFrame", -1),
        "live_enemy_outside_frame": data.get("liveEnemyOutsideFrame", -1), "live_enemy_reentry_frame": data.get("liveEnemyReentryFrame", -1),
        "outside_attack_candidates": [row for row in accepted if row["outsideHome"]],
        "friendly_attack_candidates": [row for row in accepted if row["victim"] == "friend"],
        "accepted_enemy_damage_candidates": [row for row in accepted if row["victim"] == "enemy"],
        "sleep_frame": data.get("sleepFrame", -1), "defense_input_frame": defense,
        "crisis_input_frame": crisis, "restored_sleep_frame": data.get("restoredSleepFrame", -1),
        "defensive_response_candidates": [row for row in accepted if row["victim"] == "enemy"
                                           and defense >= 0 and row["frame"] >= defense and (crisis < 0 or row["frame"] <= crisis)],
        "panic_frames_during_crisis": [row["frame"] for row in frames if crisis >= 0 and crisis < row["frame"] <= crisis + 40
                                       and "PANIC" in str(row.get("activeActivities", "")).upper()],
        "migration_frame": migration, "source_exit_exercised": data.get("sourceExitExercised", False),
        "native_shot_joins": len(data["shots"]),
        "post_migration_shot_joins": sum(row["frame"] >= migration for row in data["shots"]) if migration >= 0 else None,
        "post_migration_damage_candidates": [row for row in accepted if migration >= 0 and row["frame"] >= migration],
        "end_platform_restored": data.get("endPlatformRestored"),
        "path_searches": frames[-1].get("actualAStarSearches") if frames else None,
        "budget_deferrals": frames[-1].get("budgetDeferrals") if frames else None,
        "exact_events": events,
        "collection_completion_is_not_all_phase_success": True,
        "owner_plus_self_hurt_is_a_compound_input_not_pure_owner_defense_attribution": True,
    }


def inspect(data, scene, contract, contract_hash, original):
    captured = dict(data["contract"])
    captured.setdefault("noninferiorityDelta", None)
    captured["campaign"] = dict(captured["campaign"])
    captured["campaign"].setdefault("noninferiorityDelta", None)
    require(captured == contract and data["contractSha256"] == contract_hash and data["scene"] == scene, "contract/scene binding differs")
    require(data["schema"] == ("remaining-behavior-original-v1" if original else "remaining-behavior-port-v1"), "unknown collector schema")
    require(data["observationComplete"] is data["ownCleanupComplete"] is True
            and not data.get("error") and not data.get("cleanupError"), "native collection/cleanup incomplete")
    require(data["statisticalAdmission"] is data["complete14ClassBenchmarkAccepted"] is data["randomSourcesReset"] is False,
            "unapproved root/statistical scope")
    actual = data["actualInputs"]
    require(actual["task"] == "touhou_little_maid:" + scene["task"] and actual["schedule"] == scene["initialSchedule"]
            and actual["position"] == scene["maidSpawn"] and actual["homeEnabled"] is scene["homeEnabled"]
            and actual["restrictionRadius"] == (12 if scene["homeEnabled"] else 8)
            and actual["health"] == 20 and actual["hunger"] == 20 and actual["sitting"] is actual["pickup"] is False,
            "normal actor/task/home/schedule input differs")
    require(actual["difficulty"] == "NORMAL" and actual["dayTime"] == 18000
            and actual["raining"] is actual["thundering"] is False, "actual environment differs")
    owner_inputs(actual["owner"], original, scene["ownerMode"] == "survival")
    require(actual["owner"]["position"] == scene["ownerPosition"], "actual controlled owner start differs")
    bound = binding(data["attributeInputs"], actual["rawInitialNbtBinary"], actual["rawInitialNBT"])
    inspect_layout(actual["layout"], scene["id"], original)
    frames = data["frames"]
    reason = data.get("terminalReason", "window_end")
    require(reason in ("window_end", "maid_death", "owner_death"), "unknown native terminal reason")
    require(len(frames) == scene["frames"] if reason == "window_end" else len(frames) < scene["frames"], "missing/padded entity frames")
    migration = data.get("migrationFrame", -1)
    for i, row in enumerate(frames):
        require(row["frame"] == i and (i == 0 or row["gameTime"] == frames[i - 1]["gameTime"] + 1), "native world time/frame stream differs")
        after_owner = row.get("phase") in ("revoked_owner_world", "after_owner_migration")
        if after_owner:
            require(scene["id"] == "owner_dimension_arrow" and migration >= 0 and i >= migration
                    and row["normalRootFrameClaimed"] is False, "original owner-object frame contract was claimed after owner migration")
            require(row["owner"]["dimension"] == ("minecraft:the_end" if original else 1), "actual owner never left original dimension")
            if not original:
                require(row["factoryRuntimeSameObject"] is True and row["legacyTicks"] == 0
                        and row["age"] == i + 1 and row["livingTicks"] == 1,
                        "unchanged source actor left its normal factory/native tick stream")
        else:
            require(row["age"] == i + 1 and row["livingTicks"] == 1, "actor natural tick was skipped/doubled")
            if not original:
                require(row["fullRootAuthorized"] is True and row["legacyTicks"] == 0 and row["actualProfile"] == data["profile"],
                        "component/Legacy root entered normal phase")
    if migration >= 0:
        require(data["endPlatformRestored"] is True and len(data["endPlatformOriginal"]) == 100, "original End platform restoration unproven")
    for key in ("enemyInitial", "friendInitial"):
        if key in data:
            native_binary(data[key]["binaryNBT"])
    return {"metrics": metrics(data), "same_side_complete_initial_binding": bound,
            "actual_actor_inputs": actual, "native_other_actor_inputs": {key: data[key] for key in (
                "enemyInitial", "friendInitial", "enemyBeforeInitialization") if key in data},
            "initial_weapon_damage_values_and_native_enemy_modifiers_are_not_forced_equal": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    args = parser.parse_args()
    cb = (args.original_tool / "remaining-behavior-mechanisms-contract.json").read_bytes()
    contract = json.loads(cb)
    require(contract["contract"] == "REMAINING_BEHAVIOR_MECHANISMS_V2" and contract["noninferiorityDelta"] is None
            and contract["repetitions"] == 1 and tuple(s["id"] for s in contract["mechanisms"]) == MECHANISMS, "unapproved mechanism contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    cases = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, cases, args.allow_additional_port_tests, args.allow_failed_additional_port_tests,
                                "tlmnhqapilot:RemainingBehaviorMechanismPortTests", EXPECTED)
    require(hqa["configuration"]["turbo"] == observed["horizonqa_turbo"] == contract["calibrationTurbo"] == 4,
            "actual accelerated calibration contract differs")
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes()) and observed["pilot_sha256"] == sha(pilot.read_bytes()),
            "observed artifact binding differs")
    originals = [json.loads(p.read_bytes()) for p in args.original_captures.glob("remaining-behavior-original-*.json")]
    require(len(originals) == 4 and {d["mechanism"] for d in originals} == set(MECHANISMS), "missing/duplicate original cases")
    rows = []
    for scene in contract["mechanisms"]:
        original = next(d for d in originals if d["mechanism"] == scene["id"])
        definitions = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent,
                                        ORIGINAL, "remaining-behavior-mechanisms-build-v1")
        row = {"mechanism": scene["id"], "original": inspect(original, scene, contract, sha(cb), True),
               "selected_original_resource_definition_differences": definitions, "ports": {}}
        for profile in ("faithful_tlm", "enhanced"):
            path = args.port_run / f"remaining-behavior-{profile}-{scene['id']}.json"
            data = json.loads(path.read_bytes())
            for field, classes, jar in (
                ("productionArtifact", PRODUCTION, args.candidate_jar), ("spawnInputArtifact", SPAWN, args.candidate_jar),
                ("observerArtifact", FOLLOW_OBSERVER, pilot), ("terrainObserverArtifact", TERRAIN_OBSERVER, pilot),
                ("terrainProductionArtifact", TERRAIN_PRODUCTION, args.candidate_jar),
                ("remainingObserverArtifact", OBSERVER, pilot), ("remainingProductionArtifact", EXTRA_PRODUCTION, args.candidate_jar)):
                artifact(data[field], jar, classes)
            result = inspect(data, scene, contract, sha(cb), False)
            result["capture_sha256"] = sha(path.read_bytes())
            result["five_sourced_attribute_inputs"] = compare_attribute_inputs(original["attributeInputs"], data["attributeInputs"], contract["attributeInputs"])
            result["relevant_input_differences"] = input_differences(original, data)
            result["damage_or_kill_speed_gain_admitted"] = False
            row["ports"][profile] = result
        rows.append(row)
    result = {"schema": "remaining-behavior-mechanisms-evidence-v1", "calibration_collection_integrity_passed": True,
              "hqa_batch": batch, "hqa_counts": hqa["counts"], "mechanisms": rows, "campaign": contract["campaign"],
              "turbo": 4, "periodic_autosaves_skipped_by_framework": True, "cpu_mspt_network_durability_verdict": "NOT_EVALUATED",
              "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "contract_sha256": sha(cb), "original_build_sha256": sha(args.original_build.read_bytes()),
              "original_compiled_classes": json.loads(args.original_build.read_bytes())["compiledClasses"],
              "analysis_inputs_sha256": {name: sha(Path(__file__).with_name(name).read_bytes()) for name in (
                  "compare_remaining_behavior_mechanisms.py", "compare_terrain_life_mechanisms.py", "compare_activity_competition.py",
                  "compare_follow_mechanisms.py", "compare_port.py", "attribute_inputs.py", "nbt_binding.py", "check.py")},
              "contract_scope_corrections": [{
                  "path": "campaign.primaryMetrics.owner_dimension_arrow",
                  "retained_captured_contract_text": contract["campaign"]["primaryMetrics"]["owner_dimension_arrow"],
                  "correction": "Only the native owner moves. MaidProjectileSource binds shooter world/registry/UUID and owner UUID; those can remain valid. Continued legal fire/use is observed, not a stale-source violation. Finite ammo/wear conservation remains strict.",
                  "basis": "MaidProjectileSource.owner/beforeUpdate and MaidBowAttack launch context; no online-owner-location requirement",
                  "source_exit_submechanism": "NOT_EXERCISED; actual maid source migration/normal original request refusal is deferred",
                  "original_legacy_phase_label_revoked_owner_world_is_not_source_exit_evidence": True,
                  "captured_contract_or_input_hash_modified_by_analysis": False,
              }],
              "formal_repetition_freeze_pending": True,
              "formal_repetition_scope_note": "Calibrate the three door/melee/competition mechanisms separately; current owner-only B08 is exploratory, not validation of source-exit cancellation. Do not recycle this calibration into formal samples.",
              "statistical_verdict": "NOT_EVALUATED", "noninferiority_delta": None,
              "complete_14_class_benchmark_accepted": False, "full_release_passed": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("mechanisms", "campaign", "original_compiled_classes")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
