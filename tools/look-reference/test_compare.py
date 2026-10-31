"""纯协议边界测试，不生成原版游戏结果或AI成绩"""

import copy
import tempfile
import unittest
from unittest import mock
from pathlib import Path

import compare
import control


class StrictProtocolTest(unittest.TestCase):
    def test_boolean_is_not_a_counter_or_coordinate(self):
        for operation in (compare.integer, compare.number):
            with self.assertRaises(ValueError):
                operation(True)
        with self.assertRaises(ValueError):
            compare.vector([0, False, 1])

    def test_vector_is_exactly_three_finite_scalars(self):
        for value in ([0, 1], [0, 1, 2, 3], [0, [1], 2], [0, float("nan"), 2], [0, float("inf"), 2]):
            with self.assertRaises(ValueError):
                compare.vector(value)
        compare.vector([0, 1.5, -2])

    def test_definition_digest_is_lowercase_hex(self):
        compare.digest("a0" * 32)
        for value in ("g0" * 32, "A0" * 32, "a0" * 31, True):
            with self.assertRaises(ValueError):
                compare.digest(value)

    def test_duplicate_json_keys_and_nonfinite_values_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for value in ('{"count":1,"count":2}', '{"count":NaN}'):
                path.write_text(value, encoding="utf-8")
                with self.assertRaises(ValueError):
                    compare.load(path)

    def test_diff_does_not_equate_boolean_and_integer(self):
        self.assertIsNotNone(compare.difference({"count": 1}, {"count": True}))
        self.assertIsNone(compare.difference([1], [1.0]))

    def test_single_or_missing_rebuild_cannot_pass_empty_row_gate(self):
        # This is a missing-evidence protocol fixture, not a synthetic successful game row
        reference = {"schema": "look-reference-capture-v1", "inputObservationSchema": "look-component-input-observations-v1",
                     "contracts": compare.CONTRACTS, "complete": True, "naturalBrainVerified": False,
                     "nativeHelperTicked": False, "nativeNavigationVerified": False, "fault": "none", "cases": []}
        with self.assertRaises(ValueError):
            compare.checked_rows(reference, {"cases": [{"id": "required-original-row"}]})


class BaselineAdmissionTest(unittest.TestCase):
    """Only admission counterexamples: these are not fabricated successful game captures."""

    def reports(self):
        identity = {"classes": {"selected.OriginalClass": {"definitionSha256": "a" * 64,
                    "resourceSha256": "b" * 64, "definitionCount": 1, "stage": "observed", "loaderClass": "loader"}}}
        current = {"identity": copy.deepcopy(identity), "fault": "target-feet"}
        baseline = {"identity": copy.deepcopy(identity), "fault": "none"}
        # Opaque output isolates the three-rebuild admission rule; identity/row validation is tested elsewhere
        rows = {("case", index): {"rebuild": index, "observedOutput": 7} for index in range(3)}
        return current, baseline, rows

    def test_fault_capture_cannot_be_its_own_normal_baseline(self):
        current, baseline, rows = self.reports()
        baseline["fault"] = "target-feet"
        with self.assertRaisesRegex(ValueError, "normal baseline"):
            compare.admit_baseline(current, baseline, rows)

    def test_same_resource_but_different_actual_definition_cannot_be_compared(self):
        current, baseline, rows = self.reports()
        baseline["identity"]["classes"]["selected.OriginalClass"]["definitionSha256"] = "c" * 64
        with self.assertRaisesRegex(ValueError, "definition identity"):
            compare.admit_baseline(current, baseline, rows)

    def test_baseline_rebuild_drift_is_invalid_before_effect_comparison(self):
        current, baseline, rows = self.reports()
        rows[("case", 2)]["observedOutput"] = 8
        with self.assertRaisesRegex(ValueError, "three fresh-state A/A"):
            compare.admit_baseline(current, baseline, rows)


