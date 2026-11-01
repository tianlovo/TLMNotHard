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


def metrics(frames, positions, owners, observed_steps=None):
    distances = [math.dist(a, b) for a, b in zip(positions, owners)]
    if observed_steps is None:
        observed_steps = [None] + [math.dist(positions[i - 1], positions[i]) for i in range(1, len(positions))]
    large_steps = [i for i, step in enumerate(observed_steps) if step is not None and step > 3]
    return {
        "frames": len(frames),
        "mean_distance": sum(distances) / len(distances),
        "last20_mean_distance": sum(distances[-20:]) / 20,
        "max_distance": max(distances),
        "within_2_5_fraction": sum(d <= 2.5 for d in distances) / len(distances),
        "first_within_2_5_frame": next((i for i, d in enumerate(distances) if d <= 2.5), None),
        "large_step_frames_not_teleport_method_proof": large_steps,
        "first_frame_displacement_observed": observed_steps[0] is not None,
        "largest_observed_step": max(step for step in observed_steps if step is not None),
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
    assert port["contract"] == contract["contract"]
    assert contract["contract"] in ("FOLLOW_NATURAL_O_V1", "FOLLOW_NATURAL_WATER_O_V1")
    water = contract["contract"] == "FOLLOW_NATURAL_WATER_O_V1"
    if water:
        assert port["contractSha256"] == capture["contractSha256"]
    assert port["complete"] is True and port["ownCleanupComplete"] is True
    assert port["candidateIsCompleteFaithfulProfile"] is False and port["statisticalAdmission"] is False
    assert port["originalEquivalencePassed"] is False and port["worldRngAndSensorPhaseUncontrolled"] is True
    assert observed["process_exit"] == 0 and observed["candidate_sha256"] == args.candidate_sha256
    assert hqa["status"] == "passed" and hqa["counts"]["failed"] == hqa["counts"]["incomplete"] == 0
    test_name = "collectSameWaterInputsOnFreshNaturalActors" if water else "collectSameOwnerInputsOnFreshNaturalActors"
    assert any(t["name"] == test_name and t["status"] == "passed" for t in hqa["tests"])
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
        if water:
            assert row["input"] == specs[key[0]]
            assert row["terrainBefore"] == row["terrainAfter"]
            o_terrain = o_rows[key]["terrainBefore"]
            assert row["terrainBefore"] == {k: o_terrain[k] for k in ("kind", "waterCells", "glassCells", "cells")}
        for i, frame in enumerate(row["frames"]):
            assert type(frame["index"]) is int and type(frame["age"]) is int
            assert frame["index"] == i and frame["age"] == i + 1 and frame["health"] == 20
            assert frame["activity"] == "WORK"
            intended = original.planned(specs[key[0]], i, [0, 0, 0])
            assert math.dist(frame["owner"], intended) <= contract["ownerTolerance"]
            assert len(frame["position"]) == 3
            attributes["port_component"].add(frame["speed"])
        p_rows[key] = row
    expected_runs = len(contract["cases"]) * contract["rebuilds"]
    expected_frames = sum(scene["ticks"] for scene in contract["cases"]) * contract["rebuilds"]
    assert set(p_rows) == set(o_rows) and len(uuids) == expected_runs
    assert len({row["ownerUuid"] for row in p_rows.values()}) == 1
    assert port["totalFrames"] == sum(len(row["frames"]) for row in p_rows.values()) == expected_frames
    origin = contract["origin"]
    for key, row in o_rows.items():
        name = key[0] + ":" + str(key[1])
        o_frames = row["frames"]
        p_frames = p_rows[key]["frames"]
        o_positions = [[v - base for v, base in zip(f["after"]["maid"]["position"], origin)] for f in o_frames]
        o_owners = [[v - base for v, base in zip(f["after"]["owner"]["position"], origin)] for f in o_frames]
        p_positions = [f["position"] for f in p_frames]
        p_owners = [f["owner"] for f in p_frames]
        descriptions["O"][name] = metrics(o_frames, o_positions, o_owners,
            [math.dist(frame["before"]["maid"]["position"], frame["after"]["maid"]["position"]) for frame in o_frames])
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
                        "Minecraft1.20.1 vs GTNH1.7.10 native world/physics",
                        "only the declared synthetic owner trajectories and terrain; no controlled RNG or teleport-method observer",
                        "port has END samples only; its frame0 displacement is unobserved and never treated as zero",
                        "no complete F/N, statistical benchmark, cost comparison or prediction benefit admission"],
    }
    if water:
        def node_types(samples):
            from collections import Counter
            counts = Counter()
            for sample in samples:
                counts.update(sample.get("nodeTypes", []))
            return dict(counts)
        report["water_observations"] = {}
        for key, row in p_rows.items():
            original_frames = [frame["after"] for frame in o_rows[key]["frames"]]
            def counts(frames):
                from collections import Counter
                return dict(Counter(str(frame["height"]) for frame in frames))
            report["water_observations"][key[0] + ":" + str(key[1])] = {
                "O_height_frames": counts(original_frames), "port_height_frames": counts(row["frames"]),
                "O_sampled_path_node_occurrences": node_types([frame["navigation"]["path"] or {} for frame in original_frames]),
                "port_sampled_path_node_occurrences": node_types(row["frames"]),
            }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"controls_and_capture_passed": True, "original_equivalence_passed": False,
                      "statistical_admission": False, "runs_per_group": expected_runs, "frames_per_group": expected_frames,
                      "movement_attributes": report["source_default_movement_attributes"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
