"""核原O与完整F/N的三个自然跟随/等待探索输入及轨迹；不授非劣效或十四类测评准入"""

import argparse
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs, measurement
from check import UPSTREAM, require, sha
from compare_port import artifact
from nbt_binding import binary_snapshot, compare_same_side_nbt

MECHANISMS = ("temporary_obstacle", "owner_reversal", "waiting_owner_food")
ROOT = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid"
ORIGINAL_CLASSES = {
    ROOT, "com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos",
    "com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidBrain",
    "com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask",
    "com.github.tartaricacid.touhoulittlemaid.entity.task.TaskIdle",
    "com.tianluoqaq.tlmnh.reference.sleep.SleepNaturalGameTests",
    "com.tianluoqaq.tlmnh.reference.sleep.SleepAttributeSnapshot",
    "com.tianluoqaq.tlmnh.reference.sleep.FollowMechanismGameTests",
    "com.tianluoqaq.tlmnh.reference.sleep.FollowMechanismGameTests$Session",
}
PRODUCTION = [ROOT, "com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain",
              "com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain",
              "com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor",
              "com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime",
              "com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator"]
OBSERVER = ["com.tianluoqaq.tlmnh.qa.FollowMechanismPortTests" + suffix for suffix in ("", "$Collector")]
OBSERVER += ["com.tianluoqaq.tlmnh.qa.SleepAttributePortSnapshot"]
SPAWN = ["com.tianluoqaq.tlmnh.common.development.DeterministicMaidSpawn",
         "com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel"]


def vector(value):
    require(isinstance(value, list) and len(value) == 3 and all(
        isinstance(n, (int, float)) and not isinstance(n, bool) and math.isfinite(n) for n in value),
        "invalid captured position/motion")
    return value


def owner_facts(owner, profile, full=False):
    expected = "net.minecraft.server.level.ServerPlayer" if profile == "original" else "net.minecraft.entity.player.EntityPlayerMP"
    require(owner["class"] == expected and owner["realNetworkLogin"] is False,
            "owner is a different actor class, FakePlayer/mock or mislabeled network profile")
    require(owner["worldRegistered"] is owner["worldPlayerList"] is owner["nativeHandlerPresent"]
            is owner["memoryChannelOpen"] is True,
            "native controlled owner registration/connection differs")
    if profile == "original":
        require(owner["connectedPlayerRegistry"] is True and owner["uuidOwnerLookup"] is True,
                "original maid owner needs the actual PlayerList UUID index")
    else:
        require(owner["connectedPlayerRegistry"] is True,
                "port controlled owner lacks actual SCM global registration")
    require(owner["alive"] is True and owner["spectator"] is owner["sneaking"] is owner["invisible"] is False,
            "controlled owner state changed")
    require(owner["mode"].upper() == "CREATIVE" and owner["flying"] is owner["mayFly"] is owner["disableDamage"] is True,
            "controlled owner creative/flying protection changed")
    vector(owner["position"]); vector(owner["eyePosition"])
    for key in ("eyeHeight", "width", "height"):
        require(isinstance(owner[key], (int, float)) and math.isfinite(owner[key]) and owner[key] > 0,
                "actual owner dimensions missing")
    if full:
        binary_snapshot(owner["binaryNBT"], owner["rawNBT"])


def expected_layout(contract, blocked=False):
    width, height, depth = contract["layoutVolume"]
    result = {(x, 0, z, "stone") for x in range(width) for z in range(depth)}
    wall = contract["corridorWalls"]
    result |= {(x, y, z, "stone") for x in range(wall["x"][0], wall["x"][1] + 1)
               for y in range(wall["y"][0], wall["y"][1] + 1) for z in wall["z"]}
    if blocked:
        gate = contract["temporaryObstacle"]
        result |= {(gate["x"], y, z, "stone") for y in range(gate["y"][0], gate["y"][1] + 1)
                   for z in range(gate["z"][0], gate["z"][1] + 1)}
    return result


def layout(value):
    require(isinstance(value, list), "layout missing")
    result = set()
    for cell in value:
        require(cell["registry"] == "minecraft:stone" and cell["semantic"] == "stone"
                and cell.get("metadata", 0) == 0, "unknown actual block or metadata")
        key = tuple(cell[k] for k in ("x", "y", "z", "semantic"))
        require(key not in result, "duplicate layout cell")
        result.add(key)
    return result


def check_event_schedule(scene, events):
    require(len(events) == len(scene["events"]), "missing/duplicate scene event")
    for spec, actual in zip(scene["events"], events):
        require(all(actual.get(key) == value for key, value in spec.items())
                and actual["phase"] == "END_after_recorded_frame", "event input/timing/phase changed")


