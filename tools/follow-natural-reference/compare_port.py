"""核对实际O/移植侧自然输入与清理，再报告描述性跟随数据；不准入完整F或统计收益"""

import argparse
import gzip
import hashlib
import importlib.util
import json
import math
from pathlib import Path

if not __debug__:
    raise RuntimeError("Evidence checks must not be disabled")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def metrics(frames, positions, owners):
    distances = [math.dist(a, b) for a, b in zip(positions, owners)]
    large_steps = [i for i in range(1, len(positions)) if math.dist(positions[i - 1], positions[i]) > 3]
    return {
        "frames": len(frames),
        "mean_distance": sum(distances) / len(distances),
        "last20_mean_distance": sum(distances[-20:]) / 20,
        "max_distance": max(distances),
        "within_2_5_fraction": sum(d <= 2.5 for d in distances) / len(distances),
        "first_within_2_5_frame": next((i for i, d in enumerate(distances) if d <= 2.5), None),
        "large_step_frames_not_teleport_method_proof": large_steps,
    }


def rms(a, b):
    assert len(a) == len(b)
    return math.sqrt(sum(math.dist(x, y) ** 2 for x, y in zip(a, b)) / len(a))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-capture", type=Path, required=True)
    parser.add_argument("--original-build-identity", type=Path, required=True)
    parser.add_argument("--original-tool", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--port-capture", type=Path, required=True)
    parser.add_argument("--hqa-result", type=Path, required=True)
    parser.add_argument("--observed", type=Path, required=True)
    parser.add_argument("--candidate-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("verified_original_natural", args.original_tool / "compare.py")
    original = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(original)
    capture = original.load(args.original_capture)
    contract, o_rows = original.verified_rows(capture, args.original_build_identity)
    port = original.load(args.port_capture)
    hqa = original.load(args.hqa_result)
    observed = original.load(args.observed)
    assert port["schema"] == "follow-natural-port-observation-v1"
    assert port["contract"] == contract["contract"] == "FOLLOW_NATURAL_O_V1"
    assert port["complete"] is True and port["ownCleanupComplete"] is True
    assert port["candidateIsCompleteFaithfulProfile"] is False and port["statisticalAdmission"] is False
    assert port["originalEquivalencePassed"] is False and port["worldRngAndSensorPhaseUncontrolled"] is True
    assert observed["process_exit"] == 0 and observed["candidate_sha256"] == args.candidate_sha256
    assert hqa["status"] == "passed" and hqa["counts"]["failed"] == hqa["counts"]["incomplete"] == 0
    assert any(t["name"] == "collectSameOwnerInputsOnFreshNaturalActors" and t["status"] == "passed" for t in hqa["tests"])
    original.finite_values(port)
    p_rows = {}
    uuids = set()
    descriptions = {"O": {}, "port_component": {}}
    trajectories = {"O": {}, "port_component": {}}
    attributes = {"O": set(), "port_component": set()}
    specs = {row["id"]: row for row in contract["cases"]}
    for row in port["runs"]:
        key = row["id"], row["rebuild"]
        assert key not in p_rows and row["maidUuid"] not in uuids
        uuids.add(row["maidUuid"])
        assert row["complete"] is True and row["actorRemoved"] is True
        assert len(row["frames"]) == specs[key[0]]["ticks"]
        for i, frame in enumerate(row["frames"]):
            assert frame["index"] == i and frame["age"] == i + 1 and frame["health"] == 20
            assert frame["activity"] == "WORK"
            intended = original.planned(specs[key[0]], i, [0, 0, 0])
            assert math.dist(frame["owner"], intended) <= contract["ownerTolerance"]
            assert len(frame["position"]) == 3
            attributes["port_component"].add(frame["speed"])
        p_rows[key] = row
    assert set(p_rows) == set(o_rows) and len(uuids) == 9
    assert len({row["ownerUuid"] for row in p_rows.values()}) == 1
    assert port["totalFrames"] == sum(len(row["frames"]) for row in p_rows.values()) == 1800
    origin = contract["origin"]
    for key, row in o_rows.items():
        name = key[0] + ":" + str(key[1])
        o_frames = row["frames"]
        p_frames = p_rows[key]["frames"]
        o_positions = [[v - base for v, base in zip(f["after"]["maid"]["position"], origin)] for f in o_frames]
        o_owners = [[v - base for v, base in zip(f["after"]["owner"]["position"], origin)] for f in o_frames]
        p_positions = [f["position"] for f in p_frames]
        p_owners = [f["owner"] for f in p_frames]
        descriptions["O"][name] = metrics(o_frames, o_positions, o_owners)
        descriptions["port_component"][name] = metrics(p_frames, p_positions, p_owners)
        trajectories["O"][key] = o_positions
        trajectories["port_component"][key] = p_positions
        attributes["O"].update(f["after"]["movementAttribute"]["value"] for f in o_frames)
    assert attributes["O"] == attributes["port_component"], "Movement attribute control differs"
    rms_descriptions = {}
    for case in specs:
        value = {}
        for group in ("O", "port_component"):
            value[group + "_within_sample_rms"] = [
                rms(trajectories[group][case, a], trajectories[group][case, b]) for a in range(3) for b in range(a + 1, 3)
            ]
        value["all_O_port_pair_rms"] = [
            rms(trajectories["O"][case, a], trajectories["port_component"][case, b]) for a in range(3) for b in range(3)
        ]
        rms_descriptions[case] = value
    report = {
        "controls_and_capture_passed": True,
        "full_faithful_profile_passed": False,
        "original_equivalence_passed": False,
        "statistical_admission": False,
        "candidate_sha256": args.candidate_sha256,
        "inputs": {"original_capture": digest(args.original_capture), "original_identity": digest(args.original_build_identity),
                   "port_capture": digest(args.port_capture), "hqa": digest(args.hqa_result), "observed": digest(args.observed)},
        "source_default_movement_attributes": {key: sorted(values) for key, values in attributes.items()},
        "descriptive_runs": descriptions,
        "descriptive_rms": rms_descriptions,
        "limitations": ["three unseeded repeats, no significance claim", "original real connected owner vs port standard FakePlayer",
                        "Minecraft1.20.1 vs GTNH1.7.10 native world/physics", "only three flat synthetic owner trajectories",
                        "no complete F/N, combat/water benchmark, cost comparison or prediction benefit admission"],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"controls_and_capture_passed": True, "original_equivalence_passed": False,
                      "statistical_admission": False, "runs_per_group": 9, "frames_per_group": 1800,
                      "movement_attributes": report["source_default_movement_attributes"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
