"""四机制输入采集审阅：按弹道源与物理实现分组，记录输入差异。"""

import argparse
import json
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs, measurement
from check import require, sha
from compare_follow_mechanisms import ORIGINAL_CLASSES, original_identity, verify_port_results
from compare_port import artifact
from nbt_binding import binary_snapshot, compare_same_side_nbt
from unified_task_inputs import expected_task_input, task_mapping_receipt

PREFIX = "com.tianluoqaq.tlmnh."
ROOT = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid"
ORIGINAL = ORIGINAL_CLASSES | {"com.tianluoqaq.tlmnh.reference.sleep.CombatMechanismGameTests" + s
                               for s in ("", "$Session")}
ORIGINAL |= {"com.github.tartaricacid.touhoulittlemaid.entity." + s for s in (
    "task.TaskAttack", "task.TaskBowAttack", "ai.brain.task.MaidMeleeAttack", "ai.brain.task.MaidShootTargetTask")}
PRODUCTION = [ROOT] + [PREFIX + s for s in (
    "common.ai.faithful.MaidFaithfulBrain", "common.ai.enhanced.EnhancedCoreBrain",
    "common.ai.enhanced.EnhancedCombatController", "common.combat.MaidBowAttack", "common.combat.NativeArrowMedium",
    "common.combat.ArrowPhysics", "common.combat.ArrowInterceptSolver", "common.combat.ObservedTargetMotion")]
OBSERVER = [PREFIX + "qa.NaturalRangedPairTests" + s for s in ("", "$Collector", "$Flight")]
OBSERVER += [PREFIX + "qa.ModernCoreTests$Fixture", PREFIX + "qa.SleepAttributePortSnapshot"]
COMBAT_OBSERVER = [PREFIX + "qa.CombatMechanismPortTests" + s for s in ("", "$Collector", "$MeleeCollector")]
SPAWN = [PREFIX + "common.development." + s for s in ("DeterministicMaidSpawn", "IsolatedPlayerChannel")]
SOURCE = [PREFIX + "common.combat." + s for s in ("MaidGravityFreeArrow", "SourceBowShot")]
ROOT_CONTRACT = [PREFIX + s for s in ("common.ai.faithful.ModernFollowCoreActor",
                                   "common.ai.MaidBrainCoordinator", "common.ai.policy.MaidPolicyRuntime")]
EXPECTED = {"faithfulMeleeDefense", "enhancedMeleeDefense", "faithfulSourceMotion", "faithfulNativeMotion",
            "enhancedStaticMotion", "enhancedLastSampleMotion", "enhancedMotion", "faithfulSourceSafety",
            "faithfulNativeSafety", "enhancedSafety", "faithfulSourceWithdrawal", "faithfulNativeWithdrawal",
            "enhancedWithdrawal"}
FLIGHTS = {"O": "SOURCE_ORIGINAL", "F": "SOURCE_ORIGINAL", "F-R": "NATIVE_STATIC_COMPENSATION",
           "N-static": "NATIVE_STATIC_COMPENSATION", "N-last": "NATIVE_LAST_SAMPLE_COMPENSATION",
           "N": "NATIVE_MOTION_COMPENSATION"}


def typed(record):
    return binary_snapshot(record, record["rawSNBT"])[1]


def gravity_metadata(shot, source_original, original=False):
    # Native observer row.addProperty receives Java float; Gson writes its shortest float32 decimal.
    # Accept only the known surface provider's canonical 0.05 (or source zero), never a numeric tolerance.
    expected_json = 0.0 if source_original else 0.05
    require(shot["gravity"] == expected_json, "actual arrow gravity metadata differs from known canonical float output")
    bits = struct.pack(">f", shot["gravity"])
    require(bits == struct.pack(">f", expected_json), "actual arrow gravity float32 bits differ")
    promoted = struct.unpack(">f", bits)[0]
    return {"raw_json_number": shot["gravity"], "java_observer_field_type": "double_source_zero" if original else "float",
            "binary32_hex": bits.hex(), "promoted_binary64_value": promoted,
            "raw_json_vs_promoted": measurement(shot["gravity"], promoted, 0),
            "numeric_tolerance_added": False}