def inspect(data, contract, contract_hash, profile):
    require(data["profile"] == profile and data["contract"] == contract and data["contractSha256"] == contract_hash,
            "actual contract/profile changed")
    require(data["observationComplete"] is data["ownCleanupComplete"] is True and not data.get("error"),
            "capture incomplete or cleanup failed")
    require(data["benchmarkVerdict"] == "NOT_EVALUATED" and data["statisticalAdmission"] is False
            and data["complete14ClassBenchmarkAccepted"] is False and data["randomSourcesReset"] is False
            and data["realLoggedInOwner"] is False and data["ownerProfile"] == contract["ownerProfile"], "scope upgraded")
    require(data["entityClass"] == ROOT and data["initialNonEmptyItemStackCount"] == data["initialActiveEffectCount"] == 0,
            "wrong actor or nonempty initial inventory/effects")
    mechanism = data["mechanism"]
    scene = next(s for s in contract["mechanisms"] if s["id"] == mechanism)
    require(data["scene"] == scene, "scene input changed")
    if profile == "original":
        require(data["schema"] == "follow-paired-reference-mechanisms-v1" and data["brainClass"] == "net.minecraft.world.entity.ai.Brain",
                "not actual original Brain")
    else:
        require(data["schema"] == "follow-paired-port-mechanisms-v1" and data["selection"] == "SELECTED"
                and data["bindingEntry"] == "entity_protected_profile_selection", "not complete production root factory")
    inputs = data["actualInputs"]
    require(inputs["model"] == contract["model"] and inputs["schedule"] == contract["schedule"]
            and inputs["hunger"] == contract["hunger"] and inputs["homeEnabled"] is False
            and inputs["restrictionRadius"] == contract["restrictionRadius"]
            and inputs["sitting"] is scene["initialSitting"] and inputs["pickup"] is scene["foodPresent"]
            and inputs["position"] == contract["maidSpawn"], "actual maid initial state differs")
    require(inputs["difficulty"] == contract["difficulty"] and inputs["dayTime"] == contract["dayTime"]
            and inputs["raining"] is inputs["thundering"] is False, "actual initial environment differs")
    require(layout(inputs["layout"]) == expected_layout(contract), "actual initial layout differs")
    binding = compare_same_side_nbt(data["attributeInputs"], inputs)
    require(binding["typed_structure_equal"], "same-side complete initial NBT binding failed")
    owner_facts(inputs["owner"], profile, True)
    food = inputs["food"]
    require(food["present"] is scene["foodPresent"], "food presence differs")
    if scene["foodPresent"]:
        require(food["registry"] == contract["food"]["registry"] and food["count"] == contract["food"]["count"]
                and food["position"] == contract["food"]["position"], "actual initial food input differs")
        binary_snapshot(food["itemNbtBinary"], food["itemNBT"])
    frames = data["frames"]
    require(len(frames) == contract["framesPerActor"], "frame window truncated; retain failures")
    for i, frame in enumerate(frames):
        require(frame["frame"] == i and frame["age"] == i + 1 and frame["livingTicks"] == 1
                and frame["dayTime"] == contract["dayTime"] and frame["health"] > 0, "natural actor/tick phase changed")
        vector(frame["position"]); vector(frame["motion"])
        owner_facts(frame["owner"], profile)
        for phase in ("before", "afterCall"):
            owner_facts(frame["ownerInput"][phase], profile)
        require(frame["ownerInput"]["afterCall"]["position"] == frame["ownerInput"]["plannedPosition"],
                "native owner position call did not land")
        planned_owner = contract["ownerStart"]
        for event in scene["events"]:
            if event["frame"] < i and event["kind"] == "owner_position":
                planned_owner = event["position"]
        require(frame["ownerInput"]["plannedPosition"] == planned_owner,
                "recorded native owner call differs from the contracted event sequence")
        if i:
            require(frame["gameTime"] == frames[i-1]["gameTime"] + 1, "world tick sequence corrupted")
        if profile != "original":
            require(frame["actualProfile"] == profile and frame["fullRootAuthorized"] is True and frame["legacyTicks"] == 0,
                    "component/Legacy/profile entered full-root observation")
    check_event_schedule(scene, data["events"])
    for event in data["events"]:
        owner_facts(event["ownerBefore"], profile, True)
        if event["kind"] in ("close_obstacle", "open_obstacle"):
            require(layout(event["layoutAfter"]) == expected_layout(contract, event["kind"] == "close_obstacle"),
                    "actual obstacle event layout differs")
        if event["kind"] == "release_wait":
            require(event["sittingBefore"] is True and event["sittingAfter"] is False, "actual wait release rejected")
    return {"profile": profile, "mechanism": mechanism, "same_side_initial_nbt_binding": binding,
            "metrics": metrics(data), "owner_initial_actual": inputs["owner"],
            "event_owner_actual": [event["ownerBefore"] for event in data["events"]],
            "collection_complete_is_not_task_success": True}


