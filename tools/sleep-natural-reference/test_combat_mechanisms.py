"""采集准入机制的离线负例单元测试。"""

import copy
import unittest

from compare_combat_mechanisms import gravity_metadata, layout, native_pair


class CombatInputCounterexamples(unittest.TestCase):
    def test_known_java_float_gravity_keeps_raw_decimal_and_exact_promoted_bits(self):
        result = gravity_metadata({"gravity": 0.05}, False)
        self.assertEqual(result["binary32_hex"], "3d4ccccd")
        self.assertEqual(result["promoted_binary64_value"], 0.05000000074505806)
        self.assertFalse(result["raw_json_vs_promoted"]["exact_equal"])
        self.assertFalse(result["numeric_tolerance_added"])
        self.assertEqual(gravity_metadata({"gravity": 0.0}, True, True)["binary32_hex"], "00000000")
        for changed in (0.050001, 0.05000000074505806, 0.005):
            with self.assertRaises(ValueError):
                gravity_metadata({"gravity": changed}, False)

    def test_native_pair_preserves_modifiers_and_cannot_hide_physics_or_equipment_changes(self):
        conditions = {"nativeGravity": 0.05000000074505806, "effectiveGravity": 0.05000000074505806,
                      "nativeDrag": 0.9900000095367432,
                      "target": {"attributes": [{"name": "mod.attribute", "value": 1, "modifiers": []}]},
                      "weapon": {"opaque": "full native item snapshot"}}
        self.assertTrue(native_pair(conditions, copy.deepcopy(conditions))["same_physics_comparison_eligible"])
        changed = copy.deepcopy(conditions)
        changed["effectiveGravity"] = 0
        self.assertFalse(native_pair(conditions, changed)["same_physics_comparison_eligible"])
        changed = copy.deepcopy(conditions)
        changed["target"]["attributes"][0]["modifiers"].append({"uuid": "actual unknown modifier", "amount": 0.1})
        result = native_pair(conditions, changed)
        self.assertFalse(result["same_physics_comparison_eligible"])
        self.assertEqual(result["right"], changed)
        self.assertFalse(result["statistical_admission"])

    def test_enclosure_rejects_missing_wall_or_nonzero_metadata(self):
        rows = [{"x": x, "y": y, "z": z, "registry": "minecraft:glass", "metadata": 0}
                for x in range(21) for y in range(11) for z in range(9)
                if x in (0, 20) or y in (0, 10) or z in (0, 8)]
        layout(rows)
        with self.assertRaises(ValueError):
            layout(rows[1:])
        changed = copy.deepcopy(rows)
        changed[0]["metadata"] = 1
        with self.assertRaises(ValueError):
            layout(changed)


if __name__ == "__main__":
    unittest.main()
