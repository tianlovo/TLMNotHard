"""原移动sink三次新状态A/A，以及真实移植组件的状态与调用对照"""

import argparse
import copy
import hashlib
import json
import math
import struct
import uuid
from pathlib import Path

TOOL = Path(__file__).resolve().parent
BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
INPUT_SCHEMA = "move-sink-input-observations-v1"
if not __debug__:
    raise RuntimeError("Do not disable verification assertions with Python optimization")


def load(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON key: " + key)
            result[key] = value
        return result

    def finite(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("Non-finite JSON number")
        return number

    return json.loads(Path(path).read_text(encoding="utf-8"),
                      object_pairs_hook=pairs, parse_float=finite,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def first_difference(left, right, path="$"):
    numeric = isinstance(left, (int, float)) and isinstance(right, (int, float))
    if (type(left) is not type(right) and not numeric) or isinstance(left, bool) != isinstance(right, bool):
        return {"path": path, "reference": left, "actual": right}
    if isinstance(left, dict) and isinstance(right, dict):
        if left.keys() != right.keys():
            return {"path": path, "referenceKeys": sorted(left), "actualKeys": sorted(right)}
        for key in left:
            result = first_difference(left[key], right[key], path + "." + key)
            if result:
                return result
    elif isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return {"path": path, "referenceLength": len(left), "actualLength": len(right)}
        for index, (a, b) in enumerate(zip(left, right)):
            result = first_difference(a, b, f"{path}[{index}]")
            if result:
                return result
    elif left != right:
        return {"path": path, "reference": left, "actual": right}
    return None


def digest_shape(value):
    return isinstance(value, str) and len(value) == 64 and all(char in "0123456789abcdef" for char in value)


def verify_input_binding(reference, contract):
    """Verify fixture inputs and continuity, never calculate a sink result."""
    assert reference["inputObservationSchema"] == INPUT_SCHEMA, "Missing reviewed actual-input observations"
    checks = 0

    def equal(actual, expected, where):
        nonlocal checks
        checks += 1
        difference = first_difference(expected, actual, where)
        assert difference is None, "Actual input binding differs: " + str(difference)

    def merge(destination, patch):
        for key, value in patch.items():
            if isinstance(value, dict) and isinstance(destination.get(key), dict):
                merge(destination[key], value)
            else:
                destination[key] = copy.deepcopy(value)

    def vector(value, integral, where):
        assert isinstance(value, list) and len(value) == 3, where
        assert all(type(item) is int if integral else type(item) in (int, float) and math.isfinite(item) for item in value), where
        return value

    def centered(block):
        return [block[0] + .5, block[1], block[2] + .5]

    def observed(value, state, tracker_type, target_uuid, where):
        for key in ("maidBlock", "targetEntityBlock"):
            vector(value[key], True, where + "." + key)
        for key in ("maidPosition", "targetEntityPosition"):
            vector(value[key], False, where + "." + key)
        assert type(value["targetSpectator"]) is bool and type(value["worldGameTime"]) is int, where
        walk, tracker = state["memory"]["walk"], value["walkTracker"]
        if walk is None:
            equal(tracker, None, where + ".walkTracker")
        else:
            assert isinstance(tracker, dict), where
            equal(tracker["type"], tracker_type, where + ".trackerType")
            equal(tracker["block"], walk["block"], where + ".trackerBlock")
            vector(tracker["currentPosition"], False, where + ".trackerCurrentPosition")
            if tracker_type == "entity":
                equal(tracker["entityIsConfiguredTarget"], True, where + ".configuredTargetIdentity")
                equal(tracker["block"], value["targetEntityBlock"], where + ".actualEntityTrackerBlock")
                equal(tracker["entitySpectator"], value["targetSpectator"], where + ".actualEntitySpectator")
                equal(walk["entityTrackerSpectator"], value["targetSpectator"], where + ".walkSpectator")
                assert isinstance(tracker["entityRuntimeClass"], str) and tracker["entityRuntimeClass"], where
                assert str(uuid.UUID(tracker["entityUuid"])) == tracker["entityUuid"], where
                if target_uuid is not None:
                    equal(tracker["entityUuid"], target_uuid, where + ".targetUuidContinuity")
                target_uuid = tracker["entityUuid"]
            else:
                equal(tracker_type, "block", where + ".supportedTrackerType")
                equal(walk["entityTrackerSpectator"], False, where + ".blockIsNotEntitySpectator")
                for key in ("entityIsConfiguredTarget", "entityUuid", "entityRuntimeClass", "entitySpectator"):
                    equal(tracker[key], None, where + "." + key)
        return target_uuid

    def f32(value):
        assert type(value) in (int, float) and math.isfinite(value)
        return struct.unpack("!f", struct.pack("!f", value))[0]

    def memory_equal(actual, expected, where):
        for key in ("registered", "path", "cantReachSince"):
            equal(actual[key], expected[key], where + "." + key)
        if actual["walk"] is None or expected["walk"] is None:
            equal(actual["walk"], expected["walk"], where + ".walk")
        else:
            for key in ("block", "closeEnough", "entityTrackerSpectator"):
                equal(actual["walk"][key], expected["walk"][key], where + ".walk." + key)
            equal(f32(actual["walk"]["speed"]), f32(expected["walk"]["speed"]), where + ".walk.speed(float32)")

    specs = {case["id"]: case for case in contract["cases"]}
    rows, method_steps = 0, 0
    for row in reference["cases"]:
        spec = specs[row["id"]]
        config = copy.deepcopy(spec["initial"])
        where = row["id"] + "/" + str(row["rebuild"])
        equal(row["paths"], spec["paths"], where + ".actualPaths")
        initial = row["initialState"]
        observation = row["initialInputObservation"]
        for actual_key, planned_key in (("maidBlock", "maidBlock"), ("targetEntityBlock", "targetBlock"), ("targetSpectator", "targetSpectator")):
            equal(observation[actual_key], config[planned_key], where + ".initial." + actual_key)
        equal(observation["maidPosition"], centered(config["maidBlock"]), where + ".initial.maidPosition")
        equal(observation["targetEntityPosition"], centered(config["targetBlock"]), where + ".initial.targetPosition")
        equal(row["initialMaidBlock"], observation["maidBlock"], where + ".initialMaidAlias")
        equal(row["initialTargetBlock"], observation["targetEntityBlock"], where + ".initialTargetAlias")
        equal(row["initialTargetSpectator"], observation["targetSpectator"], where + ".initialSpectatorAlias")
        flags = config["registered"]
        tracker_type = config["walk"]["tracker"]
        expected_walk = None
        if flags["walk"] and config["walk"]["present"]:
            expected_walk = {"block": config["targetBlock"], "speed": config["walk"]["speed"],
                             "closeEnough": config["walk"]["closeEnough"],
                             "entityTrackerSpectator": tracker_type == "entity" and config["targetSpectator"]}
        memory_equal(initial["memory"], {"registered": flags, "walk": expected_walk,
                     "path": config["pathMemory"] if flags["path"] else None,
                     "cantReachSince": config["cantReachSince"] if flags["cantReachSince"] else None}, where + ".initial.memory")
        equal(initial["sink"], {"status": "STOPPED", "remainingCooldown": 0, "path": None,
                               "lastTargetPos": None, "speedModifier": 0, "endTimestamp": 0}, where + ".newSinkInitialState")
        equal(initial["navigation"], {key: config["navigation"][key] for key in ("path", "done", "stuck")}, where + ".initial.navigation")
        target_uuid = observed(observation, initial, tracker_type, None, where + ".initialObservation")
        previous = initial
        previous_observation = observation
        replies, world_values = [], []
        pending_callback = config["onCreateMaidBlock"]
        for index, (step, planned) in enumerate(zip(row["steps"], spec["steps"])):
            at = where + ".steps[" + str(index) + "]"
            patch = planned["patch"]
            merge(config, patch)
            before, after = step["before"], step["after"]
            before_input, after_input = step["inputBeforeInvocation"], step["inputAfterInvocation"]
            for key in ("maidBlock", "maidPosition", "targetEntityBlock", "targetEntityPosition", "targetSpectator"):
                expected = previous_observation[key]
                if key in ("maidBlock", "maidPosition") and "maidBlock" in patch:
                    expected = config["maidBlock"] if key == "maidBlock" else centered(config["maidBlock"])
                if key in ("targetEntityBlock", "targetEntityPosition") and "targetBlock" in patch:
                    expected = config["targetBlock"] if key == "targetEntityBlock" else centered(config["targetBlock"])
                if key == "targetSpectator" and "targetSpectator" in patch:
                    expected = config["targetSpectator"]
                equal(before_input[key], expected, at + ".actualBefore." + key)
            equal(before_input["worldGameTime"], planned["worldGameTime"], at + ".actualBefore.worldClock")
            invocation = step["methodInvocation"]
            equal(invocation, {"operation": planned["operation"], "methodTime": planned["methodTime"],
                              "worldGameTime": planned["worldGameTime"], "entityIsFixtureMaid": True,
                              "worldIsFixtureLevel": True}, at + ".actualInvocationArguments")
            memory = copy.deepcopy(previous["memory"])
            navigation = copy.deepcopy(previous["navigation"])
            if "registered" in patch:
                for key, value in config["registered"].items():
                    if not value or not memory["registered"][key]:
                        memory[key] = None
                    memory["registered"][key] = value
            if "walk" in patch:
                tracker_type = config["walk"]["tracker"]
                memory["walk"] = ({"block": before_input["targetEntityBlock"], "speed": config["walk"]["speed"],
                                    "closeEnough": config["walk"]["closeEnough"],
                                    "entityTrackerSpectator": tracker_type == "entity" and before_input["targetSpectator"]}
                                   if memory["registered"]["walk"] and config["walk"]["present"] else None)
            if memory["walk"] is not None and tracker_type == "entity":
                memory["walk"]["block"] = before_input["targetEntityBlock"]
                memory["walk"]["entityTrackerSpectator"] = before_input["targetSpectator"]
            for patch_key, memory_key, registration_key in (("pathMemory", "path", "path"), ("cantReachSince", "cantReachSince", "cantReachSince")):
                if patch_key in patch:
                    memory[memory_key] = config[patch_key] if memory["registered"][registration_key] else None
            if "navigation" in patch:
                for key in ("path", "done", "stuck"):
                    if key in patch["navigation"]:
                        navigation[key] = config["navigation"][key]
            memory_equal(before["memory"], memory, at + ".patchedMemoryAndContinuity")
            equal(before["navigation"], navigation, at + ".patchedNavigationAndContinuity")
            equal(before["sink"], previous["sink"], at + ".unpatchedSinkContinuity")
            target_uuid = observed(before_input, before, tracker_type, target_uuid, at + ".beforeObservation")
            if "createReplies" in patch:
                equal(replies, [], at + ".previousRepliesConsumed")
                replies = list(patch["createReplies"])
            if "onCreateMaidBlock" in patch:
                pending_callback = config["onCreateMaidBlock"]
            creates = [call for call in step["callTrace"] if call["op"] == "createPath"]
            for call in creates:
                assert replies, at + ".createReplyWithoutPlannedDependency"
                equal(call["resultPath"], replies.pop(0), at + ".actualCreateReply")
                equal(call["accuracy"], 0, at + ".actualCreateAccuracy")
                if call["entry"] == "block":
                    equal(call["target"], before["memory"]["walk"]["block"], at + ".actualQueryTarget")
                else:
                    equal(call["entry"], "coordinate_wrapper", at + ".supportedQueryEntry")
                    equal(call["target"], step["fallbackObservation"]["queryBlock"], at + ".actualFallbackQuery")
                assert call["resultPath"] is None or call["resultPath"] in row["paths"], at
            callbacks = step["callbackObservations"]
            actual_maid_block = before_input["maidBlock"]
            actual_maid_position = before_input["maidPosition"]
            if creates and pending_callback is not None:
                equal(len(callbacks), 1, at + ".callbackCount")
                callback = callbacks[0]
                equal(callback["createIndex"], 0, at + ".callbackQueryIndex")
                equal(callback["from"], actual_maid_block, at + ".callbackActualFrom")
                equal(callback["to"], pending_callback, at + ".callbackRequestedDestination")
                equal(callback["actualAfterBlock"], pending_callback, at + ".callbackActualDestination")
                actual_maid_block = callback["actualAfterBlock"]
                actual_maid_position = centered(actual_maid_block)
                pending_callback = None
            else:
                equal(callbacks, [], at + ".noUnplannedCallback")
            equal(after_input["maidBlock"], actual_maid_block, at + ".actualAfter.maidBlock")
            equal(after_input["maidPosition"], actual_maid_position, at + ".actualAfter.maidPosition")
            for key in ("targetEntityBlock", "targetEntityPosition", "targetSpectator", "worldGameTime"):
                equal(after_input[key], before_input[key], at + ".unchangedExternalInput." + key)
            target_uuid = observed(after_input, after, tracker_type, target_uuid, at + ".afterObservation")
            moves = [call for call in step["callTrace"] if call["op"] == "moveTo"]
            equal(len(moves), len(step["moveObservations"]), at + ".moveObservationCount")
            move_index = 0
            for call in step["callTrace"]:
                if call["op"] == "worldNextInt":
                    assert type(call["bound"]) is int and type(call["value"]) is int and 0 <= call["value"] < call["bound"], at
                    world_values.append(call["value"])
                elif call["op"] == "moveTo":
                    move = step["moveObservations"][move_index]
                    move_index += 1
                    equal({key: move[key] for key in ("path", "speed", "result")},
                          {key: call[key] for key in ("path", "speed", "result")}, at + ".actualMoveObservation")
                    equal(call["result"], config["navigation"]["moveResult"], at + ".actualMoveReturn")
                    equal(move["before"], navigation, at + ".actualMoveBefore")
                    equal(move["after"], {"path": call["path"], "done": False, "stuck": navigation["stuck"]}, at + ".controlledMoveDependency")
                    navigation = copy.deepcopy(move["after"])
                elif call["op"] == "stopNavigation":
                    navigation["path"], navigation["done"] = None, True
                else:
                    equal(call["op"], "createPath", at + ".knownDependencyOperation")
            equal(after["navigation"], navigation, at + ".controlledNavigationAfter")
            helper = step["fallbackObservation"]
            for item in helper["stabilityChecks"]:
                equal(item["stable"], config["stableDestination"], at + ".actualStabilityReturn")
            for item in helper["malusChecks"]:
                equal(f32(item["value"]), f32(config["malus"]), at + ".actualMalusReturn")
            for item in helper["scores"]:
                equal(config["scoreMode"], "constant", at + ".reviewedScoreDependency")
                equal(item["score"], 1, at + ".actualConstantScore")
            previous, previous_observation = after, after_input
            method_steps += 1
        equal(replies, [], where + ".plannedRepliesConsumed")
        equal(world_values, spec["worldInts"], where + ".actualWorldIntegerFeed")
        rows += 1
    return {"schema": INPUT_SCHEMA, "verified": True, "checks": checks, "rows": rows, "methodSteps": method_steps,
            "scope": "actual fixture inputs, invocation arguments and explicit patch/continuity; no sink outputs recomputed"}


def verified_rows(reference, build_path):
    build = load(build_path)
    contract = load(TOOL / "contract.json")
    profile = load(TOOL / "identity-classes.json")
    identity = reference["identity"]
    assert reference["schema"] == "move-sink-reference-v1" and reference["complete"] is True
    assert reference["contract"] == "MOVE_SINK_CORE_V1"
    assert reference["naturalBrainVerified"] is False and reference["nativeNavigationVerified"] is False
    assert build["schema"] == "move-reference-build-v1" and build["status"] == "BASE_LOCK_VERIFIED"
    assert build["upstreamCommit"] == identity["upstreamCommit"] == BASE
    assert build["minecraft"] == "1.20.1" and build["forge"] == "47.2.0" and build["javaFeature"] == 17
    assert build["contractSha256"] == reference["contractSha256"] == sha(TOOL / "contract.json")
    assert build["identityClassesSha256"] == sha(TOOL / "identity-classes.json")
    assert identity["status"] == "VERIFIED_SELECTED_COMPONENT_CLASSES"
    assert identity["buildIdentitySha256"] == sha(build_path)
    assert identity["agentSha256"] == build["definitionAgentSha256"]
    assert identity["baseReferenceBuildIdentitySha256"] == build["baseReferenceBuildIdentitySha256"]
    assert build["baseReferenceBuildIdentity"]["status"] == "LOCK_VERIFIED"
    assert set(identity["classes"]) == set(profile["classes"]) == set(build["expectedResources"])
    for name, item in identity["classes"].items():
        assert item["definitionCount"] == 1 and digest_shape(item["definitionSha256"])
        assert item["resourceSha256"] == build["expectedResources"][name]["resourceSha256"]
        assert digest_shape(item["resourceSha256"])
    specs = {case["id"]: case for case in contract["cases"]}
    assert len(specs) == len(contract["cases"])
    rows = {}
    for row in reference["cases"]:
        key = (row["id"], row["rebuild"])
        assert key not in rows and row["complete"] is True
        spec = specs[row["id"]]
        assert row["input"] == spec
        assert len(row["steps"]) == len(spec["steps"])
        for index, (step, planned) in enumerate(zip(row["steps"], spec["steps"])):
            assert step["index"] == index
            assert step["operation"] == planned["operation"]
            assert step["methodTime"] == planned["methodTime"] and step["worldGameTime"] == planned["worldGameTime"]
            assert step["input"] == {"externalPatch": planned["patch"]}
            for field in ("before", "after", "methodReturn", "callTrace", "moveObservations", "callbackObservations",
                          "worldRandomCalls", "maidRandomCalls", "fallbackObservation"):
                assert field in step, "Incomplete actual step"
        rows[key] = row
    assert set(rows) == {(name, rebuild) for name in specs for rebuild in range(3)}, "Incomplete or extra original rows"
    verify_input_binding(reference, contract)
    return rows


def compare(reference_path, build_path, replay_path=None):
    reference = load(reference_path)
    rows = verified_rows(reference, build_path)
    report = {"schema": "move-component-comparison-v1", "passed": False, "originalAA": False,
              "contractSha256": reference["contractSha256"], "referenceSha256": sha(reference_path),
              "comparatorSha256": sha(Path(__file__)), "inputBindingVerified": True,
              "inputObservationSchema": INPUT_SCHEMA,
              "scope": "original sink/Behavior with controlled callouts; not native navigation, full fallback implementation or natural Brain"}
    for case_id in sorted({key[0] for key in rows}):
        first = {key: value for key, value in rows[(case_id, 0)].items() if key != "rebuild"}
        for rebuild in (1, 2):
            actual = {key: value for key, value in rows[(case_id, rebuild)].items() if key != "rebuild"}
            diff = first_difference(first, actual)
            if diff:
                report["firstDifference"] = {"case": case_id, "rebuild": rebuild, **diff}
                return report
    report["originalAA"] = True
    report["freshBuildsPerCase"] = 3
    report["cases"] = len(rows)
    report["methodSteps"] = sum(len(row["steps"]) for row in rows.values())
    if replay_path:
        replay = load(replay_path)
        assert replay["schema"] == "move-sink-port-replay-v1" and replay["contractSha256"] == reference["contractSha256"]
        assert replay["inputObservationSchema"] == INPUT_SCHEMA, "Replay omitted the reviewed actual-input contract"
        actual_rows = {}
        for row in replay["cases"]:
            key = (row["id"], row["rebuild"])
            assert key not in actual_rows
            actual_rows[key] = row
        assert actual_rows.keys() == rows.keys(), "Replay row set differs"
        for key, original in rows.items():
            actual = actual_rows[key]
            if len(original["steps"]) != len(actual["steps"]):
                report["firstDifference"] = {"case": key[0], "rebuild": key[1], "path": "$.steps.length"}
                return report
            for index, (a, b) in enumerate(zip(original["steps"], actual["steps"])):
                fields = ("before", "after", "methodReturn", "callTrace")
                diff = first_difference({field: a[field] for field in fields}, {field: b[field] for field in fields})
                if diff:
                    report["firstDifference"] = {"case": key[0], "rebuild": key[1], "step": index, **diff}
                    return report
        report["replaySha256"] = sha(replay_path)
        report["portControlledComponentEquivalent"] = True
    report["passed"] = True
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--build-identity", required=True, type=Path)
    parser.add_argument("--replay", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = compare(args.reference, args.build_identity, args.replay)
    except (AssertionError, KeyError, TypeError, ValueError, OSError, OverflowError, struct.error) as failure:
        report = {"schema": "move-component-comparison-v1", "passed": False,
                  "errorType": type(failure).__name__, "error": str(failure)}
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(text)
    print(text, end="")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
