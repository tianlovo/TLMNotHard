"""Four normal-root terrain/safety/pantry families plus retained approach contrast; calibration is not statistical admission."""

import argparse
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs
from check import require, sha
from compare_activity_competition import semantic_layout
from compare_follow_mechanisms import (
    ORIGINAL_CLASSES, PRODUCTION, SPAWN, input_differences, original_identity, verify_port_results,
)
from compare_port import artifact
from nbt_binding import binary_snapshot

PREFIX = "com.tianluoqaq.tlmnh."
MECHANISMS = ("terrain_circuit", "fire_cliff", "native_creeper", "pantry_near", "pantry_approach")
SUFFIXES = ("TerrainCircuit", "FireCliff", "NativeCreeper", "PantryNear", "PantryApproach")
EXPECTED = {arm + scene for arm in ("faithful", "enhanced") for scene in SUFFIXES}
ORIGINAL = ORIGINAL_CLASSES | {"com.tianluoqaq.tlmnh.reference.sleep." + name for name in (
    "ActivityCompetitionGameTests", "ActivityCompetitionGameTests$Session", "TerrainLifeMechanismGameTests",
    "TerrainLifeMechanismGameTests$Session", "TerrainLifeMechanismGameTests$PantrySession")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task." + name for name in (
    "MaidPanicTask", "MaidRunAwayTask", "MaidCheckRateTask", "MaidMoveToBlockTask",
    "MaidStealEdibleMoveBlockTask", "MaidStealEdibleUseTask")}
ORIGINAL.add("com.github.tartaricacid.touhoulittlemaid.event.EntityJoinWorldEvent")
FOLLOW_OBSERVER = [PREFIX + "qa.FollowMechanismPortTests" + suffix for suffix in ("", "$Collector")]
FOLLOW_OBSERVER += [PREFIX + "qa.SleepAttributePortSnapshot"]
PANTRY_OBSERVER = [PREFIX + "qa.ActivityCompetitionPortTests" + suffix for suffix in ("", "$Collector")] + FOLLOW_OBSERVER
TERRAIN_OBSERVER = [PREFIX + "qa.TerrainLifeMechanismPortTests" + suffix for suffix in ("", "$Collector", "$PantryCollector")]
TERRAIN_OBSERVER += [PREFIX + "qa.NaturalRangedPairTests"]
TERRAIN_PRODUCTION = [PREFIX + name for name in ("common.ai.navigation.MaidPathNavigator",
    "common.ai.enhanced.EnhancedLifeController", "common.ai.enhanced.EnhancedPanicController", "common.life.MaidHomeArea",
    "common.ai.MaidCreeperAvoidance", "common.ai.MaidCreeperAvoidance$AvoidMaidGoal")]
TERRAIN_PRODUCTION += ["com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task." + name for name in (
    "MaidCheckRateTask", "MaidMoveToBlockTask", "MaidStealEdibleMoveBlockTask", "MaidStealEdibleUseTask")]


def native_binary(record):
    return binary_snapshot(record, record["rawSNBT"])


def binding(attribute, paired, legacy):
    a, x = native_binary(attribute["rawEntityNbtBinary"])
    b, y = native_binary(paired)
    require(x == y, "same-side complete typed initial structure differs")
    return {"typed_structure_equal": True, "binary_sha256": {"attribute": sha(a), "paired": sha(b)},
            "retained_legacy_snbt": {"attribute": attribute["rawEntityNBT"], "paired": legacy},
            "legacy_text_equals_own_binary_label": {
                "attribute": attribute["rawEntityNBT"] == attribute["rawEntityNbtBinary"]["rawSNBT"],
                "paired": legacy == paired["rawSNBT"]},
            "differing_legacy_label_binding": "NOT_PROVEN; no SNBT parsing/sorting",
            "scope": "two complete native binary snapshots, same actor/engine; tag types/list order/float bits retained"}


def enclosure_cells(size, height):
    return {(x, y, z): "glass" for x in range(size) for y in range(1, height) for z in range(size)
            if x in (0, size - 1) or z in (0, size - 1) or y == height - 1}