def owner(owner, original):
    require(owner["class"] == ("net.minecraft.server.level.ServerPlayer" if original
                               else "net.minecraft.entity.player.EntityPlayerMP"), "not the declared native controlled owner")
    require(owner["worldPlayerList"] is owner["connectedPlayerRegistry"] is owner["nativeHandlerPresent"]
            is owner["memoryChannelOpen"] is True and owner["realNetworkLogin"] is False,
            "actual native connection/registry profile changed")
    require(owner["mode"] == "survival" and owner["flying"] is owner["mayFly"] is True
            and owner["disableDamage"] is owner["entityInvulnerable"] is False,
            "controlled native owner mode/protection differs")
    require(owner["worldRegistered" if original else "registered"] is True, "owner left actual registry")
    if original:
        require(owner["uuidOwnerLookup"] is True, "original getOwner UUID lookup unavailable")
        typed(owner["binaryNBT"])
    else:
        require(owner["nativeLoginInitializationComplete"] is True, "normal SCM login chain incomplete")
        typed(owner["fullNbt"])


def layout(rows):
    actual = set()
    for row in rows:
        require(row["registry"] == "minecraft:glass" and row.get("metadata", 0) == 0,
                "unknown initial combat enclosure block/state")
        value = tuple(row[k] for k in ("x", "y", "z"))
        require(value not in actual, "duplicate combat layout cell")
        actual.add(value)
    expected = {(x, y, z) for x in range(21) for y in range(11) for z in range(9)
                if x in (0, 20) or y in (0, 10) or z in (0, 8)}
    require(actual == expected, "actual initial enclosure differs")


