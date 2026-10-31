"""核原版LOOK与三行为组件的身份、实际输入和三份新状态A/A，不计算AI评分"""

import argparse
import copy
import hashlib
import json
import math
import re
from pathlib import Path

TOOL = Path(__file__).resolve().parent
BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
CONTRACTS = ["LOOK_SINK_CORE_V1", "FOLLOW_MOVE_LOOK_COMPONENT_V2"]
if not __debug__:
    raise RuntimeError("Do not disable verification assertions")


def load(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON key: " + key)
            result[key] = value
        return result

    def finite(value):
        result = float(value)
        require(math.isfinite(result), "Non-finite JSON float")
        return result

    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=pairs, parse_float=finite,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    require(isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None, "Invalid digest")


def integer(value):
    require(type(value) is int, "Strict integer required")
    return value


def number(value):
    require(type(value) in (int, float) and math.isfinite(value), "Finite numeric scalar required")
    return value


def boolean(value):
    require(type(value) is bool, "Strict boolean required")
    return value


def vector(value, integral=False):
    require(isinstance(value, list) and len(value) == 3, "Three coordinate values required")
    for item in value:
        integer(item) if integral else number(item)
    return value


def difference(left, right, path="$"):
    if isinstance(left, bool) != isinstance(right, bool):
        return {"path": path, "reference": left, "actual": right}
    if isinstance(left, dict) and isinstance(right, dict):
        if left.keys() != right.keys():
            return {"path": path, "referenceKeys": sorted(left), "actualKeys": sorted(right)}
        for key in left:
            found = difference(left[key], right[key], path + "." + key)
            if found:
                return found
    elif isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return {"path": path, "referenceLength": len(left), "actualLength": len(right)}
        for index, (a, b) in enumerate(zip(left, right)):
            found = difference(a, b, f"{path}[{index}]")
            if found:
                return found
    elif type(left) is not type(right) and not (type(left) in (int, float) and type(right) in (int, float)):
        return {"path": path, "reference": left, "actual": right}
    elif left != right:
        return {"path": path, "reference": left, "actual": right}
    return None


def equal(actual, expected, where):
    found = difference(expected, actual, where)
    require(found is None, "Actual input continuity differs: " + str(found))


def tracker(value):
    if value is None:
        return
    require(isinstance(value, dict), "Tracker object required")
    require(isinstance(value["identity"], str) and re.fullmatch("t[1-9][0-9]*", value["identity"]), "Stable tracker identity missing")
    equal(value["class"], "net.minecraft.world.entity.ai.behavior.EntityTracker", "tracker.class")
    equal(value["target"], "owner", "tracker.target")
    boolean(value["trackEye"])
    vector(value["position"])
    vector(value["block"], True)


def input_shape(value):
    integer(value["worldGameTime"])
    for key in ("maidPosition", "ownerPosition", "ownerEyePosition"):
        vector(value[key])
    number(value["ownerEyeHeight"])
    number(value["radius"])
    for key in ("ownerAlive", "ownerSpectator", "ownerResolved", "home", "canBrainMove", "breathing",
                "lookRegistered", "visibleMemoryPresent", "visibleCandidateContainsOwner", "worldRegisteredMaid", "worldRegisteredOwner"):
        boolean(value[key])
    require(value["lookRegistered"] and value["ownerResolved"] and value["ownerAlive"] and not value["ownerSpectator"], "Controlled owner domain unavailable")
    require(not value["worldRegisteredMaid"] and not value["worldRegisteredOwner"], "Component silently became registered/natural")
    require(not value["home"] and not value["breathing"] and value["canBrainMove"], "Outside dry component scope")
    tracker(value["look"])
    equal(value["ownerEyePosition"], [value["ownerPosition"][0], value["ownerPosition"][1] + value["ownerEyeHeight"], value["ownerPosition"][2]], "ownerEyePosition")
    if value["look"] is not None:
        expected = value["ownerEyePosition"] if value["look"]["trackEye"] else value["ownerPosition"]
        equal(value["look"]["position"], expected, "actual tracker currentPosition")
        equal(value["look"]["block"], [math.floor(v) for v in value["ownerPosition"]], "tracker foot block")


def state_shape(state):
    memory = state["memory"]
    tracker(memory["look"])
    if memory["walk"] is not None:
        tracker(memory["walk"]["tracker"])
        number(memory["walk"]["speed"])
        integer(memory["walk"]["closeEnough"])
    if memory["cantReachSince"] is not None:
        integer(memory["cantReachSince"])
    for value in state["tasks"].values():
        require(value["status"] in ("STOPPED", "RUNNING"), "Unknown Behavior status")
        integer(value["endTimestamp"])
    require(set(state["tasks"]) == {"look", "move", "follow"}, "Selected behavior set differs")
    integer(state["tasks"]["move"]["remainingCooldown"])
    number(state["tasks"]["move"]["speedModifier"])
    if state["tasks"]["move"]["lastTargetPos"] is not None:
        vector(state["tasks"]["move"]["lastTargetPos"], True)
    control_shape(state["lookControl"])
    boolean(state["navigation"]["done"])
    boolean(state["navigation"]["stuck"])
    integer(state["worldIntegerIndex"])


def control_shape(value):
    vector(value["wanted"])
    require(integer(value["cooldown"]) >= 0, "Negative native look cooldown")
    number(value["yawSpeed"])
    number(value["pitchSpeed"])


def identity(reference, build_path):
    build = load(build_path)
    profile = load(TOOL / "identity-classes.json")
    runtime = reference["identity"]
    equal(build["schema"], "look-reference-build-v1", "build.schema")
    equal(build["status"], "BASE_LOCK_VERIFIED", "build.status")
    equal(runtime["schema"], "look-reference-runtime-v1", "runtime.schema")
    equal(runtime["status"], "VERIFIED_SELECTED_COMPONENT_CLASSES", "runtime.status")
    equal(build["upstreamCommit"], BASE, "build.upstream")
    equal(runtime["upstreamCommit"], BASE, "runtime.upstream")
    equal(build["minecraft"], "1.20.1", "minecraft")
    equal(build["forge"], "47.2.0", "forge")
    equal(integer(build["javaFeature"]), 17, "javaFeature")
    equal(runtime["buildIdentitySha256"], sha(build_path), "runtime build binding")
    equal(build["contractSha256"], sha(TOOL / "contract.json"), "actual contract")
    equal(reference["contractSha256"], build["contractSha256"], "capture contract")
    equal(build["identityClassesSha256"], sha(TOOL / "identity-classes.json"), "selected profile")
    equal(runtime["agentSha256"], build["definitionAgentSha256"], "premain observer")
    digest(runtime["agentSha256"])
    equal(runtime["baseReferenceBuildIdentitySha256"], build["baseReferenceBuildIdentitySha256"], "base manifest binding")
    digest(runtime["baseReferenceBuildIdentitySha256"])
    base = build["baseReferenceBuildIdentity"]
    require(base["schema"] == "reference-build-identity-v1" and base["status"] == "LOCK_VERIFIED" and base["upstreamCommit"] == BASE,
            "Verified fixed original build absent")
    selected = profile["classes"]
    require(len(selected) == len(set(selected)) and set(selected) == set(runtime["classes"]) == set(build["expectedResources"]), "Selected class set differs")
    equal(set(profile["compiledClasses"]), set(build["compiledClasses"]), "compiled class set")
    for name, observed in runtime["classes"].items():
        equal(integer(observed["definitionCount"]), 1, "definition count")
        digest(observed["definitionSha256"])
        digest(observed["resourceSha256"])
        equal(observed["stage"], "premain-transformer-input-after-defining-loader-transformations", "definition stage")
        equal(observed["resourceSha256"], build["expectedResources"][name]["resourceSha256"], "class resource binding")
        if name in build["compiledClasses"]:
            equal(observed["resourceSha256"], build["compiledClasses"][name], "compiled bytes binding")
    inputs = {}
    for entry in build["toolInputs"]:
        path = Path(entry["file"])
        require(not path.is_absolute() and not re.match(r"^[A-Za-z]:|^[\\/]", entry["file"])
                and "\\" not in entry["file"] and ".." not in path.parts and entry["file"] not in inputs, "Invalid tool input path")
        actual_path = TOOL / path
        require(actual_path.resolve().is_relative_to(TOOL.resolve()), "Tool input escaped tool directory")
        for ancestor in (actual_path, *actual_path.parents):
            if ancestor == TOOL:
                break
            require(not ancestor.is_symlink() and not (hasattr(ancestor, "is_junction") and ancestor.is_junction()), "Redirected tool input")
        digest(entry["sha256"])
        equal(entry["sha256"], sha(TOOL / path), "actual tool source binding")
        inputs[entry["file"]] = entry["sha256"]
    expected = {str(p.relative_to(TOOL)).replace("\\", "/") for p in TOOL.rglob("*") if p.is_file()
                and "__pycache__" not in p.parts and p.suffix in (".java", ".gradle", ".json", ".py")}
    equal(set(inputs), expected, "complete tool source set")
    return build



def validate_initial(row, spec, fault):
    """Freeze fixture construction inputs/state, not any policy result."""
    observed, state = row["initialInputObservation"], row["initialState"]
    equal(observed["maidPosition"], [.5, 192, .5], "new fixture maid position")
    equal(observed["radius"], 8, "new fixture radius")
    require(number(observed["ownerEyeHeight"]) > 0, "Positive observed eye height required")
    equal(observed["worldGameTime"], spec["steps"][0]["methodTime"], "new fixture initial clock")
    equal(observed["visibleMemoryPresent"], True, "new fixture visible memory")
    equal(observed["visibleCandidateContainsOwner"], spec["initial"]["visibleMemory"] == "owner", "initial visibility candidates")
    equal(observed["look"] is not None, spec["initial"]["lookPresent"], "new fixture initial LOOK")
    if observed["look"] is not None:
        expected_eye = spec["initial"]["trackEye"] and not (fault == "target-feet" and row["id"] == "L01_min_duration")
        equal(observed["look"]["trackEye"], expected_eye, "new fixture tracker eye input")
    equal(state["memory"], {"look": observed["look"], "walk": None, "path": None, "cantReachSince": None}, "new fixture memory")
    stopped = {"status": "STOPPED", "endTimestamp": 0}
    equal(state["tasks"], {"look": stopped, "follow": stopped,
                          "move": {**stopped, "remainingCooldown": 0, "path": None, "lastTargetPos": None, "speedModifier": 0}},
          "new original behavior state")
    equal(state["lookControl"], {"wanted": [0, 0, 0], "cooldown": 0, "yawSpeed": 0, "pitchSpeed": 0}, "new native control state")
    equal(state["navigation"], {"path": None, "done": True, "stuck": False,
                                "source": "controlled full path; no native navigation tick"}, "new controlled navigation")
    equal(state["worldIntegerIndex"], 0, "new world integer cursor")


def bind_method_inputs(before_patch, before_method, after_method, patch, config, actual_time):
    """Only explicit fixture patches and their coordinate derivatives may change inputs."""
    require(set(patch) <= {"targetPosition", "lookPresent", "trackEye", "visibleMemory"}, "Unsupported explicit input patch")
    expected = copy.deepcopy(before_patch)
    expected["worldGameTime"] = actual_time
    if "targetPosition" in patch:
        expected["ownerPosition"] = copy.deepcopy(config["targetPosition"])
        at, eye = expected["ownerPosition"], expected["ownerEyeHeight"]
        expected["ownerEyePosition"] = [at[0], at[1] + eye, at[2]]
        if expected["look"] is not None:
            expected["look"]["position"] = copy.deepcopy(expected["ownerEyePosition"] if expected["look"]["trackEye"] else at)
            expected["look"]["block"] = [math.floor(value) for value in at]
    if "lookPresent" in patch or "trackEye" in patch:
        equal(before_method["look"] is not None, config["lookPresent"], "explicit LOOK presence input")
        # The new identity is observed, not synthesized; input_shape verifies entity/class/coordinates
        expected["look"] = copy.deepcopy(before_method["look"])
    if "visibleMemory" in patch:
        expected["visibleMemoryPresent"] = True
        expected["visibleCandidateContainsOwner"] = config["visibleMemory"] == "owner"
    equal(before_method, expected, "unpatched actual inputs before original invocation")
    expected_after = copy.deepcopy(before_method)
    expected_after["look"] = copy.deepcopy(after_method["look"])
    equal(after_method, expected_after, "read-only actual inputs after original invocation")



def bind_memory_before(previous_memory, before_memory, observed_inputs):
    """Fixture patches may replace LOOK or move an existing entity tracker, never rewrite WALK/PATH policy state."""
    expected = copy.deepcopy(previous_memory)
    expected["look"] = copy.deepcopy(observed_inputs["look"])
    if expected["walk"] is not None:
        value = expected["walk"]["tracker"]
        value["position"] = copy.deepcopy(observed_inputs["ownerEyePosition"] if value["trackEye"] else observed_inputs["ownerPosition"])
        value["block"] = [math.floor(coordinate) for coordinate in observed_inputs["ownerPosition"]]
    equal(before_memory, expected, "fixture must preserve unpatched policy memory")


def original_aa_difference(rows):
    for name in sorted({key[0] for key in rows}):
        first = {key: value for key, value in rows[(name, 0)].items() if key != "rebuild"}
        for rebuild in (1, 2):
            found = difference(first, {key: value for key, value in rows[(name, rebuild)].items() if key != "rebuild"})
            if found:
                return {"case": name, "rebuild": rebuild, **found}
    return None


def admit_baseline(reference, baseline, baseline_rows):
    """Both reports have already passed identity() and checked_rows()."""
    require(baseline["fault"] == "none", "Fault capture cannot serve as normal baseline")
    equal(reference["identity"]["classes"], baseline["identity"]["classes"], "normal baseline actual definition identity")
    require(original_aa_difference(baseline_rows) is None, "Normal baseline did not pass three fresh-state A/A")


def checked_rows(reference, contract):
    equal(reference["schema"], "look-reference-capture-v1", "capture.schema")
    equal(reference["inputObservationSchema"], "look-component-input-observations-v1", "observed inputs")
    equal(reference["contracts"], CONTRACTS, "contracts")
    require(reference["complete"] is True and reference["naturalBrainVerified"] is False
            and reference["nativeHelperTicked"] is False and reference["nativeNavigationVerified"] is False, "Capture scope or completeness differs")
    require(reference["fault"] in ("none", "deadline-equality-plus-one", "target-feet", "look-priority-last"), "Unknown actual fault mode")
    specs = {row["id"]: row for row in contract["cases"]}
    require(len(specs) == len(contract["cases"]), "Duplicate case ids")
    rows = {}
    for row in reference["cases"]:
        key = (row["id"], integer(row["rebuild"]))
        require(key not in rows and key[0] in specs and key[1] in range(3), "Unexpected/duplicate component row")
        require(row["complete"] is True and row["cleanupComplete"] is True and row["worldRandomRestored"] is True
                and row["worldClockRestored"] is True, "Incomplete observation/cleanup")
        spec = specs[key[0]]
        equal(row["input"], spec, "contract input")
        equal(row["contract"], CONTRACTS[0 if spec["kind"] == "look" else 1], "row component scope")
        require(len(row["steps"]) == len(spec["steps"]), "Missing or extra original method steps")
        require(row["coreActivityActive"] is True, "Selected CORE activity not active")
        registrations = row["registeredBehaviors"]
        require(len(registrations) == 3 and {value["role"] for value in registrations} == {"look", "move", "follow"}, "Actual registered behavior set differs")
        role_classes = {"look": "net.minecraft.world.entity.ai.behavior.LookAtTargetSink",
                        "move": "net.minecraft.world.entity.ai.behavior.MoveToTargetSink",
                        "follow": "com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask"}
        for value in registrations:
            priority = 4 if value["role"] == "look" and reference["fault"] == "look-priority-last" else {"look": 0, "move": 2, "follow": 3}[value["role"]]
            equal(integer(value["priority"]), priority, "Actual registered priority")
            equal(value["class"], role_classes[value["role"]], "Original registered class")
            require(value["coreActivity"] is True, "Behavior outside selected CORE activity")
        state_shape(row["initialState"])
        previous = row["initialState"]
        previous_input = row["initialInputObservation"]
        input_shape(previous_input)
        config = copy.deepcopy(spec["initial"])
        equal(previous_input["ownerPosition"], config["targetPosition"], "initial target position")
        equal(previous_input["look"] is not None, config["lookPresent"], "initial LOOK presence")
        validate_initial(row, spec, reference["fault"])
        consumed = []
        for index, (step, planned) in enumerate(zip(row["steps"], spec["steps"])):
            require(step["complete"] is True, "Original method observation failed")
            equal(integer(step["index"]), index, "method index")
            equal(step["operation"], planned["operation"], "original method")
            equal(integer(step["requestedMethodTime"]), planned["methodTime"], "planned method time")
            equal(step["externalPatch"], planned["externalPatch"], "explicit patch")
            obs = step["inputObservations"]
            for field in ("beforePatch", "beforeMethod", "afterMethod"):
                input_shape(obs[field])
            equal(obs["beforePatch"], previous_input, "pre-patch input continuity")
            state_shape(step["before"])
            state_shape(step["after"])
            for name in ("tasks", "navigation", "lookControl", "worldIntegerIndex"):
                equal(step["before"][name], previous[name], "untouched state before method")
            config.update(planned["externalPatch"])
            before, after = obs["beforeMethod"], obs["afterMethod"]
            equal(before["ownerPosition"], config["targetPosition"], "actual patched position")
            equal(after["ownerPosition"], before["ownerPosition"], "original methods do not move controlled owner")
            actual_time = integer(step["actualMethodTime"])
            expected_time = planned["methodTime"] + int(reference["fault"] == "deadline-equality-plus-one" and key[0] == "L01_min_duration" and index == 2)
            equal(actual_time, expected_time, "consumed method argument")
            equal(before["worldGameTime"], actual_time, "actual clock before invocation")
            equal(after["worldGameTime"], actual_time, "actual clock after invocation")
            equal(step["methodInvocation"], {"operation": step["operation"], "timeArgument": None if step["operation"] == "brainPasses" else actual_time,
                                           "worldGameTime": actual_time, "entityIsConfiguredMaid": True, "serverThread": True}, "actual original method entry")
            bind_method_inputs(obs["beforePatch"], before, after, planned["externalPatch"], config, actual_time)
            bind_memory_before(previous["memory"], step["before"]["memory"], before)
            equal(step["before"]["memory"]["look"], before["look"], "actual pre-method LOOK")
            equal(step["after"]["memory"]["look"], after["look"], "actual post-method LOOK")
            equal(before["visibleCandidateContainsOwner"], config["visibleMemory"] == "owner", "actual visibility candidates")
            if "lookPresent" in planned["externalPatch"] or "trackEye" in planned["externalPatch"]:
                equal(before["look"] is not None, config["lookPresent"], "explicit LOOK patch")
            else:
                if before["look"] is None or obs["beforePatch"]["look"] is None:
                    equal(before["look"], obs["beforePatch"]["look"], "LOOK presence continuity without external patch")
                else:
                    for field in ("identity", "class", "target", "trackEye"):
                        equal(before["look"][field], obs["beforePatch"]["look"][field], "LOOK object continuity without explicit replacement")
            if before["look"] is not None:
                expected_eye = config["trackEye"] and not (reference["fault"] == "target-feet" and key[0] == "L01_min_duration")
                equal(before["look"]["trackEye"], expected_eye, "actual tracker eye mode")
            if step["operation"] == "tryStart":
                boolean(step["methodReturn"])
            else:
                equal(step["methodReturn"], None, "void native method")
            draws = 0
            for call in step["callTrace"]:
                op = call["op"]
                require(op in ("worldNextInt", "isVisibleBy", "setLookAt", "resolveOwner", "createPath", "moveTo", "stopNavigation"), "Unknown original call")
                if op == "worldNextInt":
                    bound, value = integer(call["bound"]), integer(call["value"])
                    require(0 <= value < bound, "Invalid actual world integer")
                    consumed.append(value)
                    draws += 1
                elif op == "setLookAt":
                    vector(call["target"])
                    control_shape(call["before"])
                    control_shape(call["after"])
                    equal(call["after"]["wanted"], call["target"], "native look target write")
                elif op == "isVisibleBy":
                    boolean(call["result"])
                    equal(call["target"], "owner", "actual visibility receiver")
                elif op == "resolveOwner":
                    equal(call["owner"], "owner", "controlled actual resolver")
                elif op == "createPath":
                    vector(call["target"], True)
                    integer(call["accuracy"])
                    boolean(call["canReach"])
                elif op == "moveTo":
                    number(call["speed"])
                    boolean(call["result"])
            equal(step["after"]["worldIntegerIndex"] - step["before"]["worldIntegerIndex"], draws, "actual shared RNG cursor")
            previous = step["after"]
            previous_input = after
        equal(consumed, spec["worldInts"], "actual bounded world stream")
        rows[key] = row
    equal(set(rows), {(name, rebuild) for name in specs for rebuild in range(3)}, "three fresh states per case")
    return rows


def effects(row):
    return [{field: step[field] for field in ("before", "after", "methodReturn", "callTrace")} for step in row["steps"]]


def compare(capture_path, build_path, baseline_path=None):
    reference = load(capture_path)
    identity(reference, build_path)
    contract = load(TOOL / "contract.json")
    rows = checked_rows(reference, contract)
    result = {"schema": "look-component-comparison-v1", "passed": False, "originalAA": False,
              "referenceSha256": sha(capture_path), "contractSha256": reference["contractSha256"],
              "comparatorSha256": sha(__file__), "inputBindingVerified": True,
              "aiCapabilityEvaluated": False, "naturalBrainVerified": False, "portReplayEvaluated": False}
    found = original_aa_difference(rows)
    if found:
        result["firstDifference"] = found
        return result
    result.update(originalAA=True, rows=len(rows), freshBuildsPerCase=3,
                  methodSteps=sum(len(row["steps"]) for row in rows.values()))
    if baseline_path is not None:
        baseline = load(baseline_path)
        identity(baseline, build_path)
        base_rows = checked_rows(baseline, contract)
        admit_baseline(reference, baseline, base_rows)
        result["baselineOriginalAA"] = True
        result["baselineDefinitionIdentityEqual"] = True
        for key, row in rows.items():
            found = difference(effects(base_rows[key]), effects(row))
            if found:
                result["firstDifference"] = {"case": key[0], "rebuild": key[1], **found}
                result["baselineStateEffectsEqual"] = False
                return result
        result["baselineStateEffectsEqual"] = True
        result["baselineSha256"] = sha(baseline_path)
    result.update(passed=True, verdict="COMPONENT_REFERENCE_AA_VALID_NOT_AI_SCORE")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--build-identity", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    try:
        result = compare(args.capture, args.build_identity, args.baseline)
    except (ValueError, KeyError, TypeError, OSError) as failure:
        print(json.dumps({"schema": "look-component-comparison-v1", "passed": False, "invalidObservation": True,
                          "errorType": type(failure).__name__}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
