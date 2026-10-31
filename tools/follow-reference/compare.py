"""核对原版自身三次新建A/A，并可比对实际移植组件重放，严格保留首次分歧"""

import argparse
import hashlib
import json
from pathlib import Path


TOOL = Path(__file__).resolve().parent
BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
if not __debug__:
    raise RuntimeError("Do not disable verification assertions with Python optimization")
RAW_KEYS = ("input", "before", "normalised", "callTrace", "teleportReturns", "teleportObservations",
            "maidRandomCalls", "worldRandomCalls", "behaviourStatusAfterStart", "behaviourStatusAfterTickOrStop",
            "gameTimeArgument", "initialMaidPosition", "ownerPosition", "initialMaidBoundingBox", "finalPosition", "finalBoundingBox")


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"), parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def first_difference(left, right, path="$"):
    numeric_pair = isinstance(left, (int, float)) and isinstance(right, (int, float))
    if (type(left) is not type(right) and not numeric_pair) or isinstance(left, bool) != isinstance(right, bool):
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


def verified_rows(reference, build_path):
    build = load(build_path)
    identity = reference["identity"]
    contract = load(TOOL / "contract.json")
    selected = load(TOOL / "identity-classes.json")
    assert reference["schema"] == "follow-core-reference-v1" and reference["complete"] is True
    assert reference["contract"] == "FOLLOW_CORE_V1" and reference["contractSha256"] == sha(TOOL / "contract.json")
    assert reference["naturalBrainVerified"] is False and reference["moveToTargetSinkVerified"] is False
    assert build["schema"] == "follow-reference-build-v1" and build["status"] == "BASE_LOCK_VERIFIED"
    assert build["upstreamCommit"] == identity["upstreamCommit"] == BASE
    assert build["contractSha256"] == reference["contractSha256"]
    assert build["identityClassesSha256"] == sha(TOOL / "identity-classes.json")
    assert identity["status"] == "VERIFIED_SELECTED_COMPONENT_CLASSES" and identity["buildIdentitySha256"] == sha(build_path)
    assert identity["agentSha256"] == build["definitionAgentSha256"]
    assert identity["sourceTaskSha256"] == build["originalTaskSourceSha256"]
    assert identity["baseReferenceBuildIdentitySha256"] == build["baseReferenceBuildIdentitySha256"]
    assert build["baseReferenceBuildIdentity"]["status"] == "LOCK_VERIFIED"
    assert set(identity["classes"]) == set(selected["classes"])
    for name, item in identity["classes"].items():
        assert item["definitionCount"] == 1
        assert all(isinstance(item[key], str) and len(item[key]) == 64
                   and all(char in "0123456789abcdef" for char in item[key])
                   for key in ("definitionSha256", "resourceSha256"))
        if name in build["compiledClasses"]:
            assert item["resourceSha256"] == build["compiledClasses"][name]
    rows = {}
    specs = {case["id"]: case for case in contract["cases"]}
    for row in reference["cases"]:
        key = (row["id"], row["rebuild"])
        assert key not in rows, "Duplicate case/rebuild"
        for field in RAW_KEYS:
            assert field in row, "Missing actual capture field: " + field
        assert row["id"] in specs, "Unknown case input"
        spec = specs[row["id"]]
        declared = row["input"]
        assert {name: value for name, value in declared.items() if name != "actual"} == spec, "Declared input differs from contract"
        actual = declared["actual"]
        assert actual["radius"] == spec["radius"] and actual["home"] is spec["home"]
        assert actual["breathing"] is spec["breathing"]
        assert actual["canBrainMove"] is (spec["movement"] == "free"), "Movement setup did not bind to original maid state"
        resolved = spec["ownerState"] != "offline"
        assert actual["ownerResolved"] is resolved
        if resolved:
            assert actual["ownerSpectator"] is (spec["ownerState"] == "spectator")
            assert actual["ownerDeadOrDying"] is (spec["ownerState"] == "dead")
            assert actual["sameLevel"] is (spec["ownerState"] != "other_level")
            assert actual["ownerUnderwater"] is spec["ownerUnderwater"]
            a, b = row["initialMaidPosition"], row["ownerPosition"]
            dx, dy, dz = a["x"] - b["x"], a["y"] - b["y"], a["z"] - b["z"]
            assert actual["distanceSquared"] == dx * dx + dy * dy + dz * dz, "Actual distance differs from observed entity positions"
        else:
            assert all(actual[key] is None for key in ("distanceSquared", "ownerSpectator", "ownerDeadOrDying", "sameLevel", "ownerUnderwater"))
        assert row["before"]["walk"]["target"] == spec["walkTarget"], "Initial WALK target did not bind"
        assert row["before"]["breathing"] is spec["breathing"]
        assert row["before"]["path"] == ("present" if spec["seedOtherMemories"] else "none")
        assert all(row["before"][key] == ("owner" if spec["seedOtherMemories"] else "none") for key in ("look", "attack", "targetPos"))
        rows[key] = row
    expected = {(case["id"], rebuild) for case in contract["cases"] for rebuild in range(3)}
    assert set(rows) == expected, "Incomplete or extra original cases"
    return rows


def compare(reference_path, build_path, replay_path=None):
    reference = load(reference_path)
    rows = verified_rows(reference, build_path)
    report = {"schema": "follow-component-comparison-v1", "passed": False, "originalAA": False,
              "contractSha256": reference["contractSha256"], "referenceSha256": sha(reference_path),
              "comparatorSha256": sha(Path(__file__)), "inputBindingVerified": True,
              "scope": "original task controlled component, not full Brain, native movement or behaviour benchmark"}
    for case_id in sorted({key[0] for key in rows}):
        first = rows[(case_id, 0)]
        for rebuild in (1, 2):
            diff = first_difference({key: first[key] for key in RAW_KEYS},
                                    {key: rows[(case_id, rebuild)][key] for key in RAW_KEYS})
            if diff:
                report["firstDifference"] = {"case": case_id, "rebuild": rebuild, **diff}
                return report
    report["originalAA"] = True
    report["freshBuildsPerCase"] = 3
    report["cases"] = len(rows)
    if replay_path is not None:
        replay = load(replay_path)
        assert replay["schema"] == "follow-core-port-replay-v1" and replay["contractSha256"] == reference["contractSha256"]
        replay_rows = {}
        for row in replay["cases"]:
            key = (row["id"], row["rebuild"])
            assert key not in replay_rows
            replay_rows[key] = row
        assert replay_rows.keys() == rows.keys(), "Replay case set differs"
        for key, row in rows.items():
            diff = first_difference({field: row[field] for field in ("normalised", "callTrace")},
                                    {field: replay_rows[key][field] for field in ("normalised", "callTrace")})
            if diff:
                report["firstDifference"] = {"case": key[0], "rebuild": key[1], **diff}
                return report
        report["replaySha256"] = sha(replay_path)
        report["portComponentEquivalent"] = True
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
        result = compare(args.reference, args.build_identity, args.replay)
    except (AssertionError, KeyError, TypeError, ValueError, OSError) as failure:
        result = {"schema": "follow-component-comparison-v1", "passed": False, "errorType": type(failure).__name__, "error": str(failure)}
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(rendered)
    print(rendered, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