def expected_terrain(mechanism):
    cells = {(x, 0, z): "stone" for x in range(33) for z in range(33)}
    if mechanism == "terrain_circuit":
        cells.update({(x, 1, z): "stone_slab" for x in range(10, 14) for z in range(10, 15)})
        cells.update({(x, 1, z): "stone" for x in range(14, 25) for z in range(10, 15)})
        cells.update({(x, 1, z): "stone" for x in range(18, 25) for z in range(15, 20)})
        cells.update({(x, y, z): "stone" for x in range(16, 20) for y in range(2, 5) for z in (11, 13)})
    elif mechanism == "fire_cliff":
        cells.update({(x, 8, z): "stone" for x in range(33) for z in range(33)
                      if not (10 <= x <= 14 and 8 <= z <= 16)})
        cells.update({(x, 9, z): "fire" for x in (5, 6) for z in range(11, 14)})
    cells.update(enclosure_cells(33, 12))
    return cells


def terrain_layout(rows, mechanism):
    result = {}
    for row in rows:
        point = tuple(row[k] for k in ("x", "y", "z"))
        require(point not in result and all(type(n) is int for n in point), "duplicate/noninteger terrain cell")
        registry = row["registry"]
        require(registry in ("minecraft:stone", "minecraft:stone_slab", "minecraft:fire", "minecraft:glass"), "unmapped terrain block")
        name = registry.split(":")[1]
        if "metadata" in row:
            require(row["metadata"] == 0, "unmapped native terrain metadata")
        if name == "stone_slab" and "rawBlockState" in row:
            require(row["rawBlockState"] == "Block{minecraft:stone_slab}[type=bottom,waterlogged=false]",
                    "unmapped original slab type/fluid state")
        if name == "glass" and "rawBlockState" in row:
            require(row["rawBlockState"] == "Block{minecraft:glass}", "unmapped original enclosure state")
        result[point] = name
    require(result == expected_terrain(mechanism), "actual semantic terrain blueprint differs")


def pantry_layout(rows):
    boundary, interior = {}, []
    for row in rows:
        if row["registry"] != "minecraft:glass":
            interior.append(row)
            continue
        point = tuple(row[key] for key in ("x", "y", "z"))
        require(point not in boundary and all(type(n) is int for n in point)
                and row["semantic"] == "glass" and row["metadata"] == 0, "invalid/duplicate pantry enclosure")
        if "rawBlockState" in row:
            require(row["rawBlockState"] == "Block{minecraft:glass}", "unknown pantry enclosure state")
        boundary[point] = "glass"
    require(boundary == enclosure_cells(25, 8), "pantry enclosure differs or has a gap")
    expected = {(x, 0, z, "stone") for x in range(25) for z in range(25)} | {(12, 1, 12, "snack_cabinet"), (12, 2, 12, "cake")}
    require(semantic_layout(interior) == expected, "actual pantry interior differs")


def owner_inputs(owner, original, survival):
    require(owner["class"] == ("net.minecraft.server.level.ServerPlayer" if original else "net.minecraft.entity.player.EntityPlayerMP"),
            "controlled owner is not the declared native player")
    require(owner["worldRegistered"] is owner["worldPlayerList"] is owner["connectedPlayerRegistry"]
            is owner["nativeHandlerPresent"] is owner["memoryChannelOpen"] is True and owner["realNetworkLogin"] is False,
            "native player registration/connection changed")
    if original:
        require(owner["uuidOwnerLookup"] is True, "original UUID owner lookup missing")
    else:
        require(owner["nativeLoginInitializationComplete"] is True, "normal native SCM login chain incomplete")
    require(owner["mode"] == ("survival" if survival else "creative") and owner["mayFly"] is owner["flying"] is True
            and owner["disableDamage"] is (not survival), "actual native owner mode/protection differs")
    native_binary(owner["binaryNBT"])


def metrics(data, scene):
    frames = data["frames"]
    positions = [row["position"] for row in frames]
    distances = [math.dist(a, b) for a, b in zip(positions, positions[1:])]
    result = {"observed_frames": len(frames), "terminal_reason": data.get("terminalReason", "window_end"),
              "behavior_success": data.get("behaviorSuccess"), "arrival_frame": data.get("arrivalFrame"),
              "actual_sampled_path_length": sum(distances),
              "large_displacements": [{"frame": i + 1, "distance": length} for i, length in enumerate(distances) if length > 1.5],
              "large_displacements_are_not_method_verified_teleports": True,
              "burning_frames": sum(row.get("burning", False) for row in frames),
              "minimum_health": min([row["health"] for row in frames] + [data.get("terminalActor", {}).get("health", 20)]),
              "actual_damage_candidates": data.get("damageEvents", []),
              "waypoint_events": [row for row in data.get("events", []) if row["kind"] == "owner_waypoint_reached"],
              "actual_astar_searches": frames[-1].get("actualAStarSearches") if frames else None,
              "actual_budget_deferrals": frames[-1].get("budgetDeferrals") if frames else None,
              "unobserved_metrics_remain_unknown": True}
    if scene["id"].startswith("pantry"):
        result.update(first_bite_frame=data.get("reactiveTriggerFrame"), release_frame=data.get("reactiveReleaseFrame"),
                      natural_placement_frame=data.get("naturalPlacementFrame"),
                      first_bite_exercised=data.get("reactiveTriggerFrame", -1) >= 0,
                      placement_exercised=data.get("naturalPlacementFrame", -1) >= 0,
                      final_consumed_mouths=frames[-1]["cakeBitesConsumed"] if frames else None,
                      final_actor_states={"maid": frames[-1]["maid"], "second": frames[-1]["second"]} if frames else None)
    if scene["id"] == "native_creeper":
        result.update(first_panic_frame=next((row["frame"] for row in frames if "PANIC" in row["activeActivities"].upper()), None),
                      first_creeper_target_frame=next((row["frame"] for row in frames if row.get("creeperTarget")), None),
                      maximum_swell=max((row.get("creeperSwelling", 0) for row in frames), default=0),
                      explosion_damage_candidates=[row for row in data.get("damageEvents", []) if row["explosion"]])
    return result


