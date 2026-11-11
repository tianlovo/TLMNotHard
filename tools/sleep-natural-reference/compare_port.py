"""核对原版与完整F/N自然睡眠观察，报告描述性差异，不准入完整B10或统计收益。"""

import argparse
import hashlib
import json
import math
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

from check import check, require
from attribute_inputs import snapshot

PRODUCTION = [
    "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid",
    "com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain",
    "com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain",
    "com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime",
    "com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor",
    "com.tianluoqaq.tlmnh.common.life.MaidHomeArea",
    "com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed",
]
OBSERVER = ["com.tianluoqaq.tlmnh.qa.NaturalSleepPortTests" + suffix for suffix in ("", "$Collector")]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def artifact(identity, path, classes):
    require(identity["artifactSha256"] == digest(path.read_bytes()), "actual artifact changed")
    require(identity["javaSpecificationVersion"] == "25" and identity["transformedDefinitionBytesVerified"] is False,
            "runtime or identity scope changed")
    require(set(identity["classResources"]) == set(identity["classResourceBindings"]) == set(classes),
            "selected packaged classes changed")
    with zipfile.ZipFile(path) as jar:
        for name in classes:
            entry = name.replace(".", "/") + ".class"
            require(identity["classResourceBindings"][name] == {
                "logicalEntry": entry, "physicalEntry": entry, "multiReleaseVersion": 0}, "resource binding changed")
            require(identity["classResources"][name] == digest(jar.read(entry)), "packaged class mismatch: " + name)


def metrics(row):
    frames = row["frames"]
    sleeping = [s for s in frames if s["sleeping"]]
    return {"repetition": row["repetition"], "frames": len(frames),
            "first_sleep_frame": sleeping[0]["frame"] if sleeping else None,
            "sleep_frames": len(sleeping),
            "sleep_y_values_rounded_6dp": sorted({round(s["position"][1], 6) for s in sleeping}),
            "sleep_max_horizontal_distance_from_head_center": max(
                (math.hypot(s["position"][0] - 7.5, s["position"][2] - 4.5) for s in sleeping), default=None),
            "sleep_max_abs_motion": max((max(abs(v) for v in s["motion"]) for s in sleeping), default=None)}


def stable_sleep(data):
    """基础无干扰场景下的入睡与位置保持检查。"""
    for row in data["runs"]:
        frames = row["frames"]
        first = next((i for i, s in enumerate(frames) if s["sleeping"]), None)
        require(first is not None and first + 1 < len(frames), "baseline has no sustained sleep observation")
        for i, s in enumerate(frames[first:], start=first):
            require(s["sleeping"] and s["bedOwned"] and s["bedOccupied"], "baseline sleep or own bed lease changed")
            y = 1.8 if i == first else 1.5625
            require(all(abs(a - b) < 1e-6 for a, b in zip(s["position"], (7.5, y, 4.5))),
                    "sleep position drift: repetition=%s frame=%s" % (row["repetition"], i))
            require(abs(s["motion"][0]) < 1e-6 and abs(s["motion"][2]) < 1e-6, "sleep horizontal motion remains")
    return {"passed": True, "scope": "uninterrupted baseline entry and subsequent sleeping anchor/own bed lease only"}


