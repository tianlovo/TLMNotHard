"""合同和差分器的合成输入单元测试，不作为原版采集或端到端证据"""

import copy
import unittest
from unittest.mock import patch

import compare_ab
from compare_aa import validate
from scenario_contract import HEAD_MODE, HEAD_CONTRACT, head_angles, head_inputs


def fixtures():
    reference = {"schema": "observation-v0", "inputMode": HEAD_MODE, "upstreamCommit": compare_ab.BASE,
                 "faultInjection": "none", "actualAssetSha256": {}, "frames": []}
    replay = {"schema": "port-replay-v0", "referenceSha256": "synthetic-unit-input", "upstreamCommit": compare_ab.BASE,
              "modelChoice": "geckolib:winefox_hanfu", "stageObserverNeutral": True, "assetSha256": {}, "frames": []}
    for step in range(72):
        yaw, pitch = head_angles(step)
        core = {"Head": {"translation": [0.0, 0.0, 0.0], "rotationRadians": [0.0, 0.0, 0.0]}}
        head = {"Head": {"translation": [0.0, 0.0, 0.0], "rotationRadians": [-pitch / 60, -yaw / 60, 0.0]}}
        query = {"bodyYaw": -180, "netHeadYaw": -yaw, "headPitch": -pitch,
                 "groundSpeed": 0, "verticalSpeed": 0, "yawSpeed": 0,
                 "x": 0, "y": 65, "z": 0, "previousX": 0, "previousY": 65, "previousZ": 0}
        frame = {"tick": step, "partialTick": 0, "onGround": True, "fallback": False,
                 "phase": "render-post", "backend": "gecko", "seekTicks": step,
                 "modelChoice": replay["modelChoice"], "headPitch": pitch, "netHeadYaw": yaw,
                 "previousHeadPitch": pitch, "bodyYaw": 180, "previousBodyYaw": 180,
                 "headYaw": 180 + yaw, "previousHeadYaw": 180 + yaw, "limbPosition": 0, "limbSpeed": 0,
                 "setupAnimInvoked": True, "engineUpdated": True,
                 "inputs": {"contract": HEAD_CONTRACT, "render": head_inputs(step, "gecko"), "query": query, "state": {}},
                 "coreBones": core, "headBones": head, "bones": copy.deepcopy(head), "controllers": []}
        reference["frames"].append(frame)
        replay["frames"].append({"tick": step, "backend": "gecko", "seekTicks": step, "engineUpdated": True,
                                 "consumedState": {}, "consumedRender": copy.deepcopy(frame["inputs"]["render"]),
                                 "consumedQuery": copy.deepcopy(query), "controllers": [],
                                 "coreBones": copy.deepcopy(core), "headBones": copy.deepcopy(head), "bones": copy.deepcopy(head)})
    aa = {"passed": True, "models": [{"model": replay["modelChoice"], "passed": True,
          "samples": [{"sha256": replay["referenceSha256"]}] * 3}]}
    return reference, replay, aa


def compare_unit(reference, replay, aa):
    # 这里只测阶段投影和差分，不访问Git资产，也不将合成数据写进实测报告
    with patch.object(compare_ab, "pinned_assets", return_value=({}, [])), patch.object(compare_ab, "dependencies", return_value={}):
        return compare_ab.compare(reference, replay, replay["referenceSha256"], aa)


class HeadTurnContractTest(unittest.TestCase):
    def test_backend_signs_and_segment_boundaries_are_explicit(self):
        self.assertEqual((0, 0), head_angles(17))
        self.assertEqual((30, 15), head_angles(18))
        self.assertEqual((30, 15), head_angles(35))
        self.assertEqual((-45, -20), head_angles(36))
        self.assertEqual((-45, -20), head_angles(53))
        self.assertEqual((0, 0), head_angles(54))
        self.assertEqual(-30, head_inputs(18, "gecko")["netHeadYaw"])
        self.assertEqual(30, head_inputs(18, "bedrock")["netHeadYaw"])

    def test_valid_nonzero_inputs_do_not_require_equal_animation_stages(self):
        reference, replay, aa = fixtures()
        validate(reference)
        self.assertEqual("CPU_MATCH_EXACT", compare_unit(reference, replay, aa)["status"])

    def test_core_head_and_final_differences_are_each_observable(self):
        for phase in ("coreBones", "headBones", "bones"):
            with self.subTest(phase=phase):
                reference, replay, aa = fixtures()
                replay["frames"][18][phase]["Head"]["translation"][0] = .25
                result = compare_unit(reference, replay, aa)
                self.assertEqual("CPU_DIFFERENCE", result["status"])
                self.assertEqual("frames[18]." + phase + ".Head.translation[0]", result["comparison"]["first"]["path"])
                self.assertEqual(1, result["bone_phases"][phase]["count"])

    def test_wrong_original_sign_or_missing_actual_phase_is_not_accepted(self):
        reference, _, _ = fixtures()
        reference["frames"][18]["inputs"]["render"]["netHeadYaw"] = 30
        with self.assertRaises(ValueError):
            validate(reference)
        reference, _, _ = fixtures()
        del reference["frames"][18]["coreBones"]
        with self.assertRaises(KeyError):
            validate(reference)

    def test_missing_or_failed_observer_neutrality_is_inconclusive(self):
        reference, replay, aa = fixtures()
        replay["stageObserverNeutral"] = False
        self.assertEqual("INCONCLUSIVE", compare_unit(reference, replay, aa)["status"])


if __name__ == "__main__":
    unittest.main()
