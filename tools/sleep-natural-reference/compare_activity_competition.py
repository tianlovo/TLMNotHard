"""三机制完整O/F/N输入与真实轨迹审阅；语义输入逐项准入，统计、真实网络与范围外字段单列。"""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs, measurement
from check import require, sha
from compare_port import artifact
from compare_follow_mechanisms import (ORIGINAL_CLASSES, PRODUCTION, ROOT, SPAWN, check_captured_contract,
                                      original_identity, owner_facts, vector, verify_port_results)
from nbt_binding import binary_snapshot, compare_same_side_nbt

MECHANISMS = ("waiting_threat_food", "meal_low_air", "shared_cake_interruption")
OBSERVER = ["com.tianluoqaq.tlmnh.qa.ActivityCompetitionPortTests" + suffix for suffix in ("", "$Collector")]
OBSERVER += ["com.tianluoqaq.tlmnh.qa.FollowMechanismPortTests" + suffix for suffix in ("", "$Collector")]
OBSERVER += ["com.tianluoqaq.tlmnh.qa.SleepAttributePortSnapshot"]
ORIGINAL = ORIGINAL_CLASSES | {"com.tianluoqaq.tlmnh.reference.sleep.ActivityCompetitionGameTests" + s for s in ("", "$Session")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task." + s for s in (
    "MaidPanicTask", "MaidBreathAirTask", "MaidHealSelfTask", "MaidWorkMealTask",
    "MaidStealEdibleMoveBlockTask", "MaidStealEdibleUseTask")}
EXPECTED_NAMES = {prefix + suffix for prefix in ("faithful", "enhanced")
                  for suffix in ("WaitingThreatFood", "NaturalMealLowAir", "SharedCakeInterruption")}


def semantic_layout(rows):
    result = set()
    for row in rows:
        point = tuple(row[k] for k in ("x", "y", "z"))
        require(len(point) == 3 and all(isinstance(n, int) for n in point), "invalid layout coordinates")
        require(row["semantic"] in ("stone", "cake", "snack_cabinet"), "unmapped initial terrain")
        item = (*point, row["semantic"])
        require(item not in result, "duplicate layout point")
        if row["semantic"] == "snack_cabinet":
            if row["registry"] == "touhou_little_maid:snack_cabinet":
                # 原采集器的metadata=0是占位；实际现代状态必须明确为北向全罩
                require(row["metadata"] == 0 and row["rawBlockState"] ==
                        "Block{touhou_little_maid:snack_cabinet}[facing=north,type=1]",
                        "unmapped original cabinet direction/type")
            else:
                # BlockSnackCabinet低两位0沿默认北向，TYPE_FULL=1存于高两位
                require(row["registry"] == "tlmnh:snack_cabinet" and row["metadata"] == 4,
                        "unmapped port cabinet direction/type")
        else:
            require(row["registry"] == "minecraft:" + row["semantic"] and row["metadata"] == 0,
                    "initial block/cake registry or metadata differs")
        result.add(item)
    return result


def expected_layout(mechanism):
    rows = {(x, 0, z, "stone") for x in range(25) for z in range(25)}
    if mechanism == "shared_cake_interruption":
        rows |= {(12, 1, 12, "snack_cabinet"), (12, 2, 12, "cake")}
    return rows


def raw_input(snapshot):
    native_label = snapshot["binaryNBT"]["rawSNBT"]
    binary_snapshot(snapshot["binaryNBT"], native_label)
    text_equal = snapshot["rawNBT"] == native_label
    return {
        "legacy_raw_snbt": snapshot["rawNBT"], "binary_native_raw_snbt": native_label,
        "text_equal": text_equal,
        "legacy_raw_to_binary_label_status": "EXACT_TEXT_LABEL" if text_equal else "NOT_PROVEN",
        "binary_native_label_validated": True,
        "legacy_snbt_parsed_or_reordered": False,
        "scope": "binary bytes retain their own captured native label; a differing extra legacy string is retained without claiming its binding",
    }


