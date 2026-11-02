"""Verify matched native aim observations and report descriptive outcomes, without statistical admission."""

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import struct

if not __debug__:
    raise RuntimeError("Evidence checks must not be disabled")

ARMS = ("NATIVE_STATIC_COMPENSATION", "NATIVE_LAST_SAMPLE_COMPENSATION", "NATIVE_MOTION_COMPENSATION")
CASES = ("stationary", "lateral", "accelerating", "reverse_after_release", "jump_after_release")
DISTANCES = (8, 11, 14)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(value):
    assert value["schema"] == "native-aim-ablation-v1" and value["complete"] is True
    assert value["expectedShots"] == value["completedShots"] == len(value["shots"]) == 45
    assert value["initialSpeed"] == 3 and value["spread"] == 0
    assert value["fullReleasePassed"] is value["statisticalAdmission"] is False
    drag = struct.unpack("f", struct.pack("f", .99))[0]
    gravity = struct.unpack("f", struct.pack("f", .05))[0]
    assert value["expectedAirDrag"] == drag and value["expectedGravity"] == gravity
    cells, arrows, targets, shooters = {}, set(), set(), set()
    for row in value["shots"]:
        key = row["case"], row["distance"], row["profile"]
        assert key not in cells and row["case"] in CASES and row["distance"] in DISTANCES and row["profile"] in ARMS
        assert row["complete"] is True and row["bowWear"] == 1 and row["remainingArrows"] == 7
        assert row["nativeUnobstructedPhysicsSamples"] == 2 and row["attackBase"] == 2
        assert row["targetWidth"] == row["targetHeight"] == .9
        assert row["arrowUuid"] not in arrows and row["targetUuid"] not in targets
        arrows.add(row["arrowUuid"])
        targets.add(row["targetUuid"])
        shooters.add(row["shootingMaidUuid"])
        frames = row["frames"]
        assert [frame["step"] for frame in frames] == list(range(13))
        assert all(math.isfinite(v) for frame in frames for v in frame["target"])
        assert row["hit"] is (row["nativeArrowDamageEvents"] > 0 and row["finalTargetHealth"] < 200)
        position, velocity = row["launchPosition"], row["launchVelocity"]
        assert abs(math.dist(velocity, [0, 0, 0]) - 3) < 1E-6
        assert abs(frames[4]["target"][0] - position[0] - row["distance"]) < 1E-10
        for index in (5, 6):
            frame = frames[index]
            expected_position = [p + v for p, v in zip(position, velocity)]
            expected_velocity = [velocity[0] * drag, velocity[1] * drag - gravity, velocity[2] * drag]
            assert frame["arrowDead"] is False and frame["arrowAge"] == index - 4
            assert max(abs(a - b) for a, b in zip(frame["arrow"], expected_position)) < 1E-9, "native_position"
            assert max(abs(a - b) for a, b in zip(frame["arrowVelocity"], expected_velocity)) < 1E-12, "native_velocity"
            position, velocity = frame["arrow"], frame["arrowVelocity"]
        if row["case"] == "stationary":
            assert row["hit"] is True
        cells[key] = row
    assert len(shooters) == 1 and len(arrows) == len(targets) == 45
    summary = {}
    for case in CASES:
        summary[case] = {}
        for profile in ARMS:
            rows = [cells[(case, distance, profile)] for distance in DISTANCES]
            summary[case][profile] = {"hits": sum(row["hit"] for row in rows), "shots": 3,
                                      "hit_x_separations": [row["distance"] for row in rows if row["hit"]]}
        for distance in DISTANCES:
            rows = [cells[(case, distance, profile)] for profile in ARMS]
            inputs = [[frame["target"] for frame in row["frames"]] for row in rows]
            assert inputs[0] == inputs[1] == inputs[2], "matched_target_inputs"
            assert rows[0]["launchPosition"] == rows[1]["launchPosition"] == rows[2]["launchPosition"], "matched_origin"
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("capture", "observed", "hqa-result", "main-jar", "pilot-jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    value = json.loads(args.capture.read_text(encoding="utf-8"))
    observed = json.loads(args.observed.read_text(encoding="utf-8"))
    hqa = json.loads(args.hqa_result.read_text(encoding="utf-8"))
    assert observed["process_exit"] == 0 and hqa["status"] == "passed"
    assert hqa["counts"]["failed"] == hqa["counts"]["incomplete"] == 0
    names = {test["name"] for test in hqa["tests"] if test["status"] == "passed"}
    assert {"matchedActuatorsUseTheSameRealPhysicsAndRecordAllPredeterminedShots",
            "everyNativeArmRejectsASolidBarrierWithoutChargingResources"} <= names
    path = Path(__file__).resolve().parents[1] / "follow-natural-reference/compare_port.py"
    spec = importlib.util.spec_from_file_location("packaged_identity_check", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    prefix = "com.tianluoqaq.tlmnh.common.combat."
    module.verify_packaged_resources(value["productionArtifact"], args.main_jar, observed["candidate_sha256"],
                                    tuple(prefix + name for name in ("MaidBowAttack", "ObservedTargetMotion",
                                                                    "ArrowInterceptSolver", "ArrowPhysics")))
    module.verify_packaged_resources(value["observerArtifact"], args.pilot_jar, observed["pilot_sha256"], (
        "com.tianluoqaq.tlmnh.qa.NativeAimAblationTests", "com.tianluoqaq.tlmnh.qa.NativeAimAblationTests$Experiment"))
    outcomes = verify(value)
    result = {"controls_and_observation_passed": True, "candidate_sha256": observed["candidate_sha256"],
              "capture_sha256": digest(args.capture), "matched_shots": 45, "actual_physics_samples": 90,
              "bow_wear_points": 45, "finite_arrows_consumed": 45, "descriptive_outcomes": outcomes,
              "distance_field_means_x_separation": True, "statistical_admission": False,
              "full_natural_ai_comparison": False, "full_release_passed": False,
              "limitations": ["prescribed target positions with native Pig movement speed zero",
                              "production release called after five observations; no natural Brain/draw-cycle claim",
                              "three predetermined x separations, zero spread; no population significance claim",
                              "native world arrow ticks/collision/damage; class resources, not transformed definitions"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
