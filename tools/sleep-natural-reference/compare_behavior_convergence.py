"""Inspect three bounded normal-root mechanisms; retain refusal, nontrigger and unequal inputs as outcomes."""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs
from check import require, sha
from compare_follow_mechanisms import ORIGINAL_CLASSES, PRODUCTION, SPAWN, input_differences, original_identity, verify_port_results
from compare_port import artifact
from compare_terrain_life_mechanisms import (
    FOLLOW_OBSERVER, PANTRY_OBSERVER, TERRAIN_OBSERVER, TERRAIN_PRODUCTION, binding, enclosure_cells, native_binary, owner_inputs,
)

PREFIX = "com.tianluoqaq.tlmnh."
MECHANISMS = ("redstone_door_recovery", "source_exit_arrow", "pantry_sleep_command")
EXPECTED = {arm + name for arm in ("faithful", "enhanced")
            for name in ("RedstoneDoorRecovery", "SourceExitArrow", "PantrySleepCommand")}
OBSERVER = [PREFIX + "qa.BehaviorConvergencePortTests" + suffix for suffix in ("", "$Collector", "$PantryCollector")]
EXTRA_PRODUCTION = [PREFIX + name for name in (
    "common.combat.MaidProjectileSource", "common.combat.MaidProjectileImpactProtection",
    "common.lifecycle.MaidDimensionTravel", "compat.malisisdoors.common.block.NativeDoorState")]
FAVOR_PRODUCTION = ["com.github.tartaricacid.touhoulittlemaid.entity.favorability." + name for name in ("FavorabilityManager", "Type")]
ORIGINAL = ORIGINAL_CLASSES | {PREFIX + "reference.sleep." + name for name in (
    "ActivityCompetitionGameTests", "ActivityCompetitionGameTests$Session", "TerrainLifeMechanismGameTests",
    "TerrainLifeMechanismGameTests$Session", "TerrainLifeMechanismGameTests$PantrySession",
    "BehaviorConvergenceGameTests", "BehaviorConvergenceGameTests$Session", "BehaviorConvergenceGameTests$PantrySession")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task." + name for name in (
    "MaidBedTask", "MaidPanicTask", "MaidCheckRateTask", "MaidMoveToBlockTask", "MaidStealEdibleMoveBlockTask",
    "MaidStealEdibleUseTask", "MaidShootTargetTask")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid." + name for name in (
    "entity.task.TaskBowAttack", "event.EntityHurtEvent", "event.EntityJoinWorldEvent")}
PRIMARY_BITE_SOURCE = {"com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.CakeEdible"} | set(FAVOR_PRODUCTION)
ORIGINAL |= PRIMARY_BITE_SOURCE


def expected_layout(mechanism):
    size, height = (25, 8) if mechanism == "pantry_sleep_command" else (33, 12)
    cells = {(x, 0, z): "stone" for x in range(size) for z in range(size)}
    cells.update(enclosure_cells(size, height))
    if mechanism == "redstone_door_recovery":
        cells.update({(x, y, z): "stone" for x in range(1, 32) for y in range(1, 4) for z in (3, 5)})
        cells.update({(x, 3, 4): "stone" for x in range(1, 32)})
        cells.update({(11, y, 4): "iron_door" for y in (1, 2)})
    elif mechanism == "pantry_sleep_command":
        cells.update({(12, 1, 12): "snack_cabinet", (12, 2, 12): "cake", (10, 1, 12): "bed_head", (10, 1, 13): "bed_foot"})
    return cells


