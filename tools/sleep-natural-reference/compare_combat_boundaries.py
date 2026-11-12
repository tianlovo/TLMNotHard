"""Strict supplemental stationary/peek/finite-ammo episodes, separate from frozen combat V2 samples."""

import argparse
import json
import math
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

from attribute_inputs import compare_attribute_inputs, measurement
from check import require, sha
from compare_combat_mechanisms import (
    ORIGINAL as COMBAT_ORIGINAL, PRODUCTION, OBSERVER, COMBAT_OBSERVER,
    SPAWN, SOURCE, ROOT_CONTRACT, inspect, native_pair, typed,
)
from compare_follow_mechanisms import original_identity, verify_port_results
from compare_port import artifact

PREFIX = "com.tianluoqaq.tlmnh."
ORIGINAL = COMBAT_ORIGINAL | {
    PREFIX + "reference.sleep.CombatBoundaryGameTests" + suffix for suffix in ("", "$Session")
}
BOUNDARY_OBSERVER = [PREFIX + "qa.CombatBoundaryPortTests" + suffix for suffix in ("", "$Collector")]
MECHANISMS = ("stationary", "peek", "finite")
EXPECTED = {prefix + suffix for prefix in (
    "faithfulSource", "faithfulNative", "enhancedStatic", "enhancedLastSample", "enhancedMotion"
) for suffix in ("Stationary", "Peek")}
EXPECTED |= {"faithfulSourceFinite", "enhancedMotionFinite"}


def compound(record):
    name, root = typed(record)
    require(root[0] == 10, "native item/entity snapshot must be a compound")
    return root[1]


def item_count(record):
    value = compound(record)
    if b"Count" not in value:
        require(not value, "nonempty native item snapshot lacks Count")
        return 0
    require(value[b"Count"][0] == 1, "native ItemStack Count type changed")
    return value[b"Count"][1]


def native_origin(data):
    target = data["targetInitial"]
    tag = compound(target["fullNbt"])
    kind, (subtype, values) = tag[b"Pos"]
    require(kind == 9 and subtype == 6 and len(values) == 3, "native entity Pos type changed")
    absolute = [struct.unpack(">d", part[1])[0] for part in values]
    origin = [a - b for a, b in zip(absolute, target["position"])]
    require(all(math.isfinite(n) and n == int(n) for n in origin), "native scene origin is not block aligned")
    return origin


def boundary_layout(rows, kind):
    cells = {}
    for row in rows:
        key = tuple(row[k] for k in ("x", "y", "z"))
        require(key not in cells and row.get("metadata", 0) == 0, "duplicate or nondefault boundary block")
        cells[key] = row["registry"]
    expected = {(x, y, z): "minecraft:glass" for x in range(21) for y in range(11) for z in range(9)
                if x in (0, 20) or y in (0, 10) or z in (0, 8)}
    expected.update({(13, y, z): "minecraft:stone" for y in range(1, 10) for z in range(1, 8)
                     if (z == 4 if kind == "pillar" else z != 4 or y > 3)})
    require(cells == expected, "actual pillar/doorway layout differs from declared native blocks")


