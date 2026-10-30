"""回放两个已通过原版重复检查的空手待机样本，并验证错误与缺输入会失败"""

import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from compare_ab import compare
from reference_environment import EVIDENCE, GIT, PROJECT, RUNTIME


def source_state():
    digest = hashlib.sha256()
    files = sorted(path for folder in (PROJECT / "src/main/java", PROJECT / "src/main/resources")
                   for path in folder.rglob("*") if path.is_file())
    for path in files:
        digest.update(path.relative_to(PROJECT).as_posix().encode())
        digest.update(b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    head = subprocess.check_output([GIT, "-C", str(PROJECT), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output([GIT, "-C", str(PROJECT), "status", "--porcelain"], text=True)
    return {"head": head, "dirty": bool(dirty), "main_input_files": len(files), "main_input_sha256": digest.hexdigest()}


def replay(reference, output, log, fault="none"):
    wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
    command = [os.environ.get("TLMNH_PORT_GRADLE", str(PROJECT / wrapper)),
               "--init-script=tools/animation-reference-1.20.1/replay.init.gradle", "replayReferenceAnimation",
               "-PreferenceInput=" + str(reference), "-PreferenceOutput=" + str(output),
               "-PreferenceReplayFault=" + fault, "--no-configuration-cache", "--console=plain",
               "-Dorg.gradle.jvmargs=-Xmx3G"]
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=PROJECT, stdout=stream, stderr=subprocess.STDOUT, timeout=180)
    return result.returncode


def main():
    aa = json.loads((EVIDENCE / "aa-experiment.json").read_text(encoding="utf-8"))
    if not aa["passed"]:
        raise ValueError("Original repeatability must pass before replay")
    run = EVIDENCE / "replays" / str(time.time_ns())
    run.mkdir(parents=True)
    report = {"scope": "two controlled empty-idle CPU fixtures and negative controls", "passed": False,
              "equivalence_verified": False, "rendering_verified": False, "approved_for_bulk": False,
              "cases": [], "source_before": source_state()}
    try:
        sources = {}
        for index, case in enumerate(aa["models"]):
            path = (RUNTIME / case["samples"][0]["file"]).resolve(strict=True)
            if not path.is_relative_to(RUNTIME):
                raise ValueError("Original baseline must remain in the reference runtime")
            raw = path.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            if digest != case["samples"][0]["sha256"]:
                raise ValueError("Original baseline changed after A/A")
            reference = json.loads(raw)
            sources[case["model"]] = (path, raw, reference)
            output = run / (str(index) + "-replay.json")
            code = replay(path, output, run / (str(index) + "-gradle.log"))
            if code:
                raise RuntimeError("Port replay failed for model " + case["model"] + "; see its Gradle log")
            comparison = compare(reference, json.loads(output.read_text(encoding="utf-8")), digest, aa)
            (run / (str(index) + "-comparison.json")).write_text(json.dumps(comparison, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            report["cases"].append({"model": case["model"], "reference_sha256": digest,
                                    "comparison": comparison, "replay": output.relative_to(EVIDENCE).as_posix()})
            print(json.dumps({"model": case["model"], "status": comparison["status"]}), flush=True)
        path, raw, reference = sources["geckolib:winefox_hanfu"]
        output = run / "head-fault-replay.json"
        if replay(path, output, run / "head-fault-gradle.log", "head_offset"):
            raise RuntimeError("Actual port fault run did not finish")
        fault = compare(reference, json.loads(output.read_text(encoding="utf-8")), hashlib.sha256(raw).hexdigest(), aa)
        (run / "head-fault-comparison.json").write_text(json.dumps(fault, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        first = fault.get("comparison", {}).get("first") or {}
        report["actual_port_fault_detected"] = (fault["status"] == "CPU_DIFFERENCE"
            and first.get("path") == "frames[0].bones.Head.translation[0]"
            and first.get("candidate", 0) - first.get("reference", 0) == .25)
        incomplete = json.loads(raw)
        del incomplete["frames"][0]["inputs"]["query"]["groundSpeed"]
        invalid = run / "incomplete-input.json"
        invalid.write_text(json.dumps(incomplete, ensure_ascii=False) + "\n", encoding="utf-8")
        log = run / "incomplete-input-gradle.log"
        code = replay(invalid, run / "incomplete-replay.json", log)
        report["incomplete_input_rejected"] = code != 0 and "Missing captured field: groundSpeed" in log.read_text(encoding="utf-8", errors="replace")
        report["source_after"] = source_state()
        report["main_inputs_unchanged_during_run"] = report["source_before"]["main_input_sha256"] == report["source_after"]["main_input_sha256"]
        report["passed"] = (all(case["comparison"]["status"] == "CPU_MATCH_EXACT" for case in report["cases"])
            and report["actual_port_fault_detected"] and report["incomplete_input_rejected"] and report["main_inputs_unchanged_during_run"])
    except Exception as error:
        report["error"] = str(error)
    report["evidence_directory"] = run.relative_to(EVIDENCE).as_posix()
    text = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    (run / "experiment.json").write_text(text, encoding="utf-8")
    (EVIDENCE / "ab-experiment.json").write_text(text, encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "error": report.get("error"),
                      "actual_port_fault_detected": report.get("actual_port_fault_detected"),
                      "incomplete_input_rejected": report.get("incomplete_input_rejected")}), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