def inspect(data, scene, contract_hash, original, expected_schema=None, unified_tasks=False):
    mechanism = scene["id"]
    if expected_schema is None:
        expected_schema = "combat-mechanism-original-v1" if original else "combat-mechanism-port-v1"
    require(data["schema"] == expected_schema
            and data["contractSha256"] == contract_hash and data["arm"] in scene["arms"], "contract/arm changed")
    require(data["requestedFlightProfile"] == FLIGHTS[data["arm"]], "post-factory or wrong ablation specification")
    require(data["observationComplete"] is data["collectedFramesComplete"] is True
            and not data.get("error") and not data.get("cleanupError"), "actual collection failed or incomplete")
    require(data["statisticalAdmission"] is data["complete14ClassBenchmarkAccepted"] is data["randomSourcesReset"] is False,
            "unapproved scope upgrade")
    initial = data["actualInputs"]
    expected_task = "touhou_little_maid:" + ("attack" if mechanism == "melee" else "ranged_attack")
    if unified_tasks:
        expected_task = expected_task_input(data, mechanism, original)
    require(initial["task"] == expected_task
            and initial["sitting"] is (mechanism != "melee") and initial["schedule"] == "ALL"
            and initial["position"] == [8.5, 1, 4.5] and initial["homeEnabled"] is True
            and initial["restrictionRadius"] == 12 and initial["health"] == 20 and initial["hunger"] == 0,
            "relevant initial root/command inputs differ")
    if original:
        require(data["entityClass"] == ROOT and data["brainClass"] == "net.minecraft.world.entity.ai.Brain"
                and data["ownCleanupComplete"] is True, "not the original full natural root")
        binding = compare_same_side_nbt(data["attributeInputs"], initial)
        require(binding["typed_structure_equal"], "original same-side full typed initial binding failed")
        owner(initial["owner"], True)
        layout(initial["layout"])
        attrs = data["attributeInputs"]
        physical = {"maid": data["maidPhysicalConditions"], "target": data["targetPhysicalConditions"]}
    else:
        a, b = data["maidInitialBindingBefore"], data["maidInitialBindingAfter"]
        attr = data["maidMappedAttributes"]["rawEntityNbtBinary"]
        equal = typed(a) == typed(b) == typed(attr) == typed(data["maidInitial"]["fullNbt"])
        require(equal, "port same-side complete typed initial binding failed")
        binding = {"typed_structure_equal": equal, "before": a, "after": b,
                   "attribute": attr, "scope": "same actor on same engine; full raw strings and typed bytes retained"}
        owner(data["ownerInitial"], False)
        layout(data["initialLayout"])
        attrs = data["maidMappedAttributes"]
        physical = data["comparedInitialConditions"]
    target = data["targetInitial"]
    if original:
        max_health = data["targetPhysicalConditions"]["attributes"]["minecraft:generic.max_health"]
    else:
        names = [row for row in target["physicalConditions"]["attributes"] if row["name"] == "generic.maxHealth"]
        require(len(names) == 1, "missing/duplicate actual target max-health attribute")
        max_health = names[0]
    # V2 使用两端原生范围内的 1000；首轮请求 2000/O 实 1024/GTNH 实 2000 仍是不同输入
    expected_health = 1000
    require(max_health["base"] == expected_health and max_health["value"] == expected_health,
            "actual target base/value differs from V2 common native-range input")
    require(target["registered"] is target["alive"] is True and target["health"] == expected_health
            and target["position"] == ([12.5, 1, 4.5] if mechanism == "melee" else [17.5, 1, 4.5]),
            "initial controlled target changed or replaced")
    typed(target["fullNbt"])
    frames = data["frames"]
    require(len(frames) == scene["frames"], "truncated combat capture")
    for i, row in enumerate(frames):
        require(row["frame"] == i and row["age"] == i + 1, "natural END phase/age stream changed")
        if original:
            require(row["livingTicks"] == 1 and (i == 0 or row["gameTime"] == frames[i - 1]["gameTime"] + 1),
                    "original natural living/world tick stream changed")
            require(row["restrictionCenter"] == [8, 1, 4] and row["restrictionRadius"] == 12,
                    "normal original SchedulePos changed the declared home center/radius")
        else:
            # Collector asserts exact runtime/actuator identities, one native living tick and zero Legacy ticks each frame.
            require(row["fullRootAuthorized"] is True and row["livingTicks"] == 1 and row["legacyTicks"] == 0
                    and row["actualProfile"] == ("enhanced" if data["arm"].startswith("N") else "faithful_tlm")
                    and row["actualFlightProfile"] == FLIGHTS[data["arm"]], "complete root/actuator/tick authorization failed")
    shots = data["shots"]
    require(len({s["uuid"] for s in shots}) == len(shots), "duplicate actual arrow identity")
    for shot in shots:
        require(shot["sourceOriginal"] is (data["arm"] in ("O", "F")), "source/native arrow group changed")
        gravity_metadata(shot, data["arm"] in ("O", "F"), original)
        typed(shot["fullNbt"] if original else shot["launch"]["fullNbt"])
    opportunities = sum(row.get("observedOpportunity", row.get("canObserveShotOpportunity", False)) for row in frames)
    result = {"arm": data["arm"], "frames": len(frames), "same_side_initial_binding": binding,
              "current_fact_opportunity_frames": opportunities, "actual_arrow_joins": len(shots),
              "actual_confirmed_hits": sum(s.get("hit") is True for s in shots),
              "gravity_metadata": [gravity_metadata(s, data["arm"] in ("O", "F"), original) for s in shots],
              "actual_rejected_releases": "UNKNOWN" if original else data.get("rejectedShots", 0),
              "accepted_melee": data.get("acceptedMelee", "UNKNOWN_original_native_counter"),
              "owner_defense_and_competition_exercised": data.get("defenseAndCompetitionExercised"),
              "native_break_exercised": data.get("naturalBreakExercised"),
              "withdrawal_sequence_exercised": data.get("withdrawalSequenceExercised", data.get("aliveWorldExitExercised")),
              "initial_physical_conditions": physical,
              "native_enemy_actual_spawn": data.get("nativeEnemyRegistered"),
              "native_enemy_later_stage_inputs_are_prior_outcomes_not_forced_equal": mechanism == "melee",
              "target_health_input": {"requested_base_and_health": expected_health, "actual_base": max_health["base"],
                                      "actual_value": max_health["value"], "actual_health": target["health"],
                                      "requested_health_matches_actual": target["health"] == expected_health,
                                      "source_range": "modern MAX_HEALTH 1..1024; setHealth clamps by getMaxHealth" if original
                                                      else "GTNH maxHealth upper bound Double.MAX_VALUE; actual value individually verified",
                                      "cross_engine_health_equivalence_claimed": False},
              "source_behavior_success_inferred_from_collection": False}
    if mechanism == "motion":
        result["segments"] = [{**segment,
            "actual_joins": sum(segment["start"] <= s["spawnFrame"] < segment["end"] for s in shots),
            "opportunity_frames": sum(row.get("observedOpportunity", row.get("canObserveShotOpportunity", False))
                                      for row in frames[segment["start"]:segment["end"]])} for segment in scene["segments"]]
    if mechanism == "safety":
        result["wall_join_frames"] = [s["spawnFrame"] for s in shots if 60 <= s["spawnFrame"] < 105]
        result["friendly_crossing_shots"] = [s for s in shots if "friendlyCrossing" in s]
        result["original_protection_not_assumed"] = True
    if mechanism == "melee":
        enemy = data["nativeEnemyRegistered"]
        typed(enemy["fullNbt"])
        require(data["nativeEnemyDifficulty"] == "NORMAL" and enemy["registered"] is enemy["alive"] is True,
                "actual native enemy stage difficulty/registration differs")
        result["native_enemy_observed_frames"] = sum("nativeEnemy" in row for row in frames)
        result["native_enemy_maid_damage_candidates"] = [event for event in data["damageEvents"]
            if event["victim"] == enemy["uuid"] and event.get("source", event.get("directSource"))
            == (data["maidUuid"] if original else data["maidInitial"]["uuid"])]
        result["native_enemy_final"] = data["nativeEnemyFinal"]
    if unified_tasks:
        result["initial_task_mapping"] = task_mapping_receipt(data, mechanism, original)
    return result, attrs, physical


