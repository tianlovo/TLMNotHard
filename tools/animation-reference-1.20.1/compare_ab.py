"""核对小样本输入、原版重复证据和实际资源，再比较移植版CPU输出"""

import argparse
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

from compare_aa import validate
from reference_environment import EVIDENCE, GIT, UPSTREAM
from scenario_contract import WALK_MODE, HEAD_MODE, IDLE_CONTRACT, WALK_CONTRACT, HEAD_CONTRACT

BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
BEDROCK_ANIMATIONS = [
    "head/default", "head/beg", "head/music_shake", "arm/default", "arm/swing",
    "leg/default", "sit/default", "head/blink", "tail/default", "sit/skirt_rotation",
]
EXTERNAL = {
    "query.ground_speed": ["groundSpeed"], "query.vertical_speed": ["verticalSpeed"],
    "query.yaw_speed": ["yawSpeed"],
    "query.position_delta": ["previousX", "previousY", "previousZ", "x", "y", "z", "partialTick"],
    "ysm.head_pitch": ["headPitch"], "ysm.head_yaw": ["netHeadYaw"], "ysm.is_close_eyes": ["closeEyes"],
}
INTERNAL = {"query.anim_time", "ysm.bone_rot", "ysm.bone_pos", "ysm.bone_scale"}
MATH = {"math." + name for name in ("abs", "clamp", "exp", "floor", "max", "min", "pi", "pow", "sqrt")}


def differences(expected, actual, path):
    result = {"count": 0, "first": None, "maximum_absolute_numeric_delta": 0.0}

    def record(where, left, right):
        result["count"] += 1
        if result["first"] is None:
            result["first"] = {"path": where, "reference": left, "candidate": right}

    def visit(left, right, where):
        if isinstance(left, bool) or isinstance(right, bool):
            if type(left) is not type(right) or left != right:
                record(where, left, right)
        elif isinstance(left, (int, float)) and isinstance(right, (int, float)):
            if not math.isfinite(left) or not math.isfinite(right):
                raise ValueError("Non-finite comparison value: " + where)
            if left != right:
                result["maximum_absolute_numeric_delta"] = max(result["maximum_absolute_numeric_delta"], abs(left - right))
                record(where, left, right)
        elif isinstance(left, dict) and isinstance(right, dict):
            if left.keys() != right.keys():
                record(where + ".keys", sorted(left), sorted(right))
            for key in sorted(left.keys() & right.keys()):
                visit(left[key], right[key], where + "." + key)
        elif isinstance(left, list) and isinstance(right, list):
            if len(left) != len(right):
                record(where + ".length", len(left), len(right))
            for index, (a, b) in enumerate(zip(left, right)):
                visit(a, b, where + "[" + str(index) + "]")
        elif type(left) is not type(right) or left != right:
            record(where, left, right)

    visit(expected, actual, path)
    return result


def upstream_asset(location, upstream=UPSTREAM):
    domain, name = location.split(":", 1)
    prefix = "src/main/resources/assets/"
    if location != "touhou_little_maid:animation/maid.animation.json":
        prefix += "touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets/"
    path = prefix + domain + "/" + name
    process = subprocess.run([GIT, "-C", str(upstream), "cat-file", "blob", BASE + ":" + path], capture_output=True)
    if process.returncode:
        raise ValueError("Cannot read the pinned upstream asset: " + location)
    return process.stdout


def pinned_assets(assets, port_assets, canonical_assets=None):
    result = {}
    checks = []
    if assets.keys() != port_assets.keys():
        raise ValueError("Different actual runtime asset lists")
    for location, expected in assets.items():
        _, name = location.split(":", 1)
        if canonical_assets is not None and location not in canonical_assets:
            raise ValueError("Missing approved canonical asset: " + location)
        raw = upstream_asset(location) if canonical_assets is None else canonical_assets[location]
        pinned = hashlib.sha256(raw).hexdigest()
        if port_assets[location] != pinned:
            raise ValueError("Port asset bytes differ from pinned upstream: " + location)
        variants = {pinned: "exact pinned bytes"}
        if name.endswith(".json"):
            json.loads(raw.decode("utf-8"))
            lf = raw.replace(b"\r\n", b"\n")
            variants.setdefault(hashlib.sha256(lf).hexdigest(), "JSON LF only")
            variants.setdefault(hashlib.sha256(lf.replace(b"\n", b"\r\n")).hexdigest(), "JSON CRLF only")
        if expected not in variants:
            raise ValueError("Original asset has unreviewed content changes: " + location)
        checks.append({"asset": location, "original_match": variants[expected], "port_matches_pinned_bytes": True,
                       "original_sha256": expected, "pinned_sha256": pinned})
        result[location] = raw
    return result, checks


