"""从已批准的冻结包运行独立Java CPU回归，无需原版checkout或游戏进程"""

import argparse
import json
import os
import subprocess
import time
import zipfile
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import url2pathname

from ab_experiment import source_state
from baseline_bundle import catalog_entry, digest, json_bytes, strict_json, verify_bundle
from compare_ab import compare
from reference_environment import PROJECT

MAIN = "com.tianluoqaq.tlmnh.reference.replay.ReferenceAnimationReplay"
REQUIRED_CLASSES = {MAIN,
    "com.tianluoqaq.tlmnh.client.render.BuiltinGeckoPlayer",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController",
    "com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.AnimationProcessor",
    "com.tianluoqaq.tlmnh.client.render.BuiltinBedrockAnimations",
    "com.tianluoqaq.tlmnh.client.render.MaidAnimationState",
    "com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame",
    "com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel",
    "com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels",
    "com.tianluoqaq.tlmnh.client.render.BuiltinGeckoAnimations"}


def file_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != "file" or parsed.netloc not in ("", "localhost"):
        raise ValueError("Replay source is not a local compiled output")
    return Path(url2pathname(parsed.path)).resolve(strict=True)


def prepared_classes(launcher):
    if launcher.get("schema") != "reference-replay-launcher-v1" or launcher.get("mainClass") != MAIN:
        raise ValueError("Unknown replay launcher")
    roots = [Path(value).resolve(strict=True) for value in launcher["mainClasses"] if Path(value).is_dir()]
    production = {}
    for root in roots:
        for path in root.rglob("*.class"):
            name = path.relative_to(root).as_posix()
            if name in production:
                raise ValueError("Duplicate compiled production class")
            production[name] = {"path": path, "sha256": digest(path.read_bytes())}
    if not production:
        raise ValueError("No current compiled production classes")
    helper_name = MAIN.replace(".", "/") + ".class"
    helpers = [Path(value).resolve() / helper_name for value in launcher["testClasses"]
               if (Path(value) / helper_name).is_file()]
    if len(helpers) != 1:
        raise ValueError("Exactly one current replay helper is required")
    helper = {"path": helpers[0], "sha256": digest(helpers[0].read_bytes())}
    for location in launcher["classpath"]:
        path = Path(location).resolve()
        if path in roots or not path.exists():
            continue
        if path.is_dir():
            duplicate = any((path / name).is_file() for name in production)
            if (path / helper_name).is_file() and path / helper_name != helper["path"]:
                duplicate = True
        elif zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                duplicate = bool(set(archive.namelist()) & (production.keys() | {helper_name}))
        else:
            raise ValueError("Unknown replay classpath entry")
        if duplicate:
            raise ValueError("A non-production classpath entry shadows a current production class")
    return production, helper


def outputs_unchanged(production, helper):
    return all(value["path"].is_file() and digest(value["path"].read_bytes()) == value["sha256"]
               for value in [*production.values(), helper])


def verify_replay_sources(replay, launcher, production, helper):
    if set(replay.get("classSha256", {})) != REQUIRED_CLASSES or set(replay.get("classSources", {})) != REQUIRED_CLASSES:
        raise ValueError("Replay omitted or changed its required actual class witnesses")
    if not outputs_unchanged(production, helper):
        raise ValueError("Compiled replay outputs changed after preparation")
    if not replay.get("javaRuntime", "").startswith("25."):
        raise ValueError("Replay did not use Java25")
    if any(value.startswith(("-javaagent:", "-agentlib:", "-agentpath:")) for value in replay["runtimeInputArguments"]):
        raise ValueError("CPU replay must not load bytecode agents")
    main_roots = {Path(value).resolve(strict=True) for value in launcher["mainClasses"] if Path(value).is_dir()}
    test_roots = {Path(value).resolve(strict=True) for value in launcher["testClasses"] if Path(value).is_dir()}
    for name, expected_sha in replay["classSha256"].items():
        relative = name.replace(".", "/") + ".class"
        source = replay["classSources"][name]
        code = file_url(source["codeSource"])
        resource = file_url(source["resource"])
        if name == MAIN:
            if code not in test_roots or resource != helper["path"] or expected_sha != helper["sha256"]:
                raise ValueError("Replay helper did not come from the current test output")
        else:
            actual = production.get(relative, {})
            if code not in main_roots or resource != actual.get("path") or expected_sha != actual.get("sha256"):
                raise ValueError("Replay used a production class outside current main output: " + name)
        if digest(resource.read_bytes()) != expected_sha:
            raise ValueError("Replay class digest does not match its actual compiled source")
    assets = Path(launcher["mainResources"]).resolve(strict=True)
    for location, expected_sha in replay["assetSha256"].items():
        domain, relative = location.split(":", 1)
        actual = file_url(replay["assetSources"][location])
        if actual != assets / "assets" / domain / relative or digest(actual.read_bytes()) != expected_sha:
            raise ValueError("Replay asset did not come from current production resources: " + location)


def prepare(run, offline):
    launcher_path = run / "launcher.json"
    executable = os.environ.get("TLMNH_PORT_GRADLE", str(PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")))
    command = [executable, "--init-script=tools/animation-reference-1.20.1/replay.init.gradle", "prepareReferenceReplay",
               "-PreferenceLauncher=" + str(launcher_path), "--no-configuration-cache", "--console=plain",
               "-Dorg.gradle.jvmargs=-Xmx3G"]
    if offline:
        command.append("--offline")
    with (run / "prepare-gradle.log").open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=PROJECT, stdout=stream, stderr=subprocess.STDOUT, timeout=240)
    if result.returncode:
        raise RuntimeError("Replay compilation failed; see prepare-gradle.log")
    return strict_json(launcher_path.read_bytes())


def java_case(launcher, source, output, log, fault="none"):
    environment = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    work = Path(launcher["workingDirectory"])
    work.mkdir(parents=True, exist_ok=True)
    command = [launcher["executable"], "-Xmx512m", "-cp", os.pathsep.join(launcher["classpath"]),
               launcher["mainClass"], str(source), str(output), fault]
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=work, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=60)
    return result.returncode


