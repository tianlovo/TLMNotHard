"""运行两个代表模型的原版受控重复实验，不扩展批量或宣称移植等价"""

import hashlib
import argparse
import importlib.util
import json
import subprocess
import sys
import time
from pathlib import Path

from reference_environment import PROBE, RUNTIME, EVIDENCE

REPORT = EVIDENCE / "aa-experiment.json"
MODELS = ("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid")


def control(command, *arguments, allow_failure=False):
    result = subprocess.run([sys.executable, str(PROBE / "control.py"), command,
                             "--timeout", "50", *arguments], capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0 and not allow_failure:
        raise RuntimeError((result.stdout + result.stderr)[-2500:])
    return json.loads(result.stdout)


def clear_and_reload():
    control("clear_scene")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        state = control("status")["response"]
        if state["scene"]["status"] == "idle" and state["maidCount"] == 0:
            break
        time.sleep(.1)
    else:
        raise TimeoutError("Previous reference actor did not leave")
    control("reload")


def ensure_lab():
    state = control("status")["response"]
    if not state["minimalReferenceMods"]:
        raise ValueError("Unexpected reference runtime mods")
    if not state["worldLoaded"]:
        control("open_lab")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = control("status")["response"]
            if state["worldLoaded"] and state["playerPresent"]:
                break
            time.sleep(.2)
        else:
            raise TimeoutError("Reference world not ready")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep-client", action="store_true")
    args = parser.parse_args()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    report = {"scope": "feasibility: original controlled A/A only", "models": [], "passed": False,
              "full_capture_contract_passed": False, "ab_equivalence_executed": False,
              "e2e_fault_injection_executed": False, "equivalence_verified": False}
    try:
        ensure_lab()
        spec = importlib.util.spec_from_file_location("compare_aa", PROBE / "compare_aa.py")
        comparator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(comparator)
        for model in MODELS:
            samples = []
            case = {"model": model, "samples": [], "comparisons": []}
            report["models"].append(case)
            for index in range(3):
                clear_and_reload()
                result = control("sample_case", "--model", model, "--frames", "30", "--seed", "424242")
                path = Path(result["capture"]["path"])
                raw = path.read_bytes()
                sample = json.loads(raw)
                comparator.validate(sample)
                if any(frame["modelChoice"] != model for frame in sample["frames"]):
                    raise ValueError("Unexpected actual model choice")
                samples.append(sample)
                case["samples"].append({"file": path.relative_to(RUNTIME).as_posix(),
                                        "sha256": hashlib.sha256(raw).hexdigest(),
                                        "bones": result["capture"]["bone_counts"], "frames": 30})
                print(json.dumps({"model": model, "sample": index + 1, "captured": True}), flush=True)
            case["comparisons"] = [comparator.compare(samples[0], value) for value in samples[1:]]
            case["passed"] = all(value["status"] == "PASS_REPEATABILITY" for value in case["comparisons"])
        report["passed"] = all(case["passed"] for case in report["models"])
    except Exception as error:
        report["error"] = str(error)
    finally:
        try:
            if not args.keep_client or not report["passed"]:
                control("shutdown")
                report["shutdown_requested"] = True
        except Exception as error:
            report["shutdown_error"] = str(error)
        REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, allow_nan=False), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