def dependencies(reference, asset_bytes):
    frames = reference["frames"]
    model = frames[0]["modelChoice"]
    namespace = model.split(":", 1)[0]
    metadata = json.loads(asset_bytes[namespace + ":maid_model.json"])
    entry = next(value for value in metadata["model_list"] if value["model_id"] == model)
    if frames[0]["backend"] == "bedrock":
        expected = ["touhou_little_maid:animation/maid/default/" + name + ".js" for name in BEDROCK_ANIMATIONS]
        if entry["animation"] != expected:
            raise ValueError("Unreviewed Bedrock animation list")
        return {"kind": "reviewed Bedrock functions", "animations": expected,
                "inputs": ["state", "render"], "scope": "reviewed grounded empty-equipment animation inputs; no gameplay movement proof"}
    merged = {}
    for location in entry["animation"]:
        merged.update(json.loads(asset_bytes[location])["animations"])
    for name, animation in json.loads(asset_bytes["touhou_little_maid:animation/maid.animation.json"])["animations"].items():
        merged.setdefault(name, animation)
    clips = sorted({layer["clip"] for frame in frames for layer in frame["controllers"]
                    if layer["state"] != "STOPPED" and layer["clip"] is not None})
    used = set()
    for clip in clips:
        used.update(re.findall(r"\b(?:q|query|tlm|ysm|math|c|context)\.[A-Za-z_0-9]+", json.dumps(merged[clip])))
    used = {"query." + name[2:] if name.startswith("q.") else name for name in used}
    missing = used - EXTERNAL.keys() - INTERNAL - MATH
    if missing:
        raise ValueError("Unreviewed animation dependency: " + ", ".join(sorted(missing)))
    for frame in frames:
        query = frame["inputs"]["query"]
        for name in used & EXTERNAL.keys():
            if not all(field in query for field in EXTERNAL[name]):
                raise ValueError("Missing captured external query: " + name)
    return {"kind": "reviewed active clips", "clips": clips, "symbols": sorted(used),
            "external_inputs": {name: EXTERNAL[name] for name in sorted(used & EXTERNAL.keys())},
            "internal_values_recomputed": sorted(used & (INTERNAL | MATH))}