def metrics(data):
    frames, contract = data["frames"], data["contract"]
    positions = [data["actualInputs"]["position"]] + [f["position"] for f in frames]
    last_event = data["events"][-1]["frame"]
    arrival = next((f["frame"] for f in frames if f["frame"] > last_event
                    and math.dist(f["position"], f["owner"]["position"]) <= contract["arrivalDistance"]), None)
    result = {"first_arrival_frame_after_last_event": arrival,
              "ticks_after_last_event_to_arrival": None if arrival is None else arrival - last_event,
              "arrival_observed": arrival is not None, "all_frames_retained": len(frames),
              "horizontal_travel_length": sum(math.hypot(b[0]-a[0], b[2]-a[2]) for a, b in zip(positions, positions[1:])),
              "maximum_observed_step_distance": max(math.dist(a, b) for a, b in zip(positions, positions[1:])),
              "final_owner_distance": math.dist(frames[-1]["position"], frames[-1]["owner"]["position"]),
              "walk_target_frames": sum(f["walkTarget"] for f in frames),
              "cant_reach_memory_frames": sum("cantReachSince" in f for f in frames),
              "path_request_count": "NOT_CAPTURED", "teleport_method_calls": "NOT_CAPTURED",
              "cpu_mspt": "NOT_CAPTURED", "task_success_and_noninferiority_verdict": "NOT_EVALUATED"}
    if data["mechanism"] == "waiting_owner_food":
        window = [f for f in frames if f["frame"] <= last_event]
        start = data["actualInputs"]["position"]
        result["waiting_before_release"] = {
            "frames": len(window), "sitting_false_frames": sum(not f["sitting"] for f in window),
            "walk_target_frames": sum(f["walkTarget"] for f in window),
            "maximum_horizontal_displacement": max(math.hypot(f["position"][0]-start[0], f["position"][2]-start[2]) for f in window),
            "food_remaining_count_at_release": window[-1]["food"].get("count"),
            "compliance_verdict": "NOT_EVALUATED"}
    return result


def original_identity(data, build_bytes, tool, agent):
    build = json.loads(build_bytes)
    require(build["schema"] == "follow-mechanisms-build-v1" and build["upstreamCommit"] == UPSTREAM
            and build["baseReferenceBuildIdentity"]["status"] == "LOCK_VERIFIED", "original base identity unverified")
    for entry in build["toolInputs"]:
        path = (tool / entry["file"]).resolve()
        require(path.is_relative_to(tool.resolve()) and sha(path.read_bytes()) == entry["sha256"], "frozen original tool input changed")
    identity = data["identity"]
    require(identity["buildIdentitySha256"] == identity["agent"]["manifestSha256"] == sha(build_bytes)
            and identity["agent"]["agentSha256"] == build["definitionAgentSha256"] == sha(agent.read_bytes()), "original agent manifest mismatch")
    require(set(identity["classes"]) == set(build["compiledClasses"]) == ORIGINAL_CLASSES
            and identity["wholeClasspathOrPostDefinitionBytesVerified"] is False, "original selected identity scope changed")
    differences = []
    for name, row in identity["classes"].items():
        definition = row["definition"]
        require(row["resourceSha256"] == build["compiledClasses"][name] and definition["count"] == 1
                and definition["stage"] == "premain-transformer-input-after-defining-loader-transformations",
                "selected original resource/first definition differs")
        if row["resourceSha256"] != definition["sha256"]:
            differences.append(name)
    return differences