def actor_frame(row):
    for key in ("age", "air", "hunger", "favorability", "soup", "bowls", "apples"):
        require(isinstance(row[key], int), "invalid live actor integer " + key)
    require(row["health"] > 0 and row["soup"] >= 0 and row["bowls"] >= 0, "dead actor or invalid material count")
    vector(row["position"])
    require(all(isinstance(row[k], bool) for k in ("sitting", "inWater", "usingFood", "goingToBreath")), "invalid actual actor flags")


def inspect(data, contract, contract_hash, profile):
    check_captured_contract(data, contract, contract_hash, profile)
    scene = next(s for s in contract["mechanisms"] if s["id"] == data["mechanism"])
    mechanism = scene["id"]
    require(data["scene"] == scene and data["entityClass"] == ROOT and data["initialActiveEffectCount"] == 0,
            "actual scene/entity/effect inputs differ")
    require(data["observationComplete"] is data["ownCleanupComplete"] is data["extraCleanupComplete"] is True
            and not data.get("error") and not data.get("cleanupError"), "capture/cleanup incomplete; keep raw failure")
    require(data["benchmarkVerdict"] == "NOT_EVALUATED" and data["statisticalAdmission"] is False
            and data["complete14ClassBenchmarkAccepted"] is False and data["randomSourcesReset"] is False,
            "unapproved statistical/root scope")
    if profile == "original":
        require(data["schema"] == "activity-competition-original-v1" and data["brainClass"] == "net.minecraft.world.entity.ai.Brain",
                "not original natural Brain")
    else:
        require(data["schema"] == "activity-competition-port-v1" and data["selection"] == "SELECTED"
                and data["bindingEntry"] == "entity_protected_profile_selection", "not complete factory root")
    actual = data["actualInputs"]
    controlled_actor_raw_bindings = {}
    initial_state = data["initialActorState"]
    actor_frame(initial_state)
    require(initial_state["age"] == 0 and initial_state["air"] > 0
            and initial_state["health"] == scene["health"] and initial_state["hunger"] == contract["hunger"]
            and initial_state["sitting"] is scene["initialSitting"] and not initial_state["inWater"]
            and not initial_state["usingFood"], "actual primary live initial state differs")
    require(data["initialNonEmptyItemStackCount"] == (1 if mechanism == "meal_low_air" else 0),
            "unexpected initial material inputs")
    if mechanism == "meal_low_air":
        require(initial_state["soup"] == 1 and initial_state["bowls"] == initial_state["apples"] == 0
                and data["actualFoodDuration"] == 32 and data["actualFoodNutrition"] > 0,
                "actual natural soup item/duration/nutrition input differs")
    position = contract["sharedCake"]["primarySpawn"] if mechanism == "shared_cake_interruption" else contract["maidSpawn"]
    require(actual["position"] == position and actual["model"] == contract["model"] and actual["schedule"] == contract["schedule"]
            and actual["health"] == scene["health"] and actual["hunger"] == contract["hunger"]
            and actual["sitting"] is scene["initialSitting"] and actual["pickup"] is scene["foodPresent"]
            and actual["restrictionRadius"] == contract["restrictionRadius"] and actual["homeEnabled"] is False,
            "actual relevant maid initial inputs differ from contract")
    # 原TaskIdle.getUid与移植MaidTask.IDLE.getId均输出同一完整命名空间ID
    require(contract["task"] == "idle" and actual["task"] == "touhou_little_maid:idle", "idle task semantic mapping changed")
    require(actual["difficulty"] == contract["difficulty"] and actual["dayTime"] == contract["dayTime"]
            and actual["raining"] is actual["thundering"] is False, "actual relevant world input differs")
    require(semantic_layout(actual["layout"]) == expected_layout(mechanism), "actual initial blueprint differs")
    binding = compare_same_side_nbt(data["attributeInputs"], actual)
    require(binding["typed_structure_equal"], "same-side full initial typed binding failed")
    owner_facts(actual["owner"], profile, True)
    require(actual["owner"]["position"] == contract["ownerStart"], "native owner start differs")
    require(actual["food"]["present"] is scene["foodPresent"], "food input presence differs")
    if scene["foodPresent"]:
        food = actual["food"]
        require(food["registry"] == contract["food"]["registry"] and food["count"] == 1
                and food["position"] == contract["food"]["position"], "actual dropped food differs")
        binary_snapshot(food["itemNbtBinary"], food["itemNBT"])
        threat = data["threatInitial"]
        controlled_actor_raw_bindings["threat"] = raw_input(threat)
        require(threat["class"] == ("net.minecraft.world.entity.monster.Creeper" if profile == "original"
                                   else "net.minecraft.entity.monster.EntityCreeper") and threat["persistent"] is True
                and threat["position"] == contract["threat"]["position"]
                and threat["movementBase"] == threat["movementValue"] == 0 and threat["health"] == threat["maxHealth"] == 20,
                "controlled actual native threat was replaced or changed")
    if mechanism == "shared_cake_interruption":
        controlled_actor_raw_bindings["second"] = raw_input(data["secondInitial"])
        second = data["secondInitial"]
        second_state = data["secondInitialState"]
        actor_frame(second_state)
        require(second["class"] == ROOT and second_state["age"] == 0 and second_state["health"] == scene["health"]
                and second_state["hunger"] == contract["hunger"] and second_state["position"] == contract["sharedCake"]["secondSpawn"]
                and not second_state["sitting"] and not second_state["inWater"] and not second_state["usingFood"]
                and second_state["soup"] == second_state["bowls"] == second_state["apples"] == 0,
                "actual second actor relevant initial state differs")
        second_binding = compare_same_side_nbt(data["secondAttributeInputs"],
            {"rawInitialNBT": second["binaryNBT"]["rawSNBT"], "rawInitialNbtBinary": second["binaryNBT"]})
        require(second_binding["typed_structure_equal"], "second actor typed initial binding failed")
    else:
        second_binding = None
    frames = data["frames"]
    require(len(frames) == scene["frames"], "truncated activity window")
    for i, frame in enumerate(frames):
        require(frame["frame"] == i and frame["age"] == i + 1 and frame["livingTicks"] == 1, "natural primary tick phase differs")
        require(i == 0 or frame["gameTime"] == frames[i - 1]["gameTime"] + 1, "world tick stream corrupted")
        owner_facts(frame["owner"], profile)
        actor_frame(frame["maid"])
        require(frame["maid"]["age"] == i + 1, "primary actor frame mismatched")
        for phase in ("before", "afterCall"):
            owner_facts(frame["ownerInput"][phase], profile)
        require(frame["ownerInput"]["afterCall"]["position"] == frame["ownerInput"]["plannedPosition"], "owner control did not land")
        planned = scene["events"][-1]["ownerPosition"] if mechanism == "waiting_threat_food" and i > 120 else contract["ownerStart"]
        require(frame["ownerInput"]["plannedPosition"] == planned, "uncontracted/future owner position input")
        if profile != "original":
            require(frame["actualProfile"] == profile and frame["fullRootAuthorized"] is True and frame["legacyTicks"] == 0,
                    "component/Legacy entered complete-root capture")
        if mechanism == "meal_low_air":
            require(frame["maid"]["soup"] + frame["maid"]["bowls"] == 1, "natural soup/container conservation failed")
        if mechanism == "shared_cake_interruption":
            actor_frame(frame["second"])
            require(frame["second"]["age"] == i + 1 and 0 <= frame["cakeBitesConsumed"] <= 7,
                    "second actor/one cake input changed")
        if mechanism == "waiting_threat_food":
            require(frame["threatRegistered"] is (i <= 80), "actual persistent threat registration differs from schedule")
    events = data["events"]
    require(all(e["phase"] == "END_after_recorded_frame" for e in events), "unrecorded input phase")
    if mechanism == "waiting_threat_food":
        require([(e["frame"], e["kind"]) for e in events] == [(80, "remove_threat"), (120, "release_wait")], "fixed event schedule differs")
    else:
        trigger, release = data["reactiveTriggerFrame"], data["reactiveReleaseFrame"]
        expected_kinds = ("natural_meal_low_air", "clear_water_input") if mechanism == "meal_low_air" else ("first_shared_cake_bite_wait", "release_both_waiting")
        require([(e["frame"], e["kind"]) for e in events] == ([] if trigger < 0 else [(trigger, expected_kinds[0])] +
                ([] if release < 0 else [(release, expected_kinds[1])])), "reactive event log differs")
        require(trigger < 0 or release == trigger + 40 or release < 0 and trigger + 40 >= len(frames), "reactive interval differs")
        if trigger >= 0:
            fact = frames[trigger]["maid"]["usingFood"] if mechanism == "meal_low_air" else frames[trigger]["cakeBitesConsumed"] > 0
            require(fact, "reactive input preceded its actual natural trigger")
    return {"profile": profile, "mechanism": mechanism, "same_side_initial_nbt_binding": binding,
            "second_same_side_initial_nbt_binding": second_binding, "relevant_contract_inputs_validated": True,
            "controlled_actor_raw_bindings": controlled_actor_raw_bindings,
            "scenario_input_exercised": data["scenarioInputExercised"], "metrics": metrics(data),
            "collection_complete_is_not_task_success": True}


