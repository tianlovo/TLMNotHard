"""核对五种自然睡眠机制的实际输入与原始观察，不授予完整B10或统计结论"""

import argparse
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path

from check import UPSTREAM, require, sha
from compare_port import OBSERVER, PRODUCTION, artifact, metrics

MECHANISMS = ("normal", "occupied_release", "unreachable", "bed_removed", "hurt_sleeping")
SLEEP_OWNER = ["com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator",
               "com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernLife"]
PAIRED_OBSERVER = ["com.tianluoqaq.tlmnh.qa.SleepMechanismPortTests" + s for s in ("", "$Collector")]


def original_identity(data, build_bytes, tool, agent_bytes):
    build = json.loads(build_bytes)
    require(build["upstreamCommit"] == UPSTREAM and build["baseReferenceBuildIdentity"]["status"] == "LOCK_VERIFIED",
            "original toolchain unverified")
    for item in build["toolInputs"]:
        path = (tool / item["file"]).resolve()
        require(path.is_relative_to(tool.resolve()) and sha(path.read_bytes()) == item["sha256"],
                "original frozen tool input changed: " + item["file"])
    identity = data["identity"]
    require(identity["buildIdentitySha256"] == identity["agent"]["manifestSha256"] == sha(build_bytes),
            "original premain manifest mismatch")
    require(identity["agent"]["agentSha256"] == build["definitionAgentSha256"] == sha(agent_bytes), "agent mismatch")
    require(identity["wholeClasspathOrPostDefinitionBytesVerified"] is False, "identity scope upgraded")
    require(set(identity["classes"]) == set(build["compiledClasses"]) and len(identity["classes"]) == (12 if "difficulty" in data["contract"] else 11),
            "selected original class set changed")
    differences = []
    for name, item in identity["classes"].items():
        require(item["resourceSha256"] == build["compiledClasses"][name], "original resource mismatch: " + name)
        definition = item["definition"]
        require(definition["count"] == 1 and definition["stage"] ==
                "premain-transformer-input-after-defining-loader-transformations" and len(definition["sha256"]) == 64,
                "original first definition missing: " + name)
        if definition["sha256"] != item["resourceSha256"]:
            differences.append(name)
    return differences