def input_differences(original, port):
    a, b = original["actualInputs"], port["actualInputs"]
    result = {"maid": [], "owner": [], "raw_task_ids": {"original": a["task"], "port": b["task"]},
              "owner_class_and_dimension_raw": {"original": {k: a["owner"][k] for k in ("class", "dimension")},
                                                "port": {k: b["owner"][k] for k in ("class", "dimension")}},
              "complete_cross_engine_input_equivalence_accepted": False,
              "real_logged_in_or_multiclient_owner_accepted": False}
    for section, keys in (("maid", ("width", "height", "health", "hunger", "restrictionRadius")),
                          ("owner", ("width", "height", "eyeHeight"))):
        x, y = (a, b) if section == "maid" else (a["owner"], b["owner"])
        for key in keys:
            m = measurement(x[key], y[key], 0)
            if not m["exact_equal"]:
                result[section].append({"field": key, **m})
    for key in ("position", "eyePosition", "mode", "flying", "mayFly", "disableDamage", "entityInvulnerable",
                "connectedPlayerRegistry"):
        if a["owner"][key] != b["owner"][key]:
            result["owner"].append({"field": key, "original": a["owner"][key], "port": b["owner"][key]})
    result["selected_attribute_inputs"] = compare_attribute_inputs(original["attributeInputs"], port["attributeInputs"],
                                                                   original["contract"]["attributeInputs"])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    args = parser.parse_args()
    contract_bytes = (args.original_tool / "follow-mechanisms-contract.json").read_bytes()
    contract = json.loads(contract_bytes)
    require(contract["contract"] == "FOLLOW_PAIRED_MECHANISMS_V1" and contract["noninferiorityDelta"] is None
            and contract["complete14ClassBenchmarkAccepted"] is False and contract["repetitions"] == 1
            and tuple(s["id"] for s in contract["mechanisms"]) == MECHANISMS, "unapproved exploratory contract or statistical margin")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    require(hqa["status"] == "passed" and hqa["exitCode"] == observed["process_exit"] == 0
            and observed["status"] == hqa and observed["full_release_passed"] is False, "full HQA run failed/incomplete")
    tests = hqa["tests"]; selected = [t for t in tests if t["classname"] == "tlmnhqapilot:FollowMechanismPortTests"]
    expected = {label + suffix for label in ("faithful", "enhanced") for suffix in ("TemporaryObstacle", "OwnerReversal", "WaitingOwnerFood")}
    require(len(tests) == hqa["counts"]["selectedTests"] == hqa["counts"]["passed"] and len(selected) == 6
            and (len(tests) >= 6 if args.allow_additional_port_tests else len(tests) == 6)
            and {t["name"] for t in selected} == expected and all(t["status"] == "passed" for t in tests)
            and all(hqa["counts"][k] == 0 for k in ("failed", "timedOut", "skipped", "incomplete", "issues")), "wrong HQA cases")
    xml_cases = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    require(len(xml_cases) == len(tests) and {c.get("classname") + "." + c.get("name") for c in xml_cases}
            == {t["id"] for t in tests} and all(not any(c.find(k) is not None for k in ("failure", "error", "skipped")) for c in xml_cases), "JUnit differs")
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes()) and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual candidate/pilot mismatch")
    originals = [json.loads(p.read_bytes()) for p in args.original_captures.glob("follow-mechanism-original-*.json")]
    require(len(originals) == 3 and {d["mechanism"] for d in originals} == set(MECHANISMS), "original captures missing/duplicated")
    actors, owners, scenes = set(), set(), []
    for original in sorted(originals, key=lambda d: d["mechanism"]):
        mechanism = original["mechanism"]
        definitions = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent)
        require(json.loads(args.original_build.read_bytes())["contractSha256"] == sha(contract_bytes), "build contract differs")
        observations = [inspect(original, contract, sha(contract_bytes), "original")]; pairs = {}
        captures = [original]
        for profile in ("faithful_tlm", "enhanced"):
            port = json.loads((args.port_run / f"follow-mechanism-{profile}-{mechanism}.json").read_bytes())
            artifact(port["productionArtifact"], args.candidate_jar, PRODUCTION)
            artifact(port["observerArtifact"], pilot, OBSERVER); artifact(port["spawnInputArtifact"], args.candidate_jar, SPAWN)
            observations.append(inspect(port, contract, sha(contract_bytes), profile))
            pairs[profile] = input_differences(original, port); captures.append(port)
        for capture in captures:
            uid, owner = capture["maidUuid"], capture["actualInputs"]["owner"]["uuid"]
            require(uid not in actors and owner not in owners, "actor/owner reused across mechanisms or profiles")
            actors.add(uid); owners.add(owner)
        scenes.append({"mechanism": mechanism, "observations": observations, "input_pairs": pairs,
                       "original_resource_definition_differences": definitions})
    result = {"schema": "follow-paired-mechanisms-comparison-v1", "observation_integrity_passed": True,
              "comparison_tool_sha256": sha(Path(__file__).read_bytes()),
              "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "contract_sha256": sha(contract_bytes), "independent_actor_count": len(actors), "independent_owner_count": len(owners),
              "frames": len(actors) * contract["framesPerActor"], "hqa_total_case_count": len(tests), "hqa_mechanism_case_count": 6,
              "scenes": scenes, "noninferiority_delta": None, "statistical_verdict": "NOT_EVALUATED",
              "complete_14_class_benchmark_accepted": False, "full_release_passed": False,
              "complete_cross_engine_input_equivalence_accepted": False, "real_logged_in_or_multiclient_owner_accepted": False,
              "definition_difference_explanation_verified": False,
              "scope": "three controlled-native-owner exploratory mechanisms only; retain absent recovery, wait motion, actual input differences and unmeasured metrics"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "scenes"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