class InputBindingTest(unittest.TestCase):
    """Fixture-input invariants, not a model of LookAtTargetSink output."""

    def observed(self):
        return {"worldGameTime": 100, "maidPosition": [.5, 192, .5], "radius": 8,
                "ownerPosition": [.5, 192, 8.5], "ownerEyeHeight": 1.25,
                "ownerEyePosition": [.5, 193.25, 8.5], "look": None,
                "visibleMemoryPresent": True, "visibleCandidateContainsOwner": True,
                "ownerAlive": True, "ownerSpectator": False}

    def test_stable_unpatched_input_drift_is_rejected_even_when_before_and_after_agree(self):
        original = self.observed()
        for field, value in (("maidPosition", [1.5, 192, .5]), ("radius", 9), ("ownerSpectator", True)):
            changed = copy.deepcopy(original)
            changed[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "unpatched actual inputs"):
                compare.bind_method_inputs(original, changed, copy.deepcopy(changed), {}, {}, 100)
        changed = copy.deepcopy(original)
        changed["ownerEyeHeight"] = 1.5
        changed["ownerEyePosition"] = [.5, 193.5, 8.5]
        with self.assertRaisesRegex(ValueError, "unpatched actual inputs"):
            compare.bind_method_inputs(original, changed, copy.deepcopy(changed), {}, {}, 100)

    def test_explicit_owner_position_patch_keeps_other_inputs_frozen(self):
        original = self.observed()
        changed = copy.deepcopy(original)
        changed.update(worldGameTime=101, ownerPosition=[2.5, 193, 8.5], ownerEyePosition=[2.5, 194.25, 8.5])
        patch = {"targetPosition": [2.5, 193, 8.5]}
        compare.bind_method_inputs(original, changed, copy.deepcopy(changed), patch, copy.deepcopy(patch), 101)
        drifted = copy.deepcopy(changed)
        drifted["radius"] = 9
        with self.assertRaisesRegex(ValueError, "read-only actual inputs"):
            compare.bind_method_inputs(original, changed, drifted, patch, copy.deepcopy(patch), 101)

    def test_dynamic_walk_coordinates_are_allowed_but_path_speed_and_identity_drift_are_rejected(self):
        observed = self.observed()
        observed.update(ownerPosition=[2.5, 193, 8.5], ownerEyePosition=[2.5, 194.25, 8.5])
        target = {"identity": "t1", "class": "net.minecraft.world.entity.ai.behavior.EntityTracker", "target": "owner",
                  "trackEye": True, "position": [.5, 193.25, 8.5], "block": [0, 192, 8]}
        previous = {"look": None, "walk": {"tracker": target, "speed": .5, "closeEnough": 2}, "path": "p1", "cantReachSince": None}
        expected = copy.deepcopy(previous)
        expected["walk"]["tracker"].update(position=[2.5, 194.25, 8.5], block=[2, 193, 8])
        compare.bind_memory_before(previous, expected, observed)
        bad_path = copy.deepcopy(expected)
        bad_path["path"] = "p2"
        bad_speed = copy.deepcopy(expected)
        bad_speed["walk"]["speed"] = 1
        bad_identity = copy.deepcopy(expected)
        bad_identity["walk"]["tracker"]["identity"] = "t2"
        for changed in (bad_path, bad_speed, bad_identity):
            with self.assertRaisesRegex(ValueError, "preserve unpatched policy memory"):
                compare.bind_memory_before(previous, changed, observed)

    def test_new_fixture_position_and_control_are_bound_before_any_method(self):
        observed = self.observed()
        spec = {"initial": {"lookPresent": False, "visibleMemory": "owner"}, "steps": [{"methodTime": 100}]}
        stopped = {"status": "STOPPED", "endTimestamp": 0}
        state = {"memory": {"look": None, "walk": None, "path": None, "cantReachSince": None},
                 "tasks": {"look": copy.deepcopy(stopped), "follow": copy.deepcopy(stopped),
                           "move": {**stopped, "remainingCooldown": 0, "path": None, "lastTargetPos": None, "speedModifier": 0}},
                 "lookControl": {"wanted": [0, 0, 0], "cooldown": 0, "yawSpeed": 0, "pitchSpeed": 0},
                 "navigation": {"path": None, "done": True, "stuck": False, "source": "controlled full path; no native navigation tick"},
                 "worldIntegerIndex": 0}
        nominal = {"id": "input-protocol-only", "initialInputObservation": observed, "initialState": state}
        for field, value in (("maidPosition", [1.5, 192, .5]), ("radius", 9)):
            changed = copy.deepcopy(nominal)
            changed["initialInputObservation"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                compare.validate_initial(changed, spec, "none")
        changed = copy.deepcopy(nominal)
        changed["initialState"]["lookControl"]["cooldown"] = 2
        with self.assertRaisesRegex(ValueError, "new native control"):
            compare.validate_initial(changed, spec, "none")
        changed = copy.deepcopy(nominal)
        changed["initialState"]["memory"]["path"] = "unexpected-preexisting-path"
        with self.assertRaisesRegex(ValueError, "new fixture memory"):
            compare.validate_initial(changed, spec, "none")


class AtomicRequestTest(unittest.TestCase):
    def test_reader_glob_sees_only_complete_request_after_writer_closes(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "request.json"
            real_dump = control.json.dump

            def observe_dump(value, stream):
                self.assertFalse(target.exists())
                self.assertEqual([], list(Path(directory).glob("*.json")))
                self.assertTrue(target.with_suffix(".json.tmp").is_file())
                real_dump(value, stream)
                self.assertFalse(target.exists())

            with mock.patch.object(control.json, "dump", side_effect=observe_dump):
                control.write_request(target, {"command": "status"})
            self.assertEqual({"command": "status"}, compare.load(target))
            self.assertFalse(target.with_suffix(".json.tmp").exists())

    def test_failed_writer_keeps_final_json_unpublished_and_does_not_overwrite_tmp(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "request.json"
            with mock.patch.object(control.json, "dump", side_effect=RuntimeError("expected writer fault")):
                with self.assertRaises(RuntimeError):
                    control.write_request(target, {"command": "status"})
            self.assertFalse(target.exists())
            self.assertTrue(target.with_suffix(".json.tmp").exists())
            with self.assertRaises(FileExistsError):
                control.write_request(target, {"command": "status"})
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
