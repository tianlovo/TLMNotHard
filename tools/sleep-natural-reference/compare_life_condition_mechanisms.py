"""Strict collection checks and bounded outcomes for cabinet conditions and shared-bed handoff."""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs
from check import require, sha
from compare_follow_mechanisms import PRODUCTION, SPAWN, input_differences, original_identity, verify_port_results
from compare_port import artifact
from compare_terrain_life_mechanisms import (
    ORIGINAL as TERRAIN_ORIGINAL, PANTRY_OBSERVER, TERRAIN_OBSERVER, TERRAIN_PRODUCTION,
    binding, enclosure_cells, native_binary, owner_inputs,
)

PREFIX = "com.tianluoqaq.tlmnh."
MECHANISMS = ("cabinet_conditions", "shared_bed_handoff")
EXPECTED = {arm + case for arm in ("faithful", "enhanced") for case in ("CabinetConditions", "SharedBedHandoff")}
ORIGINAL = TERRAIN_ORIGINAL | {PREFIX + "reference.sleep.LifeConditionMechanismGameTests" + suffix for suffix in ("", "$Session")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid." + name for name in (
    "entity.ai.brain.task.MaidBedTask", "block.BlockMaidBed", "entity.ai.edible.CakeEdible",
    "entity.favorability.FavorabilityManager", "entity.favorability.Type")}
OBSERVER = [PREFIX + "qa.LifeConditionMechanismPortTests" + suffix for suffix in ("", "$Collector")]
LIFE_PRODUCTION = [PREFIX + name for name in (
    "common.life.TileEntityMaidBed", "common.ai.faithful.MaidCakeAction", "common.ai.faithful.MaidModernLife")]


def add_shell(cells, low_x, high_x, low_z, high_z, top):
    cells.update({(x, y, z): "glass" for x in range(low_x, high_x + 1) for y in range(1, top + 1)
                  for z in range(low_z, high_z + 1)
                  if x in (low_x, high_x) or z in (low_z, high_z) or y == top})


def expected_layout(mechanism, phase="initial"):
    cells = {(x, 0, z): "stone" for x in range(25) for z in range(25)}
    cells.update(enclosure_cells(25, 8))
    if mechanism == "cabinet_conditions":
        add_shell(cells, 8, 16, 8, 16, 5)
        add_shell(cells, 10, 14, 10, 14, 4)
        if phase != "initial":
            cells[12, 1, 12] = "snack_cabinet"
        if phase == "opened":
            del cells[10, 1, 12]
            del cells[10, 2, 12]
    else:
        cells[12, 1, 12] = "bed_head"
        cells[12, 1, 13] = "bed_foot"
    return cells


def layout(rows, mechanism, original, phase="initial"):
    result = {}
    domain = "touhou_little_maid" if original else "tlmnh"
    for row in rows:
        point = tuple(row[key] for key in ("x", "y", "z"))
        require(all(type(value) is int for value in point) and point not in result, "duplicate/noninteger layout cell")
        registry = row["registry"]
        require(registry in {"minecraft:stone", "minecraft:glass", domain + ":maid_bed", domain + ":snack_cabinet"},
                "unexpected native initial/dynamic block")
        name = registry.split(":", 1)[1]
        if name == "maid_bed":
            require(point in ((12, 1, 12), (12, 1, 13)), "extra native bed input")
            name = "bed_head" if point[2] == 12 else "bed_foot"
        if original:
            expected = ("Block{minecraft:" + name + "}" if name in ("stone", "glass")
                        else "Block{touhou_little_maid:snack_cabinet}[facing=north,type=0]" if name == "snack_cabinet"
                        else "Block{touhou_little_maid:maid_bed}[facing=north,occupied=false,part="
                        + ("head" if name == "bed_head" else "foot") + "]")
            require(row["rawBlockState"] == expected, "actual original block state differs")
        else:
            require(row["metadata"] == (10 if name == "bed_head" else 2 if name == "bed_foot" else 0),
                    "actual native block metadata differs")
        result[point] = name
    require(result == expected_layout(mechanism, phase), "complete life arena blueprint differs")


def tile_input(row, present):
    require(row["present"] is present, "native tile presence differs")
    if present:
        native_binary(row["binaryNBT"])