def compare(reference, replay, digest, aa, canonical_assets=None):
    report = {"scope": "two reviewed empty-equipment fixtures; local bones and controllers on CPU",
              "status": "INCONCLUSIVE", "equivalence_verified": False, "rendering_verified": False,
              "approved_for_bulk": False, "tolerance": 0,
              "tolerance_scope": "raw exact diagnostics; no cross-platform error budget calibrated"}
    try:
        validate(reference)
        head_fixture = reference["inputMode"] == HEAD_MODE
        contract = HEAD_CONTRACT if head_fixture else WALK_CONTRACT if reference["inputMode"] == WALK_MODE else IDLE_CONTRACT
        if reference["upstreamCommit"] != BASE or reference["faultInjection"] != "none":
            raise ValueError("Wrong or faulted original baseline")
        if replay["schema"] != "port-replay-v0" or replay["referenceSha256"] != digest or replay["upstreamCommit"] != BASE:
            raise ValueError("Replay is not bound to this original capture")
        model = reference["frames"][0]["modelChoice"]
        if model != replay["modelChoice"] or not aa["passed"]:
            raise ValueError("Model mismatch or missing original repeatability pass")
        cases = [case for case in aa["models"] if case["model"] == model and case["passed"]]
        if len(cases) != 1 or len(cases[0]["samples"]) < 3 or cases[0]["samples"][0]["sha256"] != digest:
            raise ValueError("Capture is not the verified repeated baseline")
        asset_bytes, checks = pinned_assets(reference["actualAssetSha256"], replay["assetSha256"], canonical_assets)
        report["assets"] = checks
        report["asset_normalization"] = "Original valid JSON may use exactly LF or CRLF; all port assets match pinned Git bytes; binary assets require exact bytes"
        report["dependencies"] = dependencies(reference, asset_bytes)
        original_frames, port_frames = reference["frames"], replay["frames"]
        if len(original_frames) != len(port_frames):
            raise ValueError("Replay did not consume every input step")
        original, candidate = [], []
        projections = set()
        for index, (left, right) in enumerate(zip(original_frames, port_frames)):
            if left["inputs"]["contract"] != contract or right["tick"] != index or right["backend"] != left["backend"]:
                raise ValueError("Unreviewed input contract or skipped step")
            for field, consumed in (("state", "consumedState"), ("render", "consumedRender")):
                if differences(left["inputs"][field], right[consumed], "inputs." + field)["count"]:
                    raise ValueError("Consumed inputs differ: " + field)
            a, b = {"bones": left["bones"]}, {"bones": right["bones"]}
            if left["backend"] == "gecko":
                if differences(left["inputs"]["query"], right["consumedQuery"], "inputs.query")["count"]:
                    raise ValueError("Consumed query inputs differ")
                if not head_fixture and (left["coreBones"] != left["headBones"] or left["headBones"] != left["bones"]):
                    raise ValueError("Extra original animation phases were active outside this idle contract")
                if head_fixture:
                    if replay["stageObserverNeutral"] is not True or right["engineUpdated"] is not True:
                        raise ValueError("Missing actual or neutral Gecko phase sampling")
                    a.update(coreBones=left["coreBones"], headBones=left["headBones"])
                    b.update(coreBones=right["coreBones"], headBones=right["headBones"])
                layers = []
                port_names = {layer["name"] for layer in right["controllers"]}
                for layer in left["controllers"]:
                    if layer["name"] == "magic_casting" and layer["name"] not in port_names:
                        if layer["state"] != "STOPPED" or layer["clip"] is not None or layer["tickOffset"] != 0 or layer["speed"] != 1:
                            raise ValueError("Absent magic layer is active")
                        projections.add("magic_casting: verified STOPPED/null/offset0/speed1 at every original step")
                    else:
                        layers.append(layer)
                a.update(controllers=layers, seekTicks=left["seekTicks"])
                b.update(controllers=right["controllers"], seekTicks=right["seekTicks"])
            original.append(a)
            candidate.append(b)
        report["conditional_layer_projection"] = sorted(projections)
        report["input_steps_consumed"] = len(original_frames)
        report["input_and_asset_contract_passed"] = True
        if head_fixture:
            report["phase_scope"] = "Gecko core, head and final compared independently; Bedrock final only"
            report["bone_phases"] = {}
            for phase in ("coreBones", "headBones", "bones"):
                if phase in original[0]:
                    report["bone_phases"][phase] = differences([frame[phase] for frame in original],
                                                               [frame[phase] for frame in candidate], "frames." + phase)
        report["comparison"] = differences(original, candidate, "frames")
        report["comparison"].pop("maximum_absolute_numeric_delta")
        report["bone_channels"] = {}
        keys = original[0]["bones"][next(iter(original[0]["bones"]))].keys()
        for field in keys:
            expected = [{name: pose[field] for name, pose in frame["bones"].items()} for frame in original]
            actual = [{name: pose[field] for name, pose in frame["bones"].items()} for frame in candidate]
            report["bone_channels"][field] = differences(expected, actual, "frames.bones." + field)
        report["status"] = "CPU_MATCH_EXACT" if report["comparison"]["count"] == 0 else "CPU_DIFFERENCE"
    except (KeyError, TypeError, ValueError, StopIteration) as error:
        report["error"] = str(error)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("replay", type=Path)
    parser.add_argument("--aa-report", type=Path, default=EVIDENCE / "aa-experiment.json")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    raw = args.reference.read_bytes()
    result = compare(json.loads(raw), json.loads(args.replay.read_text(encoding="utf-8")),
                     hashlib.sha256(raw).hexdigest(), json.loads(args.aa_report.read_text(encoding="utf-8")))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result["status"] == "CPU_MATCH_EXACT" else 1


if __name__ == "__main__":
    raise SystemExit(main())
