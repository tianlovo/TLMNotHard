"""Bounded original A/A, production A/B, real replay faults and recovery."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from compare import CONTRACT, compare, require, validate
from control import send


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def replay(launcher, original, destination, mode):
    require(sha(launcher["agent"]) == launcher["agentSha256"], "Prepared geometry agent changed")
    require(sha(launcher["identity"]) == launcher["identitySha256"], "Prepared port identity changed")
    command = [launcher["executable"], "-XX:+DisableAttachMechanism",
               "-Dtlmnh.geometry.buildIdentity=" + launcher["identity"],
               "-javaagent:" + launcher["agent"] + "=" + ",".join(launcher["selected"]),
               "-cp", os.pathsep.join(launcher["classpath"]), launcher["mainClass"],
               str(original), str(destination), mode]
    result = subprocess.run(command, cwd=launcher["workingDirectory"], capture_output=True, timeout=120)
    destination.with_suffix(".stdout.log").write_bytes(result.stdout)
    destination.with_suffix(".stderr.log").write_bytes(result.stderr)
    require(result.returncode == 0 and destination.is_file(), "Production geometry process failed; see retained logs")
    report = json.loads(destination.read_text(encoding="utf-8"))
    require(report["referenceSha256"] == sha(original), "Port result belongs to a different original")
    return report


def run(game_directory, launcher_file, evidence):
    # This output directory must be new; no old reference or rejection is overwritten
    evidence.mkdir(parents=True, exist_ok=False)
    result = {"schema": "geometry-experiment-v2", "passed": False, "cases": [],
              "scope": "two reviewed fresh bind meshes through original native and port production geometry methods",
              "worldRendererVerified": False, "dynamicAnimationVerified": False, "gpuEquivalenceVerified": False,
              "orderDependentRenderingVerified": False,
              "evidenceDirectory": str(evidence.resolve()), "launcherSha256": sha(launcher_file)}
    try:
        launcher = json.loads(launcher_file.read_text(encoding="utf-8"))
        require(launcher["schema"] == "geometry-replay-launcher-v2", "Unsupported production launcher")
        state = send(game_directory, "status")
        require(state["worldLoaded"] is True and state["spectator"] is True, "Original protected spectator lab must be open")
        result["originalStatus"] = state
        for index, model in enumerate(CONTRACT["models"]):
            captured = send(game_directory, "capture_bind", model=model)
            original_file = evidence / (str(index) + "-original.json")
            shutil.copyfile(captured["localCapture"], original_file)
            require(sha(original_file) == captured["captureSha256"], "Copied original capture differs")
            original = json.loads(original_file.read_text(encoding="utf-8"))
            validate(original, True)
            case = {"model": model, "originalSha256": sha(original_file), "originalAA": "three fresh native parses exactly stable", "runs": {}}
            result["cases"].append(case)
            for label, mode in (("normal", "none"), ("mirror_x", "mirror_x"), ("uv_shift", "uv_shift"), ("corner_swap", "corner_swap"), ("recovery", "none")):
                port_file = evidence / (str(index) + "-port-" + label + ".json")
                port = replay(launcher, original_file, port_file, mode)
                comparison = compare(original, port)
                (evidence / (str(index) + "-compare-" + label + ".json")).write_text(json.dumps(comparison, indent=2) + "\n", encoding="utf-8")
                case["runs"][label] = {"portSha256": sha(port_file), "comparison": comparison}
            normal = case["runs"]["normal"]["comparison"]
            mirror = case["runs"]["mirror_x"]["comparison"]
            uv = case["runs"]["uv_shift"]["comparison"]
            corner = case["runs"]["corner_swap"]["comparison"]
            recovery = case["runs"]["recovery"]["comparison"]
            case["passed"] = (normal["matched"] and recovery["matched"]
                              and mirror["verdict"] == "GEOMETRY_DIFFERENCE" and not mirror["position"]["matched"]
                              and corner["verdict"] == "GEOMETRY_DIFFERENCE" and not corner["position"]["matched"]
                              and uv["verdict"] == "GEOMETRY_DIFFERENCE" and not uv["uv"]["matched"]
                              and uv["position"]["matched"] and uv["normal"]["matched"] and uv["bindTreeMatched"])
        result["passed"] = len(result["cases"]) == 2 and all(case["passed"] for case in result["cases"])
        result["verdict"] = "PASS_BOUNDED_BIND_GEOMETRY_V2_EXPERIMENT" if result["passed"] else "BIND_GEOMETRY_V2_EXPERIMENT_FAILED"
    except (Exception, KeyboardInterrupt) as failure:
        result["verdict"] = "INCONCLUSIVE"
        result["error"] = str(failure)
    finally:
        with (evidence / "experiment.json").open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.game_dir, args.launcher, args.evidence)
    print(json.dumps({"passed": result["passed"], "verdict": result["verdict"], "error": result.get("error"),
                      "report": str(args.evidence / "experiment.json")}, ensure_ascii=False))
    return 0 if result["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
