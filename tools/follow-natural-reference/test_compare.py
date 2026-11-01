"""Only evidence-parser and normalisation invariants; no synthetic original-AI oracle"""

import gzip
import importlib.util
import json
import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("follow_natural_compare", Path(__file__).with_name("compare.py"))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class EvidenceChecks(unittest.TestCase):
    def test_vectors_require_three_finite_numbers(self):
        compare.check_vector([0, 1.5, -2])
        for invalid in ([], [1], [1, 2], [1, 2, 3, 4], (1, 2, 3), [True, 2, 3], ["1", 2, 3], [float("nan"), 2, 3], [float("inf"), 2, 3], [10 ** 1000, 2, 3]):
            with self.assertRaises(AssertionError):
                compare.check_vector(invalid)

    def test_definition_digest_and_count_have_exact_shapes(self):
        value = {"definitionCount": 1, "definitionSha256": "a" * 64}
        compare.check_runtime_identity_shapes({"classes": {"actual.Class": value}})
        for invalid in ({**value, "definitionCount": True}, {**value, "definitionCount": 1.0},
                        {**value, "definitionSha256": "x" * 64}, {**value, "definitionSha256": "A" * 64},
                        {**value, "definitionSha256": "a" * 63}):
            with self.assertRaises(AssertionError):
                compare.check_runtime_identity_shapes({"classes": {"actual.Class": invalid}})

    def test_only_original_edible_action_members_are_accepted(self):
        value = {"kind": "maid_edible_block_action", "class": "com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction", "name": "TRY_STEAL"}
        compare.check_edible_action(value)
        compare.check_edible_action({**value, "name": "TRY_PLACE"})
        for invalid in ({**value, "name": "UNKNOWN"}, {**value, "class": "other.Enum"}, {**value, "kind": "generic_enum"}, {**value, "extra": True}):
            with self.assertRaises(AssertionError):
                compare.check_edible_action(invalid)

    def test_sampling_rejects_gift_in_hand_or_wrong_slot(self):
        empty = {"registryId": "minecraft:air", "count": 0, "empty": True}
        owner = {"selectedSlot": 1, "mainHand": empty, "offHand": empty}
        compare.check_empty_hands(owner, 1)
        for changed in ({**owner, "selectedSlot": 0},
                        {**owner, "mainHand": {"registryId": "touhou_little_maid:smart_slab_init", "count": 1, "empty": False}},
                        {**owner, "offHand": {"registryId": "minecraft:stone", "count": 1, "empty": False}}):
            with self.assertRaises(AssertionError):
                compare.check_empty_hands(changed, 1)

    def test_duplicate_and_nonfinite_json_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for text in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":1e9999}'):
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    compare.load(path)

    def test_compressed_size_is_bounded_after_decompression(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.gz"
            with gzip.open(path, "wb") as stream:
                stream.write(b'"' + b"x" * 200 + b'"')
            with patch.object(compare, "MAX_JSON", 32):
                with self.assertRaises(ValueError):
                    compare.load(path)

    def test_normalisation_keeps_movement_attributes_and_ttl(self):
        row = {"maidUuid": "maid-uuid", "ownerUuid": "owner-uuid",
               "spawnState": {"maid": {"tickCount": 7}, "owner": {"tickCount": 100}}}
        source = {"gameTime": 1001, "maid": {"uuid": "maid-uuid", "entityId": 37, "tickCount": 8,
                                             "position": [64.5, 192, 0.5], "movement": [0.2, 0, 0]},
                  "movementAttribute": {"base": 0.7, "value": 0.7, "modifiers": []},
                  "memory": {"minecraft:cant_reach_walk_target_since": {"present": True, "value": 1000, "timeToLive": 9}}}
        actual = compare.normalise(source, row, [64, 192, 0], 1000)
        self.assertEqual(actual["maid"]["position"], [0.5, 0, 0.5])
        self.assertEqual(actual["maid"]["movement"], [0.2, 0, 0])
        self.assertEqual(actual["maid"]["tickCount"], 1)
        self.assertEqual(actual["movementAttribute"], source["movementAttribute"])
        self.assertEqual(actual["memory"]["minecraft:cant_reach_walk_target_since"]["value"], 0)
        self.assertEqual(actual["memory"]["minecraft:cant_reach_walk_target_since"]["timeToLive"], 9)
        self.assertEqual(source["maid"]["uuid"], "maid-uuid")

    def test_motion_difference_survives_normalisation(self):
        row = {"maidUuid": "m", "ownerUuid": "o", "spawnState": {"maid": {"tickCount": 0}, "owner": {"tickCount": 0}}}
        a = {"maid": {"uuid": "m", "entityId": 1, "tickCount": 1, "movement": [0.1, 0, 0]}}
        b = json.loads(json.dumps(a))
        b["maid"]["movement"][0] = 0.2
        self.assertIsNotNone(compare.first_difference(compare.normalise(a, row, [0, 0, 0], 0), compare.normalise(b, row, [0, 0, 0], 0)))

    def test_boundary_scene_is_a_real_input_jump(self):
        contract = compare.load(Path(__file__).with_name("flat-contract-v1.json"))
        scene = next(case for case in contract["cases"] if case["id"] == "N03_teleport_boundary")
        a = compare.planned(scene, 39, contract["origin"])
        b = compare.planned(scene, 40, contract["origin"])
        self.assertEqual(b[0] - a[0], 14)
        self.assertEqual(a[1:], b[1:])


class CapturedEvidenceChecks(unittest.TestCase):
    """Optional real-capture mutations; a frozen identity adapter is only a layered checker review.

    Set TLMNH_FOLLOW_NATURAL_TEST_CAPTURE and TLMNH_FOLLOW_NATURAL_TEST_BUILD.
    For an older capture, explicitly set TLMNH_FOLLOW_NATURAL_TEST_FROZEN_TOOL
    to its retained tool directory. That original checker verifies its own bound
    inputs; the current checker only rechecks evidence and class digest shapes.
    This mode does not establish a current-tool build identity or AI verdict.
    """

    @classmethod
    def setUpClass(cls):
        capture_path = os.environ.get("TLMNH_FOLLOW_NATURAL_TEST_CAPTURE")
        build_path = os.environ.get("TLMNH_FOLLOW_NATURAL_TEST_BUILD")
        if not capture_path and not build_path:
            raise unittest.SkipTest("No retained real natural capture configured")
        if not capture_path or not build_path:
            raise RuntimeError("Both retained capture and build identity are required")
        cls.capture = compare.load(Path(capture_path))
        cls.build_path = Path(build_path)
        frozen_path = os.environ.get("TLMNH_FOLLOW_NATURAL_TEST_FROZEN_TOOL")
        cls.frozen = None
        if frozen_path:
            module_spec = importlib.util.spec_from_file_location("frozen_natural_fixture_identity", Path(frozen_path) / "compare.py")
            cls.frozen = importlib.util.module_from_spec(module_spec)
            module_spec.loader.exec_module(cls.frozen)
            cls.frozen.identity(cls.capture, cls.build_path)
        cls.verify(cls.capture)

    @classmethod
    def verify(cls, document):
        if cls.frozen is None:
            return compare.verified_rows(document, cls.build_path)

        def frozen_identity_and_current_shapes(capture, manifest):
            cls.frozen.identity(capture, manifest)
            compare.check_runtime_identity_shapes(capture["identity"])

        with patch.object(compare, "identity", side_effect=frozen_identity_and_current_shapes):
            return compare.verified_rows(document, cls.build_path)

    def changed_row(self):
        result = dict(self.capture)
        result["runs"] = list(self.capture["runs"])
        result["runs"][0] = copy.deepcopy(result["runs"][0])
        return result

    def reject(self, document):
        with self.assertRaises((AssertionError, KeyError, ValueError, TypeError)):
            self.verify(document)

    def test_retained_evidence_body_passes_with_explicit_identity_scope(self):
        contract, rows = self.verify(self.capture)
        self.assertEqual(len(rows), len(contract["cases"]) * contract["rebuilds"])

    def test_frame_and_living_tick_corruption_are_rejected(self):
        value = self.changed_row()
        value["runs"][0]["frames"].pop(30)
        self.reject(value)
        for count in (None, 0, 2, True, 1.0):
            with self.subTest(livingTick=count):
                value = self.changed_row()
                frame = value["runs"][0]["frames"][30]
                if count is None:
                    del frame["livingTickEvents"]
                else:
                    frame["livingTickEvents"] = count
                self.reject(value)
        value = self.changed_row()
        value["runs"][0]["frames"][0]["index"] = False
        self.reject(value)

    def test_truthy_registration_and_owner_flags_are_rejected(self):
        keys = ("worldRegisteredByUuid", "worldRegisteredById", "positionEntityTicking", "ownerResolvedSamePlayerListObject")
        for key in keys:
            for invalid in ("false", 1):
                with self.subTest(key=key, value=invalid):
                    value = self.changed_row()
                    value["runs"][0]["frames"][30]["after"][key] = invalid
                    self.reject(value)
        for path in (("ownerConnection", "connected"), ("owner", "abilityInvulnerable"), ("maid", "alive")):
            value = self.changed_row()
            value["runs"][0]["frames"][30]["after"][path[0]][path[1]] = "false"
            self.reject(value)

    def test_runtime_digest_shapes_are_rejected_on_actual_capture(self):
        for key, invalid in (("definitionSha256", "x" * 64), ("definitionSha256", "a" * 63), ("definitionCount", True)):
            with self.subTest(key=key, value=invalid):
                value = dict(self.capture)
                value["identity"] = copy.deepcopy(value["identity"])
                first = next(iter(value["identity"]["classes"].values()))
                first[key] = invalid
                self.reject(value)

    def test_vector_shape_corruption_is_rejected_on_actual_capture(self):
        for group, key in (("maid", "position"), ("maid", "movement"), ("owner", "position"), ("moveControl", "wanted")):
            with self.subTest(group=group, key=key):
                value = self.changed_row()
                value["runs"][0]["frames"][30]["after"][group][key] = [64]
                self.reject(value)
        value = self.changed_row()
        value["runs"][0]["frames"][30]["ownerInput"]["plannedPosition"] = value["runs"][0]["frames"][30]["ownerInput"]["plannedPosition"][:1]
        self.reject(value)
        value = self.changed_row()
        invoked = next(frame for frame in value["runs"][0]["frames"] if frame["ownerInput"]["methodInvoked"])
        invoked["ownerInput"]["actualArguments"] = invoked["ownerInput"]["actualArguments"][:1]
        self.reject(value)
        value = self.changed_row()
        path = next(frame["after"]["navigation"]["path"] for frame in value["runs"][0]["frames"] if frame["after"]["navigation"]["path"] is not None)
        path["nodes"][0] = path["nodes"][0][:1]
        self.reject(value)

    def test_owner_cleanup_failures_are_rejected(self):
        value = dict(self.capture)
        value["ownerAfterCleanupState"] = copy.deepcopy(value["ownerAfterCleanupState"])
        value["ownerAfterCleanupState"]["selectedSlot"] = (value["ownerAfterCleanupState"]["selectedSlot"] + 1) % 9
        self.reject(value)
        value = dict(self.capture)
        value["cleanupStages"] = copy.deepcopy(value["cleanupStages"])
        value["cleanupStages"][3]["success"] = False
        self.reject(value)

    def test_identity_mismatch_or_missing_class_is_rejected(self):
        value = dict(self.capture)
        value["identity"] = copy.deepcopy(value["identity"])
        value["identity"]["buildIdentitySha256"] = "0" * 64
        self.reject(value)
        value = dict(self.capture)
        value["identity"] = copy.deepcopy(value["identity"])
        value["identity"]["classes"].pop(next(iter(value["identity"]["classes"])))
        self.reject(value)

    def test_missing_fresh_rebuilds_are_rejected(self):
        value = dict(self.capture)
        value["runs"] = [row for row in self.capture["runs"] if row["rebuild"] == 0]
        self.reject(value)
        value["runs"] = self.capture["runs"][:1]
        self.reject(value)

    def test_cross_frame_clock_discontinuity_is_rejected(self):
        value = self.changed_row()
        frame = value["runs"][0]["frames"][30]
        for boundary in ("before", "after"):
            frame[boundary]["maid"]["tickCount"] += 1
        self.reject(value)


if __name__ == "__main__":
    unittest.main()