def finite_analysis(data, frames, original):
    events = data["boundaryEvents"]
    require(item_count(data["externalReserveInitial"]) == 1 and data["initialInventoryArrows"] == 3
            and data["initialExternalReserveArrows"] == 1 and data["ammoBudget"] == 4,
            "finite initial inventory/reserve budget differs")
    zero = [row["frame"] for row in frames if row["inventoryArrows"] == 0 and joins_at(data, row["frame"]) == 3]
    first_zero = zero[0] if zero else -1
    require(data["firstNaturalZeroFrame"] == first_zero, "reported first natural zero differs from actual join/inventory facts")
    restocks = [event for event in events if event["action"] == "insert_owned_one_arrow"]
    require(len(events) == len(restocks) <= 1, "unexpected finite inventory intervention")
    restock = restocks[0] if restocks else None
    restock_at = restock["frame"] if restock else -1
    require(data["restockStartFrame"] == restock_at, "reported one-arrow input frame differs")
    if restock:
        require(first_zero >= 0 and restock_at == first_zero + 41
                and restock["actualArrowJoinsBefore"] == 3
                and restock["inventoryArrowsAfter"] == 1 and restock["externalReserveArrowsAfter"] == 0
                and typed(restock["reserveBefore"]) == typed(data["externalReserveInitial"]) == typed(restock["slotAfter"]),
                "one owned arrow was altered, early, duplicated or inserted at the wrong phase")
    second_zero = next((row["frame"] for row in frames if restock_at >= 0 and row["frame"] >= restock_at
                        and row["inventoryArrows"] == 0 and joins_at(data, row["frame"]) == 4), -1)
    require(data["secondNaturalZeroFrame"] == second_zero, "reported second natural zero differs from actual facts")
    first_dry = 0 if first_zero < 0 else (restock_at - 1 if restock_at >= 0 else frames[-1]["frame"]) - first_zero
    second_dry = 0 if second_zero < 0 else frames[-1]["frame"] - second_zero
    require(data["firstDryCompletedFrames"] == first_dry and data["secondDryCompletedFrames"] == second_dry,
            "completed dry-window observation count changed")
    for row in frames:
        index = row["frame"]
        reserve = 1 if restock_at < 0 or index < restock_at else 0
        require(row["reserveArrows"] == row["externallyHeldArrows"] == reserve,
                "actual external reserve was hidden or recreated")
        if first_zero <= index < restock_at or (first_zero >= 0 and restock_at < 0 and index >= first_zero):
            require(row["inventoryArrows"] == 0 and joins_at(data, index) == 3 and wear(row, original) == 3,
                    "new arrow/payment appeared during the first real zero-ammo interval")
        if second_zero >= 0 and index >= second_zero:
            require(row["inventoryArrows"] == 0 and joins_at(data, index) == 4 and wear(row, original) == 4,
                    "new arrow/payment appeared during the final real zero-ammo interval")
    complete = second_zero >= 0 and first_dry >= 40 and second_dry >= 40
    require(data["finiteSequenceComplete"] is complete, "finite sequence completion overclaimed")
    require(item_count(data["externalReserveFinal"]) == frames[-1]["reserveArrows"]
            and item_count(data["ammunitionFinal"]) == frames[-1]["inventoryArrows"], "terminal native item snapshot differs")
    return {"three_natural_shots_and_zero_observed": first_zero >= 0, "first_zero_frame": first_zero,
            "restock_start_frame": restock_at, "second_zero_frame": second_zero,
            "first_completed_dry_frames": first_dry, "second_completed_dry_frames": second_dry,
            "owned_reserve_full_typed_nbt_unchanged_through_insertion": restock is not None,
            "finite_sequence_complete": complete,
            "old_in_flight_hits_during_empty_ammo_are_allowed": True,
            "business_success": complete, "statistical_verdict": "NOT_EVALUATED"}


def joins_at(data, frame):
    return sum(shot["spawnFrame"] <= frame for shot in data["shots"])


def wear(row, original):
    return row["weaponWear" if original else "bowWear"]