def metrics(data):
    frames = data["frames"]
    initial = frames[0]["maid"]

    def first(predicate, after=-1):
        return next((r["frame"] for r in frames if r["frame"] > after and predicate(r)), None)

    result = {"frames": len(frames), "first_natural_food_use": first(lambda r: r["maid"]["usingFood"]),
            "reactive_trigger_frame": data["reactiveTriggerFrame"], "reactive_release_frame": data["reactiveReleaseFrame"],
            "first_post_input_dry_frame": first(lambda r: not r["maid"]["inWater"], data["reactiveTriggerFrame"])
                if data["mechanism"] == "meal_low_air" and data["reactiveTriggerFrame"] >= 0 else None,
            "first_post_input_air_intent": first(lambda r: r["maid"]["goingToBreath"], data["reactiveTriggerFrame"])
                if data["mechanism"] == "meal_low_air" and data["reactiveTriggerFrame"] >= 0 else None,
            "first_food_use_after_clear": first(lambda r: r["maid"]["usingFood"], data["reactiveReleaseFrame"])
                if data["mechanism"] == "meal_low_air" and data["reactiveReleaseFrame"] >= 0 else None,
            "activity_transitions": sum(a["maid"]["activity"] != b["maid"]["activity"] for a, b in zip(frames, frames[1:])),
            "initial_health": data["initialActorState"]["health"], "first_observed_health": initial["health"],
            "final_health": frames[-1]["maid"]["health"],
            "min_health": min(r["maid"]["health"] for r in frames),
            "food_and_containers_final": {k: frames[-1]["maid"][k] for k in ("soup", "bowls", "apples")},
            "cake_bites_consumed": frames[-1].get("cakeBitesConsumed"),
            "first_cake_bite": first(lambda r: r.get("cakeBitesConsumed", 0) > 0),
            "resource_assertions_passed": data["resourceAssertionsPassed"],
            "resource_assertion_scope": data["resourceAssertionScope"]}
    if data["mechanism"] == "waiting_threat_food":
        spawn = data["initialActorState"]["position"]
        food_before_release = frames[120]["food"]
        result["waiting_with_threat"] = {
            "maximum_horizontal_displacement": max(
                ((r["maid"]["position"][0] - spawn[0]) ** 2
                 + (r["maid"]["position"][2] - spawn[2]) ** 2) ** .5
                for r in frames[:81]
            ),
            "displacement_baseline": spawn,
            "first_panic_frame": first(lambda r: "PANIC" in r["maid"]["activity"].upper()),
            "threat_distance_squared_min": min(r["threatDistanceSquared"] for r in frames[:81]),
            "threat_distance_squared_max": max(r["threatDistanceSquared"] for r in frames[:81]),
            "food_remaining_before_release": food_before_release.get("count", 0) if food_before_release["present"] else 0,
            "food_entity_before_release": food_before_release,
            "maid_apples_before_release": frames[120]["maid"]["apples"],
            "sitting_false_frames_before_command_release": sum(not r["maid"]["sitting"] for r in frames[:121]),
        }
        result["first_arrival_after_release"] = first(
            lambda r: sum((x - y) ** 2 for x, y in zip(r["maid"]["position"], r["owner"]["position"])) <= 4,
            120,
        )
        arrival = result["first_arrival_after_release"]
        result["arrival_delay_after_release_ticks"] = None if arrival is None else arrival - 120
    if data["mechanism"] == "shared_cake_interruption" and data["reactiveTriggerFrame"] >= 0:
        start = data["reactiveTriggerFrame"]
        end = data["reactiveReleaseFrame"]
        result["additional_bites_during_both_waiting"] = (
            frames[end if end >= 0 else -1]["cakeBitesConsumed"] - frames[start]["cakeBitesConsumed"]
        )
        result["post_release_further_bite_frame"] = first(
            lambda r: r["cakeBitesConsumed"] > frames[end]["cakeBitesConsumed"], end,
        ) if end >= 0 else None
        result["both_favorability_final"] = [frames[-1][key]["favorability"] for key in ("maid", "second")]
    return result