def native_pair(a, b):
    """Three N specifications differ only in creation-time aim policy when these actual inputs are equal."""
    equal = a == b
    return {"exact_recorded_conditions_equal": equal, "left": a, "right": b,
            "same_physics_comparison_eligible": equal,
            "statistical_admission": False, "scope": "captured class/dimensions/all attributes/modifiers/effects, weapon, ammo, gravity/drag; RNG streams not equalized"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--unified-tasks", action="store_true",
                        help="Verify Enhanced unified-task state and its retained ranged-command input")
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    args = parser.parse_args()
    raw = (args.original_tool / "combat-mechanisms-contract.json").read_bytes()
    contract = json.loads(raw)
    require(contract["contract"] == "COMBAT_MECHANISMS_V2" and contract["inputRevision"] == 2
            and contract["target"]["health"] == 1000 and contract["noninferiorityDelta"] is None
            and contract["repetitions"] == 1 and [s["id"] for s in contract["mechanisms"]] == ["melee", "motion", "safety", "withdraw"],
            "unapproved combat/statistical contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    cases = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, cases, args.allow_additional_port_tests, args.allow_failed_additional_port_tests,
                                "tlmnhqapilot:CombatMechanismPortTests", EXPECTED)
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes())
            and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual runtime artifacts changed")
    originals = [json.loads(p.read_bytes()) for p in args.original_captures.glob("combat-mechanism-original-*.json")]
    require(len(originals) == 4 and {d["mechanism"] for d in originals} == {s["id"] for s in contract["mechanisms"]},
            "missing/duplicate original captures")
    actors, owners, mechanisms = set(), set(), []
    for scene in contract["mechanisms"]:
        original = next(d for d in originals if d["mechanism"] == scene["id"])
        defs = original_identity(original, args.original_build.read_bytes(), args.original_tool, args.original_agent,
                                 ORIGINAL, "combat-mechanisms-build-v1")
        require(json.loads(args.original_build.read_bytes())["contractSha256"] == sha(raw), "original build contract changed")
        require(original["contract"] == contract, "captured original contract changed")
        o, original_attrs, _ = inspect(original, scene, sha(raw), True, unified_tasks=args.unified_tasks)
        rows, pairs, physical = [o], {}, {}
        captures = [original]
        for arm in scene["arms"][1:]:
            port = json.loads((args.port_run / f"combat-mechanism-{scene['id']}-{arm}.json").read_bytes())
            captured = dict(port["contract"])
            captured.setdefault("noninferiorityDelta", None)
            require(captured == contract, "port captured resource differs")
            for key, classes, jar in (("productionArtifact", PRODUCTION, args.candidate_jar), ("observerArtifact", OBSERVER, pilot),
                                      ("combatObserverArtifact", COMBAT_OBSERVER, pilot), ("spawnInputArtifact", SPAWN, args.candidate_jar),
                                      ("sourceArrowArtifact", SOURCE, args.candidate_jar), ("combatRootArtifact", ROOT_CONTRACT, args.candidate_jar)):
                artifact(port[key], jar, classes)
            row, attributes, conditions = inspect(port, scene, sha(raw), False, unified_tasks=args.unified_tasks)
            rows.append(row)
            physical[arm] = conditions
            pairs[arm] = {"five_sourced_attributes": compare_attribute_inputs(original_attrs, attributes, contract["attributeInputs"]),
                          "actual_target_health": measurement(original["targetInitial"]["health"], port["targetInitial"]["health"], 0),
                          "original_relevant_inputs": original["actualInputs"], "port_relevant_inputs": port["actualInputs"],
                          "original_source_vs_native_physics_different": arm not in ("F",),
                          "full_cross_engine_nbt_equivalence_claimed": False,
                          "target_health_identical": original["targetInitial"]["health"] == port["targetInitial"]["health"]}
            captures.append(port)
        for data in captures:
            if data["arm"] == "O":
                uid, owner_id = data["maidUuid"], data["actualInputs"]["owner"]["uuid"]
            else:
                uid, owner_id = data["maidInitial"]["uuid"], data["ownerInitial"]["uuid"]
            require(uid not in actors and owner_id not in owners, "actor/owner reused across independent arms")
            actors.add(uid)
            owners.add(owner_id)
        native = {arm: native_pair(physical["F-R"], condition) for arm, condition in physical.items()
                  if "F-R" in physical and arm.startswith("N")}
        mechanisms.append({"mechanism": scene["id"], "observations": rows, "input_pairs_to_original": pairs,
                           "native_actual_input_pairs_to_F_R": native, "original_resource_definition_differences": defs})
    analysis_files = ("compare_combat_mechanisms.py", "compare_follow_mechanisms.py", "compare_port.py",
                      "attribute_inputs.py", "nbt_binding.py", "check.py", "unified_task_inputs.py")
    result = {"schema": "combat-mechanisms-comparison-v1", "observation_integrity_passed": True, "hqa_batch": batch,
              "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "analysis_inputs_sha256": {name: sha(Path(__file__).with_name(name).read_bytes()) for name in analysis_files},
              "original_runtime_build_sha256": sha(args.original_build.read_bytes()), "contract_sha256": sha(raw),
              "mechanisms": mechanisms, "independent_actor_count": len(actors), "independent_owner_count": len(owners),
              "statistical_verdict": "NOT_EVALUATED", "noninferiority_delta": None, "full_release_passed": False,
              "complete_14_class_benchmark_accepted": False, "real_multiclient_owner_accepted": False,
              "remaining_contract_items": contract["remaining"],
              "scope": "one sample per arm; actual normal roots and current-input physics groups; collection is not behavior success or an unmeasured advantage"}
    if args.unified_tasks:
        result["schema"] = "combat-mechanisms-comparison-unified-v1"
        result["analysis_revision"] = "unified-task-input-v1"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "mechanisms"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