def metrics(data):
    frames = data["frames"]
    common = {"frames": len(frames), "collector_behavior_success": data.get("behaviorSuccess"),
              "collection_complete_is_not_behavior_success": True, "resource_assertions_passed": data.get("resourceAssertionsPassed")}
    if data["mechanism"] == "cabinet_conditions":
        placed = data.get("naturalPlacementFrame", -1)
        common.update(natural_placement_frame=placed, open_to_placement_ticks=placed - 800 if placed >= 800 else None,
                      final_primary_native_consumes=frames[-1]["primaryConsumes"],
                      final_secondary_native_consumes=frames[-1]["secondaryConsumes"],
                      ambiguous_shared_consumes=frames[-1].get("ambiguousSharedConsumes", 0),
                      final_backpack_cakes=frames[-1]["backpackCake"], final_world_mouths=frames[-1]["worldCakeRemainingMouths"],
                      final_consumed_mouths=frames[-1]["cakeBitesConsumed"], negative_window_frames=800,
                      exact_native_consume_events=data["consumeEvents"], source_attribution_limits_retained=True)
    else:
        release, successor = data.get("releaseCommandFrame", -1), data.get("successorSleepFrame", -1)
        common.update(first_sleep_frame=data.get("firstSleepFrame"), release_command_frame=release,
                      first_awake_frame=data.get("firstAwakeFrame"), successor_sleep_frame=successor,
                      release_to_successor_sleep_ticks=successor - release if release >= 0 and successor >= release else None,
                      first_occupant_uuid=data.get("firstOccupantUuid"),
                      maximum_simultaneous_sleepers=max(int(row["primarySleeping"]) + int(row["secondarySleeping"]) for row in frames),
                      source_has_per_actor_bed_lease_api=False)
    return common


