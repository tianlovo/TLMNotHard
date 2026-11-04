"""Strict low-air episode inputs/identity and bounded native escape/recovery outcomes."""

import argparse
import json
import math
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs
from check import require, sha
from compare_follow_mechanisms import (
    PRODUCTION, SPAWN, OBSERVER as FOLLOW_OBSERVER,
    input_differences, original_identity, verify_port_results,
)
from compare_port import artifact
from compare_terrain_life_mechanisms import ORIGINAL as TERRAIN_ORIGINAL, binding, enclosure_cells, native_binary, owner_inputs

PREFIX = "com.tianluoqaq.tlmnh."
MECHANISMS = ("low_air_open_surface", "low_air_roof_release")
EXPECTED = {arm + case for arm in ("faithful", "enhanced") for case in ("LowAirOpenSurface", "LowAirRoofRelease")}
ORIGINAL = TERRAIN_ORIGINAL | {
    PREFIX + "reference.sleep.LifeConditionMechanismGameTests" + suffix
    for suffix in ("", "$Session", "$OpportunityReader", "$AirSession")
}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid." + name for name in (
    "entity.ai.brain.task.MaidBedTask", "block.BlockMaidBed", "entity.ai.edible.CakeEdible",
    "entity.favorability.FavorabilityManager", "entity.favorability.Type",
    "entity.ai.brain.task.MaidBreathAirTask", "entity.ai.brain.task.MaidBreathAirStopTask",
)}
AIR_OBSERVER = [PREFIX + "qa.LifeConditionMechanismPortTests" + suffix for suffix in ("", "$AirCollector")]
AIR_PRODUCTION = [
    "com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirTask",
    "com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBreathAirStopTask",
    PREFIX + "common.ai.enhanced.EnhancedBreathingController",
    PREFIX + "common.ai.navigation.MaidPathNavigator",
]


def expected_layout(mechanism, opened=False):
    cells = {(x, 0, z): "stone" for x in range(25) for z in range(25)}
    cells.update(enclosure_cells(25, 8))
    cells.update({(x, y, z): "stone" for x in range(4, 23) for y in range(1, 4) for z in range(8, 17)})
    cells.update({(x, y, z): "water" for x in range(8, 17) for y in range(1, 4) for z in range(10, 15)})
    if mechanism == "low_air_roof_release":
        cells.update({(x, 4, z): "glass" for x in range(8, 17) for z in range(10, 15)})
        if opened:
            for x in (12, 13):
                for z in (12, 13):
                    del cells[x, 4, z]
    return cells


def layout(rows, mechanism, original, opened=False):
    cells = {}
    for row in rows:
        point = tuple(row[key] for key in ("x", "y", "z"))
        require(all(type(value) is int for value in point) and point not in cells, "duplicate/noninteger air-arena cell")
        registry = row["registry"]
        if registry == "minecraft:flowing_water":
            require(not original and opened and mechanism == "low_air_roof_release"
                    and point in {(x, 3, z) for x in (12, 13) for z in (12, 13)},
                    "flowing-water transition outside the four opened source cells")
            registry = "minecraft:water"
        require(registry in {"minecraft:stone", "minecraft:glass", "minecraft:water"}, "unknown native air-arena block")
        name = registry.split(":", 1)[1]
        require(row["metadata"] == 0, "native source-water or solid metadata differs")
        if original:
            expected = "Block{minecraft:" + name + "}" + ("[level=0]" if name == "water" else "")
            require(row["rawBlockState"] == expected, "actual original water/solid state differs")
        cells[point] = name
    require(cells == expected_layout(mechanism, opened), "complete air-arena input differs")


def java_float(value):
    require(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value), "invalid native float")
    return struct.unpack(">f", struct.pack(">f", value))[0]