def inspect(data, scene, contract, contract_hash, original):
    captured = dict(data["contract"])
    captured.setdefault("noninferiorityDelta", None)
    captured["campaign"] = dict(captured["campaign"])
    captured["campaign"].setdefault("noninferiorityDelta", None)
    require(captured == contract and data["contractSha256"] == contract_hash and data["scene"] == scene, "captured contract/scene differs")
    require(data["observationComplete"] is data["ownCleanupComplete"] is True
            and not data.get("error") and not data.get("cleanupError"), "collection/cleanup failed")
    require(data["statisticalAdmission"] is data["complete14ClassBenchmarkAccepted"] is data["randomSourcesReset"] is False,
            "unapproved statistical/root scope")
    require(data["schema"] == ("terrain-life-original-v1" if original else "terrain-life-port-v1"), "wrong collector schema")
    actual = data["actualInputs"]
    pantry = scene["id"].startswith("pantry")
    expected_position = scene["primarySpawn"] if pantry else scene["maidSpawn"]
    require(actual["position"] == expected_position and actual["task"] == "touhou_little_maid:idle"
            and actual["schedule"] == "ALL" and actual["health"] == 20 and actual["sitting"] is False
            and actual["hunger"] == (0 if pantry else 20) and actual["homeEnabled"] is scene.get("homeEnabled", False),
            "actual initial relevant actor inputs differ")
    require(actual["model"] == contract["model"] and actual["pickup"] is False
            and actual["difficulty"] == contract["difficulty"] and actual["dayTime"] == contract["dayTime"]
            and actual["raining"] is actual["thundering"] is False, "actor/world preparation differs")
    owner_inputs(actual["owner"], original, scene["id"] == "native_creeper")
    primary_binding = binding(data["attributeInputs"], actual["rawInitialNbtBinary"], actual["rawInitialNBT"])
    if pantry:
        pantry_layout(actual["layout"])
        second = data["secondInitial"]
        require(second["position"] == scene["secondarySpawn"], "secondary initial position differs")
        secondary_binding = binding(data["secondAttributeInputs"], second["binaryNBT"], second["rawNBT"])
    else:
        terrain_layout(actual["layout"], scene["id"])
        secondary_binding = None
    if scene["id"] == "native_creeper":
        require(data["ownerSpawnProtectionAtThreat"] <= 0, "actual owner spawn protection remained active")
        threat = data["threatInitial"]
        require(threat["class"] == ("net.minecraft.world.entity.monster.Creeper" if original else "net.minecraft.entity.monster.EntityCreeper")
                and threat["registered"] is threat["alive"] is True and threat["position"] == scene["threatSpawn"],
                "native Creeper input replaced or changed")
        native_binary(threat["binaryNBT"])
        expected_goal = ("net.minecraft.world.entity.ai.goal.AvoidEntityGoal" if original
                         else PREFIX + "common.ai.MaidCreeperAvoidance$AvoidMaidGoal")
        require(sum(goal["class"] == expected_goal and goal["priority"] == 1
                    for goal in data["actualCreeperGoals"]) == 1, "normal join source avoidance goal missing or duplicated")
    frames = data["frames"]
    reason = data.get("terminalReason", "window_end")
    require(reason in ("window_end", "maid_death", "owner_death"), "unknown terminal reason")
    require(len(frames) == scene["frames"] if reason == "window_end" else len(frames) < scene["frames"],
            "unexpected frame count; no padding or death deletion permitted")
    for i, row in enumerate(frames):
        require(row["frame"] == i and row["age"] == i + 1 and row["livingTicks"] == 1
                and (i == 0 or row["gameTime"] == frames[i - 1]["gameTime"] + 1), "natural frame stream changed")
        if not original:
            require(row["fullRootAuthorized"] is True and row["legacyTicks"] == 0 and row["actualProfile"] == data["profile"],
                    "component/Legacy or unauthorized root entered capture")
    return {"metrics": metrics(data, scene), "same_side_initial_binding": primary_binding,
            "secondary_initial_binding": secondary_binding,
            "unknown_nbt_and_platform_fields_retained": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    args = parser.parse_args()
    contract_bytes = (args.original_tool / "terrain-life-mechanisms-contract.json").read_bytes()
    contract = json.loads(contract_bytes)
    require(contract["contract"] == "TERRAIN_LIFE_MECHANISMS_V2" and contract["repetitions"] == 1
            and contract["noninferiorityDelta"] is None and contract["campaign"]["status"] == "CALIBRATION_ONLY"
            and tuple(scene["id"] for scene in contract["mechanisms"]) == MECHANISMS, "unapproved calibration contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    xml = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, xml, args.allow_additional_port_tests, args.allow_failed_additional_port_tests,
                                "tlmnhqapilot:TerrainLifeMechanismPortTests", EXPECTED)
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes())
            and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual artifact binding changed")
    originals = [json.loads(path.read_bytes()) for path in args.original_captures.glob("terrain-life-original-*.json")]
    require(len(originals) == 5 and {data["mechanism"] for data in originals} == set(MECHANISMS), "missing/duplicate original captures")
    scenes = []
    for scene in contract["mechanisms"]:
        original = next(data for data in originals if data["mechanism"] == scene["id"])
        definitions = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent,
                                        ORIGINAL, "terrain-life-mechanisms-build-v1")
        require(json.loads(args.original_build.read_bytes())["contractSha256"] == sha(contract_bytes), "original manifest contract differs")
        row = {"mechanism": scene["id"], "original": inspect(original, scene, contract, sha(contract_bytes), True),
               "original_resource_definition_differences": definitions, "ports": {}}
        for profile in ("faithful_tlm", "enhanced"):
            data = json.loads((args.port_run / f"terrain-life-{profile}-{scene['id']}.json").read_bytes())
            for field, classes, jar in (("productionArtifact", PRODUCTION, args.candidate_jar), ("spawnInputArtifact", SPAWN, args.candidate_jar),
                ("observerArtifact", PANTRY_OBSERVER if scene["id"].startswith("pantry") else FOLLOW_OBSERVER, pilot),
                ("terrainObserverArtifact", TERRAIN_OBSERVER, pilot), ("terrainProductionArtifact", TERRAIN_PRODUCTION, args.candidate_jar)):
                artifact(data[field], jar, classes)
            result = inspect(data, scene, contract, sha(contract_bytes), False)
            result["five_sourced_attribute_pair"] = compare_attribute_inputs(original["attributeInputs"], data["attributeInputs"], contract["attributeInputs"])
            result["relevant_input_comparison"] = input_differences(original, data)
            result["relevant_input_comparison"]["step_height_raw"] = {
                "original": original.get("actualStepHeight"), "port": data.get("actualStepHeight"),
                "pantry_not_separately_observed": scene["id"].startswith("pantry"),
            }
            result["actual_initial_inputs"] = {"original": original["actualInputs"], "port": data["actualInputs"]}
            row["ports"][profile] = result
        scenes.append(row)
    result = {"schema": "terrain-life-mechanisms-calibration-v1", "calibration_collection_integrity_passed": True,
              "hqa_batch": batch, "scenes": scenes, "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "original_build_sha256": sha(args.original_build.read_bytes()), "contract_sha256": sha(contract_bytes),
              "analysis_inputs_sha256": {name: sha(Path(__file__).with_name(name).read_bytes()) for name in (
                  "compare_terrain_life_mechanisms.py", "compare_activity_competition.py", "compare_follow_mechanisms.py", "compare_port.py", "attribute_inputs.py", "nbt_binding.py", "check.py")},
              "campaign": contract["campaign"], "statistical_verdict": "NOT_EVALUATED", "noninferiority_delta": None,
              "all_behavior_success_inferred_from_collection": False, "complete_14_class_benchmark_accepted": False,
              "real_network_multiplayer_accepted": False, "full_release_passed": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("scenes", "campaign")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