def episode(data, scene, contract_hash, original, unified_tasks=False):
    schema = "combat-boundary-original-v1" if original else "combat-boundary-port-v1"
    result, attributes, conditions = inspect(data, scene, contract_hash, original, expected_schema=schema,
                                             unified_tasks=unified_tasks)
    require(data["oldCombatSamplesReplaced"] is False and data["resourceAssertionsPassed"] is True,
            "old evidence replaced or resource assertions incomplete")
    require(data["mechanism" if original else "scene"] == scene["id"], "wrong supplementary episode")
    require(data["endObserverScope"] == "Read native END facts and queue next START inputs only",
            "unexpected boundary input phase")
    frames, events, origin = data["frames"], data["boundaryEvents"], native_origin(data)
    finite = scene["id"] == "finite"
    budget, initial_count = (4, 3) if finite else (24, 24)
    require(data["ammoBudget"] == budget and data["initialInventoryArrows"] == initial_count
            and item_count(data["ammunitionInitial"]) == initial_count, "initial native ammo count differs")
    if not finite:
        require(data["initialExternalReserveArrows"] == 0 and item_count(data["externalReserveInitial"]) == 0,
                "unexpected reserve on the normal stationary/peek input")
    plan_reference_differences, end_target_displacements = [], []
    for row in frames:
        index, plan = row["frame"], row["boundaryTargetInputPlan"]
        require((row["boundaryTargetInputPhase"] == "START_before_world_update"
                 or (index == 0 and row["boundaryTargetInputPhase"] == "registered_spawn_before_first_update"))
                and len(plan) == 3
                and all(isinstance(n, (int, float)) and math.isfinite(n) for n in plan), "current target input plan missing")
        require(plan[:2] == [17.5, 1], "fixed target x/y input changed")
        if scene["id"] != "peek" or index < 40:
            require(plan[2] == 4.5, "stationary target START input changed")
        else:
            require(2.8 <= plan[2] <= 6.2, "peek target left the declared bounded lateral input")
            reference = 4.5 + 1.7 * math.sin((index - 40) * .12)
            if plan[2] != reference:
                plan_reference_differences.append({"frame": index, **measurement(reference, plan[2], 0)})
        actual = row["targetInputBeforeWorldUpdate"]["position"]
        expected = [(a + b) - a for a, b in zip(origin, plan)]
        require(actual == expected, "native current target position call did not land at its actual binary64 plan")
        require(row["target"]["registered"] is row["target"]["alive"] is True, "controlled native target disappeared")
        require(row["ammoBudget"] == budget and row["inventoryArrows"] + row["externallyHeldArrows"]
                + joins_at(data, index) == budget and wear(row, original) == joins_at(data, index),
                "actual arrow joins, finite inventory/reserve or exactly-once wear differ")
        if not original:
            require(row["confirmedShots"] == joins_at(data, index), "actuator confirmations differ from actual native arrow joins")
        displaced = [measurement(a, b, 0) for a, b in zip(actual, row["target"]["position"])]
        if not all(value["exact_equal"] for value in displaced):
            end_target_displacements.append({"frame": index, "axes": displaced})
    if scene["id"] == "peek":
        require([(event["frame"], event["action"]) for event in events] == [(40, "pillar"), (140, "doorway")],
                "pillar/doorway current-world inputs missing or repeated")
        for event in events:
            boundary_layout(event["layoutAfter"], event["action"])
    elif not finite:
        require(not events, "unrequested stationary fixture input")
    require(all(event["phase"] == "START_before_world_update" for event in events), "boundary input happened outside START")
    require(data["confirmedShots"] == len(data["shots"]), "terminal actual native arrow joins differ from confirmations")
    windows = []
    spans = ((0, 40, "clear"), (40, 140, "pillar"), (140, 260, "doorway")) if scene["id"] == "peek" else ((0, len(frames), "clear"),)
    for start, stop, geometry in spans:
        rows = frames[start:stop]
        visible = [row["targetVisible"] for row in rows]
        require(all(isinstance(value, bool) for value in visible)
                and all(row["boundaryGeometry"] == geometry for row in rows), "actual visibility/geometry labels changed")
        shots = [shot for shot in data["shots"] if start <= shot["spawnFrame"] < stop]
        windows.append({"start": start, "end_exclusive": stop, "geometry": geometry,
                        "visible_frames": sum(visible), "occluded_frames": len(visible) - sum(visible),
                        "visibility_transitions": sum(a != b for a, b in zip(visible, visible[1:])),
                        "observed_current_fact_opportunities": sum(row.get("observedOpportunity", row.get("canObserveShotOpportunity", False)) for row in rows),
                        "actual_arrow_joins": len(shots), "confirmed_hits_from_these_arrows": sum(shot.get("hit") is True for shot in shots),
                        "actual_rejected_release_delta": "UNKNOWN" if original else rows[-1]["rejectedShots"] - (frames[start - 1]["rejectedShots"] if start else 0),
                        "joins_with_end_frame_los_false": [shot["uuid"] for shot in shots if not frames[shot["spawnFrame"]]["targetVisible"]],
                        "los_at_end_is_a_current_fact_not_a_synthetic_release_authorization": True})
    per_arrow = []
    for shot in data["shots"]:
        row = frames[shot["spawnFrame"]]
        per_arrow.append({"uuid": shot["uuid"], "spawn_frame": shot["spawnFrame"],
                          "source_physics_group": "source_original" if shot["sourceOriginal"] else "native",
                          "at_spawn_frame_END_facts": {key: row.get(key) for key in (
                              "targetVisible", "targetSelected", "usedTicks", "inventoryArrows",
                              "externallyHeldArrows", "bowStatus", "rejectedShots")},
                          "current_START_target": row["targetInputBeforeWorldUpdate"],
                          "bow_wear_after_frame": wear(row, original),
                          "confirmed_hit": shot.get("hit") is True,
                          "raw_shot": shot})
    result["per_arrow_actual_opportunity_payment_and_hit"] = per_arrow
    result["actual_release_attempt_telemetry"] = "UNKNOWN_original" if original else data["releaseEvents"]
    result["unresolved_live_airborne_arrows_at_window_end"] = [
        {"uuid": shot["uuid"], "spawn_frame": shot["spawnFrame"], "last_observed": shot["lastObserved"]}
        for shot in data["shots"] if shot.get("hit") is not True and shot.get("dead") is False
        and shot.get("lastObserved", {}).get("inGround") is False
    ]
    result.update({"bounded_current_input_script_recorded": True,
                   "python_vs_java_sin_plan_differences_exact_not_tolerance": plan_reference_differences,
                   "native_target_end_displacements_from_current_start_call": end_target_displacements,
                   "actual_target_fixed_for_entire_episode": not end_target_displacements and scene["id"] != "peek",
                   "opportunity_windows": windows, "full_finite_resource_and_wear_invariants": True})
    if finite:
        result["finite_ammo"] = finite_analysis(data, frames, original)
    else:
        exposed = scene["id"] == "stationary" or all(w["visible_frames"] > 0 and w["occluded_frames"] > 0 for w in windows[1:])
        result["scenario_inputs_exercised"] = exposed
        generic = exposed and bool(data["shots"]) and any(shot.get("hit") is True for shot in data["shots"])
        if scene["id"] == "peek":
            recovery = peek_recovery(data, original)
            result["pre_correction_generic_whole_window_hit_flag"] = generic
            result["peek_recovery"] = recovery
            result["business_success"] = (exposed and recovery["recovery_observed_in_both_obstructed_phases"]
                                          if recovery["qualified_visible_draw_window"] else None)
            result["business_status"] = recovery["business_status"]
            result["scenario_inputs_scope"] = "Native geometry/LOS switching; full-charge recovery qualification reported separately"
        else:
            result["business_success"] = generic
        result["business_success_is_not_statistical_or_full_B06_B07_admission"] = True
    return result, attributes, conditions