def inspect(data, scene, contract, digest, original):
    captured = dict(data["contract"])
    captured.setdefault("noninferiorityDelta", None)
    captured["campaign"] = dict(captured["campaign"])
    captured["campaign"].setdefault("noninferiorityDelta", None)
    require(captured == contract and data["contractSha256"] == digest and data["scene"] == scene, "actual contract/scene differs")
    require(data["schema"] == ("life-condition-original-v1" if original else "life-condition-port-v1")
            and data["observationComplete"] is data["ownCleanupComplete"] is True
            and not data.get("error") and not data.get("cleanupError"), "collection or cleanup incomplete")
    require(data["randomSourcesReset"] is data["statisticalAdmission"] is data["complete14ClassBenchmarkAccepted"] is False,
            "unapproved RNG/statistical scope")
    inputs = data["actualInputs"]
    require(inputs["position"] == scene["primarySpawn"] and inputs["task"] == "touhou_little_maid:idle"
            and inputs["schedule"] == scene["initialSchedule"] and inputs["health"] == 20
            and inputs["hunger"] == scene["initialHunger"] and inputs["restrictionRadius"] == scene["initialHomeRadius"]
            and inputs["homeEnabled"] is True and inputs["sitting"] is inputs["pickup"] is False, "actual primary inputs differ")
    require(inputs["model"] == contract["model"] and inputs["difficulty"] == "NORMAL" and inputs["dayTime"] == 18000
            and inputs["raining"] is inputs["thundering"] is False, "actual native environment differs")
    owner_inputs(inputs["owner"], original, False)
    require(inputs["owner"]["position"] == scene["ownerPosition"], "owner input differs")
    layout(inputs["layout"], scene["id"], original)
    primary = binding(data["attributeInputs"], inputs["rawInitialNbtBinary"], inputs["rawInitialNBT"])
    second = data["secondInitial"]
    require(second["position"] == scene["secondarySpawn"], "secondary position differs")
    second_snbt = second["rawNBT"] if original else second["binaryNBT"]["rawSNBT"]
    secondary = binding(data["secondAttributeInputs"], second["binaryNBT"], second_snbt)
    pantry = scene["id"] == "cabinet_conditions"
    tile_input(data["initialLifeTileInput"], not pantry)
    item = None
    if pantry:
        raw, decoded = native_binary(data["initialCakeInput"])
        fields = decoded[1][1]
        tag = fields[b"tag"]
        require(data["initialCakeItemRegistry"] == "minecraft:cake" and data["initialCakeCount"] == 1
                and data["initialCakeDamage"] == 0 and tag[0] == 10
                and tag[1][b"LifeConditionInput"] == (3, 201)
                and tag[1][b"UnknownCakePayload"] == (11, (7, -17, 65537)), "protected full item input differs")
        item = {"binary_sha256": sha(raw), "opaque_extra_fields_preserved": True, "mapped_payload_exact": True}
    frames = data["frames"]
    require(len(frames) == scene["frames"], "missing or padded natural frame stream")
    for index, row in enumerate(frames):
        require(row["frame"] == index and row["age"] == row["maid"]["age"] == row["second"]["age"] == index + 1
                and row["livingTicks"] == 1 and (index == 0 or row["gameTime"] == frames[index - 1]["gameTime"] + 1),
                "natural actor/world update stream differs")
        if not original:
            require(row["fullRootAuthorized"] is True and row["legacyTicks"] == 0 and row["actualProfile"] == data["profile"],
                    "component or Legacy substituted for full root")
        if pantry:
            require(row["phase"] == ("no_cabinet" if index < 200 else "closed_cabinet" if index < 800 else "opened_cabinet")
                    and row["cabinetPresent"] is (index >= 200) and row["gateClosed"] is (index < 800), "native input phase differs")
            require(row["finiteMouthBalance"] == row["cakeBitesConsumed"] + row["worldCakeRemainingMouths"] + row["backpackCake"] * 7 == 7
                    and 0 <= row["nativeConsumeDelta"] <= 2, "finite actual-callback mouth balance differs")
            if index < 800:
                require(row["protectedCakeInputUnchanged"] is True and row["backpackCake"] == 1
                        and row["worldCakePresent"] is False and row["cakeBitesConsumed"] == 0, "negative-window protected input changed")
        else:
            require(row["bedComplete"] is True and not (row["primarySleeping"] and row["secondarySleeping"]), "native bed or unique sleeper invariant failed")
            if not original:
                require(not (row["primaryBedOwned"] and row["secondaryBedOwned"])
                        and (not row["primarySleeping"] or row["primaryBedOwned"])
                        and (not row["secondarySleeping"] or row["secondaryBedOwned"]), "native bed lease ownership differs")
    if pantry:
        require([(event["frame"], event["kind"]) for event in data["events"]] == [
            (200, "place_native_empty_cabinet_inside_existing_closed_shell"), (800, "open_two_high_native_gate")], "native cabinet event boundaries differ")
        for event in data["events"]:
            require(event["phase"] == "START_before_native_world_update", "input changed completed END observation")
            layout(event["layoutAfter"], scene["id"], original, "closed" if event["frame"] == 200 else "opened")
            tile_input(event["tileAfter"], True)
        counts = {index: 0 for index in range(scene["frames"])}
        for event in data["consumeEvents"]:
            require(event["phase"] == "native_consume_sound_callback" and 800 <= event["frame"] < scene["frames"], "consume event outside permitted native phase")
            counts[event["frame"]] += 1
        require(all(row["nativeConsumeDelta"] == counts[row["frame"]] for row in frames), "mouths were inferred without native consume callbacks")
    elif data.get("releaseCommandFrame", -1) >= 0:
        require(len(data["events"]) == 1, "unexpected bed input command")
        event = data["events"][0]
        require(event["kind"] == "first_occupant_normal_all_and_wait" and event["phase"] == "START_before_native_world_update"
                and event["frame"] == data["firstSleepFrame"] + 40 == data["releaseCommandFrame"]
                and event["triggerCompletedFrame"] == event["frame"] - 1, "bed released before a real first-sleep window")
    return {"same_side_complete_primary_binding": primary, "same_side_complete_secondary_binding": secondary,
            "initial_item_mapping": item, "metrics": metrics(data), "business_success_kept_separate": data["behaviorSuccess"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    args = parser.parse_args()
    raw_contract = (args.original_tool / "life-condition-mechanisms-contract.json").read_bytes()
    contract, digest = json.loads(raw_contract), sha(raw_contract)
    require(contract["contract"] == "LIFE_CONDITION_MECHANISMS_V1" and contract["noninferiorityDelta"] is None
            and tuple(scene["id"] for scene in contract["mechanisms"]) == MECHANISMS, "unapproved life-condition contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    xml = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, xml, args.allow_additional_port_tests, args.allow_failed_additional_port_tests,
                                "tlmnhqapilot:LifeConditionMechanismPortTests", EXPECTED)
    pilot = args.port_run / "tested-pilot.jar"
    require(hqa["configuration"]["turbo"] == observed["horizonqa_turbo"] == 1
            and observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes())
            and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual execution artifact/tick contract differs")
    originals = [json.loads(path.read_bytes()) for path in args.original_captures.glob("life-condition-original-*.json")]
    require(len(originals) == 2 and {row["mechanism"] for row in originals} == set(MECHANISMS), "missing/duplicate original cases")
    rows = []
    for scene in contract["mechanisms"]:
        original = next(row for row in originals if row["mechanism"] == scene["id"])
        differences = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent,
                                        ORIGINAL, "life-condition-mechanisms-build-v1")
        row = {"mechanism": scene["id"], "original": inspect(original, scene, contract, digest, True),
               "selected_resource_definition_differences": differences, "ports": {}}
        for arm in ("faithful_tlm", "enhanced"):
            path = args.port_run / f"life-condition-{arm}-{scene['id']}.json"
            port = json.loads(path.read_bytes())
            for field, classes, jar in (("productionArtifact", PRODUCTION, args.candidate_jar), ("spawnInputArtifact", SPAWN, args.candidate_jar),
                    ("observerArtifact", PANTRY_OBSERVER, pilot), ("terrainObserverArtifact", TERRAIN_OBSERVER, pilot),
                    ("terrainProductionArtifact", TERRAIN_PRODUCTION, args.candidate_jar),
                    ("lifeConditionObserverArtifact", OBSERVER, pilot), ("lifeConditionProductionArtifact", LIFE_PRODUCTION, args.candidate_jar)):
                artifact(port[field], jar, classes)
            checked = inspect(port, scene, contract, digest, False)
            checked["capture_sha256"] = sha(path.read_bytes())
            checked["five_initial_attributes_primary"] = compare_attribute_inputs(original["attributeInputs"], port["attributeInputs"], contract["attributeInputs"])
            checked["five_initial_attributes_secondary"] = compare_attribute_inputs(original["secondAttributeInputs"], port["secondAttributeInputs"], contract["attributeInputs"])
            checked["actual_relevant_input_differences"] = input_differences(original, port)
            row["ports"][arm] = checked
        rows.append(row)
    result = {"schema": "life-condition-mechanisms-evidence-v1", "collection_checks_passed": True, "hqa_batch": batch,
              "mechanisms": rows, "contract_sha256": digest, "candidate_sha256": sha(args.candidate_jar.read_bytes()),
              "pilot_sha256": sha(pilot.read_bytes()), "original_build_sha256": sha(args.original_build.read_bytes()),
              "analysis_sha256": sha(Path(__file__).read_bytes()), "calibration_used_as_formal": False,
              "noninferiority_delta": None, "statistical_verdict": "NOT_EVALUATED", "complete14_accepted": False,
              "limits": ["Collection integrity does not make an untriggered placement/handoff a passed behavior.",
                         "Native sound callbacks plus actual block/inventory state bind finite mouths; ambiguous original positions are not credited to both actors.",
                         "Original occupied flag and sleeping anchors differ from target explicit bed leases; both raw mechanisms remain visible.",
                         "This new cabinet/bed contract does not close the retained200 F pantry sleep-resume failure or rewrite its different input geometry.",
                         "No cross-engine full-NBT equality, independent statistical result or release acceptance is claimed."]}
    outcomes = [{"mechanism": row["mechanism"], "profile": "original", "success": row["original"]["business_success_kept_separate"]}
                for row in rows]
    outcomes.extend({"mechanism": row["mechanism"], "profile": arm, "success": checked["business_success_kept_separate"]}
                    for row in rows for arm, checked in row["ports"].items())
    result["actual_business_outcomes"] = outcomes
    result["all_observed_business_mechanisms_succeeded"] = all(row["success"] for row in outcomes)
    result["untriggered_or_unsuccessful_cases"] = [row for row in outcomes if not row["success"]]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "mechanisms"}))


if __name__ == "__main__":
    main()
