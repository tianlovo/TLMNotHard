"""核对实际O/移植侧自然输入与清理，再报告描述性跟随数据；不准入完整F或统计收益"""

import argparse
import gzip
import hashlib
import importlib.util
import json
import math
import zipfile
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


def verify_packaged_resources(identity, artifact, expected_sha, classes):
    assert artifact is not None, "Schema v2 requires the actual packaged artifact"
    assert digest(artifact) == identity["artifactSha256"] == expected_sha
    assert identity["javaSpecificationVersion"] == "25"
    assert identity["transformedDefinitionBytesVerified"] is False
    assert set(identity["classResources"]) == set(identity["classResourceBindings"]) == set(classes)
    with zipfile.ZipFile(artifact) as archive:
        for name in classes:
            entry = name.replace(".", "/") + ".class"
            binding = identity["classResourceBindings"][name]
            assert binding == {"logicalEntry": entry, "physicalEntry": entry, "multiReleaseVersion": 0}
            assert hashlib.sha256(archive.read(entry)).hexdigest() == identity["classResources"][name]


def verify_policy(value, row, expected_profile):
    assert value["profile"] == expected_profile
    assert value["ready"] is value["afterNativeNavigation"] is value["developmentOnly"] is value["bindingStable"] is True
    enhanced = expected_profile == "enhanced_core_component"
    assert value["brainClass"] == ("com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain" if enhanced
                                   else "com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain")
    if enhanced:
        assert value["flightProfile"] == "NATIVE_MOTION_COMPENSATION"
        assert type(value["independentCoreDecisions"]) is int and value["independentCoreDecisions"] >= 0
    assert value["contextMaidUuid"] == row["maidUuid"]
    assert type(value["epoch"]) is int and 0 <= value["epoch"] <= 2 ** 63 - 1
    return value["epoch"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-capture", type=Path, required=True)
    parser.add_argument("--original-build-identity", type=Path, required=True)
    parser.add_argument("--original-tool", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--port-capture", type=Path, required=True)
    parser.add_argument("--hqa-result", type=Path, required=True)
    parser.add_argument("--observed", type=Path, required=True)
    parser.add_argument("--candidate-sha256", required=True)
    parser.add_argument("--expected-port-profile", choices=("follow_core_component", "faithful_tlm", "enhanced_core_component"),
                        default="follow_core_component")
    parser.add_argument("--candidate-jar", type=Path)
    parser.add_argument("--pilot-jar", type=Path)
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
    assert port["schema"] in ("follow-natural-port-observation-v1", "follow-natural-port-observation-v2")
    version_two = port["schema"] == "follow-natural-port-observation-v2"
    faithful = args.expected_port_profile == "faithful_tlm"
    enhanced = args.expected_port_profile == "enhanced_core_component"
    factory = faithful or enhanced
    assert version_two or not factory, "Historical component captures cannot establish F or N identity"
    port_label = "N_development" if enhanced else "F_development" if faithful else "port_component"
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
    if version_two:
        assert port["expectedProfile"] == args.expected_port_profile
        assert port["contractSha256"] == capture["contractSha256"] == digest(args.original_tool / "contract.json")
        if faithful:
            assert observed["canonical_faithful_profile"] is True
        if enhanced:
            assert observed["requested_natural_profile"] == "enhanced_core_component"
            assert observed["canonical_faithful_profile"] is False
            assert port["candidateIsCompleteEnhancedProfile"] is False
        production_classes = (
            "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid",
            "com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain" if enhanced
                else "com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain",
            "com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime")
        actor_class = "com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor"
        if enhanced or actor_class in port["productionArtifact"]["classResources"]:
            production_classes += (actor_class,)
        verify_packaged_resources(port["productionArtifact"], args.candidate_jar, args.candidate_sha256,
                                  production_classes)
        verify_packaged_resources(port["observerArtifact"], args.pilot_jar, observed["pilot_sha256"], (
            "com.tianluoqaq.tlmnh.qa.NaturalFollowPortTests",
            "com.tianluoqaq.tlmnh.qa.NaturalFollowPortTests$Collector",
            "com.tianluoqaq.tlmnh.qa.NaturalWaterPortTests",
            "com.tianluoqaq.tlmnh.qa.ModernCoreTests$Fixture"))
    p_rows = {}
    uuids = set()
    descriptions = {"O": {}, port_label: {}}
    trajectories = {"O": {}, port_label: {}}
    attributes = {"O": set(), port_label: set()}
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
        if version_two:
            assert row["bindingEntry"] == ("entity_protected_profile_selection" if factory else "explicit_development_component")
            assert row["selectionResult"] == ("SELECTED" if factory else "NOT_APPLICABLE")
            previous_epoch = verify_policy(row["initialPolicy"], row, args.expected_port_profile)
        for i, frame in enumerate(row["frames"]):
            if version_two:
                epoch = verify_policy(frame["policy"], row, args.expected_port_profile)
                if enhanced:
                    assert frame["policy"]["independentCoreDecisions"] > 0
                assert epoch >= previous_epoch
                previous_epoch = epoch
            assert type(frame["index"]) is int and type(frame["age"]) is int
            assert frame["index"] == i and frame["age"] == i + 1 and frame["health"] == 20
            assert frame["activity"] == "WORK"
            intended = original.planned(specs[key[0]], i, [0, 0, 0])
            assert math.dist(frame["owner"], intended) <= contract["ownerTolerance"]
            assert len(frame["position"]) == 3
            attributes[port_label].add(frame["speed"])
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
        descriptions[port_label][name] = metrics(p_frames, p_positions, p_owners)
        trajectories["O"][key] = o_positions
        trajectories[port_label][key] = p_positions
        attributes["O"].update(f["after"]["movementAttribute"]["value"] for f in o_frames)
    assert attributes["O"] == attributes[port_label], "Movement attribute control differs"
    rms_descriptions = {}
    for case in specs:
        value = {}
        for group in ("O", port_label):
            value[group + "_within_sample_rms"] = [
                rms(trajectories[group][case, a], trajectories[group][case, b]) for a in range(3) for b in range(a + 1, 3)
            ]
        value["all_O_port_pair_rms"] = [
            rms(trajectories["O"][case, a], trajectories[port_label][case, b]) for a in range(3) for b in range(3)
        ]
        rms_descriptions[case] = value
    report = {
        "controls_and_capture_passed": True,
        "full_faithful_profile_passed": False,
        "original_equivalence_passed": False,
        "statistical_admission": False,
        "candidate_sha256": args.candidate_sha256,
        "port_profile": args.expected_port_profile,
        "port_group": port_label,
        "policy_provenance_verified": version_two,
        "canonical_faithful_binding_verified": faithful and version_two,
        "independent_enhanced_binding_verified": enhanced and version_two,
        "full_enhanced_profile_passed": False,
        "packaged_class_resources_verified": version_two,
        "post_transform_definitions_verified": False,
        "inputs": {"original_capture": digest(args.original_capture), "original_identity": digest(args.original_build_identity),
                   "port_capture": digest(args.port_capture), "hqa": digest(args.hqa_result), "observed": digest(args.observed)},
        "source_default_movement_attributes": {key: sorted(values) for key, values in attributes.items()},
        "descriptive_runs": descriptions,
        "descriptive_rms": rms_descriptions,
        "limitations": ["three unseeded repeats, no significance claim", "original real connected owner vs port standard FakePlayer",
                        "Minecraft1.20.1 vs GTNH1.7.10 native world/physics",
                        "only the declared synthetic owner trajectories and terrain; no controlled RNG or teleport-method observer",
                        "port has END samples only; its frame0 displacement is unobserved and never treated as zero",
                        "packaged class resources are verified for v2; post-transform class definitions are not attested",
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