def inspect(data, contract, contract_hash, profile):
    require(data["observationComplete"] is True and not data.get("error"), "incomplete capture")
    require(data["cleanupComplete" if profile == "original" else "ownCleanupComplete"] is True
            and data["controlledReservationAndWeatherRestored"] is True, "cleanup incomplete")
    require(data["contract"] == contract and data["contractSha256"] == contract_hash, "contract mismatch")
    require(data["benchmarkVerdict"] == "NOT_EVALUATED" and data["completeB10Accepted"] is False
            and data["randomSourcesReset"] is False, "scope upgraded")
    require(data["schema"] == ("sleep-paired-reference-mechanisms-v1" if profile == "original"
                               else "sleep-paired-port-mechanisms-v1"), "wrong capture schema")
    require(len(data["runs"]) == 1 and data["profile"] == profile, "wrong actor/profile count")
    row = data["runs"][0]
    require(row["repetition"] == 0 and row["entityClass"] == PRODUCTION[0], "not exact natural actor")
    if profile == "original":
        require(row["nativeBedPoiCount"] == (0 if data["mechanism"] == "occupied_release" else 1) and row["brainClass"] == "net.minecraft.world.entity.ai.Brain",
                "original POI/Brain changed")
    else:
        require(row["selection"] == "SELECTED" and row["actualProfile"] == profile, "production factory not selected")
    frames = row["frames"]
    require(len(frames) == contract["framesPerActor"], "frames missing")
    for i, sample in enumerate(frames):
        require(sample["frame"] == i and sample["age"] == i + 1 and sample["livingTicks"] == 1,
                "natural entity/Living tick sequence corrupted")
        require(sample["dayTime"] == contract["dayTime"] and sample["health"] > 0, "input time/actor changed")
        require(all(len(sample[k]) == 3 and all(math.isfinite(v) for v in sample[k]) for k in ("position", "motion")),
                "invalid trajectory")
        if profile != "original":
            require(sample["actualProfile"] == profile and sample["fullRootAuthorized"] is True,
                    "full selected root/Legacy exclusion changed")
        if i:
            require(sample["gameTime"] == frames[i-1]["gameTime"] + 1, "world tick sequence corrupted")
    first = next((i for i, f in enumerate(frames) if f["sleeping"]), None)
    mechanism = data["mechanism"]
    events = row["events"]
    expected = (contract["releaseFrame"] if mechanism == "occupied_release" else
                first + contract["afterFirstSleepingFrames"] if first is not None and mechanism in
                ("bed_removed", "hurt_sleeping") else None)
    if expected is not None and expected >= len(frames):
        expected = None
    require(len(events) == (0 if expected is None else 1), "event missing/duplicated")
    if events:
        event = events[0]
        require(event["frame"] == expected and event["kind"] == mechanism and
                event["preSleeping"] == frames[expected]["sleeping"] and
                event["healthBefore"] == frames[expected]["health"], "event phase or input changed")
    summary = metrics(row)
    summary.update({"profile": profile, "mechanism": mechanism, "events": events,
                    "event_not_triggered": mechanism in ("bed_removed", "hurt_sleeping") and not events})
    # 只报告已记录结果；无入睡/无事件的样本保留，不能变成一条通过的故障测试
    if mechanism == "unreachable":
        summary["slept_despite_unreachable_layout"] = first is not None
    if mechanism == "occupied_release":
        summary["slept_before_release"] = any(f["sleeping"] for f in frames[:contract["releaseFrame"]+1])
    if events:
        index = events[0]["frame"]
        summary["first_awake_frame_after_event"] = next((f["frame"] for f in frames[index+1:] if not f["sleeping"]), None)
        summary["later_sleep_frames"] = sum(f["sleeping"] for f in frames[index+1:])
    return summary


def immediate_hurt_wake(data):
    require(data["mechanism"] == "hurt_sleeping", "wrong wake mechanism")
    row = data["runs"][0]
    require(len(row["events"]) == 1, "sleeping damage event absent")
    event = row["events"][0]
    require(event["preSleeping"] is True and event["damageAccepted"] is True
            and event["healthAfter"] == event["healthBefore"] - data["contract"]["damage"], "actual sleeping damage not observed")
    require(event["postSleeping"] is False and event["postBedOccupied"] is False,
            "accepted sleeping damage did not immediately wake/release the bed")
    require(row["frames"][event["frame"] + 1]["sleeping"] is False, "next natural frame did not remain awake")
    return {"passed": True, "scope": "accepted generic sleeping damage immediate pose/bed release and next natural frame only"}


def pair_inputs(original, port):
    a, b = original["runs"][0], port["runs"][0]
    differences = []
    for key in ("initialPosition", "homeCenter", "actualRestrictRadius"):
        if a[key] != b[key]:
            differences.append({"key": key, "original": a[key], "port": b[key]})
    x, y = a["actualInputs"], b["actualInputs"]
    for key in ("width", "height", "health", "maxHealth", "movementSpeed", "hunger", "favorability",
                "model", "pickup", "difficulty", "raining", "thundering") + (("nonEmptyItemStackCount", "activeEffectCount")
                if "difficulty" in original["contract"] else ()):
        same = (math.isclose(x[key], y[key], abs_tol=1e-6, rel_tol=0) if
                isinstance(x[key], (int, float)) and not isinstance(x[key], bool) else x[key] == y[key])
        if not same:
            differences.append({"key": key, "original": x[key], "port": y[key]})
    # 任务ID在版本间有独立注册名，保留原值供审查，不用删前缀猜测等价
    tasks = {"original": x["task"], "port": y["task"]}
    def layout(value):
        return sorted((v["x"], v["y"], v["z"], v["semantic"]) for v in value["nonAirBlocks"])
    layout_equal = layout(x) == layout(y)
    require(all(v["semantic"] != "unknown" for z in (x, y) for v in z["nonAirBlocks"]), "unknown layout semantics")
    require(all((v["metadata"] & 3) == 2 for v in y["nonAirBlocks"] if v["semantic"].startswith("maid_bed")),
            "port bed north metadata incorrectly labelled")
    require(x["rawInitialNBT"] and y["rawInitialNBT"], "raw entity inputs absent")
    if "difficulty" in original["contract"]:
        require(x["difficulty"] == y["difficulty"] == original["contract"]["difficulty"], "actual difficulty not frozen")
        require(x["nonEmptyItemStackCount"] == y["nonEmptyItemStackCount"] == x["activeEffectCount"] == y["activeEffectCount"] == 0,
                "fresh actual items/effects not empty")
    return {"recorded_field_differences": differences, "semantic_block_layout_equal": layout_equal,
            "raw_task_ids": tasks, "inventory_and_full_NBT_semantic_equivalence_evaluated": False,
            "complete_actual_environment_pair_accepted": False,
            "scope": "recorded scalar/home/layout comparison; raw NBT retained, engine RNG/physics and full inventory semantics not qualified"}