def metrics(data):
    frames = data["frames"]
    states = [row["airState"] for row in frames]
    first = lambda predicate: next((index for index, state in enumerate(states) if predicate(state)), -1)
    head = first(lambda state: not state["headInWater"])
    air = first(lambda state: state["air"] >= 290)
    plan = first(lambda state: state["goingToBreath"])
    cleared = next((i for i, state in enumerate(states) if plan >= 0 and i >= plan
                    and not state["goingToBreath"] and state["air"] >= 290), -1)
    arrival = next((i for i, state in enumerate(states) if air >= 0 and i >= air
                    and not state["goingToBreath"] and state["ownerDistanceSquared"] <= 4), -1)
    terminal = data["terminalActorState"]
    previous, net_loss = java_float(20), java_float(0)
    for state in states + [terminal]:
        health = java_float(state["health"])
        loss = max(java_float(0), java_float(previous - health))
        net_loss = java_float(net_loss + loss)
        previous = health
    derived = {
        "firstHeadOutsideWaterFrame": head, "firstAirAtLeast290Frame": air,
        "firstBreathingPlanFrame": plan, "breathingPlanClearedFrame": cleared,
        "ownerArrivalAfterRecoveryFrame": arrival,
        "minimumAirSupply": min([60, terminal["air"]] + [state["air"] for state in states]),
    }
    require(all(data[key] == value for key, value in derived.items()), "recorded air/recovery summary differs from actual frames")
    require(java_float(data["observedNetHealthLoss"]) == net_loss, "native float health-loss aggregate differs")
    safe = data["terminalReason"] == "window_end" and terminal["alive"] and head >= 0 and air >= 0
    require(data["airSafetySucceeded"] is safe and data["normalActivityResumeObserved"] is (arrival >= 0)
            and data["behaviorSuccess"] is (safe and arrival >= 0)
            and data["followTaskCauseDirectlyObserved"] is False, "behavior summary or causal scope differs")
    return {
        "natural_frames": len(frames), "declared_horizon": data["scene"]["frames"],
        "terminal_reason": data["terminalReason"], "native_death_callback_observed": data["nativeDeathCallbackObserved"],
        **derived, "native_float_health_loss": net_loss, "raw_recorded_health_loss": data["observedNetHealthLoss"],
        "air_safety_succeeded": safe, "physical_owner_arrival_after_recovery": arrival >= 0,
        "complete_recovery_business_success": data["behaviorSuccess"],
        "actual_native_damage_callbacks": data["damageCallbacks"],
        "callback_amount_or_cancel_is_not_final_health_commit": True,
        "air_refill_time_not_ai_benefit": True, "follow_task_cause_directly_observed": False,
    }


