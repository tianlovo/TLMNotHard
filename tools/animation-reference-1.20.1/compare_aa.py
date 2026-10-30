"""比较受控原版观测的重复性，不宣称跨版本等价或完整渲染通过"""

import argparse
import json
import math
from pathlib import Path


def validate(value):
    if value.get("schema") != "observation-v0" or value.get("inputMode") != "controlled-idle-v0":
        raise ValueError("Only controlled original observations are supported by this experiment")
    frames = value.get("frames", [])
    if not frames:
        raise ValueError("Empty controlled observation")
    for index, frame in enumerate(frames):
        if frame["tick"] != index or frame["partialTick"] != 0 or not frame["onGround"]:
            raise ValueError("Controlled time or ground input contract was not satisfied")
        if frame["headPitch"] != 0 or frame["netHeadYaw"] != 0 or frame["fallback"]:
            raise ValueError("Controlled view input or model contract was not satisfied")
        if frame["backend"] == "gecko" and frame["seekTicks"] != index:
            raise ValueError("Original animation clock did not follow the requested steps")
        if frame["phase"] != "render-post" or not frame["bones"]:
            raise ValueError("Incomplete rendered observation")


def compare(reference, candidate):
    report = {"scope": "original controlled observation repeatability only", "status": "INCONCLUSIVE",
              "equivalence_verified": False, "rendering_verified": False}
    try:
        validate(reference)
        validate(candidate)
        for key in ("entityUuid", "seed", "upstreamCommit", "inputMode"):
            if reference.get(key) != candidate.get(key):
                raise ValueError("Different experiment input: " + key)
    except (KeyError, TypeError, ValueError) as error:
        report["error"] = str(error)
        return report
    differences = 0
    first = None
    maximum_delta = 0.0

    def difference(path, expected, actual):
        nonlocal differences, first
        differences += 1
        if first is None:
            first = {"path": path, "reference": expected, "candidate": actual}

    def visit(expected, actual, path):
        nonlocal maximum_delta
        if isinstance(expected, bool) or isinstance(actual, bool):
            if type(expected) is not type(actual) or expected != actual:
                difference(path, expected, actual)
        elif isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
            if not math.isfinite(expected) or not math.isfinite(actual):
                difference(path, str(expected), str(actual))
            elif expected != actual:
                maximum_delta = max(maximum_delta, abs(expected - actual))
                difference(path, expected, actual)
        elif isinstance(expected, dict) and isinstance(actual, dict):
            if expected.keys() != actual.keys():
                difference(path + ".keys", sorted(expected), sorted(actual))
            for key in sorted(expected.keys() & actual.keys()):
                visit(expected[key], actual[key], path + "." + key)
        elif isinstance(expected, list) and isinstance(actual, list):
            if len(expected) != len(actual):
                difference(path + ".length", len(expected), len(actual))
            for index, (left, right) in enumerate(zip(expected, actual)):
                visit(left, right, path + "[" + str(index) + "]")
        elif type(expected) is not type(actual) or expected != actual:
            difference(path, expected, actual)

    visit(reference["frames"], candidate["frames"], "frames")
    report.update(status="PASS_REPEATABILITY" if differences == 0 else "REPEATABILITY_DIFF",
                  differences=differences, first_divergence=first, maximum_absolute_numeric_delta=maximum_delta,
                  reference_frames=len(reference["frames"]), candidate_frames=len(candidate["frames"]),
                  tolerance=0, tolerance_scope="raw same-runtime repetition, not a cross-version tolerance")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    result = compare(json.loads(args.reference.read_text(encoding="utf-8")),
                     json.loads(args.candidate.read_text(encoding="utf-8")))
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result["status"] == "PASS_REPEATABILITY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