def deterministic_spawn_inputs(data):
    """核实际出生状态和同一份NBT的读取值，不由调用工具函数推断生效"""
    contract = data["contract"]
    require("initialOrientation" in contract and "initialSoundPackId" in contract,
            "deterministic spawn contract absent")
    expected = dict(contract["initialOrientation"])
    expected.update({"previous" + key[0].upper() + key[1:]: value for key, value in list(expected.items())})
    require(set(expected) == {"yaw", "pitch", "headYaw", "bodyYaw", "previousYaw", "previousPitch",
                              "previousHeadYaw", "previousBodyYaw"}, "unknown orientation contract")
    for row in data["runs"]:
        initial = row["actualInputs"]
        require(initial.get("orientation") == expected, "actual spawn orientation differs from contract")
        require(initial.get("serializedRotation") == [expected["yaw"], expected["pitch"]],
                "serialized spawn rotation differs from contract")
        require(initial.get("soundPackId") == initial.get("serializedSoundPackId") == contract["initialSoundPackId"],
                "actual/serialized spawn sound pack differs from contract")
    return {"passed": True, "scope": "actual and serialized initial orientation/sound only; RNG streams not reset or paired"}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--original-captures", type=Path, required=True)
    p.add_argument("--original-build", type=Path, required=True)
    p.add_argument("--original-tool", type=Path, required=True)
    p.add_argument("--original-agent", type=Path, required=True)
    p.add_argument("--port-run", type=Path, required=True)
    p.add_argument("--candidate-jar", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--require-immediate-hurt-wake", action="store_true")
    p.add_argument("--require-paired-inputs", action="store_true")
    p.add_argument("--require-deterministic-spawn", action="store_true")
    args = p.parse_args()
    hqa = json.loads((args.port_run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.port_run / "observed.json").read_bytes())
    require(hqa["status"] == "passed" and hqa["exitCode"] == observed["process_exit"] == 0
            and observed["status"] == hqa and observed["full_release_passed"] is False, "HQA failed/incomplete")
    require(hqa["counts"]["selectedTests"] == hqa["counts"]["passed"] == 10 and
            all(hqa["counts"][k] == 0 for k in ("failed", "timedOut", "skipped", "incomplete", "issues")), "HQA case count changed")
    expected_names = {label + "".join(w.capitalize() for w in mechanism.split("_"))
                      for label in ("faithful", "enhanced") for mechanism in MECHANISMS}
    require({t["name"] for t in hqa["tests"]} == expected_names and all(t["status"] == "passed" for t in hqa["tests"]),
            "wrong HQA cases")
    cases = list(ET.parse(args.port_run / "TEST-horizonqa.xml").getroot().iter("testcase"))
    require(len(cases) == 10 and {c.get("name") for c in cases} == expected_names and
            all(not any(c.find(t) is not None for t in ("failure", "error", "skipped")) for c in cases), "JUnit differs")
    pilot = args.port_run / "tested-pilot.jar"
    require(observed["candidate_sha256"] == sha(args.candidate_jar.read_bytes()) and
            observed["pilot_sha256"] == sha(pilot.read_bytes()), "run artifacts differ")
    tool = args.original_tool
    contract_bytes = (tool / "paired-contract.json").read_bytes()
    contract = json.loads(contract_bytes)
    require(contract["contract"] == "SLEEP_PAIRED_MECHANISMS_V1" and contract["mechanisms"] == list(MECHANISMS)
            and contract["repetitions"] == 1 and contract["completeB10Accepted"] is False, "wrong exploration contract")
    originals = [json.loads(f.read_bytes()) for f in sorted(args.original_captures.glob("sleep-paired-*.json"))]
    require(len(originals) == 5 and {d["mechanism"] for d in originals} == set(MECHANISMS), "original scenes missing/duplicated")
    all_uuids, scenes = set(), []
    for original in originals:
        mechanism = original["mechanism"]
        differences = original_identity(original, args.original_build.read_bytes(), tool, args.original_agent.read_bytes())
        require(json.loads(args.original_build.read_bytes())["contractSha256"] == sha(contract_bytes), "build contract differs")
        summaries = [inspect(original, contract, sha(contract_bytes), "original")]
        if args.require_deterministic_spawn:
            summaries[0]["deterministic_spawn_inputs"] = deterministic_spawn_inputs(original)
        if args.require_immediate_hurt_wake and mechanism == "hurt_sleeping":
            summaries[0]["immediate_hurt_wake"] = immediate_hurt_wake(original)
        pairs = {}
        actors = [original]
        for profile in ("faithful_tlm", "enhanced"):
            port = json.loads((args.port_run / ("sleep-paired-" + profile + "-" + mechanism + ".json")).read_bytes())
            artifact(port["productionArtifact"], args.candidate_jar, PRODUCTION)
            artifact(port["observerArtifact"], pilot, OBSERVER)
            artifact(port["pairedObserverArtifact"], pilot, PAIRED_OBSERVER)
            if "difficulty" in contract:
                artifact(port["sleepOwnerArtifact"], args.candidate_jar, SLEEP_OWNER)
            if args.require_deterministic_spawn:
                artifact(port["spawnInputArtifact"], args.candidate_jar,
                         ["com.tianluoqaq.tlmnh.common.development.DeterministicMaidSpawn"])
            summaries.append(inspect(port, contract, sha(contract_bytes), profile))
            if args.require_deterministic_spawn:
                summaries[-1]["deterministic_spawn_inputs"] = deterministic_spawn_inputs(port)
            pairs[profile] = pair_inputs(original, port)
            if args.require_paired_inputs:
                require(not pairs[profile]["recorded_field_differences"] and pairs[profile]["semantic_block_layout_equal"]
                        and len(set(pairs[profile]["raw_task_ids"].values())) == 1, "recorded paired inputs differ")
            if args.require_immediate_hurt_wake and mechanism == "hurt_sleeping":
                summaries[-1]["immediate_hurt_wake"] = immediate_hurt_wake(port)
            actors.append(port)
        for actor in actors:
            uid = actor["runs"][0]["uuid"]
            require(uid not in all_uuids, "actor reused across scenes/profiles")
            all_uuids.add(uid)
        scenes.append({"mechanism": mechanism, "observations": summaries, "input_pairs": pairs,
                       "original_resource_definition_differences": differences})
    result = {"observation_integrity_passed": True, "scenes": scenes, "independent_actor_count": len(all_uuids),
              "frames": 15 * contract["framesPerActor"], "contract_sha256": sha(contract_bytes),
              "candidate_sha256": sha(args.candidate_jar.read_bytes()), "pilot_sha256": sha(pilot.read_bytes()),
              "complete_b10_accepted": False, "statistical_verdict": "NOT_EVALUATED",
              "definition_difference_explanation_verified": False, "full_release_passed": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "scenes"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