def inspect(data, scene, contract, digest, original):
    captured = dict(data["contract"])
    captured.setdefault("noninferiorityDelta", None)
    captured["campaign"] = dict(captured["campaign"])
    captured["campaign"].setdefault("noninferiorityDelta", None)
    require(captured == contract and data["contractSha256"] == digest and data["scene"] == scene, "actual air contract/scene differs")
    require(data["schema"] == ("life-recovery-original-v1" if original else "life-recovery-port-v1")
            and data["observationComplete"] is data["ownCleanupComplete"] is data["extraCleanupComplete"] is True
            and not data.get("error") and not data.get("cleanupError"), "air observation or owned-input cleanup incomplete")
    require(data["randomSourcesReset"] is data["statisticalAdmission"] is data["complete14ClassBenchmarkAccepted"] is False,
            "unapproved RNG/statistical scope")
    inputs = data["actualInputs"]
    require(inputs["position"] == scene["primarySpawn"] and inputs["task"] == "touhou_little_maid:idle"
            and inputs["schedule"] == "ALL" and inputs["health"] == 20 and inputs["hunger"] == 20
            and inputs["restrictionRadius"] == 8 and inputs["homeEnabled"] is inputs["sitting"] is inputs["pickup"] is False,
            "actual primary air inputs differ")
    require(inputs["model"] == contract["model"] and inputs["difficulty"] == "NORMAL" and inputs["dayTime"] == 18000
            and inputs["raining"] is inputs["thundering"] is False
            and data["airInputAppliedBeforeJoin"] == data["actualInitialAirSupply"] == 60
            and data["initialNonEmptyItemStackCount"] == data["initialActiveEffectCount"] == 0,
            "actual native air/environment/material input differs")
    owner_inputs(inputs["owner"], original, False)
    require(inputs["owner"]["position"] == scene["ownerPosition"], "controlled owner input differs")
    layout(inputs["layout"], scene["id"], original)
    initial = binding(data["attributeInputs"], inputs["rawInitialNbtBinary"], inputs["rawInitialNBT"])
    native_binary(data["terminalActorBinaryNBT"])
    frames = data["frames"]
    terminal = data["terminalActorState"]
    if data["terminalReason"] == "window_end":
        require(len(frames) == scene["frames"] and terminal["alive"] is True, "missing/padded air frame stream")
    else:
        require(data["terminalReason"] == "maid_death" and data["nativeDeathCallbackObserved"] is True
                and terminal["alive"] is False and terminal["health"] <= 0 and len(frames) < scene["frames"]
                and data["deathOutputsNotExplicitlyDeleted"] is True and data["deathOutputOwnershipVerified"] is False,
                "unverified early terminal was relabelled as native death")
    for index, row in enumerate(frames):
        state = row["airState"]
        require(row["frame"] == index and row["age"] == state["nativeAge"] == index + 1 and row["livingTicks"] == 1
                and state["alive"] is state["registered"] is True and state["worldTime"] == row["gameTime"]
                and (index == 0 or row["gameTime"] == frames[index - 1]["gameTime"] + 1),
                "natural air actor/world stream differs")
        if not original:
            require(row["fullRootAuthorized"] is True and row["legacyTicks"] == 0 and row["actualProfile"] == data["profile"],
                    "component or Legacy substituted for natural full root")
        require(type(state["air"]) is int and isinstance(state["headInWater"], bool)
                and isinstance(state["goingToBreath"], bool), "native air observation type differs")
    initial_water = {"basis": "first_native_end_observation", "head_in_water": frames[0]["airState"]["headInWater"]}
    if original:
        require(initial_water["head_in_water"] is True, "original first native actor update was not underwater")
    else:
        # The normal emergency-follow branch can leave water during the first native update.
        # Bind the initial geometry instead of requiring the successful exit to happen later.
        _, initial_node = native_binary(inputs["rawInitialNbtBinary"])
        tags = initial_node[1][1]
        require(tags[b"Air"] == (2, 60) and tags[b"Pos"][0] == 9
                and tags[b"Pos"][1][0] == 6 and inputs["height"] == 1.5,
                "native initial air/position/standing dimensions differ")
        absolute_position = [struct.unpack(">d", value[1])[0] for value in tags[b"Pos"][1][1] if value[0] == 6]
        require(len(absolute_position) == 3 and all(math.isfinite(value) for value in absolute_position)
                and all((value - relative).is_integer() for value, relative in zip(absolute_position, inputs["position"])),
                "typed native position is not an integer translation of the captured arena input")
        eye_height = java_float(inputs["height"] * java_float(.85))
        eye_y = inputs["position"][1] + eye_height
        cell = (math.floor(inputs["position"][0]), math.floor(eye_y), math.floor(inputs["position"][2]))
        water = next(row for row in inputs["layout"] if (row["x"], row["y"], row["z"]) == cell)
        require(water["registry"] == "minecraft:water" and water["metadata"] == 0 and eye_y < cell[1] + 1,
                "source-backed initial native eye geometry is not inside full water")
        initial_water = {"basis": "typed_initial_position_and_layout_with_source_backed_standing_eye_geometry",
                         "native_getter_before_first_update_observed": False, "relative_eye_y": eye_y,
                         "head_in_water": True, "first_native_end_head_in_water": frames[0]["airState"]["headInWater"]}
    should_open = scene["id"] == "low_air_roof_release" and terminal["nativeAge"] > 40
    require(len(data["events"]) == int(should_open), "missing or additional native air input")
    if should_open:
        event = data["events"][0]
        require(event["frame"] == 40 and event["kind"] == "open_fixed_native_air_outlet"
                and event["phase"] == "START_before_native_world_update", "air outlet input phase differs")
        layout(event["layoutAfter"], scene["id"], original, True)
    return {"same_side_complete_initial_binding": initial, "initial_head_water_evidence": initial_water, "metrics": metrics(data),
            "collection_integrity_is_not_behavior_success": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    parser.add_argument("--duplicated-port-name", action="store_true",
                        help="Read the first V3 collector's explicitly duplicated filename suffix")
    args = parser.parse_args()
    raw = (args.original_tool / "life-condition-mechanisms-contract.json").read_bytes()
    contract, digest = json.loads(raw), sha(raw)
    require(contract["contract"] == "LIFE_CONDITION_MECHANISMS_V3" and contract["noninferiorityDelta"] is None
            and tuple(scene["id"] for scene in contract["mechanisms"]) == MECHANISMS, "unapproved air recovery contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    xml = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, xml, args.allow_additional_port_tests, args.allow_failed_additional_port_tests,
                                "tlmnhqapilot:LifeConditionMechanismPortTests", EXPECTED)
    pilot = args.port_run / "tested-pilot.jar"
    require(hqa["configuration"]["turbo"] == observed["horizonqa_turbo"] == 1
            and observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes())
            and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual native execution artifact/tick contract differs")
    originals = [json.loads(path.read_bytes()) for path in args.original_captures.glob("life-recovery-original-*.json")]
    require(len(originals) == 2 and {row["mechanism"] for row in originals} == set(MECHANISMS), "missing/duplicate original air cases")
    rows = []
    for scene in contract["mechanisms"]:
        original = next(row for row in originals if row["mechanism"] == scene["id"])
        differences = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent,
                                        ORIGINAL, "life-condition-mechanisms-build-v3")
        row = {"mechanism": scene["id"], "original": inspect(original, scene, contract, digest, True),
               "selected_resource_definition_differences": differences, "ports": {}}
        for arm in ("faithful_tlm", "enhanced"):
            path = args.port_run / f"life-recovery-{arm}-{scene['id']}.json"
            if args.duplicated_port_name:
                path = path.with_name(path.name + f"{arm}-{scene['id']}.json")
            port = json.loads(path.read_bytes())
            for field, classes, jar in (("productionArtifact", PRODUCTION, args.candidate_jar),
                    ("spawnInputArtifact", SPAWN, args.candidate_jar), ("observerArtifact", FOLLOW_OBSERVER, pilot),
                    ("airObserverArtifact", AIR_OBSERVER, pilot), ("airProductionArtifact", AIR_PRODUCTION, args.candidate_jar)):
                artifact(port[field], jar, classes)
            checked = inspect(port, scene, contract, digest, False)
            checked["capture_sha256"] = sha(path.read_bytes())
            checked["capture_file"] = path.name
            checked["five_initial_attributes"] = compare_attribute_inputs(original["attributeInputs"], port["attributeInputs"], contract["attributeInputs"])
            checked["actual_relevant_input_differences"] = input_differences(original, port)
            row["ports"][arm] = checked
        rows.append(row)
    result = {"schema": "life-recovery-mechanisms-evidence-v1", "collection_checks_passed": True,
              "hqa_batch": batch, "mechanisms": rows, "contract_sha256": digest,
              "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "original_build_sha256": sha(args.original_build.read_bytes()), "analysis_sha256": sha(Path(__file__).read_bytes()),
              "statistical_verdict": "NOT_EVALUATED", "noninferiority_delta": None, "complete14_accepted": False,
              "limits": ["Native air refill timing and eye-fluid/medium rules are retained as platform inputs, not AI gains.",
                         "Survival/escape, native breathing-plan clearing and physical owner arrival remain separate outcomes.",
                         "Native LOWEST damage callbacks are observations; health commits and later cancellations are not fabricated.",
                         "Censored deaths retain their denominator/raw terminal entity. Unknown native death outputs are not explicitly collected/deleted; ownership and later persistence remain NOT_PROVEN.",
                         "200 partial endpoint and earlier cabinet failures remain; no radius/cooldown/RNG or forced-rescue change.",
                         "Controlled native memory owners are not real network/multiplayer admission; no cross-engine full NBT or statistical/release verdict."]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "mechanisms"}))


if __name__ == "__main__":
    main()
