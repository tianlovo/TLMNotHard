"""Only evidence-parser and normalisation invariants; no synthetic original-AI oracle"""

import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("follow_natural_compare", Path(__file__).with_name("compare.py"))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class EvidenceChecks(unittest.TestCase):
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
        contract = compare.load(Path(__file__).with_name("contract.json"))
        scene = next(case for case in contract["cases"] if case["id"] == "N03_teleport_boundary")
        a = compare.planned(scene, 39, contract["origin"])
        b = compare.planned(scene, 40, contract["origin"])
        self.assertEqual(b[0] - a[0], 14)
        self.assertEqual(a[1:], b[1:])


if __name__ == "__main__":
    unittest.main()
