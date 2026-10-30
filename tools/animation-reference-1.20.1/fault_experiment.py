"""在原版实际动画执行路径注入故障，验证受控观测的自动检出能力"""

import argparse
import importlib.util
import json
from pathlib import Path

from reference_environment import PROBE, RUNTIME, EVIDENCE
from aa_experiment import control, clear_and_reload


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    aa = json.loads((EVIDENCE / "aa-experiment.json").read_text(encoding="utf-8"))
    report = {"scope": "original controlled pipeline fault detection only", "faults": [], "passed": False,
              "ab_equivalence_executed": False, "equivalence_verified": False,
              "texture_or_gl_fault_tested": False, "approved_for_bulk": False}
    try:
        if not aa["passed"]:
            raise ValueError("A/A prerequisite failed")
        baseline_case = next(value for value in aa["models"] if value["model"] == "geckolib:winefox_hanfu")
        baseline_file = RUNTIME / baseline_case["samples"][0]["file"]
        baseline = json.loads(baseline_file.read_text(encoding="utf-8"))
        if baseline.get("faultInjection") != "none":
            raise ValueError("Baseline must not contain an injected fault")
        spec = importlib.util.spec_from_file_location("compare_aa", PROBE / "compare_aa.py")
        comparator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(comparator)
        report["baseline"] = baseline_case["samples"][0]
        for fault in ("head_offset", "clock_offset", "wrong_clip"):
            clear_and_reload()
            result = control("sample_case", "--model", "geckolib:winefox_hanfu", "--frames", "30",
                             "--seed", "424242", "--fault", fault, allow_failure=True)
            response = result["response"]
            case = {"fault": fault, "response": response, "detected": False}
            report["faults"].append(case)
            if fault == "clock_offset":
                case["detected"] = response["status"] == "failed" and "seek time" in response.get("error", "")
                case["detection"] = "internal clock contract rejection"
            elif response["status"] == "complete":
                candidate_file = Path(result["capture"]["path"])
                candidate = json.loads(candidate_file.read_text(encoding="utf-8"))
                difference = comparator.compare(baseline, candidate)
                case["capture"] = result["capture"]
                case["comparison"] = difference
                case["detected"] = difference["status"] == "REPEATABILITY_DIFF"
                if fault == "head_offset":
                    first = difference.get("first_divergence") or {}
                    case["detected"] = case["detected"] and ".bones.Head.translation[0]" in first.get("path", "")
                if fault == "wrong_clip":
                    clips = {controller.get("clip") for frame in candidate["frames"]
                             for controller in frame["controllers"] if controller["name"] == "main"}
                    case["actual_main_clips"] = sorted(str(value) for value in clips)
                    case["detected"] = case["detected"] and "walk" in clips
            print(json.dumps({"fault": fault, "detected": case["detected"]}), flush=True)
        report["passed"] = len(report["faults"]) == 3 and all(value["detected"] for value in report["faults"])
    except Exception as error:
        report["error"] = str(error)
    finally:
        try:
            control("shutdown")
            report["shutdown_requested"] = True
        except Exception as error:
            report["shutdown_error"] = str(error)
        (EVIDENCE / "fault-experiment.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, allow_nan=False), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