def run_bundle(bundle, expected, run, offline):
    started = time.monotonic()
    approved = verify_bundle(bundle, expected)
    run.mkdir(parents=True, exist_ok=False)
    report = {"schema": "animation-bundle-regression-v1", "passed": False,
              "scope": "six controlled CPU fixtures with independent fresh JVMs; not game or GPU acceptance",
              "baselineId": approved["manifest"]["id"], "manifestSha256": expected,
              "originalSamplesRechecked": 18, "gradleOffline": offline, "gradleInvocations": 1,
              "renderingVerified": False, "behaviourBenchmarkVerified": False, "cases": [], "negativeControls": [],
              "sourceBefore": source_state()}
    try:
        launcher = prepare(run, offline)
        production, helper = prepared_classes(launcher)
        report["currentProductionClasses"] = len(production)
        for index, sample in enumerate(approved["cases"]):
            case = sample["case"]
            source = run / (str(index) + "-input.json")
            source.write_bytes(sample["raw"])
            output = run / (str(index) + "-replay.json")
            if java_case(launcher, source, output, run / (str(index) + "-java.log")):
                raise RuntimeError("Actual CPU replay failed for " + case["scenario"] + "/" + case["model"])
            replay = strict_json(output.read_bytes())
            verify_replay_sources(replay, launcher, production, helper)
            aa = {"passed": True, "models": [{"model": case["model"], "passed": True, "samples": case["samples"]}]}
            comparison = compare(sample["reference"], replay, digest(sample["raw"]), aa, approved["assets"])
            (run / (str(index) + "-comparison.json")).write_bytes(json_bytes(comparison))
            report["cases"].append({"model": case["model"], "scenario": case["scenario"],
                                    "status": comparison["status"], "currentProductionSourcesVerified": True,
                                    "replay": output.name})
            print(json.dumps(report["cases"][-1]), flush=True)
            if case["model"] != "geckolib:winefox_hanfu":
                continue
            fault_output = run / (str(index) + "-fault-replay.json")
            if java_case(launcher, source, fault_output, run / (str(index) + "-fault-java.log"), "head_offset"):
                raise RuntimeError("Actual fault execution failed before comparison")
            fault_replay = strict_json(fault_output.read_bytes())
            verify_replay_sources(fault_replay, launcher, production, helper)
            fault = compare(sample["reference"], fault_replay, digest(sample["raw"]), aa, approved["assets"])
            (run / (str(index) + "-fault-comparison.json")).write_bytes(json_bytes(fault))
            first = fault.get("comparison", {}).get("first") or {}
            detected = (fault["status"] == "CPU_DIFFERENCE" and first.get("path") == "frames[0].bones.Head.translation[0]"
                        and first.get("candidate", 0) - first.get("reference", 0) == .25)
            report["negativeControls"].append({"scenario": case["scenario"], "kind": "actual_gecko_head_offset",
                                               "passed": detected, "first": first})
            incomplete = strict_json(sample["raw"])
            del incomplete["frames"][0]["inputs"]["query"]["groundSpeed"]
            invalid = run / (str(index) + "-missing-field.json")
            invalid.write_bytes(json_bytes(incomplete))
            log = run / (str(index) + "-missing-field-java.log")
            code = java_case(launcher, invalid, run / (str(index) + "-invalid-replay.json"), log)
            rejected = code != 0 and "Missing captured field: groundSpeed" in log.read_text(encoding="utf-8", errors="replace")
            report["negativeControls"].append({"scenario": case["scenario"], "kind": "actual_java_missing_field", "passed": rejected})
        invalid = strict_json(approved["cases"][0]["raw"])
        invalid["schema"] = "unreviewed-capture-schema"
        source = run / "unknown-schema.json"
        source.write_bytes(json_bytes(invalid))
        log = run / "unknown-schema-java.log"
        code = java_case(launcher, source, run / "unknown-schema-replay.json", log)
        report["negativeControls"].append({"kind": "actual_java_unknown_schema", "passed": code != 0
                                          and "Unsupported capture schema" in log.read_text(encoding="utf-8", errors="replace")})
        report["sourceAfter"] = source_state()
        report["compiledOutputsUnchanged"] = outputs_unchanged(production, helper)
        report["sourcesUnchanged"] = all(report["sourceBefore"][key] == report["sourceAfter"][key]
                                          for key in ("head", "main_input_sha256", "tool_input_sha256"))
        report["passed"] = (len(report["cases"]) == 6 and len(report["negativeControls"]) == 7
                            and all(case["status"] == "CPU_MATCH_EXACT" for case in report["cases"])
                            and all(case["passed"] for case in report["negativeControls"])
                            and report["sourcesUnchanged"] and report["compiledOutputsUnchanged"])
    except Exception as error:
        report["error"] = str(error)
    report["elapsedSeconds"] = time.monotonic() - started
    (run / "experiment.json").write_bytes(json_bytes(report))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id")
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--expected-manifest-sha256")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    if args.id:
        root, expected = catalog_entry(args.id)
    else:
        root, expected = args.bundle, args.expected_manifest_sha256
        if root is None or expected is None:
            parser.error("Provide --id or --bundle with --expected-manifest-sha256")
    report = run_bundle(root.absolute(), expected, args.output.absolute(), args.offline)
    print(json.dumps({"passed": report["passed"], "elapsedSeconds": report["elapsedSeconds"], "error": report.get("error")}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