def inspect_layout(rows, mechanism, original):
    actual = {}
    for row in rows:
        point = tuple(row[key] for key in ("x", "y", "z"))
        require(all(type(n) is int for n in point) and point not in actual, "duplicate/noninteger native block input")
        registry = row["registry"]
        name = registry.split(":", 1)[1]
        domain = "touhou_little_maid" if original else "tlmnh"
        require(registry in {"minecraft:" + value for value in ("stone", "glass", "iron_door", "cake")}
                | {domain + ":snack_cabinet", domain + ":maid_bed"}, "unknown block namespace/provider")
        if name == "maid_bed":
            require(point in ((10, 1, 12), (10, 1, 13)), "unknown bed location")
            name = "bed_head" if point[2] == 12 else "bed_foot"
        require(name in ("stone", "glass", "iron_door", "snack_cabinet", "cake", "bed_head", "bed_foot"), "unmapped initial arena block")
        if original:
            state = row["rawBlockState"]
            if name in ("stone", "glass"):
                require(state == "Block{minecraft:" + name + "}", "unexpected solid block state")
            elif name == "iron_door":
                expected = "Block{minecraft:iron_door}[facing=east,half=" + ("lower" if point[1] == 1 else "upper") + ",hinge=left,open=false,powered=false]"
                require(state == expected, "original initial iron door differs")
            elif name == "cake":
                require(state == "Block{minecraft:cake}[bites=0]", "initial original cake already consumed")
            elif name == "snack_cabinet":
                require(state == "Block{touhou_little_maid:snack_cabinet}[facing=north,type=1]", "original cabinet orientation/food cover differs")
            elif name.startswith("bed_"):
                expected = "Block{touhou_little_maid:maid_bed}[facing=north,occupied=false,part=" + ("head" if name == "bed_head" else "foot") + "]"
                require(state == expected, "original bed type/occupancy differs")
        else:
            metadata = (0 if point[1] == 1 else 8) if name == "iron_door" else 10 if name == "bed_head" else 2 if name == "bed_foot" else 4 if name == "snack_cabinet" else 0
            require(type(row["metadata"]) is int and row["metadata"] == metadata, "native initial block metadata differs")
        actual[point] = name
    require(actual == expected_layout(mechanism), "complete arena blueprint differs")


def metrics(data):
    fields = ("firstCrossFrame", "secondCloseFrame", "secondApproachFrame", "secondOpenFrame", "secondCrossFrame",
              "firstBlockedSamples", "secondBlockedSamples", "sourceRequestFrame", "firstBiteFrame", "naturalSleepFrame",
              "ownerWaitFrame", "ownerReleaseFrame", "resumedBiteFrame", "restoredSleepFrame", "waitConsumptionViolations")
    result = {field: data.get(field) for field in fields}
    result.update(frames=len(data["frames"]), terminal_reason=data.get("terminalReason", "window_end"),
                  collector_behavior_success=data.get("behaviorSuccess"), native_request_outcome=data.get("nativeRequestOutcome"),
                  source_exit_exercised=data.get("sourceExitExercised", False),
                  original_world_shots=len(data.get("shots", [])), new_registered_source_shots=len(data.get("freshShots", [])),
                  actual_events=data["events"], collection_complete_is_not_behavior_success=True)
    if data.get("secondOpenFrame", -1) >= 0 and data.get("secondCrossFrame", -1) >= 0:
        result["second_open_to_cross_ticks"] = data["secondCrossFrame"] - data["secondOpenFrame"]
    if data["mechanism"] == "redstone_door_recovery":
        for name, start in (("first", 100), ("second", data.get("secondOpenFrame", -1))):
            clear = next((row["frame"] for row in data["frames"] if start >= 0 and row["frame"] >= start
                          and row["door"]["doorwayBlockCollisionCount"] == 0), None)
            result[name + "_native_doorway_clear_frame"] = clear
            result[name + "_power_to_doorway_clear_ticks"] = None if clear is None else clear - start
        result["two_blocked_recovery_opportunities_observed"] = data.get("firstBlockedSamples", 0) > 0 and data.get("secondBlockedSamples", 0) > 0
        result["both_crossings_observed"] = data.get("firstCrossFrame", -1) >= 0 and data.get("secondCrossFrame", -1) >= 0
    if data["mechanism"] == "pantry_sleep_command":
        frames = data["frames"]
        released = data.get("ownerReleaseFrame", -1)
        command = data.get("ownerWaitFrame", -1)
        changes = []
        for index, row in enumerate(frames):
            previous = frames[index - 1]["cakeBitesConsumed"] if index else 0
            if row["cakeBitesConsumed"] == previous:
                continue
            changes.append({"frame": index, "mouth_delta": row["cakeBitesConsumed"] - previous,
                            "primary_sitting": row["maid"]["sitting"], "secondary_sitting": row["second"]["sitting"],
                            "primary_activity": row["maid"]["activity"], "secondary_activity": row["second"]["activity"],
                            "primary_work_observation": row.get("maidLifeTargets"),
                            "secondary_work_observation": row.get("secondLifeTargets")})
        fresh = next((change["frame"] for change in changes if released >= 0 and change["frame"] > released), None)
        claimed = data.get("resumedBiteFrame", -1)
        claim_fresh = claimed >= 0 and released >= 0 and claimed > released and any(change["frame"] == claimed for change in changes)
        primary_counter = next((index for index in range(max(1, released + 1), len(frames))
                                if released >= 0 and "cakeBites" in frames[index].get("maidLifeTargets", {})
                                and frames[index]["maidLifeTargets"]["cakeBites"] > frames[index - 1]["maidLifeTargets"]["cakeBites"]), None)
        counter_captured = any("cakeBites" in row.get("maidLifeTargets", {}) for row in frames)
        result["derived_pantry_observations"] = {
            "actual_mouth_change_events": changes,
            "mouth_change_events_during_second_wait": [change for change in changes if command < change["frame"] <= released],
            "raw_wait_counter_measures_observation_frames_not_unique_bites": True,
            "raw_wait_counter_is_not_an_automatic_native_semantic_violation_judgment": True,
            "raw_collector_resume_frame": claimed,
            "raw_resume_claim_is_fresh_post_release_mouth_delta": claim_fresh,
            "observed_fresh_post_release_mouth_delta_frame": fresh,
            "observed_primary_consume_counter_after_release_frame": primary_counter,
            "primary_consume_counter_captured": counter_captured,
            "primary_resume_counter_observation": "NOT_CAPTURED" if not counter_captured else "OBSERVED" if primary_counter is not None else "NOT_OBSERVED_IN_WINDOW",
            "primary_counter_scope": "Port existing actor consume counter where captured; original absence remains unknown",
            "release_snapshot_mouths": frames[released]["cakeBitesConsumed"] if 0 <= released < len(frames) else None,
            "night_restore_claim_precedes_fresh_mouth": claimed >= 0 and not claim_fresh,
            "fixed_original_source_semantics": "Original setInSittingPose changes flags/order; activity switches to RIDE on the scheduled >20tick update. Original move/search/use do not independently reject sitting. Port phase semantics must use its actual observations; this reference note does not classify every port consume as allowed or broken.",
            "scope": "Retain every raw phase label; no fresh consume is inferred from an old cumulative mouth count or from a later sleep alone",
        }
    return result


