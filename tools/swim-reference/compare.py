"""核对选定原版类身份、三次新状态复现、真实故障效果及移植重放，不授全F/N准入"""

import argparse
import hashlib
import json
import math
from pathlib import Path

TOOL = Path(__file__).resolve().parent
BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load(path):
    def pairs(items):
        out = {}
        for key, value in items:
            require(key not in out, "Duplicate JSON key")
            out[key] = value
        return out
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def diff(a, b, path="$"):
    if type(a) is bool or type(b) is bool:
        return None if type(a) is type(b) and a == b else path
    if isinstance(a, dict) and isinstance(b, dict):
        if a.keys() != b.keys():
            return path + ".keys"
        for key in a:
            found = diff(a[key], b[key], path + "." + key)
            if found:
                return found
        return None
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return path + ".length"
        for i, (left, right) in enumerate(zip(a, b)):
            found = diff(left, right, path + f"[{i}]")
            if found:
                return found
        return None
    if type(a) in (int, float) and type(b) in (int, float):
        require(math.isfinite(a) and math.isfinite(b), "Non-finite observation")
        return None if abs(a-b) <= 1e-6 else path
    return None if type(a) is type(b) and a == b else path


def identity(capture, build_path):
    build = load(build_path)
    runtime = capture["identity"]
    profile = load(TOOL / "identity-classes.json")
    require(build["schema"] == "swim-reference-build-v1" and build["status"] == "BASE_LOCK_VERIFIED", "Original build not locked")
    require(runtime["schema"] == "swim-reference-runtime-v1" and runtime["status"] == "VERIFIED_SELECTED_COMPONENT_CLASSES", "Runtime classes unverified")
    require(runtime["upstreamCommit"] == build["upstreamCommit"] == BASE, "Wrong upstream")
    require((build["minecraft"], build["forge"], build["javaFeature"]) == ("1.20.1", "47.2.0", 17), "Wrong platform")
    require(runtime["buildIdentitySha256"] == sha(build_path), "Manifest binding differs")
    require(build["contractSha256"] == sha(TOOL / "contract.json") and
            build["identityClassesSha256"] == sha(TOOL / "identity-classes.json"), "Contract/profile differs")
    require(runtime["agentSha256"] == build["definitionAgentSha256"] and
            runtime["baseReferenceBuildIdentitySha256"] == build["baseReferenceBuildIdentitySha256"], "Observer binding differs")
    require(set(runtime["classes"]) == set(build["expectedResources"]) == set(profile["classes"]), "Selected class set differs")
    for name, observed in runtime["classes"].items():
        require(observed["definitionCount"] == 1 and len(observed["definitionSha256"]) == 64 and
                observed["stage"] == "premain-transformer-input-after-defining-loader-transformations", "Definition observer differs")
        require(observed["resourceSha256"] == build["expectedResources"][name]["resourceSha256"], "Class resource differs")
        if name in build["compiledClasses"]:
            require(observed["resourceSha256"] == build["compiledClasses"][name], "Compiled resource differs")
    for item in build["toolInputs"]:
        path = Path(item["file"])
        require(not path.is_absolute() and ".." not in path.parts and ":" not in str(path), "Invalid source path")
        require(sha(TOOL / path) == item["sha256"], "Captured build input changed: " + str(path))
    # 后建的比较器/重放入口单独记录摘要；不把它们冒充已参与此前原版构建
    return runtime


def checked(capture):
    contract = load(TOOL / "contract.json")
    require(contract["schema"] == "swim-core-contract-v1" and contract["id"] == "SWIM_POSE_JUMP_CORE_V1" and
            contract["pose_repetitions"] == contract["jump_repetitions"] == 3, "Unsupported contract")
    require(capture["schema"] == "swim-reference-capture-v1" and capture["complete"] is True and
            capture["naturalBrainVerified"] is False, "Wrong capture scope")
    result = {}
    pose_ids = {f"{bits}/air" for bits in range(32)} | {"0/floor", "0/wedged"}
    jump_ids = {"surface", "underwater", "navigation-busy", "threshold-equal", "lava", "changes"}
    expected = {(kind, name, n) for kind, names in (("pose", pose_ids), ("jump", jump_ids)) for name in names for n in range(3)}
    for row in capture["cases"]:
        key = row["type"], row["id"], row["rebuild"]
        require(key not in result, "Duplicate case")
        require(len(row["states"]) == (2 if key[0] == "pose" else 6), "Incomplete states")
        if key[0] == "pose":
            bits, geometry = key[1].split("/"); bits = int(bits)
            want, ground, sitting, passenger, sleep = (bool(bits & mask) for mask in (1,2,4,8,16))
            require(row["input"] == dict(want=want, ground=ground, sitting=sitting, passenger=passenger, sleep=sleep,
                    initialPose="SWIMMING" if geometry != "air" else "SLEEPING" if sleep else "STANDING", geometry=geometry), "Fixture input changed")
        else:
            for i, state in enumerate(row["states"]):
                scenario = key[1]
                require({k: state[k] for k in ("time", "water", "height", "lava", "underwater", "done")} ==
                        dict(time=(0,1,60,61,62,63)[i], water=scenario != "lava", height=.4 if scenario == "threshold-equal" else .8,
                             lava=scenario == "lava", underwater=scenario == "underwater" or scenario == "changes" and i == 2,
                             done=scenario != "navigation-busy" and not (scenario == "changes" and i == 4)), "Jump inputs changed")
        result[key] = row
    require(set(result) == expected, "Incomplete contract cases")
    for kind, name, n in expected:
        require(diff(result[(kind,name,0)]["states"], result[(kind,name,n)]["states"]) is None, "Fresh-state A/A differs")
    return result


def compare(reference, replay, build, faults):
    original, port = load(reference), load(replay)
    identity(original, build)
    require(original["fault"] == "none", "Normal baseline required")
    rows = checked(original)
    require(port["schema"] == "swim-port-replay-v1" and port["naturalBrainVerified"] is False, "Wrong replay scope")
    actual = {(r["type"],r["id"],r["rebuild"]):r for r in port["cases"]}
    require(len(actual) == len(port["cases"]) and actual.keys() == rows.keys(), "Port case set differs")
    mismatches = []
    for key, expected in rows.items():
        found = diff(expected["states"], actual[key]["states"])
        if found:
            mismatches.append(dict(case=key, path=found))
    detected = {}
    for path in faults:
        fault = load(path); identity(fault, build)
        require(fault["fault"] in ("pose-ground-ignored", "jump-underwater-allowed") and fault["fault"] not in detected, "Duplicate/unknown fault")
        other = checked(fault)
        detected[fault["fault"]] = [key for key in rows if diff(rows[key]["states"], other[key]["states"])]
        require(detected[fault["fault"]], "Actual injected fault escaped detection")
    require(set(detected) == {"pose-ground-ignored", "jump-underwater-allowed"}, "Both actual faults required")
    return dict(schema="swim-component-comparison-v1", passed=not mismatches, originalAA=True, cases=len(rows),
                stateSnapshots=312, tolerance=1e-6, actualFaultsDetected=detected, mismatches=mismatches,
                referenceSha256=sha(reference), replaySha256=sha(replay), comparatorSha256=sha(__file__),
                naturalBrainVerified=False, fullFaithfulProfileVerified=False, portArtifactBindingVerified=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path); parser.add_argument("replay", type=Path)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--fault", type=Path, action="append", required=True)
    args = parser.parse_args()
    result = compare(args.reference, args.replay, args.build, args.fault)
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["passed"] else 1)