def port_capture(data, contract, contract_sha, candidate, pilot):
    require(data["schema"] == "sleep-natural-port-feasibility-v1", "wrong port schema")
    require(data["observationComplete"] is data["ownCleanupComplete"] is True and not data.get("error"),
            "port observation or cleanup incomplete")
    require(data["contract"] == contract and data["contractSha256"] == contract_sha, "input contract changed")
    require(data["benchmarkVerdict"] == "NOT_EVALUATED" and data["completeB10Accepted"] is False
            and data["randomSourcesReset"] is False and data["samplePhase"] == "WorldTickEvent.END", "scope/phase changed")
    profile = data["profile"]
    require(profile in ("faithful_tlm", "enhanced"), "not a full production factory profile")
    artifact(data["productionArtifact"], candidate, PRODUCTION)
    artifact(data["observerArtifact"], pilot, OBSERVER)
    if "attributeInputArtifact" in data:
        artifact(data["attributeInputArtifact"], pilot, ["com.tianluoqaq.tlmnh.qa.SleepAttributePortSnapshot"])
    runs = data["runs"]
    require(len(runs) == contract["repetitions"] and len({r["uuid"] for r in runs}) == len(runs), "fresh actors missing")
    for index, row in enumerate(runs):
        if "attributeInputArtifact" in data:
            snapshot(row["attributeInputs"], "port")
        require(row["repetition"] == index and row["entityClass"] == PRODUCTION[0]
                and row["selection"] == "SELECTED" and row["actualProfile"] == profile, "factory identity changed")
        require(row["initialPosition"] == [2.5, 1, 4.5] and row["homeCenter"] == [7, 1, 4]
                and row["actualRestrictRadius"] == 6 and row["bedDirectionMetadata"] == 10, "actual port initial inputs changed")
        frames = row["frames"]
        require(len(frames) == contract["framesPerActor"], "port frames missing")
        for i, sample in enumerate(frames):
            require(sample["frame"] == i and sample["age"] == i + 1 and sample["livingTicks"] == 1,
                    "port natural tick sequence corrupted")
            require(sample["actualProfile"] == profile and sample["activity"] == "REST" and sample["dayTime"] == 6000,
                    "port natural profile or rest input changed")
            require(all(len(sample[k]) == 3 and all(math.isfinite(v) for v in sample[k]) for k in ("position", "motion"))
                    and math.isfinite(sample["health"]) and sample["health"] > 0, "invalid port frame")
            if i:
                require(sample["gameTime"] == frames[i - 1]["gameTime"] + 1, "port world tick sequence corrupted")
    return {"profile": profile, "observation_integrity_passed": True,
            "baseline_sleep_observed_in_all_repetitions": all(any(s["sleeping"] for s in r["frames"]) for r in runs),
            "runs": [metrics(r) for r in runs]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-capture", type=Path, required=True)
    parser.add_argument("--original-build", type=Path, required=True)
    parser.add_argument("--original-agent", type=Path, required=True)
    parser.add_argument("--original-tool", type=Path, required=True)
    parser.add_argument("--port-capture", type=Path, action="append", required=True)
    parser.add_argument("--candidate-jar", type=Path, required=True)
    parser.add_argument("--pilot-jar", type=Path, required=True)
    parser.add_argument("--hqa-result", type=Path, required=True)
    parser.add_argument("--observed", type=Path, required=True)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--require-stable-sleep", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raw = args.original_capture.read_bytes()
    original = json.loads(raw)
    verified = check(raw, args.original_build.read_bytes(), args.original_tool, args.original_agent.read_bytes())
    ports = [port_capture(json.loads(p.read_bytes()), original["contract"], original["contractSha256"],
                          args.candidate_jar, args.pilot_jar) for p in args.port_capture]
    require(len({p["profile"] for p in ports}) == len(ports), "duplicate profile captures")
    if args.require_stable_sleep:
        for summary, path in zip(ports, args.port_capture):
            summary["sleep_anchor_check"] = stable_sleep(json.loads(path.read_bytes()))
    hqa = json.loads(args.hqa_result.read_bytes())
    observed = json.loads(args.observed.read_bytes())
    require(hqa["status"] == "passed" and hqa["exitCode"] == observed["process_exit"] == 0
            and observed["status"] == hqa and observed["full_release_passed"] is False, "HQA process/result incomplete")
    require(observed["candidate_sha256"] == digest(args.candidate_jar.read_bytes())
            and observed["pilot_sha256"] == digest(args.pilot_jar.read_bytes()), "HQA actual artifacts differ")
    require(hqa["counts"]["selectedTests"] == hqa["counts"]["passed"] >= len(ports)
            and all(hqa["counts"][key] == 0 for key in ("failed", "timedOut", "skipped", "incomplete", "issues")),
            "HQA selected cases did not all pass")
    names = {"faithful_tlm": "collectFreshOfflineHomeFaithfulActors", "enhanced": "collectFreshOfflineHomeEnhancedActors"}
    expected = {names[p["profile"]] for p in ports}
    actual_names = {t["name"] for t in hqa["tests"]}
    require(actual_names >= expected and len(hqa["tests"]) == len(actual_names) == hqa["counts"]["selectedTests"]
            and all(t["status"] == "passed" for t in hqa["tests"]),
            "HQA cases differ from captured profiles")
    cases = list(ET.parse(args.junit).getroot().iter("testcase"))
    require({t.get("name") for t in cases} == actual_names and len(cases) == len(actual_names)
            and all(not any(t.find(tag) is not None for tag in ("failure", "error", "skipped")) for t in cases),
            "JUnit cases did not pass")
    result = {"observation_integrity_passed": True, "original_integrity": verified,
              "original_runs": [metrics(r) for r in original["runs"]], "ports": ports,
              "candidate_sha256": digest(args.candidate_jar.read_bytes()), "pilot_sha256": digest(args.pilot_jar.read_bytes()),
              "original_capture_sha256": digest(raw), "benchmark_verdict": "NOT_EVALUATED",
              "complete_b10_or_statistical_admission": False,
              "complete_actual_initial_environment_pair_binding": False,
              "scope": "offline owner/home baseline descriptive observations; RNG, cross-version physics and complete environment inputs uncontrolled"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"observation_integrity_passed": True, "profiles": [p["profile"] for p in ports],
                      "benchmark_verdict": "NOT_EVALUATED"}))


if __name__ == "__main__":
    main()