def inspect(data, scene, contract, digest, original):
    captured = dict(data["contract"])
    captured.setdefault("noninferiorityDelta", None)
    captured["campaign"] = dict(captured["campaign"])
    captured["campaign"].setdefault("noninferiorityDelta", None)
    require(captured == contract and data["contractSha256"] == digest and data["scene"] == scene, "captured input contract changed")
    require(data["schema"] == ("behavior-convergence-original-v1" if original else "behavior-convergence-port-v1"), "unexpected collector")
    require(data["observationComplete"] is data["ownCleanupComplete"] is True and not data.get("error") and not data.get("cleanupError"), "collection/cleanup failed")
    inputs = data["actualInputs"]
    pantry = scene["id"] == "pantry_sleep_command"
    require(inputs["task"] == "touhou_little_maid:" + scene["task"] and inputs["position"] == scene["maidSpawn"]
            and inputs["homeEnabled"] is scene["homeEnabled"] and inputs["restrictionRadius"] == (12 if scene["homeEnabled"] else 8)
            and inputs["schedule"] == "ALL" and inputs["health"] == 20 and inputs["hunger"] == (0 if pantry else 20)
            and inputs["sitting"] is inputs["pickup"] is False, "actual maid input differs")
    require(inputs["difficulty"] == "NORMAL" and inputs["dayTime"] == 18000
            and inputs["raining"] is inputs["thundering"] is False, "native environment input differs")
    owner_inputs(inputs["owner"], original, False)
    require(inputs["owner"]["position"] == scene["ownerPosition"], "actual owner input differs")
    bound = binding(data["attributeInputs"], inputs["rawInitialNbtBinary"], inputs["rawInitialNBT"])
    inspect_layout(inputs["layout"], scene["id"], original)
    second = None
    if pantry:
        second = binding(data["secondAttributeInputs"], data["secondInitial"]["binaryNBT"], data["secondInitial"]["rawNBT"])
        if contract["contract"] == "BEHAVIOR_CONVERGENCE_V2":
            require(data["initialPrimaryFavorability"] == 0 and data["initialPrimaryStealRewardCold"] is True,
                    "primary first-bite deterministic reward was not cold")
    frames = data["frames"]
    terminal = data.get("terminalReason", "window_end")
    require(len(frames) == scene["frames"] if terminal == "window_end" else terminal in ("maid_death", "owner_death") and len(frames) < scene["frames"], "missing/padded frame stream")
    for index, row in enumerate(frames):
        require(row["frame"] == index and (index == 0 or row["gameTime"] == frames[index - 1]["gameTime"] + 1), "native world tick stream differs")
        if row.get("phase") == "old_world_after_real_source_exit":
            require(not original and scene["id"] == "source_exit_arrow" and row["normalRootFrameClaimed"] is False,
                    "fictitious original/live-source actor frame after exit")
            require(row["sourceRegistered"] is False and row["oldSourceDead"] is True and row["oldSourceLease"] is False
                    and row["candidateImpactAvailable"] is row["candidateRuntimeStable"] is True
                    and row["candidateDimension"] == 1 and row["candidateProfile"] == data["profile"], "source exit or new shooter contract failed")
            for arrow in row["freshArrows"]:
                require(arrow["sourceStopped"] is False, "new legitimate arrow inherited source revocation")
        else:
            require(row["age"] == index + 1 and row["livingTicks"] == 1, "normal actor natural tick changed")
            if not original:
                require(row["fullRootAuthorized"] is True and row["legacyTicks"] == 0 and row["actualProfile"] == data["profile"], "component or Legacy root used")
        if scene["id"] == "redstone_door_recovery":
            door = row["door"]
            require(type(door["doorwayBlockCollisionCount"]) is int and door["doorwayBlockCollisionCount"] >= 0, "missing actual door collision")
            if "tileNBT" in door:
                native_binary(door["tileNBT"])
            second_close = data.get("secondCloseFrame", -1)
            second_open = data.get("secondOpenFrame", -1)
            expected_power = index >= 100 and not (second_close >= 0 and index >= second_close and (second_open < 0 or index < second_open))
            require(door["nativePowered"] is expected_power, "actual native redstone input did not reach the door")
    if scene["id"] == "redstone_door_recovery":
        power_events = [row for row in data["events"] if row["kind"] in ("native_redstone_power_on", "native_redstone_power_off")]
        expected_events = [(100, "native_redstone_power_on")] if len(frames) > 100 else []
        if data.get("secondCloseFrame", -1) >= 0:
            require(data["secondCloseFrame"] == data["firstCrossFrame"] + 1, "second close preceded a real first crossing")
            expected_events.append((data["secondCloseFrame"], "native_redstone_power_off"))
        if data.get("secondOpenFrame", -1) >= 0:
            require(data["secondApproachFrame"] >= data["secondCloseFrame"] + 20
                    and data["secondOpenFrame"] == data["secondApproachFrame"] + 100, "actual second blocked interval changed")
            expected_events.append((data["secondOpenFrame"], "native_redstone_power_on"))
        require([(row["frame"], row["kind"]) for row in power_events] == expected_events, "native power event schedule differs")
        if contract["contract"] == "BEHAVIOR_CONVERGENCE_V2":
            for event in data["events"]:
                if event["kind"] == "bounded_reverse_owner_input":
                    require(event["formed"] is True and 1 <= event["plannedOwner"][0] <= 10.5
                            and event["plannedOwner"][0] == event["actualMaid"][0] - 8
                            and 36 <= event["distanceSquared"] < 100, "owner input skipped native door/teleport bounds")
    if pantry and contract["contract"] == "BEHAVIOR_CONVERGENCE_V2" and data.get("firstBiteFrame", -1) >= 0:
        first = frames[data["firstBiteFrame"]]
        require(first["cakeBitesConsumed"] > 0 and first["maid"]["favorability"] > data["initialPrimaryFavorability"],
                "first mouth change was not attributed to the primary actor's cold deterministic reward")
    if data.get("sourceRequestFrame", -1) >= 0:
        require(data["endPlatformRestored"] is True and len(data["endPlatformOriginal"]) == 100, "End original data restore unproven")
        request = next(e for e in data["events"] if e["kind"] in ("normal_maid_change_dimension_request", "normal_maid_travel_to_dimension_request"))
        require(request["usingBefore"] is True and len(request["liveOldArrowsBefore"]) > 0, "missing actual old arrow/lease opportunity")
        if original:
            require(data["sourceExitExercised"] is False and data["nativeRequestOutcome"] == "REMAINED_IN_SOURCE_WORLD", "upstream capability scope changed")
        else:
            require(data["sourceExitExercised"] is True and data["nativeRequestOutcome"] == "SOURCE_EXIT", "port never exercised real source exit")
    return {"metrics": metrics(data), "same_side_complete_initial_binding": bound, "second_initial_binding": second,
            "actual_actor_inputs": inputs, "source_request_is_a_platform_capability_difference_not_original_zero_score": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    args = parser.parse_args()
    cb = (args.original_tool / "behavior-convergence-contract.json").read_bytes()
    contract = json.loads(cb)
    require(contract["contract"] in ("BEHAVIOR_CONVERGENCE_V1", "BEHAVIOR_CONVERGENCE_V2") and contract["noninferiorityDelta"] is None
            and tuple(s["id"] for s in contract["mechanisms"]) == MECHANISMS, "unapproved convergence contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    xml = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, xml, args.allow_additional_port_tests, args.allow_failed_additional_port_tests,
                                "tlmnhqapilot:BehaviorConvergencePortTests", EXPECTED)
    require(hqa["configuration"]["turbo"] == observed["horizonqa_turbo"] == contract["calibrationTurbo"] == 1, "actual door/native clock contract changed")
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes()) and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual artifacts changed")
    originals = [json.loads(p.read_bytes()) for p in args.original_captures.glob("behavior-convergence-original-*.json")]
    require(len(originals) == 3 and {d["mechanism"] for d in originals} == set(MECHANISMS), "missing/duplicate original cases")
    rows = []
    for scene in contract["mechanisms"]:
        original = next(d for d in originals if d["mechanism"] == scene["id"])
        definitions = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent,
                                        ORIGINAL if contract["contract"] == "BEHAVIOR_CONVERGENCE_V2" else ORIGINAL - PRIMARY_BITE_SOURCE,
                                        "behavior-convergence-build-v1")
        row = {"mechanism": scene["id"], "original": inspect(original, scene, contract, sha(cb), True), "ports": {},
               "selected_original_resource_definition_differences": definitions}
        for profile in ("faithful_tlm", "enhanced"):
            path = args.port_run / f"behavior-convergence-{profile}-{scene['id']}.json"
            port = json.loads(path.read_bytes())
            groups = (("productionArtifact", PRODUCTION, args.candidate_jar), ("spawnInputArtifact", SPAWN, args.candidate_jar),
                      ("observerArtifact", PANTRY_OBSERVER if scene["id"] == "pantry_sleep_command" else FOLLOW_OBSERVER, pilot),
                      ("terrainObserverArtifact", TERRAIN_OBSERVER, pilot), ("terrainProductionArtifact", TERRAIN_PRODUCTION, args.candidate_jar),
                      ("convergenceObserverArtifact", OBSERVER, pilot),
                      ("convergenceProductionArtifact", EXTRA_PRODUCTION + (FAVOR_PRODUCTION if contract["contract"] == "BEHAVIOR_CONVERGENCE_V2" else []), args.candidate_jar))
            for field, classes, jar in groups:
                artifact(port[field], jar, classes)
            result = inspect(port, scene, contract, sha(cb), False)
            result["capture_sha256"] = sha(path.read_bytes())
            result["five_sourced_attribute_inputs"] = compare_attribute_inputs(original["attributeInputs"], port["attributeInputs"], contract["attributeInputs"])
            result["relevant_input_differences"] = input_differences(original, port)
            row["ports"][profile] = result
        rows.append(row)
    result = {"schema": "behavior-convergence-evidence-v1", "collection_integrity_passed": True, "hqa_batch": batch,
              "hqa_counts": hqa["counts"], "mechanisms": rows, "candidate_sha256": sha(args.candidate_jar.read_bytes()),
              "pilot_sha256": sha(pilot.read_bytes()), "contract_sha256": sha(cb), "original_build_sha256": sha(args.original_build.read_bytes()),
              "analysis_sha256": sha(Path(__file__).read_bytes()),
              "analysis_inputs_sha256": {name: sha(Path(__file__).with_name(name).read_bytes()) for name in (
                  "compare_behavior_convergence.py", "compare_terrain_life_mechanisms.py", "compare_activity_competition.py",
                  "compare_follow_mechanisms.py", "compare_port.py", "attribute_inputs.py", "nbt_binding.py", "check.py")},
              "calibration_samples_used_as_formal": False,
              "statistical_verdict": "NOT_EVALUATED", "noninferiority_delta": None, "complete_14_class_accepted": False,
              "limits": ["Each nontrigger/failed outcome remains explicit; collection integrity is not task success.",
                         "Actual native source request refusal is a capability difference, not a zero score for O.",
                         "Native animated/instant door clocks differ; actual power, collision and completion observations are retained.",
                         "Cross-engine full NBT equality, real-client/network, CPU/MSPT and long-term admission are not claimed."]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "mechanisms"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
