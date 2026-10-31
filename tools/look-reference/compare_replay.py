"""独立LOOK移植策略重放对照，不比较原生helper旋转，不授自然AI或组合V2准入"""

import argparse
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

REFERENCE_CHECKER_SHA = "f1b401a7bfcd8c6131cd08a72827ef62661dbef85f4b2c6b64c806890bd3d173"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_reference_checker(tool):
    source = Path(tool) / "compare.py"
    if sha(source) != REFERENCE_CHECKER_SHA:
        raise ValueError("Reference checker differs from reviewed v2")
    spec = importlib.util.spec_from_file_location("look_reference_gate_v2", source)
    module = importlib.util.module_from_spec(spec)
    old = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = old
    return module


def project_state(state):
    return {"look": state["memory"]["look"], "status": state["tasks"]["look"]["status"],
            "endTimestamp": state["tasks"]["look"]["endTimestamp"], "worldIntegerIndex": state["worldIntegerIndex"]}


def project_calls(calls):
    output = []
    for call in calls:
        if call["op"] == "setLookAt":
            output.append({"op": "setLookAt", "target": call["target"]})
        elif call["op"] in ("worldNextInt", "isVisibleBy"):
            output.append(call)
        else:
            raise ValueError("Call outside independent LOOK scope")
    return output


def compare(reference_path, replay_path, tool, build_identity):
    gate = load_reference_checker(tool)
    reference, replay = gate.load(reference_path), gate.load(replay_path)
    gate.identity(reference, build_identity)
    gate.require(reference["fault"] == "none", "Normal original baseline required")
    rows = gate.checked_rows(reference, gate.load(Path(tool) / "contract.json"))
    gate.require(gate.original_aa_difference(rows) is None, "Original fresh-state A/A did not pass")
    gate.equal(replay["schema"], "look-sink-port-replay-v1", "replay.schema")
    gate.equal(replay["contract"], "LOOK_SINK_CORE_V1", "replay.contract")
    gate.equal(replay["contractSha256"], reference["contractSha256"], "contract SHA")
    gate.equal(replay["inputObservationSchema"], reference["inputObservationSchema"], "actual input schema")
    gate.require(replay["aiCapabilityEvaluated"] is False and replay["combinedV2Implemented"] is False,
                 "Replay claimed wider scope")
    gate.equal(replay["driverClass"], "com.tianluoqaq.tlmnh.common.ai.faithful.FollowCoreLookDriver", "driver class")
    expected = {key: row for key, row in rows.items() if row["contract"] == "LOOK_SINK_CORE_V1"}
    omitted = {(row["id"], gate.integer(row["rebuild"])) for row in replay["excluded"]
               if row["reason"] == "FOLLOW_MOVE_LOOK_COMPONENT_V2_NOT_IMPLEMENTED"}
    gate.equal(omitted, set(rows) - set(expected), "explicitly excluded combined rows")
    gate.equal(len(replay["excluded"]), 6, "excluded row count")
    actual = {}
    for row in replay["cases"]:
        key = (row["id"], gate.integer(row["rebuild"]))
        gate.require(key not in actual, "Duplicate port replay row")
        actual[key] = row
    gate.equal(set(actual), set(expected), "independent LOOK case set")
    result = {"schema": "look-sink-port-comparison-v1", "passed": False, "originalAA": True,
              "inputBindingVerified": True, "referenceSha256": sha(reference_path), "replaySha256": sha(replay_path),
              "comparatorSha256": sha(__file__), "referenceComparatorSha256": REFERENCE_CHECKER_SHA,
              "contractSha256": reference["contractSha256"], "rows": len(expected), "combinedRowsExcluded": 6,
              "nativeHelperPhysicsEvaluated": False, "combinedV2Evaluated": False, "aiCapabilityEvaluated": False,
              "portArtifactBindingVerified": False,
              "scope": "LOOK_SINK_CORE_V1 policy effects only; port JAR/source identity requires separate orchestration"}
    method_steps = 0
    for key, row in expected.items():
        port_steps = actual[key]["steps"]
        gate.equal(len(port_steps), len(row["steps"]), "port method step count")
        for index, (original, port) in enumerate(zip(row["steps"], port_steps)):
            # 不按fault/inputMutations标签决定相等，独立从原版raw取策略效果投影
            for name, expected_value in (("index", index), ("operation", original["operation"]),
                                         ("actualMethodTime", original["actualMethodTime"])):
                gate.equal(port[name], expected_value, "replay actual input metadata")
            expected_effects = {"before": project_state(original["before"]), "after": project_state(original["after"]),
                                "methodReturn": original["methodReturn"], "callTrace": project_calls(original["callTrace"])}
            actual_effects = {name: port[name] for name in expected_effects}
            found = gate.difference(expected_effects, actual_effects)
            if found:
                result["firstDifference"] = {"case": key[0], "rebuild": key[1], "index": index, **found}
                result["verdict"] = "LOOK_POLICY_EFFECTS_DIFFER"
                return result
            gate.equal(gate.integer(port["consumedMethodTime"]), original["actualMethodTime"], "consumed method time after equal policy effects")
            gate.equal(gate.integer(port["unconsumedVisibilityObservations"]), 0, "visibility dependency consumption")
            gate.equal(gate.integer(port["unconsumedWorldIntegerObservations"]), 0, "world integer dependency consumption")
            method_steps += 1
    result.update(passed=True, methodSteps=method_steps, verdict="LOOK_POLICY_EFFECTS_EQUAL_ARTIFACT_BINDING_EXTERNAL")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("replay", type=Path)
    parser.add_argument("--reference-tool", type=Path, required=True)
    parser.add_argument("--build-identity", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = compare(args.reference, args.replay, args.reference_tool, args.build_identity)
    except (ValueError, KeyError, TypeError, OSError) as failure:
        print(json.dumps({"passed": False, "invalidObservation": True, "errorType": type(failure).__name__}))
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