def pair_inputs(original, port):
    attributes = compare_attribute_inputs(original["attributeInputs"], port["attributeInputs"], original["contract"]["attributeInputs"])
    second_attributes = None
    if "secondAttributeInputs" in original:
        second_attributes = compare_attribute_inputs(
            original["secondAttributeInputs"], port["secondAttributeInputs"], original["contract"]["attributeInputs"],
        )
    differences = []
    sections = (
        (None, ("width", "height", "health", "hunger", "restrictionRadius")),
        ("owner", ("width", "height", "eyeHeight")),
    )
    for section, keys in sections:
        a, b = original["actualInputs"], port["actualInputs"]
        if section:
            a, b = a[section], b[section]
        for key in keys:
            values = measurement(a[key], b[key], 0)
            if not values["exact_equal"]:
                differences.append({"section": section or "maid", "field": key, **values})
    for field in ("initialActorState", "secondInitialState"):
        if field not in original:
            continue
        for key in ("air", "favorability", "soup", "bowls", "apples"):
            values = measurement(original[field][key], port[field][key], 0)
            if not values["exact_equal"]:
                differences.append({"section": field, "field": key, **values})
    if original["mechanism"] == "meal_low_air":
        for key in ("actualFoodDuration", "actualFoodNutrition"):
            values = measurement(original[key], port[key], 0)
            if not values["exact_equal"]:
                differences.append({"section": "meal", "field": key, **values})
    return {
        "mapped_attributes": attributes,
        "second_mapped_attributes": second_attributes,
        "exact_scalar_differences": differences,
        "exact_task_identity": {
            "original": original["actualInputs"]["task"], "port": port["actualInputs"]["task"],
            "expected": "touhou_little_maid:idle", "exact_equal": True,
        },
        "cabinet_representation_mapping": {
            "original": [r for r in original["actualInputs"]["layout"] if r["semantic"] == "snack_cabinet"],
            "port": [r for r in port["actualInputs"]["layout"] if r["semantic"] == "snack_cabinet"],
            "scope": "exact sourced north-facing full cabinet only; original metadata is a synthetic placeholder, original rawBlockState is authoritative; port metadata4 encodes north/full",
        } if original["mechanism"] == "shared_cake_interruption" else None,
        "relevant_semantic_contract_checked_each_side": True,
        "raw_nbt_and_unmapped_platform_fields_retained": True,
        "scope": "declared terrain/task/owner/health/hunger/food/threat inputs and five sourced attributes; cross-engine whole NBT not asserted",
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--allow-additional-port-tests", action="store_true")
    p.add_argument("--allow-failed-additional-port-tests", action="store_true")
    a = p.parse_args()
    raw = (a.original_tool / "activity-competition-contract.json").read_bytes()
    contract = json.loads(raw)
    require(
        contract["contract"] == "ACTIVITY_COMPETITION_V1" and contract["noninferiorityDelta"] is None
        and contract["repetitions"] == 1 and tuple(s["id"] for s in contract["mechanisms"]) == MECHANISMS,
        "unapproved contract or margin",
    )
    hqa = json.loads((a.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((a.port_run / "observed.json").read_bytes())
    cases = list(ET.parse(a.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(
        hqa, observed, cases, a.allow_additional_port_tests, a.allow_failed_additional_port_tests,
        "tlmnhqapilot:ActivityCompetitionPortTests", EXPECTED_NAMES,
    )
    pilot = a.port_run / "tested-pilot.jar"
    require(
        observed["candidate_sha256"] == sha(a.candidate_jar.read_bytes())
        and observed["pilot_sha256"] == sha(pilot.read_bytes()),
        "actual artifact binding differs",
    )
    originals = [json.loads(f.read_bytes()) for f in a.original_captures.glob("activity-competition-original-*.json")]
    require(
        len(originals) == 3 and {d["mechanism"] for d in originals} == set(MECHANISMS),
        "missing/duplicate original captures",
    )
    scenes = []
    actors = set()
    owners = set()
    for original in sorted(originals, key=lambda d: d["mechanism"]):
        defs = original_identity(
            original, a.original_build.read_bytes(), a.original_tool, a.original_agent,
            ORIGINAL, "activity-competition-build-v1",
        )
        require(json.loads(a.original_build.read_bytes())["contractSha256"] == sha(raw), "runtime original contract differs")
        observations = [inspect(original, contract, sha(raw), "original")]
        pairs = {}
        captures = [original]
        for profile in ("faithful_tlm","enhanced"):
            port = json.loads((a.port_run / f"activity-competition-{profile}-{original['mechanism']}.json").read_bytes())
            artifact(port["productionArtifact"], a.candidate_jar, PRODUCTION)
            artifact(port["observerArtifact"], pilot, OBSERVER)
            artifact(port["spawnInputArtifact"], a.candidate_jar, SPAWN)
            observations.append(inspect(port, contract, sha(raw), profile))
            pairs[profile] = pair_inputs(original, port)
            captures.append(port)
        for capture in captures:
            require(
                capture["maidUuid"] not in actors and capture["actualInputs"]["owner"]["uuid"] not in owners,
                "actor/owner reused across cases or profiles",
            )
            actors.add(capture["maidUuid"])
            owners.add(capture["actualInputs"]["owner"]["uuid"])
            if "secondInitial" in capture:
                require(capture["secondInitial"]["uuid"] not in actors, "secondary actor reused")
                actors.add(capture["secondInitial"]["uuid"])
        scenes.append({
            "mechanism": original["mechanism"], "observations": observations, "input_pairs": pairs,
            "original_resource_definition_differences": defs,
        })
    result = {
        "schema": "activity-competition-comparison-v1", "observation_integrity_passed": True, "hqa_batch": batch,
        "candidate_sha256": sha(a.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
        "contract_sha256": sha(raw), "mechanisms": scenes, "statistical_verdict": "NOT_EVALUATED",
        "noninferiority_delta": None, "complete_14_class_benchmark_accepted": False, "full_release_passed": False,
        "scope": "three normal-root O/F/N competition mechanisms with checked relevant inputs; per-arm reactive timing and actual outcomes retained; controlled memory owners, not real-client multiplayer",
        "analysis_provenance": {
            "schema": "activity-competition-analysis-v2",
            "script_sha256": sha(Path(__file__).read_bytes()),
            "python_inputs_sha256": {p.name: sha(p.read_bytes()) for p in Path(__file__).parent.glob("*.py")},
            "runtime_original_tool_identity_unchanged": True,
            "scope": "post-run analysis code hashes are separate from the frozen original runtime tool identity",
        },
        "input_comparability_limits": {
            "food_nutrition_is_actual_native_input": True,
            "cross_engine_healing_amount_same_input_benefit_accepted": False,
            "scope": "actual original/port nutrition differences remain explicit; no cross-engine healing benefit is inferred from unequal food values",
        },
    }
    result["extra_legacy_raw_labels_all_exact"] = all(
        binding["text_equal"] for scene in scenes for observation in scene["observations"]
        for binding in observation["controlled_actor_raw_bindings"].values()
    )
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "mechanisms"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