def peek_recovery(data, original):
    """Native joins after a real occlusion are distinct from hits of pre-obstruction arrows."""
    frames = data["frames"]
    target_id = data["targetInitial"]["uuid"]
    shots = data["shots"]
    damage = data["damageEvents"]
    hit_frames = {}
    for shot in shots:
        if shot.get("hit") is not True:
            continue
        events = [event for event in damage
                  if event["victim"] == target_id and not event["canceled"]
                  and event.get("directSource" if original else "source") == shot["uuid"]]
        candidates = sorted({event["frame"] for event in events})
        # Unambiguous native damage candidate + the already recorded END confirmed-hit fact.
        # Keep unknown timing unknown; never assign a hit to the arrow's spawn phase by convenience.
        hit_frames[shot["uuid"]] = candidates[0] if len(candidates) == 1 else None
    blocked = next((row["frame"] for row in frames[40:] if not row["targetVisible"]), None)
    visible_after = next((row["frame"] for row in frames
                          if blocked is not None and row["frame"] > blocked and row["targetVisible"]), None)
    first_recovery = next((shot["spawnFrame"] for shot in shots
                           if visible_after is not None and shot["spawnFrame"] >= visible_after
                           and frames[shot["spawnFrame"]]["targetVisible"]), None)
    phases = []
    for start, stop, geometry in ((0, 40, "clear"), (40, 140, "pillar"), (140, 260, "doorway")):
        rows = frames[start:stop]
        visible = [row["frame"] for row in rows if row["targetVisible"]]
        occluded = [row["frame"] for row in rows if not row["targetVisible"]]
        released = [shot for shot in shots if start <= shot["spawnFrame"] < stop]
        confirmed_here = [uid for uid, frame in hit_frames.items() if frame is not None and start <= frame < stop]
        recovery_visible = next((index for index in visible
                                 if any(not prior["targetVisible"] for prior in frames[40:index])), None)
        recovery_shot = next((shot["spawnFrame"] for shot in released
                              if recovery_visible is not None and shot["spawnFrame"] >= recovery_visible
                              and frames[shot["spawnFrame"]]["targetVisible"]), None)
        visible_runs = []
        run_start = None
        for row in rows:
            if row["targetVisible"] and run_start is None:
                run_start = row["frame"]
            elif not row["targetVisible"] and run_start is not None:
                visible_runs.append({"start": run_start, "end_exclusive": row["frame"], "ticks": row["frame"] - run_start})
                run_start = None
        if run_start is not None:
            visible_runs.append({"start": run_start, "end_exclusive": stop, "ticks": stop - run_start})
        maximum_visible = max((run["ticks"] for run in visible_runs), default=0)
        phases.append({"geometry": geometry, "start": start, "end_exclusive": stop,
                       "continuous_visible_windows": visible_runs,
                       "maximum_continuous_visible_ticks": maximum_visible,
                       "maximum_recorded_draw_ticks": max(row["usedTicks"] for row in rows),
                       "target_selected_frames": sum(row["targetSelected"] for row in rows),
                       "target_selected_all_frames": all(row["targetSelected"] for row in rows),
                       "qualified_visible_draw_window": maximum_visible >= 20,
                       "native_arrow_joins": len(released), "spawn_frames": [shot["spawnFrame"] for shot in released],
                       "confirmed_hits_from_arrows_launched_here": sum(shot.get("hit") is True for shot in released),
                       "confirmed_hits_during_this_phase": len(confirmed_here),
                       "confirmed_hit_arrow_uuids_during_this_phase": confirmed_here,
                       "visible_frames": len(visible), "occluded_frames": len(occluded),
                       "visibility_transitions": sum(a["targetVisible"] != b["targetVisible"] for a, b in zip(rows, rows[1:])),
                       "first_visible_frame_after_actual_prior_occlusion": recovery_visible,
                       "first_recovery_shot_frame": recovery_shot,
                       "ticks_from_first_restored_visible_frame_to_shot": None if recovery_shot is None else recovery_shot - recovery_visible,
                       "recovery_fire_observed": recovery_shot is not None,
                       "current_fact_opportunity_frames": sum(row.get("observedOpportunity", row.get("canObserveShotOpportunity", False)) for row in rows),
                       "release_gate_is_not_inferred_from_END_los": True})
    recovery = all(phase["recovery_fire_observed"] for phase in phases[1:])
    qualified = all(phase["qualified_visible_draw_window"] for phase in phases[1:])
    return {"phases": phases, "required_unenchanted_draw_ticks": 20,
            "visible_draw_threshold_scope": "Necessary continuous LOS input; not invented release permission or a guarantee that startup/cooldown also fit",
            "qualified_visible_draw_window": qualified, "recovery_shot_observed": first_recovery is not None,
            "business_status": "OBSERVED" if qualified and recovery else "NOT_OBSERVED" if qualified else "NOT_CHALLENGED",
            "first_actual_occluded_frame": blocked,
            "first_restored_visible_frame": visible_after, "first_recovery_shot_after_obstruction": first_recovery,
            "recovery_latency_from_first_restored_visible_frame": None if first_recovery is None else first_recovery - visible_after,
            "recovery_observed_in_both_obstructed_phases": recovery,
            "confirmed_hit_native_event_frames": hit_frames,
            "business_success_scope": "Actual natural re-fire after observed obstruction in both pillar and doorway phases; accuracy outcomes are listed separately",
            "no_shot_is_not_automatically_a_production_bug": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("original-captures", "original-build", "original-tool", "original-agent", "port-run", "candidate-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--devtools-jar", type=Path,
                        help="Separate devtools artifact; defaults to candidate for historical monolithic runs")
    parser.add_argument("--unified-tasks", action="store_true",
                        help="Verify Enhanced unified-task state and its retained ranged-command input")
    parser.add_argument("--allow-additional-port-tests", action="store_true")
    parser.add_argument("--allow-failed-additional-port-tests", action="store_true")
    args = parser.parse_args()
    raw = (args.original_tool / "combat-boundaries-contract.json").read_bytes()
    contract = json.loads(raw)
    require(contract["schema"] == "combat-boundaries-contract-v3"
            and contract["contract"] == "COMBAT_BOUNDARIES_V2" and contract["inputRevision"] == 1
            and contract.get("nutritionInputMapping", {}).get("originalRawHunger") == 0
            and contract.get("nutritionInputMapping", {}).get("portFood") == 20
            and contract.get("nutritionInputMapping", {}).get("naturalRegeneration") is False
            and contract["target"]["health"] == 1000 and contract["noninferiorityDelta"] is None
            and contract["repetitions"] == contract["independentSamples"] == 1
            and tuple(scene["id"] for scene in contract["mechanisms"]) == MECHANISMS,
            "unapproved supplementary combat/statistical contract")
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    cases = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    batch = verify_port_results(hqa, observed, cases, args.allow_additional_port_tests,
                                args.allow_failed_additional_port_tests, "tlmnhqapilot:CombatBoundaryPortTests", EXPECTED)
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes())
            and observed["pilot_sha256"] == sha(pilot.read_bytes()), "actual runtime artifacts changed")
    original_files = list(args.original_captures.glob("combat-boundary-original-*.json"))
    originals = [(path, json.loads(path.read_bytes())) for path in original_files]
    require(len(originals) == 3 and {data["mechanism"] for _, data in originals} == set(MECHANISMS),
            "missing/duplicate original boundary captures")
    build_bytes = args.original_build.read_bytes()
    require(json.loads(build_bytes)["contractSha256"] == sha(raw), "original build contract changed")
    actors, owners, mechanisms = set(), set(), []
    for scene in contract["mechanisms"]:
        path, original = next(value for value in originals if value[1]["mechanism"] == scene["id"])
        definitions = original_identity(original, build_bytes, args.original_tool, args.original_agent,
                                        ORIGINAL, "combat-boundaries-build-v1")
        require(original["contract"] == contract and original["scene"] == scene, "original captured boundary input changed")
        row, original_attrs, _ = episode(original, scene, sha(raw), True, unified_tasks=args.unified_tasks)
        rows, pairs, conditions, captures = [row], {}, {}, [original]
        paths = [{"arm": "O", "path": str(path.resolve()), "sha256": sha(path.read_bytes())}]
        for arm in scene["arms"][1:]:
            file = args.port_run / f"combat-boundary-{scene['id']}-{arm}.json"
            port = json.loads(file.read_bytes())
            captured = dict(port["contract"])
            captured.setdefault("noninferiorityDelta", None)
            require(captured == contract, "port captured boundary resource differs")
            for key, classes, jar in (
                ("productionArtifact", PRODUCTION, args.candidate_jar), ("observerArtifact", OBSERVER, pilot),
                ("combatObserverArtifact", COMBAT_OBSERVER, pilot), ("boundaryObserverArtifact", BOUNDARY_OBSERVER, pilot),
                ("spawnInputArtifact", SPAWN, args.devtools_jar or args.candidate_jar), ("sourceArrowArtifact", SOURCE, args.candidate_jar),
                ("combatRootArtifact", ROOT_CONTRACT, args.candidate_jar),
            ):
                artifact(port[key], jar, classes)
            row, attrs, physical = episode(port, scene, sha(raw), False, unified_tasks=args.unified_tasks)
            rows.append(row)
            conditions[arm] = physical
            captures.append(port)
            pairs[arm] = {"five_sourced_attributes": compare_attribute_inputs(original_attrs, attrs, contract["attributeInputs"]),
                          "actual_initial_inputs": {"original": original["actualInputs"], "port": port["actualInputs"]},
                          "target_full_native_inputs": {"original": original["targetInitial"], "port": port["targetInitial"]},
                          "source_vs_native_physics_different": arm != "F", "full_cross_engine_nbt_equality_claimed": False}
            paths.append({"arm": arm, "path": str(file.resolve()), "sha256": sha(file.read_bytes())})
        native = {}
        if "F-R" in conditions:
            baseline = next(data for data in captures if data["arm"] == "F-R")
            for arm, physical in conditions.items():
                if not arm.startswith("N"):
                    continue
                data = next(data for data in captures if data["arm"] == arm)
                pair = native_pair(conditions["F-R"], physical)
                baseline_calls = [row["targetInputBeforeWorldUpdate"]["position"] for row in baseline["frames"]]
                actual_calls = [row["targetInputBeforeWorldUpdate"]["position"] for row in data["frames"]]
                pair["actual_start_target_inputs_exact"] = baseline_calls == actual_calls
                pair["same_physics_comparison_eligible"] &= pair["actual_start_target_inputs_exact"]
                native[arm] = pair
        for data in captures:
            uid, owner_id = ((data["maidUuid"], data["actualInputs"]["owner"]["uuid"]) if data["arm"] == "O"
                             else (data["maidInitial"]["uuid"], data["ownerInitial"]["uuid"]))
            require(uid not in actors and owner_id not in owners, "actor/owner reused across independent arms")
            actors.add(uid)
            owners.add(owner_id)
        mechanisms.append({"mechanism": scene["id"], "observations": rows, "capture_inputs": paths,
                           "input_pairs_to_original": pairs, "native_pairs_to_F_R": native,
                           "original_resource_definition_differences": definitions})
    names = ("compare_combat_boundaries.py", "compare_combat_mechanisms.py", "compare_follow_mechanisms.py",
             "compare_port.py", "attribute_inputs.py", "nbt_binding.py", "check.py", "unified_task_inputs.py")
    result = {"schema": "combat-boundaries-comparison-v2", "analysis_revision": "peek-phase-recovery-v2",
              "observation_integrity_passed": True,
              "hqa_batch": batch, "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "analysis_inputs_sha256": {name: sha(Path(__file__).with_name(name).read_bytes()) for name in names},
              "original_runtime_build_sha256": sha(build_bytes), "contract_sha256": sha(raw), "mechanisms": mechanisms,
              "independent_actor_count": len(actors), "independent_owner_count": len(owners),
              "statistical_verdict": "NOT_EVALUATED", "noninferiority_delta": None, "full_release_passed": False,
              "complete_14_class_benchmark_accepted": False, "real_multiclient_owner_accepted": False,
              "remaining_contract_items": contract["remaining"], "old_four_mechanism_combat_evidence_replaced": False}
    if args.unified_tasks:
        result["schema"] = "combat-boundaries-comparison-unified-v1"
        result["analysis_revision"] = "unified-task-input-v1"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "mechanisms"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
